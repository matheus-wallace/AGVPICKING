package com.agvtronic.pickvoice.audio.output

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeletorDeVozTest {
  @Test
  fun `prioriza qualidade mais alta mesmo que use rede`() {
    val selecionada =
        SeletorDeVoz.melhorPtBr(
            listOf(
                voz("local-alta", qualidade = 400, latencia = 200, requerRede = false),
                voz("neural", qualidade = 500, latencia = 400, requerRede = true),
            )
        )

    assertEquals("neural", selecionada?.nome)
  }

  @Test
  fun `em empate prefere local e menor latencia`() {
    val selecionada =
        SeletorDeVoz.melhorPtBr(
            listOf(
                voz("remota", qualidade = 500, latencia = 100, requerRede = true),
                voz("local-lenta", qualidade = 500, latencia = 300, requerRede = false),
                voz("local-rapida", qualidade = 500, latencia = 100, requerRede = false),
            )
        )

    assertEquals("local-rapida", selecionada?.nome)
  }

  @Test
  fun `preserva a voz pt-BR escolhida como padrao pelo motor`() {
    val selecionada =
        SeletorDeVoz.melhorPtBr(
            listOf(
                voz("variante-3", qualidade = 300),
                voz("outra-mais-alta", qualidade = 500),
            ),
            nomePreferido = "variante-3",
        )

    assertEquals("variante-3", selecionada?.nome)
  }

  @Test
  fun `nao usa portugues de outro pais como substituto`() {
    assertNull(SeletorDeVoz.melhorPtBr(listOf(voz("pt-pt", pais = "PT"))))
  }

  private fun voz(
      nome: String,
      qualidade: Int = 400,
      latencia: Int = 200,
      requerRede: Boolean = false,
      pais: String = "BR",
  ) = CandidataDeVoz(nome, "pt", pais, qualidade, latencia, requerRede)
}
