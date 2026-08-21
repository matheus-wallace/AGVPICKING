package com.agvtronic.pickvoice.audio

/** Converte os slots estruturados do contexto Rhino de quantidade em um valor de domínio. */
object InterpretadorDeQuantidadeRhino {

  /**
   * Interpreta `a1` (milhar), `b1` (centena) e `c1` (1..99).
   *
   * A validação é deliberadamente estrita: slot desconhecido ou valor inesperado invalida a
   * inferência inteira, em vez de produzir silenciosamente uma quantidade parcial.
   */
  fun interpretar(slots: Map<String, String>): Int? {
    if (slots.isEmpty() || slots.keys.any { it !in SLOTS_ACEITOS }) return null

    val milhar = slots["a1"]?.normalizado()?.let(MILHARES::get) ?: 0
    if ("a1" in slots && milhar == 0) return null

    val centena = slots["b1"]?.normalizado()?.let(CENTENAS::get) ?: 0
    if ("b1" in slots && centena == 0) return null

    val resto =
        slots["c1"]?.trim()?.takeIf { it.matches(UM_OU_DOIS_DIGITOS) }?.toIntOrNull() ?: 0
    if ("c1" in slots && resto !in 1..99) return null

    return (milhar + centena + resto).takeIf { it in QUANTIDADE_ACEITA }
  }

  private fun String.normalizado(): String = trim().lowercase().replace(ESPACOS, " ")

  private val SLOTS_ACEITOS = setOf("a1", "b1", "c1")
  private val UM_OU_DOIS_DIGITOS = Regex("[0-9]{1,2}")
  private val ESPACOS = Regex("\\s+")
  private val QUANTIDADE_ACEITA = 1..9_999

  private val MILHARES =
      mapOf(
          "mil" to 1_000,
          "dois mil" to 2_000,
          "três mil" to 3_000,
          "quatro mil" to 4_000,
          "cinco mil" to 5_000,
          "seis mil" to 6_000,
          "sete mil" to 7_000,
          "oito mil" to 8_000,
          "nove mil" to 9_000,
      )

  private val CENTENAS =
      mapOf(
          "cem" to 100,
          "cento" to 100,
          "duzentos" to 200,
          "trezentos" to 300,
          "quatrocentos" to 400,
          "quinhentos" to 500,
          "seiscentos" to 600,
          "setecentos" to 700,
          "oitocentos" to 800,
          "novecentos" to 900,
      )
}
