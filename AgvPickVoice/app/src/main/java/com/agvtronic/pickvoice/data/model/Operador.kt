package com.agvtronic.pickvoice.data.model

/**
 * Separador logado na sessão.
 *
 * Sem login nem token no protótipo: o operador é escolhido de uma lista mockada (doc §12).
 */
data class Operador(
    val id: String,
    val nome: String,
    val matricula: String,
)
