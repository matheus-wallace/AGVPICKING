package com.agvtronic.pickvoice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InterpretadorDeQuantidadeRhinoTest {

  @Test
  fun `interpreta unidades e dezenas`() {
    listOf(4, 12, 20, 21, 22, 99).forEach { valor ->
      assertEquals(valor, interpretar(c1 = valor.toString()))
    }
  }

  @Test
  fun `interpreta centenas`() {
    assertEquals(100, interpretar(b1 = "cem"))
    assertEquals(104, interpretar(b1 = "cento", c1 = "4"))
    assertEquals(122, interpretar(b1 = "cento", c1 = "22"))
    assertEquals(200, interpretar(b1 = "duzentos"))
    assertEquals(222, interpretar(b1 = "duzentos", c1 = "22"))
    assertEquals(999, interpretar(b1 = "novecentos", c1 = "99"))
  }

  @Test
  fun `interpreta milhares`() {
    assertEquals(1000, interpretar(a1 = "mil"))
    assertEquals(1004, interpretar(a1 = "mil", c1 = "4"))
    assertEquals(1022, interpretar(a1 = "mil", c1 = "22"))
    assertEquals(1100, interpretar(a1 = "mil", b1 = "cento"))
    assertEquals(1122, interpretar(a1 = "mil", b1 = "cento", c1 = "22"))
    assertEquals(2000, interpretar(a1 = "dois mil"))
    assertEquals(2022, interpretar(a1 = "dois mil", c1 = "22"))
    assertEquals(2200, interpretar(a1 = "dois mil", b1 = "duzentos"))
    assertEquals(2222, interpretar(a1 = "dois mil", b1 = "duzentos", c1 = "22"))
    assertEquals(3578, interpretar(a1 = "três mil", b1 = "quinhentos", c1 = "78"))
    assertEquals(9999, interpretar(a1 = "nove mil", b1 = "novecentos", c1 = "99"))
  }

  @Test
  fun `inferencia invalida nao produz quantidade parcial`() {
    assertNull(InterpretadorDeQuantidadeRhino.interpretar(emptyMap()))
    assertNull(InterpretadorDeQuantidadeRhino.interpretar(mapOf("a1" to "VALOR_DESCONHECIDO", "c1" to "22")))
    assertNull(InterpretadorDeQuantidadeRhino.interpretar(mapOf("b1" to "centena desconhecida", "c1" to "22")))
    assertNull(InterpretadorDeQuantidadeRhino.interpretar(mapOf("c1" to "0")))
    assertNull(InterpretadorDeQuantidadeRhino.interpretar(mapOf("c1" to "100")))
    assertNull(InterpretadorDeQuantidadeRhino.interpretar(mapOf("slot_extra" to "1", "c1" to "22")))
  }

  @Test
  fun `quantidade sintetizada produz o evento de dominio existente`() {
    val texto =
        SintetizadorDeIntencaoRhino.sintetizarQuantidade(
            mapOf("a1" to "dois mil", "b1" to "duzentos", "c1" to "22")
        )
    val estado =
        com.agvtronic.pickvoice.domain.statemachine.PickingState.ConfirmandoQuantidade(
            com.agvtronic.pickvoice.domain.statemachine.ItemEmAndamento("ordem", 0, "endereco", 1),
            1,
        )

    assertEquals(
        IntencaoDeVoz.Direta(
            com.agvtronic.pickvoice.domain.statemachine.PickingEvent.QuantidadeInformada(2222)
        ),
        InterpretadorDeFala.interpretar(estado, texto),
    )
  }

  private fun interpretar(a1: String? = null, b1: String? = null, c1: String? = null): Int? {
    val slots = buildMap {
      a1?.let { put("a1", it) }
      b1?.let { put("b1", it) }
      c1?.let { put("c1", it) }
    }
    return InterpretadorDeQuantidadeRhino.interpretar(slots)
  }
}
