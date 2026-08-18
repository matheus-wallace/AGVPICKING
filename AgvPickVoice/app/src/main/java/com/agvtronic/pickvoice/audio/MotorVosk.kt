package com.agvtronic.pickvoice.audio

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService

/**
 * O [MotorDeAsr] do Vosk — **a superfície do Vosk, e só ela**.
 *
 * Este arquivo não é código novo: é o que morava dentro do [ReconhecedorDeComando] até a troca de
 * motor virar uma interface (add-sherpa-onnx-asr-engine - Decisão 1), movido para cá sem mudança
 * de comportamento. Gramática fechada, endpointer embutido, escala `±32767`, JSON de saída — tudo
 * exatamente como estava, porque este motor é o baseline contra o qual o sherpa-onnx vai ser
 * medido em bancada, e um baseline que mudou junto não mede nada.
 *
 * ### Streaming, ao contrário do motor novo
 *
 * O Vosk decodifica amostra a amostra e o próprio `Recognizer` decide quando a elocução acabou
 * (`acceptWaveForm` devolvendo `true`). É por isso que ele tem hipótese parcial para logar e o
 * pipeline VAD-então-ASR do sherpa-onnx não tem: lá não existe nada decodificado até o trecho
 * fechar.
 *
 * @param appContext contexto de aplicação — o modelo vive além de qualquer `Activity`.
 * @param ajustes calibração de bancada; ver [AjustesAsr].
 */
class MotorVosk(
    private val appContext: Context,
    private val ajustes: AjustesAsr = AjustesAsr(),
) : MotorDeAsr {

  override val nome: String = "vosk"

  /** Confinado na thread de áudio, como tudo neste motor. `Model` não é thread-safe. */
  private var modelo: Model? = null

  /**
   * Copia os assets para o armazenamento do app e abre o modelo.
   *
   * A cópia dos 51 MB só acontece de fato quando o arquivo `uuid` muda (ver PROVENIENCIA.md do
   * modelo); o construtor de `Model` só aceita caminho de sistema de arquivos, não de asset.
   */
  override fun carregar(): Boolean {
    if (modelo != null) return true

    LibVosk.setLogLevel(LogLevel.WARNINGS)

    val inicio = System.currentTimeMillis()
    modelo =
        runCatching {
              val caminho = StorageService.sync(appContext, DIRETORIO_MODELO, DIRETORIO_MODELO)
              Model(caminho)
            }
            .onSuccess { Log.i(TAG, "Modelo carregado em ${System.currentTimeMillis() - inicio}ms") }
            .onFailure { Log.e(TAG, "Falha ao carregar o modelo Vosk; voz desligada", it) }
            .getOrNull()

    return modelo != null
  }

  /**
   * Constrói o `Recognizer` do estado.
   *
   * Uma gramática que o modelo rejeite não pode derrubar a captura nem mexer no estado: sem
   * reconhecedor, o app segue no mesmo estado e o painel continua servindo
   * (add-audio-single-grammar-slice - Decisão 6).
   */
  override fun abrirSessao(configuracao: ConfiguracaoDeEscuta, sampleRate: Int): SessaoDeAsr? {
    val modeloCarregado = modelo ?: return null

    return runCatching {
          val gramatica = configuracao.gramatica
          val recognizer =
              if (gramatica == null) Recognizer(modeloCarregado, sampleRate.toFloat())
              else Recognizer(modeloCarregado, sampleRate.toFloat(), gramatica)

          recognizer.setEndpointerDelays(
              ajustes.silencioAntesDaFalaMs / MS_POR_SEGUNDO,
              silencioFinalDe(configuracao.perfil) / MS_POR_SEGUNDO,
              ajustes.duracaoMaximaMs / MS_POR_SEGUNDO,
          )
          // Não precisamos de timestamps por palavra; só do texto final.
          recognizer.setWords(false)

          SessaoVosk(recognizer, ajustes)
        }
        .onFailure { Log.e(TAG, "Falha ao criar o reconhecedor; estado segue intacto", it) }
        .getOrNull()
  }

  /**
   * O `t_end` do estado, com o arquivo de bancada tendo a última palavra.
   *
   * O perfil do doc §5.1 é quem manda no fluxo normal — 280 ms para um comando de uma palavra,
   * 700 ms para dígitos. Mas [AjustesAsr.silencioFinalMs] existe para calibrar sem recompilar,
   * então quando ele foi de fato alterado no arquivo passa a valer para todos os estados;
   * enquanto estiver no default, quem decide é o estado.
   */
  private fun silencioFinalDe(perfil: PerfilEndpoint): Float {
    val padrao = PerfilEndpoint.COMANDO_CURTO.silencioFinalMs
    return if (ajustes.silencioFinalMs == padrao) perfil.silencioFinalMs.toFloat()
    else ajustes.silencioFinalMs.toFloat()
  }

  /** Um `Recognizer` vivo. Ponteiro nativo — por isso [close] existe e é sempre chamado. */
  private class SessaoVosk(
      private val recognizer: Recognizer,
      private val ajustes: AjustesAsr,
  ) : SessaoDeAsr {

    override fun aceitar(janela: FloatArray): ResultadoDeAsr {
      // O Vosk espera as amostras na escala de int16 mesmo na sobrecarga de float[], e a
      // FonteAudio entrega normalizado em -1.0..1.0. Sem esta conversão não há erro: há
      // silêncio, porque tudo vira ~0 na escala que o decodificador espera.
      val paraVosk = FloatArray(janela.size) { janela[it] * ESCALA_INT16 }

      // `true` = o endpointer do Vosk fechou a elocução (add-audio-single-grammar-slice -
      // Decisão 2).
      if (recognizer.acceptWaveForm(paraVosk, paraVosk.size)) {
        return ResultadoDeAsr.Fechada(textoDoJson(recognizer.result, CAMPO_TEXTO))
      }

      // Sem log de parciais não vale pagar a chamada nativa nem o parse do JSON a cada janela.
      if (!ajustes.logParciais) return ResultadoDeAsr.NADA

      return ResultadoDeAsr.EmAndamento(textoDoJson(recognizer.partialResult, CAMPO_PARCIAL))
    }

    override fun reiniciar() = recognizer.reset()

    override fun close() = recognizer.close()

    /**
     * Extrai um campo de texto do JSON do Vosk. `{"text": "..."}` no resultado final e
     * `{"partial": "..."}` no parcial — mesma forma, campos diferentes.
     *
     * O texto sai daqui só com `trim`, sem passar pelo [NormalizadorDeTextoAsr]: o Vosk devolve
     * `[unk]` para fala fora da gramática, e tirar os colchetes quebraria a comparação com
     * [VocabularioDeVoz.DESCONHECIDA] que o [InterpretadorDeFala] faz.
     */
    private fun textoDoJson(json: String, campo: String): String =
        runCatching { JSONObject(json).optString(campo).trim() }
            .onFailure { Log.e(TAG, "Resultado do Vosk não era JSON: $json", it) }
            .getOrDefault("")
  }

  private companion object {
    const val TAG = "MotorVosk"

    /** Diretório do modelo dentro de `assets/` e também dentro do armazenamento do app. */
    const val DIRETORIO_MODELO = "modelo-vosk-pt"

    /** Campo do texto final no JSON do Vosk. */
    const val CAMPO_TEXTO = "text"

    /** Campo da hipótese em andamento no JSON do Vosk. */
    const val CAMPO_PARCIAL = "partial"

    /** `setEndpointerDelays` fala em segundos; os [AjustesAsr] falam em ms, como o doc §5.1. */
    const val MS_POR_SEGUNDO = 1_000f

    /** -1.0..1.0 (contrato da FonteAudio) -> ±32767 (o que o Vosk decodifica). */
    const val ESCALA_INT16 = 32_767f
  }
}
