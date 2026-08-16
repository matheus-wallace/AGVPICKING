package com.agvtronic.pickvoice.data.model

/**
 * Ordem de separação completa, com as linhas na ordem em que devem ser percorridas.
 *
 * Identificada por [praca] + [pedido] porque é assim que o WMS identifica: a chave de
 * `wmsesto2` começa em `PRACA, PEDIDO` e toda consulta de separação do RF filtra pelos
 * dois. [id] existe só como forma achatada pra máquina de estados, que trata a ordem como
 * um identificador opaco.
 *
 * A ordem de visita das [linhas] é a do WMS: `rua`, depois `predio`, depois `andar`.
 */
data class Ordem(
    /** `wmsesto2.praca` — código alfanumérico de 11 caracteres da praça de separação. */
    val praca: String,
    /** `wmsesto2.pedido` — numérico, ~6 dígitos. */
    val pedido: String,
    val cliente: String,
    val linhas: List<Linha>,
) {
  /** Chave achatada `praca-pedido`, usada por quem só precisa de um identificador. */
  val id: String
    get() = "$praca-$pedido"

  /** O mesmo que a tela de seleção de ordem mostra antes de carregar a ordem inteira. */
  val resumo: ResumoOrdem
    get() =
        ResumoOrdem(
            praca = praca,
            pedido = pedido,
            cliente = cliente,
            totalLinhas = linhas.size,
            totalUnidades = linhas.sumOf { it.quantidade },
        )
}

/** Cabeçalho de ordem para a lista de seleção (doc §12, tela "Ordem de separação"). */
data class ResumoOrdem(
    val praca: String,
    val pedido: String,
    val cliente: String,
    val totalLinhas: Int,
    val totalUnidades: Int,
) {
  val id: String
    get() = "$praca-$pedido"
}
