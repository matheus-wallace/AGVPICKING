package com.agvtronic.pickvoice.audio.output

/** Prioridade operacional da fala. Alertas críticos interrompem a fila de rotina. */
enum class PrioridadeFala {
  ROTINA,
  CRITICA,
}

/**
 * Mensagem já projetada e pronta para uma implementação de saída.
 *
 * [chave] identifica semanticamente a mensagem sem exigir que diagnóstico ou UI exponham o
 * texto. Uma futura saída Piper pode usar a mesma chave para resolver clips pré-renderizados.
 */
data class MensagemFalavel(
    val chave: String,
    val texto: String,
    val prioridade: PrioridadeFala = PrioridadeFala.ROTINA,
)
