package com.agvtronic.pickvoice.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeometriaPreviewTest {

  @Test
  fun `mesma proporcao ocupa todo o container`() {
    val geometria = calcularGeometriaPreview(360f, 640f, 360, 640, 0.60f)!!

    assertRetangulo(geometria.video, 0f, 0f, 360f, 640f)
    assertRetangulo(geometria.roi, 72f, 128f, 216f, 384f)
  }

  @Test
  fun `frame largo cria barras verticais e roi fica dentro do video`() {
    val geometria = calcularGeometriaPreview(300f, 600f, 400, 200, 0.50f)!!

    assertRetangulo(geometria.video, 0f, 225f, 300f, 150f)
    assertRetangulo(geometria.roi, 75f, 262.5f, 150f, 75f)
  }

  @Test
  fun `frame estreito cria barras laterais e roi fica dentro do video`() {
    val geometria = calcularGeometriaPreview(600f, 300f, 200, 400, 0.70f)!!

    assertRetangulo(geometria.video, 225f, 0f, 150f, 300f)
    assertRetangulo(geometria.roi, 247.5f, 45f, 105f, 210f)
  }

  @Test
  fun `fator fora do intervalo e limitado`() {
    val cheio = calcularGeometriaPreview(100f, 100f, 100, 100, 2f)!!
    val vazio = calcularGeometriaPreview(100f, 100f, 100, 100, -1f)!!

    assertRetangulo(cheio.roi, 0f, 0f, 100f, 100f)
    assertRetangulo(vazio.roi, 50f, 50f, 0f, 0f)
  }

  @Test
  fun `dimensao invalida nao produz geometria`() {
    assertNull(calcularGeometriaPreview(0f, 100f, 100, 100, 0.6f))
    assertNull(calcularGeometriaPreview(100f, 100f, 0, 100, 0.6f))
  }

  private fun assertRetangulo(
      atual: RetanguloPreview,
      esquerda: Float,
      topo: Float,
      largura: Float,
      altura: Float,
  ) {
    assertEquals(esquerda, atual.esquerda, TOLERANCIA)
    assertEquals(topo, atual.topo, TOLERANCIA)
    assertEquals(largura, atual.largura, TOLERANCIA)
    assertEquals(altura, atual.altura, TOLERANCIA)
  }

  private companion object {
    const val TOLERANCIA = 0.001f
  }
}
