package com.agvtronic.pickvoice.vision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricasCapturaTest {

  @Test
  fun `padrao com bordas tem mais variancia que imagem lisa`() {
    val lisa = recorte { _, _ -> 100 }
    val barras = recorte { x, _ -> if ((x / 2) % 2 == 0) 20 else 230 }

    val metricaLisa = AnalisadorMetricasCaptura().analisar(lisa)
    val metricaBarras = AnalisadorMetricasCaptura().analisar(barras)

    assertTrue(metricaBarras.varianciaLaplaciano > metricaLisa.varianciaLaplaciano)
  }

  @Test
  fun `quadros iguais possuem diferenca temporal zero`() {
    val analisador = AnalisadorMetricasCaptura()
    val barras = recorte { x, _ -> if (x % 2 == 0) 0 else 255 }

    analisador.analisar(barras)
    val segunda = analisador.analisar(barras)

    assertTrue(segunda.diferencaTemporalMedia == 0.0)
  }

  @Test
  fun `gatilho exige tres quadros elegiveis`() {
    val gatilho = GatilhoDeCaptura(ajustes())
    val boa = MetricasCaptura(varianciaLaplaciano = 500.0, diferencaTemporalMedia = 1.0)

    assertFalse(gatilho.avaliar(boa, 0).capturar)
    assertFalse(gatilho.avaliar(boa, 100).capturar)
    assertTrue(gatilho.avaliar(boa, 200).capturar)
  }

  @Test
  fun `movimento reinicia sequencia estavel`() {
    val gatilho = GatilhoDeCaptura(ajustes())
    val boa = MetricasCaptura(500.0, 1.0)
    val movimento = MetricasCaptura(500.0, 30.0)

    gatilho.avaliar(boa, 0)
    gatilho.avaliar(boa, 100)
    assertFalse(gatilho.avaliar(movimento, 200).capturar)
    assertFalse(gatilho.avaliar(boa, 300).capturar)
  }

  @Test
  fun `fracasso aplica cooldown e terceira falha esgota ciclo`() {
    val gatilho = GatilhoDeCaptura(ajustes(quadrosEstaveisParaCaptura = 1))
    val boa = MetricasCaptura(500.0, 1.0)

    assertTrue(gatilho.avaliar(boa, 0).capturar)
    assertFalse(gatilho.registrarFracasso(10))
    assertFalse(gatilho.avaliar(boa, 1_000).capturar)
    assertTrue(gatilho.avaliar(boa, 1_510).capturar)
    assertFalse(gatilho.registrarFracasso(1_520))
    assertTrue(gatilho.avaliar(boa, 3_020).capturar)
    assertTrue(gatilho.registrarFracasso(3_030))
    assertFalse(gatilho.avaliar(boa, 5_000).capturar)
  }

  @Test
  fun `orientacao e emitida uma vez depois de oito segundos`() {
    val gatilho = GatilhoDeCaptura(ajustes())
    val ruim = MetricasCaptura(0.0, 100.0)

    assertFalse(gatilho.avaliar(ruim, 0).orientarOperador)
    assertTrue(gatilho.avaliar(ruim, 8_000).orientarOperador)
    assertFalse(gatilho.avaliar(ruim, 9_000).orientarOperador)
  }

  private fun ajustes(quadrosEstaveisParaCaptura: Int = 3) =
      AjustesVisao(
          quadrosEstaveisParaCaptura = quadrosEstaveisParaCaptura,
          cooldownCapturaMs = 1_500,
          maxTentativasCaptura = 3,
      )

  private fun recorte(pixel: (x: Int, y: Int) -> Int): RecorteNv21 {
    val largura = 64
    val altura = 64
    val bytes = ByteArray(largura * altura * 3 / 2)
    for (y in 0 until altura) {
      for (x in 0 until largura) bytes[y * largura + x] = pixel(x, y).toByte()
    }
    return RecorteNv21(bytes, largura, altura)
  }
}
