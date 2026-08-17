package com.agvtronic.pickvoice.audio

/**
 * O que escutar num estado: o vocabulário aceito e a janela de endpoint.
 *
 * É a saída do [SeletorDeEscuta] e a única coisa que o [ReconhecedorDeComando] precisa saber
 * para construir um `Recognizer` — nenhuma regra de domínio atravessa para o lado do Vosk.
 *
 * @property palavras vocabulário fechado do estado. Vazio significa vocabulário **aberto**, o
 *   caso único do relato de exceção (design.md - Decisão 2).
 * @property perfil quanto silêncio fecha a elocução neste estado (doc §5.1).
 */
data class ConfiguracaoDeEscuta(
    val palavras: List<String>,
    val perfil: PerfilEndpoint,
) {

  /** `true` no único estado sem gramática fechada: o relato de exceção. */
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
