package com.agvtronic.pickvoice.data.model

import java.time.Instant

/** Fechamento da ordem, produzido no estado `ConferenciaFinal`. */
data class Conferencia(
    val ordemId: String,
    val totalLinhas: Int,
    /** Linhas com coleta registrada. */
    val linhasColetadas: Int,
    /** Índices das linhas cuja quantidade coletada difere da pedida. */
    val linhasDivergentes: List<Int>,
    val excecoes: List<Excecao>,
    val fechadaEm: Instant,
) {
  /** Conferência sem divergência e sem exceção — o caminho feliz do fim da ordem. */
  val conforme: Boolean
    get() = linhasColetadas == totalLinhas && linhasDivergentes.isEmpty() && excecoes.isEmpty()
}
