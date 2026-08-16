package com.agvtronic.pickvoice.vision

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Decodifica os frames HEVC que o stream do DAT entrega e devolve cada um como
 * [android.media.Image] em `YUV_420_888`.
 *
 * ### De onde veio este código
 *
 * A lógica de parsing de NAL units (achar o prefixo de início, cachear VPS/SPS/PPS, ativar no
 * primeiro keyframe, alimentar cada NAL separadamente) é portada de
 * `samples/CameraAccess/.../stream/HevcDecoder.kt`, que por sua vez replica o decodificador
 * interno do SDK. É código chato, sensível a detalhe e já validado contra este stream
 * específico — reescrevê-lo do zero só produziria bugs novos.
 *
 * ### O que muda em relação ao sample
 *
 * A instância de análise é configurada sem `Surface` e com
 * [MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible], o que permite pedir a saída como
 * [Image] via `getOutputImage`. Uma segunda instância pode receber [surface] e renderizar direto
 * nela, como o sample, sem copiar o frame completo para o heap.
 *
 * Esse é o motivo de não usar `compressVideo = false` no `StreamConfiguration`: naquele caminho
 * o SDK decodifica internamente e repassa o `ByteBuffer` cru do codec, mas `VideoFrame` não
 * carrega formato de pixel nenhum — o layout ficaria por conta de adivinhação, dependente de
 * aparelho (design.md - Decisão 2).
 *
 * ### Contrato de consumo
 *
 * [aoDecodificar] é chamado **na thread do decodificador** e a [Image] recebida é válida
 * **apenas durante a chamada**: assim que ela retorna, a imagem é fechada e o buffer devolvido ao
 * codec, em `finally`, mesmo que o consumidor lance. Quem consome deve copiar o que precisa (na
 * prática, o recorte da ROI do doc §6.3) e não guardar a referência. Isso é o ponto de liberação
 * determinística que o doc §4.4 exige — o descarte não depende do coletor de lixo.
 *
 * Exatamente um destino deve ser fornecido: [aoDecodificar] para análise ou [surface] para
 * preview.
 */
class DecodificadorHevc(
    private val aoDecodificar: ((Image) -> Unit)? = null,
    private val surface: Surface? = null,
    private val aoFormato: (largura: Int, altura: Int) -> Unit = { _, _ -> },
    private val aoErro: (String) -> Unit = {},
) {

  init {
    require((aoDecodificar != null) xor (surface != null)) {
      "Forneça exatamente um destino: callback YUV ou Surface"
    }
  }

  private class FrameDeEntrada(
      val dados: ByteBuffer,
      val deslocamento: Int,
      val tamanho: Int = dados.remaining(),
      val apresentacaoUs: Long = 0L,
      val ehKeyframe: Boolean = false,
      val ehConfiguracao: Boolean = false,
  ) {
    val flags: Int
      get() {
        var mascara = 0
        if (ehKeyframe) mascara = mascara or MediaCodec.BUFFER_FLAG_KEY_FRAME
        if (ehConfiguracao) mascara = mascara or MediaCodec.BUFFER_FLAG_CODEC_CONFIG
        return mascara
      }
  }

  // @Volatile pelo mesmo motivo do sample: `parar` roda na thread de quem chama enquanto os
  // callbacks do MediaCodec ainda podem estar disparando na thread do decodificador. São
  // referências únicas, publicadas na criação e anuladas no encerramento — não há estado
  // composto a proteger.
  @Volatile private var codec: MediaCodec? = null
  @Volatile private var threadDoCodec: HandlerThread? = null
  @Volatile private var formato: MediaFormat? = null
  @Volatile private var configuracaoEmCache: ByteBuffer? = null
  @Volatile private var ativo = false
  @Volatile private var primeiroFrameDeEntrada = true
  @Volatile private var recebeuKeyframe = false
  @Volatile private var avisouImagemNula = false

  private val fila = LinkedBlockingQueue<FrameDeEntrada>(CAPACIDADE_DA_FILA)

  /** Prepara o codec para um stream de [largura] x [altura]. Não bloqueia. */
  fun iniciar(largura: Int, altura: Int) {
    formato =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, largura, altura).apply {
          setInteger(MediaFormat.KEY_FRAME_RATE, TAXA_NOMINAL)
          setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
          if (surface == null) {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
          }
        }
    runCatching { garantirCodec() }
        .onFailure {
          val detalhe = "Falha ao criar o decodificador HEVC: ${it.message}"
          Log.e(TAG, detalhe, it)
          aoErro(detalhe)
        }
  }

  /**
   * Entrega um frame comprimido vindo de `Stream.videoStream`.
   *
   * Réplica de `VideoDecoder.enqueue` do SDK: percorre os NAL units do buffer, guarda o de
   * configuração, ativa o codec no primeiro keyframe e enfileira cada NAL separadamente.
   */
  fun enfileirar(dados: ByteArray, apresentacaoUs: Long) {
    if (dados.isEmpty()) return

    val buffer = ByteBuffer.wrap(dados)
    val copia = dados.copyOf()
    val marcadores = BooleanArray(3)
    var indice = acharNalUnit(copia, 0, dados.size, marcadores)

    while (indice < dados.size) {
      val tipo = tipoDoNalUnitH265(copia, indice)
      val ehKeyframe = ehTipoIrap(tipo)
      val ehConfiguracao = tipo in TIPOS_DE_CONFIGURACAO

      if (ehConfiguracao) {
        configuracaoEmCache = clonar(buffer)
      } else if (ehKeyframe) {
        if (!ativo) {
          ativo = true
          configuracaoEmCache?.let { reenfileirarConfiguracao(it) }
        }
        recebeuKeyframe = true
      }

      enfileirarInterno(
          FrameDeEntrada(
              dados = clonar(buffer),
              deslocamento = indice,
              apresentacaoUs = apresentacaoUs,
              ehKeyframe = ehKeyframe,
              ehConfiguracao = ehConfiguracao,
          )
      )

      indice = acharNalUnit(copia, indice + 1, dados.size, marcadores)
    }
  }

  /** Encerra o codec e descarta o que estiver na fila. Idempotente. */
  fun parar() {
    ativo = false
    fila.clear()
    runCatching {
      codec?.stop()
      codec?.release()
    }
        .onFailure { Log.e(TAG, "Erro ao encerrar o decodificador: ${it.message}", it) }
    codec = null
    threadDoCodec?.quit()
    threadDoCodec = null
    primeiroFrameDeEntrada = true
    recebeuKeyframe = false
    configuracaoEmCache = null
    avisouImagemNula = false
  }

  // -----------------------------------------------------------------------------------
  // Enfileiramento
  // -----------------------------------------------------------------------------------

  /** Reentrada para a configuração em cache, quando o codec ativa depois do VPS/SPS/PPS. */
  private fun reenfileirarConfiguracao(configuracao: ByteBuffer) {
    val copia =
        ByteBuffer.allocate(configuracao.capacity())
            .apply {
              configuracao.rewind()
              put(configuracao)
              flip()
              configuracao.rewind()
            }
            .array()
    val marcadores = BooleanArray(3)
    var indice = acharNalUnit(copia, 0, configuracao.limit(), marcadores)
    while (indice < configuracao.limit()) {
      val tipo = tipoDoNalUnitH265(copia, indice)
      enfileirarInterno(
          FrameDeEntrada(
              dados = clonar(configuracao),
              deslocamento = indice,
              apresentacaoUs = 0,
              ehKeyframe = ehTipoIrap(tipo),
              ehConfiguracao = tipo in TIPOS_DE_CONFIGURACAO,
          )
      )
      indice = acharNalUnit(copia, indice + 1, configuracao.limit(), marcadores)
    }
  }

  private fun enfileirarInterno(frame: FrameDeEntrada) {
    if (!ativo) return
    if (!frame.ehConfiguracao && !recebeuKeyframe) return
    if (primeiroFrameDeEntrada) {
      primeiroFrameDeEntrada = false
      ativarCodec()
    }
    if (fila.remainingCapacity() == 0) {
      Log.w(TAG, "Fila do decodificador cheia; desativando até o próximo keyframe")
      ativo = false
      return
    }
    fila.offer(frame)
  }

  // -----------------------------------------------------------------------------------
  // Codec
  // -----------------------------------------------------------------------------------

  private fun garantirCodec() {
    if (codec == null) codec = criarDecodificador()
  }

  /**
   * Prefere um decodificador de software que aceite o formato flexível.
   *
   * Duas razões para não pegar o primeiro que aparecer: o sample registra decodificadores de
   * hardware que corrompem este stream específico ([DECODIFICADORES_BLOQUEADOS]), e nem todo
   * codec anuncia `COLOR_FormatYUV420Flexible` — sem ele, `getOutputImage` devolve `null` e o
   * pipeline inteiro fica mudo.
   */
  private fun criarDecodificador(): MediaCodec {
    val mime = MediaFormat.MIMETYPE_VIDEO_HEVC
    val candidatos =
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { info ->
          !info.isEncoder &&
              info.name !in DECODIFICADORES_BLOQUEADOS &&
              info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
              runCatching {
                    info.getCapabilitiesForType(mime).colorFormats.contains(
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                    )
                  }
                  .getOrDefault(false)
        }

    val escolhido = candidatos.firstOrNull { it.isSoftwareOnly } ?: candidatos.firstOrNull()
    return if (escolhido != null) {
      Log.d(TAG, "Decodificador HEVC: ${escolhido.name} (software=${escolhido.isSoftwareOnly})")
      MediaCodec.createByCodecName(escolhido.name)
    } else {
      Log.w(TAG, "Nenhum decodificador HEVC anuncia YUV420Flexible; usando o padrão da plataforma")
      MediaCodec.createDecoderByType(mime)
    }
  }

  private fun ativarCodec() {
    runCatching {
      garantirCodec()
      val codecAtual = codec ?: return
      threadDoCodec?.quit()
      val thread = HandlerThread("DecodificadorHevc", Process.THREAD_PRIORITY_VIDEO)
      thread.start()
      threadDoCodec = thread

      codecAtual.reset()
      codecAtual.configure(formato, surface, null, 0)
      codecAtual.setCallback(
          object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) =
                aoReceberBufferDeEntrada(codec, index)

            override fun onOutputBufferAvailable(
                codec: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo,
            ) = aoReceberBufferDeSaida(codec, index, info)

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
              Log.e(TAG, "Erro do codec: ${e.message}", e)
              aoErro(e.message ?: "Erro do codec HEVC")
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
              Log.d(TAG, "Formato de saída negociado: $format")
              val largura = format.getInteger(MediaFormat.KEY_WIDTH)
              val altura = format.getInteger(MediaFormat.KEY_HEIGHT)
              aoFormato(largura, altura)
            }
          },
          Handler(thread.looper),
      )
      codecAtual.start()
    }
        .onFailure {
          val detalhe = "Falha ao ativar o decodificador: ${it.message}"
          Log.e(TAG, detalhe, it)
          aoErro(detalhe)
        }
  }

  private fun aoReceberBufferDeEntrada(codec: MediaCodec, index: Int) {
    // Antes de tocar no codec: `parar()` roda na thread de quem desliga a câmera, e um callback
    // já em voo chega aqui com o codec parado. Tocar nele nesse ponto lança, e a exceção não
    // significa nada além de "o escaneamento acabou".
    if (!ativo) return

    var enfileirado = false
    try {
      val bufferDeEntrada = codec.getInputBuffer(index)
      val frame = fila.poll(1, TimeUnit.SECONDS)

      if (frame == null || bufferDeEntrada == null || !ativo) {
        codec.queueInputBuffer(index, 0, 0, 0, 0)
        enfileirado = true
        return
      }

      frame.dados.rewind()
      bufferDeEntrada.clear()
      bufferDeEntrada.put(frame.dados)
      bufferDeEntrada.flip()
      val tamanho = minOf(frame.tamanho, bufferDeEntrada.limit() - frame.deslocamento)
      codec.queueInputBuffer(
          index,
          frame.deslocamento,
          tamanho,
          frame.apresentacaoUs,
          frame.flags,
      )
      enfileirado = true
    } catch (e: Throwable) {
      // A mesma corrida de novo, agora entre o `if (!ativo)` acima e o uso do codec: aqui ela
      // vira exceção. Só é erro de verdade se o decodificador ainda deveria estar vivo — senão
      // toda saída de `EscaneandoProduto` deixaria um `E` no logcat, e a linha que importa
      // (um erro real de codec) some no meio do ruído.
      if (ativo) Log.e(TAG, "Erro no buffer de entrada: ${e.message}", e)
      else Log.d(TAG, "Buffer de entrada descartado no encerramento: ${e.message}")
      ativo = false
    } finally {
      if (!enfileirado) {
        runCatching { codec.queueInputBuffer(index, 0, 0, 0, 0) }
      }
    }
  }

  private fun aoReceberBufferDeSaida(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
    // Mesma proteção da entrada: nem o `releaseOutputBuffer` do `finally` deve rodar depois do
    // encerramento, e por isso este `return` fica antes do `try`.
    if (!ativo) return

    if (surface != null) {
      runCatching { codec.releaseOutputBuffer(index, info.size > 0) }
          .onFailure {
            if (ativo) {
              val detalhe = "Erro ao renderizar preview: ${it.message}"
              Log.e(TAG, detalhe, it)
              aoErro(detalhe)
            }
          }
      return
    }

    try {
      if (info.size == 0) return

      val imagem = codec.getOutputImage(index)
      if (imagem == null) {
        // A falha silenciosa mais cara possível: o stream sobe, os frames chegam, nada é lido e
        // não há nada no logcat. Uma vez basta — a cada frame inundaria o log.
        if (!avisouImagemNula) {
          avisouImagemNula = true
          Log.e(
              TAG,
              "getOutputImage devolveu null — o codec não entregou YUV420 flexível. " +
                  "Formato negociado: ${runCatching { codec.outputFormat }.getOrNull()}",
          )
        }
        return
      }

      // O `use` é o ponto de liberação do doc §4.4: a imagem fecha aqui, dê no que der.
      imagem.use { aoDecodificar?.invoke(it) }
    } catch (e: Throwable) {
      Log.e(TAG, "Erro no buffer de saída: ${e.message}", e)
    } finally {
      // `render = false`: não há Surface, e o buffer precisa voltar ao codec de qualquer forma.
      runCatching { codec.releaseOutputBuffer(index, false) }
    }
  }

  // -----------------------------------------------------------------------------------
  // NalUnitUtil — portado do sample CameraAccess, que o copiou do SDK
  // -----------------------------------------------------------------------------------

  private fun acharNalUnit(
      dados: ByteArray,
      inicio: Int,
      fim: Int,
      marcadores: BooleanArray,
  ): Int {
    val comprimento = fim - inicio
    if (comprimento == 0) return fim

    when {
      marcadores[0] -> {
        limparMarcadores(marcadores)
        return inicio - 3
      }
      comprimento > 1 && marcadores[1] && dados[inicio].toInt() == 1 -> {
        limparMarcadores(marcadores)
        return inicio - 2
      }
      comprimento > 2 &&
          marcadores[2] &&
          dados[inicio].toInt() == 0 &&
          dados[inicio + 1].toInt() == 1 -> {
        limparMarcadores(marcadores)
        return inicio - 1
      }
    }

    val limite = fim - 1
    var i = inicio + 2
    while (i < limite) {
      if ((dados[i].toInt() and 0xFE) != 0) {
        // Não há prefixo aqui nem nas duas posições seguintes.
      } else if (dados[i - 2].toInt() == 0 && dados[i - 1].toInt() == 0 && dados[i].toInt() == 1) {
        limparMarcadores(marcadores)
        return i - 2
      } else {
        i -= 2
      }
      i += 3
    }

    marcadores[0] =
        if (comprimento > 2) {
          dados[fim - 3].toInt() == 0 && dados[fim - 2].toInt() == 0 && dados[fim - 1].toInt() == 1
        } else if (comprimento == 2) {
          marcadores[2] && dados[fim - 2].toInt() == 0 && dados[fim - 1].toInt() == 1
        } else {
          marcadores[1] && dados[fim - 1].toInt() == 1
        }
    marcadores[1] =
        if (comprimento > 1) dados[fim - 2].toInt() == 0 && dados[fim - 1].toInt() == 0
        else marcadores[2] && dados[fim - 1].toInt() == 0
    marcadores[2] = dados[fim - 1].toInt() == 0

    return fim
  }

  private fun limparMarcadores(marcadores: BooleanArray) {
    marcadores[0] = false
    marcadores[1] = false
    marcadores[2] = false
  }

  private fun tipoDoNalUnitH265(dados: ByteArray, deslocamento: Int): Int {
    if (deslocamento + 3 >= dados.size) return -1
    return (dados[deslocamento + 3].toInt() and 0x7E) shr 1
  }

  /**
   * Qualquer imagem IRAP (BLA 16–18, IDR 19–20, CRA 21) é ponto de reinício de decodificação.
   * Detectar só IDR perderia os CRA que alguns encoders emitem, e o stream ficaria mudo depois
   * de a fila encher uma vez.
   */
  private fun ehTipoIrap(tipo: Int): Boolean = tipo in 16..21

  private fun clonar(original: ByteBuffer): ByteBuffer {
    val copia =
        if (original.isDirect) ByteBuffer.allocateDirect(original.capacity())
        else ByteBuffer.allocate(original.capacity())
    original.rewind()
    copia.put(original)
    original.rewind()
    copia.flip()
    return copia
  }

  private companion object {
    const val TAG = "DecodificadorHevc"

    const val CAPACIDADE_DA_FILA = 100

    /** Só informa o codec; a taxa real é a do [AjustesVisao.fps]. */
    const val TAXA_NOMINAL = 30

    /** VPS (32), SPS (33) e PPS (34). */
    val TIPOS_DE_CONFIGURACAO = setOf(32, 33, 34)

    /**
     * Decodificadores de hardware que corrompem este stream em fragmentos — a lista vem do
     * sample `CameraAccess`, que a herdou do próprio SDK.
     */
    val DECODIFICADORES_BLOQUEADOS = setOf("OMX.Exynos.hevc.dec", "c2.mtk.hevc.decoder")
  }
}
