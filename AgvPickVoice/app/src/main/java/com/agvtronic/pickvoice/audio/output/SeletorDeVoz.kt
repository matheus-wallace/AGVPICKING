package com.agvtronic.pickvoice.audio.output

/** Metadados mínimos de uma voz do motor Android, mantidos puros para seleção testável. */
data class CandidataDeVoz(
    val nome: String,
    val idioma: String,
    val pais: String,
    val qualidade: Int,
    val latencia: Int,
    val requerRede: Boolean,
)

/**
 * Escolhe a voz pt-BR mais natural declarada pelo motor.
 *
 * Qualidade vem antes de conectividade porque esta é a preferência explícita do produto. Em
 * empate, uma voz local e de menor latência evita piorar a resposta no galpão.
 */
object SeletorDeVoz {
  fun melhorPtBr(
      candidatas: Iterable<CandidataDeVoz>,
      nomePreferido: String? = null,
  ): CandidataDeVoz? {
    val ptBr =
        candidatas
          .filter { it.idioma.equals("pt", ignoreCase = true) && it.pais.equals("BR", true) }
    return ptBr.firstOrNull { it.nome == nomePreferido }
        ?: ptBr.sortedWith(
            compareByDescending<CandidataDeVoz> { it.qualidade }
                .thenBy { it.requerRede }
                .thenBy { it.latencia }
                .thenBy { it.nome },
        )
            .firstOrNull()
  }
}
