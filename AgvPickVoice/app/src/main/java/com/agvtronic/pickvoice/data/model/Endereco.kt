package com.agvtronic.pickvoice.data.model

/**
 * Posição no armazém, no vocabulário do WMS da AGV — doc §11.2.
 *
 * **Renomeado em relação ao rascunho inicial** (`rua, predio, nivel, posicao`) para bater com
 * o WMS de produção, que é o sistema com que este protótipo vai integrar:
 *
 * - `nivel` → [andar]: a chave primária de `wmscam2` é
 *   `(UNIDADE, CD, SETOR, SUBSETOR, RUA, PREDIO, ANDAR)`. Não existe "nível" no cadastro de
 *   endereço; o que o operador chama de nível o WMS chama de andar, e é uma **letra**, não
 *   um número — o app de RF chega a fazer `andar.trim().charAt(0)` pra decidir fluxo.
 * - `posicao` foi **removido**: a granularidade do endereço no WMS termina em andar. Manter
 *   um quarto nível que o WMS não tem produziria endereços impossíveis de casar com
 *   `wmscam2` na hora da integração real.
 * - [cd] e [setor] entraram porque o código de barras da etiqueta os inclui — sem eles não
 *   dá pra reproduzir o [codbarra] que a câmera lê.
 *
 * Campos são `String` e não `Int` de propósito: endereço de armazém é código, não número.
 * Zeros à esquerda são significativos na etiqueta e na fala turn-by-turn, e `"04"` nunca
 * deve virar `4`.
 */
data class Endereco(
    /** Centro de distribuição — dois dígitos. */
    val cd: String,
    /** Setor dentro do CD — dois dígitos. */
    val setor: String,
    /** Andar, uma letra. É o que o rascunho chamava de "nível". */
    val andar: String,
    /** Prédio. Guardado **sem** zeros à esquerda; o [codbarra] é quem preenche pra 4. */
    val predio: String,
    /** Rua. Uma letra (ou mais, em setores com nomenclatura estendida). */
    val rua: String,
) {
  /**
   * O código de barras impresso na etiqueta da posição.
   *
   * Layout confirmado no WMS: `cd + setor + andar + predio(4, zero à esquerda) + rua`,
   * ex.: `7204B0118D` = CD 72, setor 04, andar B, prédio 0118, rua D. O `padStart` está
   * aqui porque o app de RF monta a mesma string preenchendo o prédio na hora
   * (`'0000'.substr(0, 4 - predio.length) + predio`).
   */
  val codbarra: String
    get() = "$cd$setor$andar${predio.padStart(4, '0')}$rua"

  /**
   * A forma falada da posição, pro turn-by-turn por voz.
   *
   * Separada do [codbarra] de propósito: ler dez caracteres colados em 8 kHz é ruído, e o
   * operador navega por rua/prédio/andar, não pelo código.
   */
  val etiqueta: String
    get() = "Rua $rua, prédio ${predio.trimStart('0').ifEmpty { "0" }}, andar $andar"
}
