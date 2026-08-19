package com.agvtronic.pickvoice.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O [ReamostradorLinear] é a única parte do `MotorSherpaOnnx` que roda na JVM: não depende de
 * `Context`, de `AssetManager` nem das bibliotecas nativas do ONNX Runtime (tarefa 4.3).
 *
 * Vale testá-lo com cuidado porque o custo de ele estar errado é alto e mudo: taxa errada chegando
 * ao Silero VAD não lança exceção, chama `_Exit` e mata o processo; e descontinuidade entre
 * janelas vira um clique periódico que um detector de voz confunde com início de fala.
 */
class ReamostradorLinearTest {

  private val toleranciaDeArredondamento = 1e-5f

  @Test
  fun `taxas iguais devolvem a mesma janela sem copiar`() {
    // O caminho de `degradarCanal=false`, em que a fonte já entrega 16 kHz.
    val reamostrador = ReamostradorLinear(16_000, 16_000)
    val janela = floatArrayOf(0.1f, -0.2f, 0.3f)

    assertSame(janela, reamostrador.processar(janela))
  }

  @Test
  fun `dobrar a taxa produz aproximadamente o dobro de amostras`() {
    val reamostrador = ReamostradorLinear(8_000, 16_000)
    val janela = FloatArray(512) { 0f }

    val primeira = reamostrador.processar(janela)
    val segunda = reamostrador.processar(janela)

    // A fase não fecha em múltiplo inteiro da janela, então o tamanho alterna — o que importa
    // é o total, que precisa ser o dobro da entrada dentro de uma amostra.
    assertTrue("primeira janela: ${primeira.size}", abs(primeira.size - 1_024) <= 1)
    assertTrue("segunda janela: ${segunda.size}", abs(segunda.size - 1_024) <= 1)
    assertTrue(abs((primeira.size + segunda.size) - 2_048) <= 1)
  }

  @Test
  fun `interpola o ponto medio entre duas amostras`() {
    val reamostrador = ReamostradorLinear(8_000, 16_000)

    val saida = reamostrador.processar(floatArrayOf(0f, 1f, 0f))

    // Posições 0, 0.5, 1.0, 1.5, 2.0 sobre [0, 1, 0].
    assertEquals(0f, saida[0], toleranciaDeArredondamento)
    assertEquals(0.5f, saida[1], toleranciaDeArredondamento)
    assertEquals(1f, saida[2], toleranciaDeArredondamento)
    assertEquals(0.5f, saida[3], toleranciaDeArredondamento)
    assertEquals(0f, saida[4], toleranciaDeArredondamento)
  }

  @Test
  fun `mantem a fase entre janelas consecutivas`() {
    val reamostrador = ReamostradorLinear(8_000, 16_000)

    // Duas janelas de uma rampa contínua: 0,1,2,3 seguido de 4,5,6,7.
    reamostrador.processar(floatArrayOf(0f, 1f, 2f, 3f))
    val segunda = reamostrador.processar(floatArrayOf(4f, 5f, 6f, 7f))

    // A primeira saída da segunda janela cai entre 3 (última da anterior) e 4 (primeira desta).
    // Sem guardar a amostra anterior, este valor seria 4 e a rampa teria um degrau.
    assertEquals(3.5f, segunda[0], toleranciaDeArredondamento)
    assertEquals(4f, segunda[1], toleranciaDeArredondamento)
  }

  @Test
  fun `sem continuidade entre janelas apareceria um degrau`() {
    // Contraprova do teste acima: um reamostrador recém-criado não conhece a janela anterior,
    // então começa em 4 em vez de 3,5. É exatamente esse degrau que o estado interno evita.
    val semHistorico = ReamostradorLinear(8_000, 16_000)

    val saida = semHistorico.processar(floatArrayOf(4f, 5f, 6f, 7f))

    assertNotEquals(3.5f, saida[0])
    assertEquals(4f, saida[0], toleranciaDeArredondamento)
  }

  @Test
  fun `reiniciar esquece a fase e a amostra guardada`() {
    val reamostrador = ReamostradorLinear(8_000, 16_000)
    reamostrador.processar(floatArrayOf(0f, 1f, 2f, 3f))

    reamostrador.reiniciar()
    val saida = reamostrador.processar(floatArrayOf(4f, 5f, 6f, 7f))

    assertEquals(4f, saida[0], toleranciaDeArredondamento)
  }

  @Test
  fun `preserva a forma de uma senoide dentro da banda`() {
    // 500 Hz a 8 kHz: bem abaixo dos 4 kHz de Nyquist, então a interpolação linear tem de
    // reproduzir a onda com erro pequeno. Serve como sanidade de que a conversão não inverte
    // fase nem desloca o sinal.
    val reamostrador = ReamostradorLinear(8_000, 16_000)
    val entrada = FloatArray(256) { sin(2 * PI * 500 * it / 8_000).toFloat() }

    val saida = reamostrador.processar(entrada)

    val esperado = FloatArray(saida.size) { sin(2 * PI * 500 * it / 16_000).toFloat() }
    val maiorErro = saida.indices.maxOf { abs(saida[it] - esperado[it]) }
    assertTrue("maior erro: $maiorErro", maiorErro < 0.05f)
  }

  @Test
  fun `janela vazia nao quebra nem avanca a fase`() {
    val reamostrador = ReamostradorLinear(8_000, 16_000)

    assertEquals(0, reamostrador.processar(FloatArray(0)).size)
    assertEquals(0f, reamostrador.processar(floatArrayOf(0f, 1f))[0], toleranciaDeArredondamento)
  }

  @Test
  fun `amostras seguem dentro do contrato de escala da FonteAudio`() {
    // Interpolar entre dois valores nunca sai do intervalo deles, então o -1.0..1.0 que o
    // sherpa-onnx exige continua valendo sem precisar de clipping.
    val reamostrador = ReamostradorLinear(8_000, 16_000)
    val entrada = FloatArray(512) { if (it % 2 == 0) -1f else 1f }

    val saida = reamostrador.processar(entrada)

    assertTrue(saida.all { it in -1f..1f })
  }
}
