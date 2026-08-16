package com.agvtronic.pickvoice.vision

import org.junit.Assert.assertEquals
import org.junit.Test

class PreparadorFotoTest {
  @Test
  fun `recorte de sessenta por cento fica centralizado`() {
    val area = calcularRecorteCentral(1000, 500, 0.6f)

    assertEquals(RetanguloRecorte(x = 200, y = 100, largura = 600, altura = 300), area)
  }

  @Test
  fun `fator um cobre a imagem inteira`() {
    assertEquals(
        RetanguloRecorte(x = 0, y = 0, largura = 17, altura = 13),
        calcularRecorteCentral(17, 13, 1f),
    )
  }
}
