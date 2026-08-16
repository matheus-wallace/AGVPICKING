package com.agvtronic.pickvoice.audio

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifica a aritmética do medidor de nível.
 *
 * Parece um teste sobre pouca coisa, e é de propósito: o medidor existe para ser a primeira
 * resposta confiável de "o microfone está entregando sinal?", e um medidor com a conversão de
 * dBFS errada mentiria justamente na hora em que se depende dele. O caso caro já aconteceu
 * neste projeto — silêncio digital do `VOICE_COMMUNICATION` passando por horas como se fosse
 * problema de reconhecimento.
 */
class MedidorDeNivelTest {

  @Test
  fun `so relata quando o intervalo fecha`() {
    val medidor = MedidorDeNivel(SAMPLE_RATE, intervaloMs = 1_000)

    // Meio segundo a 8 kHz: metade do intervalo, nenhuma leitura ainda.
    assertNull(medidor.acumular(FloatArray(4_000)))
    // A janela que completa o segundo fecha a leitura.
    assertNotNull(medidor.acumular(FloatArray(4_000)))
    // E o acumulador reinicia: o próximo meio segundo volta a não relatar.
    assertNull(medidor.acumular(FloatArray(4_000)))
  }

  @Test
  fun `escala cheia da 0 dBFS`() {
    assertEquals(0f, MedidorDeNivel.emDbfs(1f), TOLERANCIA_DB)
  }

  @Test
  fun `metade da amplitude da aproximadamente menos 6 dBFS`() {
    // A regra de bolso do áudio: dobrar a amplitude é +6 dB.
    assertEquals(-6.02f, MedidorDeNivel.emDbfs(0.5f), TOLERANCIA_DB)
  }

  @Test
  fun `silencio absoluto cai no piso em vez de menos infinito`() {
    // log10(0) é -infinito, que não é imprimível nem comparável. O piso é o que torna o
    // silêncio legível no logcat.
    assertEquals(MedidorDeNivel.PISO_DBFS, MedidorDeNivel.emDbfs(0f), TOLERANCIA_DB)
  }

  @Test
  fun `senoide de amplitude conhecida da o rms e o pico esperados`() {
    val medidor = MedidorDeNivel(SAMPLE_RATE, intervaloMs = 1_000)
    val amplitude = 0.5f

    val leitura = medidor.acumular(senoide(1_000.0, amplitude, SAMPLE_RATE))

    assertNotNull("um segundo inteiro deveria fechar a leitura", leitura)
    // Para uma senoide de amplitude A, o RMS é A/sqrt(2) — ou seja, 3,01 dB abaixo do pico.
    assertEquals(MedidorDeNivel.emDbfs(amplitude), leitura!!.picoDbfs, TOLERANCIA_DB)
    assertEquals(leitura.picoDbfs - 3.01f, leitura.rmsDbfs, TOLERANCIA_DB)
  }

  @Test
  fun `fala fraca fica bem abaixo de fala forte`() {
    // Reproduz o contraste medido em bancada: o alto-falante do Mac a um metro contra voz
    // humana perto do aparelho. O medidor tem que separar os dois casos com folga.
    val fraco = MedidorDeNivel(SAMPLE_RATE).acumular(senoide(1_000.0, 0.03f, SAMPLE_RATE))!!
    val forte = MedidorDeNivel(SAMPLE_RATE).acumular(senoide(1_000.0, 0.3f, SAMPLE_RATE))!!

    assertTrue(
        "10x de amplitude deveria dar ~20 dB de diferença, deu ${forte.rmsDbfs - fraco.rmsDbfs}",
        forte.rmsDbfs - fraco.rmsDbfs > 19f,
    )
  }

  private fun senoide(frequenciaHz: Double, amplitude: Float, sampleRate: Int): FloatArray =
      FloatArray(sampleRate) { (amplitude * sin(2.0 * PI * frequenciaHz * it / sampleRate)).toFloat() }

  private companion object {
    const val SAMPLE_RATE = 8_000
    const val TOLERANCIA_DB = 0.1f
  }
}
