package com.agvtronic.pickvoice.vision

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LimpadorCapturasTest {
  @get:Rule val pasta = TemporaryFolder()

  @Test
  fun `remove arquivos da pasta reservada sem atravessar subdiretorios`() {
    val reservado = pasta.newFolder("capturas-visao")
    val residuo = File(reservado, "interrompida.tmp").apply { writeText("pixels") }
    val subdiretorio = File(reservado, "outro").apply { mkdir() }
    val fora = pasta.newFile("preservar.txt")

    assertEquals(1, limparTemporariosDeCaptura(reservado))
    assertFalse(residuo.exists())
    assertTrue(subdiretorio.exists())
    assertTrue(fora.exists())
  }
}
