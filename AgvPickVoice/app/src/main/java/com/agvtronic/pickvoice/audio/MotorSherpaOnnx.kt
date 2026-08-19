package com.agvtronic.pickvoice.audio

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * O [MotorDeAsr] do sherpa-onnx: Silero VAD corta o trecho de fala, Omnilingual ASR CTC o
 * decodifica.
 *
 * ### Por que CTC, e não mais o Whisper
 *
 * O decodificador anterior era Whisper-tiny multilíngue, e a bancada de 18/08/2026 o mediu
 * **alucinando** em comandos curtos de pt-BR com sinal limpo — "iniciar" saindo como "e iniciar
 * prouximo". Não era calibração: Whisper é autoregressivo, e um decodificador que gera token a
 * token pode continuar a frase além do que o áudio disse. Omnilingual ASR é CTC, quer dizer,
 * frame-síncrono — cada quadro de áudio produz no máximo um símbolo, e não existe estado de
 * geração livre de onde inventar palavra que não foi falada. A troca é do mecanismo, não do
 * ajuste (add-sherpa-onnx-omnilingual-decoder - design.md).
 *
 * ### VAD-então-ASR, não streaming
 *
 * É a diferença estrutural para o [MotorVosk], e ela não foi uma escolha: o model zoo do
 * sherpa-onnx **não tem modelo de streaming para português**. As vias de pt-BR nesse toolkit são
 * todas offline — decodificam um trecho já delimitado, de uma vez. Por isso não existe hipótese
 * parcial aqui: até o VAD fechar o trecho, nada foi decodificado, e o resultado só aparece
 * depois da inferência sobre o trecho inteiro. A spec desta mudança registra isso como
 * comportamento esperado ("Publicação não é instantânea ao fim da fala"), não como defeito.
 *
 * ### Três coisas que a verificação da API impôs a este código
 *
 * 1. **Reamostragem obrigatória.** O Silero VAD só aceita 16 kHz; a [FonteAudio] entrega 8 kHz
 *    (canal HFP do óculos, doc §2.1). Cada sessão tem seu [ReamostradorLinear].
 * 2. **Validação defensiva antes de qualquer chamada nativa.** Erro de configuração no
 *    sherpa-onnx não lança exceção: `SHERPA_ONNX_EXIT` chama `_Exit` e o processo morre na hora,
 *    sem stack trace. Um `runCatching` em volta não protege nada. O que o C++ trataria com
 *    `_Exit` — modelo ausente, taxa diferente de 16 kHz — é conferido aqui em Kotlin, para
 *    preservar a garantia de que falha de ASR degrada em silêncio e o painel de dev segue
 *    servindo (add-audio-single-grammar-slice - Decisão 6).
 * 3. **Amostras normalizadas, sem conversão de escala.** O sherpa-onnx quer `-1.0..1.0`, que já é
 *    o contrato da [FonteAudio] — o oposto do Vosk, que exige `±32767` mesmo recebendo `float[]`.
 *    Aplicar a conversão do Vosk aqui por inércia saturaria tudo.
 *
 * Ver design.md de `add-sherpa-onnx-asr-engine`, seção "Verificação da API do sherpa-onnx".
 *
 * ### Confinamento de thread
 *
 * Sessões do ONNX Runtime não são thread-safe. VAD e reconhecedor são criados e usados só na
 * thread de áudio do [ReconhecedorDeComando], mesma regra que já valia para o `Model` do Vosk.
 * [AjustesAsr.threadsDeInferencia] não muda isso — aquele paralelismo é interno ao ONNX Runtime.
 *
 * @param appContext contexto de aplicação — os modelos vivem além de qualquer `Activity`.
 * @param ajustes calibração de bancada; ver [AjustesAsr]. **Nenhum dos parâmetros de VAD foi
 *   medido** — são os defaults do próprio sherpa-onnx, e calibrá-los é tarefa de bancada.
 */
class MotorSherpaOnnx(
    appContext: Context,
    private val ajustes: AjustesAsr = AjustesAsr(),
) : MotorDeAsr {

  override val nome: String = "sherpa-onnx"

  private val assets: AssetManager = appContext.assets

  /** Confinado na thread de áudio. Uma sessão do ONNX Runtime, viva pela vida do processo. */
  private var reconhecedor: OfflineRecognizer? = null

  /**
   * Um VAD por janela de silêncio, criado sob demanda e mantido pela vida do processo.
   *
   * O `min_silence_duration` do Silero é lido do config que o `Vad` recebeu **no construtor** —
   * o campo `config` do binding Kotlin é só um campo Kotlin, não há setter nativo que propague a
   * mudança. Como o [PerfilEndpoint] varia por estado (280 ms para comando curto, 700 ms para
   * dígitos), honrar o perfil exigiria recriar o VAD a cada transição, e criar uma sessão ONNX
   * por transição de estado é caro na thread que também lê o microfone.
   *
   * O mapa resolve os dois lados: há no máximo três valores distintos de silêncio em todo o
   * fluxo, então o VAD de cada perfil é criado uma vez e reutilizado dali em diante.
   */
  private val vadPorSilencio = mutableMapOf<Int, Vad>()

  /**
   * Carrega o reconhecedor Omnilingual ASR. O VAD de cada perfil vem depois, em [abrirSessao].
   *
   * Confere antes que os modelos existem em `assets/`: sem essa conferência, um arquivo faltando
   * derrubaria o processo dentro do C++ em vez de desligar a voz e seguir.
   */
  override fun carregar(): Boolean {
    if (reconhecedor != null) return true

    val faltando = ARQUIVOS_EXIGIDOS.filterNot { existeNoAssets(it) }
    if (faltando.isNotEmpty()) {
      Log.e(TAG, "Modelos ausentes em assets/: $faltando; voz desligada")
      return false
    }

    val inicio = System.currentTimeMillis()
    reconhecedor =
        runCatching {
              OfflineRecognizer(
                  assetManager = assets,
                  config =
                      OfflineRecognizerConfig(
                          // Só a taxa importa: o grafo do modelo tem `feature_extractor` próprio
                          // (wav2vec2) e consome forma de onda crua, então não há banco de
                          // filtros montado aqui como o Whisper exigia.
                          featConfig = FeatureConfig(sampleRate = TAXA_EXIGIDA),
                          modelConfig =
                              OfflineModelConfig(
                                  omnilingual =
                                      OfflineOmnilingualAsrCtcModelConfig(model = MODELO),
                                  tokens = TOKENS,
                                  // `modelType` fica vazio: o despacho do sherpa-onnx é por qual
                                  // sub-config está preenchida, e o `omnilingual-asr` gravado nos
                                  // metadados do .onnx não é valor aceito nesse campo — preenchê-lo
                                  // cairia no caminho de "Invalid model_type" do C++.
                                  numThreads = ajustes.threadsDeInferencia,
                              ),
                      ),
              )
            }
            .onSuccess {
              Log.i(TAG, "Omnilingual ASR carregado em ${System.currentTimeMillis() - inicio}ms")
            }
            .onFailure { Log.e(TAG, "Falha ao carregar o Omnilingual ASR; voz desligada", it) }
            .getOrNull()

    return reconhecedor != null
  }

  override fun abrirSessao(configuracao: ConfiguracaoDeEscuta, sampleRate: Int): SessaoDeAsr? {
    val reconhecedorCarregado = reconhecedor ?: return null

    val vad = vadDe(silencioFinalDe(configuracao.perfil)) ?: return null

    // O trecho sai do VAD já em 16 kHz, então o reconhecedor recebe nessa taxa e não reamostra
    // de novo por conta própria.
    return SessaoSherpaOnnx(
        vad = vad,
        reconhecedor = reconhecedorCarregado,
        reamostrador = ReamostradorLinear(sampleRate, TAXA_EXIGIDA),
    )
  }

  /**
   * O VAD de uma janela de silêncio, criado na primeira vez que aquele perfil aparece.
   *
   * `windowSize` não é configurável por [AjustesAsr] de propósito: para o `silero_vad.onnx` a
   * 16 kHz o valor tem de ser 512, e um valor diferente é um dos casos que o C++ resolve com
   * `_Exit`. Expor a chave só criaria uma forma de matar o app por arquivo de calibração.
   */
  private fun vadDe(silencioMs: Int): Vad? =
      vadPorSilencio.getOrElse(silencioMs) {
        val criado =
            runCatching {
                  Vad(
                      assetManager = assets,
                      config =
                          VadModelConfig(
                              sileroVadModelConfig =
                                  SileroVadModelConfig(
                                      model = SILERO_VAD,
                                      threshold = ajustes.vadLimiar,
                                      minSilenceDuration = silencioMs / MS_POR_SEGUNDO,
                                      minSpeechDuration = ajustes.vadFalaMinimaMs / MS_POR_SEGUNDO,
                                      windowSize = JANELA_SILERO,
                                      maxSpeechDuration = ajustes.vadFalaMaximaMs / MS_POR_SEGUNDO,
                                  ),
                              sampleRate = TAXA_EXIGIDA,
                              numThreads = ajustes.threadsDeInferencia,
                          ),
                  )
                }
                .onSuccess { Log.i(TAG, "VAD criado para ${silencioMs}ms de silêncio") }
                .onFailure { Log.e(TAG, "Falha ao criar o VAD; estado segue sem escuta", it) }
                .getOrNull() ?: return null

        vadPorSilencio[silencioMs] = criado
        criado
      }

  /**
   * O silêncio que fecha a elocução, com o arquivo de bancada tendo a última palavra.
   *
   * Mesma regra do [MotorVosk], e de propósito: o conceito é o mesmo — quanto silêncio encerra
   * a fala — e o que muda é só quem o aplica, o endpointer do Vosk ou o Silero VAD
   * (add-sherpa-onnx-asr-engine - Decisão 6).
   */
  private fun silencioFinalDe(perfil: PerfilEndpoint): Int {
    val padrao = PerfilEndpoint.COMANDO_CURTO.silencioFinalMs
    return if (ajustes.silencioFinalMs == padrao) perfil.silencioFinalMs else ajustes.silencioFinalMs
  }

  private fun existeNoAssets(caminho: String): Boolean =
      runCatching { assets.open(caminho).close() }.isSuccess

  /**
   * Uma escuta viva: alimenta o VAD janela a janela e decodifica o trecho quando ele fecha.
   *
   * O VAD e o reconhecedor são **compartilhados** e pertencem ao motor — [close] limpa o estado
   * do VAD mas não o libera, porque o próximo estado vai reusá-lo. É o mesmo arranjo do Vosk, em
   * que o `Model` sobrevive às sessões e só o `Recognizer` é descartado.
   */
  private class SessaoSherpaOnnx(
      private val vad: Vad,
      private val reconhecedor: OfflineRecognizer,
      private val reamostrador: ReamostradorLinear,
  ) : SessaoDeAsr {

    override fun aceitar(janela: FloatArray): ResultadoDeAsr {
      // As amostras já chegam em -1.0..1.0, que é exatamente o que o sherpa-onnx espera.
      vad.acceptWaveform(reamostrador.processar(janela))

      if (vad.empty()) return ResultadoDeAsr.NADA

      // Um trecho por janela. Se mais de um estiver pronto — o que exigiria duas elocuções
      // dentro dos 64 ms de uma janela —, o resto espera a janela seguinte, 64 ms depois, e o
      // versionamento do PublicadorDeVoz continua descartando o que ficou obsoleto.
      val trecho = vad.front()
      vad.pop()

      val texto =
          runCatching {
                val stream = reconhecedor.createStream()
                try {
                  stream.acceptWaveform(trecho.samples, TAXA_EXIGIDA)
                  reconhecedor.decode(stream)
                  reconhecedor.getResult(stream).text
                } finally {
                  stream.release()
                }
              }
              .onFailure { Log.e(TAG, "Falha ao decodificar o trecho; descartado", it) }
              .getOrDefault("")

      // A limpeza mora na fronteira do motor, nunca no InterpretadorDeFala
      // (add-sherpa-onnx-asr-engine - Decisão 3). Vale para qualquer decodificador: o que muda de
      // um para outro é quanta pontuação e capitalização vêm junto, não de quem é a limpeza.
      return ResultadoDeAsr.Fechada(NormalizadorDeTextoAsr.normalizar(texto))
    }

    override fun reiniciar() {
      vad.reset()
      vad.clear()
      reamostrador.reiniciar()
    }

    override fun close() {
      // Não libera o VAD: ele é do motor e o próximo estado o reaproveita. Só o estado
      // acumulado dele é que não pode atravessar a troca de configuração de escuta.
      vad.reset()
      vad.clear()
    }
  }

  private companion object {
    const val TAG = "MotorSherpaOnnx"

    /**
     * Dois diretórios em `assets/`, um por modelo: o VAD não mudou quando o decodificador mudou, e
     * separá-los mantém a proveniência de cada um rastreável. Ver PROVENIENCIA.md em cada um.
     */
    const val DIRETORIO_VAD = "modelo-sherpa-onnx"

    const val DIRETORIO_ASR = "modelo-sherpa-onnx-omnilingual"

    const val SILERO_VAD = "$DIRETORIO_VAD/silero_vad.onnx"
    const val MODELO = "$DIRETORIO_ASR/model.int8.onnx"
    const val TOKENS = "$DIRETORIO_ASR/tokens.txt"

    val ARQUIVOS_EXIGIDOS = listOf(SILERO_VAD, MODELO, TOKENS)

    /**
     * 16 kHz, e não é negociável: o Silero VAD do sherpa-onnx mata o processo em qualquer outra
     * taxa (design.md - "Verificação da API do sherpa-onnx", itens (a) e (b)).
     */
    const val TAXA_EXIGIDA = 16_000

    /** 512 amostras a 16 kHz — o único valor que o `silero_vad.onnx` aceita. */
    const val JANELA_SILERO = 512

    /** Os [AjustesAsr] falam em ms; o sherpa-onnx, em segundos. */
    const val MS_POR_SEGUNDO = 1_000f
  }
}
