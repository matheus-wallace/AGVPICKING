package com.agvtronic.pickvoice.audio

/**
 * O que escutar num estado: o vocabulário aceito e a janela de endpoint.
 *
 * É a saída do [SeletorDeEscuta] e a única coisa que o [ReconhecedorDeComando] precisa saber
 * para construir um `Recognizer` — nenhuma regra de domínio atravessa para o lado do Vosk.
 *
 * @property palavras vocabulário fechado do estado. Vazio significa vocabulário **aberto** —
 *   um caminho que hoje nenhum estado usa, desde que `TratandoExcecao` fechou a gramática
 *   (add-voice-recognition-reliability - Decisão 2). Continua suportado porque a fatia de
 *   relato de ocorrência via LLM (doc §5.4) volta a precisar dele.
 * @property perfil quanto silêncio fecha a elocução neste estado (doc §5.1).
 * @property contextoRhino qual contexto pré-carregado o Rhino usa. Outros motores ignoram este
 *   campo e continuam usando [palavras] e [perfil].
 */
data class ConfiguracaoDeEscuta(
    val palavras: List<String>,
    val perfil: PerfilEndpoint,
    val contextoRhino: TipoContextoRhino = TipoContextoRhino.PRINCIPAL,
) {

  /** `true` quando o estado não tem gramática fechada — nenhum tem, hoje (ver [palavras]). */
  val aberta: Boolean
    get() = palavras.isEmpty()

  /**
   * A gramática no formato que o construtor do `Recognizer` espera — um array JSON de
   * palavras —, ou `null` quando o vocabulário é aberto e o modelo inteiro deve valer.
   *
   * [VocabularioDeVoz.DESCONHECIDA] entra sempre e é acrescentado aqui, num lugar só: uma
   * gramática fechada sem ele obriga o decodificador a devolver a palavra conhecida mais
   * parecida com qualquer ruído.
   */
  val gramatica: String?
    get() =
        if (aberta) null
        else
            (palavras + VocabularioDeVoz.DESCONHECIDA).joinToString(
                separator = ", ",
                prefix = "[",
                postfix = "]",
            ) {
              "\"$it\""
            }
}

/** Contextos Rhino pré-carregados; a seleção efetiva ocorre somente na thread de áudio. */
enum class TipoContextoRhino {
  PRINCIPAL,
  QUANTIDADE,
}
