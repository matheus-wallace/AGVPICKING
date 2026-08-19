package com.agvtronic.pickvoice.audio

/**
 * O decodificador de fala, atrás de uma interface — o ponto de troca entre Vosk e sherpa-onnx.
 *
 * Existe pelo mesmo motivo que [FonteAudio]: **não há medição de bancada do motor novo**, e o
 * projeto já pagou para aprender que um caminho de volta vale a interface. `AudioHfpOculos` foi
 * implementado, verificado e revertido no mesmo dia trocando uma linha do `AppContainer`, porque
 * `FonteAudio` já era interface — não foi preciso desfazer commit nenhum
 * (add-sherpa-onnx-asr-engine - Decisão 1). Aqui é a mesma aposta: [MotorVosk] e
 * `MotorSherpaOnnx` convivem no binário, e quem decide qual roda é uma linha só.
 *
 * ### O que fica de fora, de propósito
 *
 * Nada de domínio atravessa esta fronteira. O motor recebe [ConfiguracaoDeEscuta] — palavras e
 * perfil de endpoint, já resolvidos pelo [SeletorDeEscuta] — e devolve texto. Ele não conhece
 * `PickingState`, não conhece `PickingEvent` e não decide se o texto "conta": isso é do
 * [InterpretadorDeFala], que já compara o texto normalizado contra o [VocabularioDeVoz] e
 * continua sendo a única camada que faz esse julgamento
 * (add-sherpa-onnx-asr-engine - Decisão 2).
 *
 * ### Confinamento de thread
 *
 * Todo método desta interface e de [SessaoDeAsr] roda na thread única de áudio do
 * [ReconhecedorDeComando]. Nem `Model`/`Recognizer` do Vosk nem sessões do ONNX Runtime são
 * thread-safe, e nenhuma implementação precisa se defender disso — quem garante é o chamador.
 */
interface MotorDeAsr {

  /** Nome curto para o log de bancada, que precisa dizer qual motor produziu cada linha. */
  val nome: String

  /**
   * Carrega o(s) modelo(s). Chamado **uma vez**, na construção do [ReconhecedorDeComando], para
   * que os segundos de carga aconteçam durante a subida do app e não na frente do operador.
   *
   * @return `false` quando o motor ficou inutilizável. Não lança: sem ASR o app segue inteiro
   *   pelo painel de dev, e derrubar o processo por falha de voz nunca foi o contrato
   *   (add-audio-single-grammar-slice - Decisão 6).
   */
  fun carregar(): Boolean

  /**
   * Abre uma sessão de decodificação para o que o estado atual pede.
   *
   * @param configuracao o vocabulário e o perfil de endpoint do estado.
   * @param sampleRate a taxa que a [FonteAudio] declara, em Hz. O motor decide o que fazer com
   *   ela — o Vosk constrói o `Recognizer` nessa taxa; o sherpa-onnx reamostra, porque o Silero
   *   VAD só aceita 16 kHz (design.md - "Verificação da API do sherpa-onnx", item (a)).
   * @return `null` quando a sessão não pôde ser criada. As amostras continuam sendo lidas e
   *   simplesmente não alimentam decodificador nenhum, que é o comportamento de hoje.
   */
  fun abrirSessao(configuracao: ConfiguracaoDeEscuta, sampleRate: Int): SessaoDeAsr?
}

/**
 * Uma decodificação em andamento, criada por [MotorDeAsr.abrirSessao] e válida enquanto o estado
 * não mudar.
 *
 * `AutoCloseable` porque toda implementação segura ponteiro nativo — `Recognizer` do Vosk, `Vad`
 * e `OfflineRecognizer` do sherpa-onnx. Quem fecha é o [ReconhecedorDeComando], inclusive no
 * cancelamento da corrotina de escuta.
 */
interface SessaoDeAsr : AutoCloseable {

  /**
   * Entrega uma janela de amostras normalizadas em `-1.0..1.0` (o contrato da [FonteAudio]) e
   * devolve o que aconteceu nela.
   *
   * Qualquer conversão de escala é do motor: o Vosk quer `±32767` mesmo recebendo `float[]`, o
   * sherpa-onnx quer o normalizado que já chega. Deixar isso vazar para o chamador foi
   * justamente o que a interface veio evitar.
   */
  fun aceitar(janela: FloatArray): ResultadoDeAsr

  /**
   * Descarta o que estava acumulado e recomeça a elocução do zero.
   *
   * Chamado quando o TTS para de falar: o que entrou no decodificador enquanto o sistema falava
   * é o próprio áudio dele vazando pelo microfone, e não pode contar como fala do operador
   * (add-state-driven-voice-flow - Decisão 6).
   */
  fun reiniciar()
}

/** O que uma janela de amostras produziu. */
sealed interface ResultadoDeAsr {

  /**
   * A elocução ainda não fechou.
   *
   * @property parcial a hipótese em andamento, **só para log** — é o que torna visível o que o
   *   ASR está ouvindo enquanto ouve, em vez de só o que sobrou no fim. Vazia quando o motor não
   *   tem hipótese parcial (o pipeline VAD-então-ASR não tem: até o VAD fechar o trecho, não há
   *   nada decodificado) ou quando [AjustesAsr.logParciais] está desligado.
   */
  data class EmAndamento(val parcial: String) : ResultadoDeAsr

  /**
   * A elocução fechou.
   *
   * @property texto o resultado final, **já normalizado pelo motor** — sem pontuação, sem
   *   capitalização de início de frase, sem o JSON do Vosk (design.md - Decisão 3). Vazio quando
   *   a elocução fechou sem nada decodificado, que é o caso comum de silêncio e não publica nada.
   */
  data class Fechada(val texto: String) : ResultadoDeAsr

  companion object {
    /** O caso mais frequente de todos: nada decodificado, nada a logar. */
    val NADA: ResultadoDeAsr = EmAndamento("")
  }
}
