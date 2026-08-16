package com.agvtronic.pickvoice.audio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifica que a degradação de canal do doc §5.2/§10.1 faz o que promete: passa a banda de
 * voz, corta o que está fora dela e entrega metade das amostras.
 *
 * Teste de JVM comum, sem emulador nem Robolectric — é exatamente por isso que a DSP mora numa
 * classe sem nenhum import de Android, separada do `AudioRecord`.
 *
 * A medida usada é a **amplitude RMS** de um tom senoidal antes e depois do filtro. Para uma
 * senoide de amplitude A, o RMS é `A/sqrt(2)`; comparar RMS de entrada e saída dá o ganho do
 * filtro naquela frequência, que é a forma direta de checar uma resposta em frequência.
 */
class DegradacaoCanalTelefonicoTest {

  @Test
  fun `decima de 16 kHz para 8 kHz, devolvendo metade das amostras`() {
    val degradacao = DegradacaoCanalTelefonico(SAMPLE_RATE_ENTRADA)

    val saida = degradacao.processar(FloatArray(1_600))

    assertEquals(800, saida.size)
    assertEquals(DegradacaoCanalTelefonico.SAMPLE_RATE_SAIDA, 8_000)
  }

  @Test
  fun `tom de 1 kHz esta dentro da banda de voz e sobrevive`() {
    val ganho = ganhoEm(1_000.0)

    // Dentro da banda passante o filtro é aproximadamente transparente. A folga cobre a
    // ondulação normal de dois biquads em cascata.
    assertTrue("1 kHz deveria passar quase intacto, ganho=$ganho", ganho > 0.7)
  }

  @Test
  fun `tom de 100 Hz esta abaixo da banda e e atenuado`() {
    val ganho = ganhoEm(100.0)

    // Duas oitavas abaixo do corte de 300 Hz: um passa-alta de 2a ordem derruba ~12 dB/oitava.
    assertTrue("100 Hz deveria ser cortado, ganho=$ganho", ganho < 0.2)
  }

  @Test
  fun `tom de 6 kHz esta acima da banda e e atenuado antes de virar aliasing`() {
    val ganho = ganhoEm(6_000.0)

    // O caso que mais importa: 6 kHz está acima do Nyquist de 4 kHz da saída. Sem o
    // passa-baixa, a decimação dobraria essa energia de volta para 2 kHz — bem no meio da
    // banda de voz, como ruído que nada depois consegue remover.
    assertTrue("6 kHz deveria ser cortado antes da decimação, ganho=$ganho", ganho < 0.1)
  }

  @Test
  fun `instancias diferentes nao compartilham o estado dos filtros`() {
    val tom = senoide(1_000.0, AMOSTRAS)

    val deUmaVez = DegradacaoCanalTelefonico(SAMPLE_RATE_ENTRADA).processar(tom)
    val emDuasPartes =
        DegradacaoCanalTelefonico(SAMPLE_RATE_ENTRADA).let { d ->
          d.processar(tom.copyOfRange(0, AMOSTRAS / 2)) +
              d.processar(tom.copyOfRange(AMOSTRAS / 2, AMOSTRAS))
        }

    // A mesma instância processando em dois blocos tem que dar o mesmo resultado que num
    // bloco só — é o que garante que o estado do biquad atravessa as chamadas corretamente,
    // sem descontinuidade na emenda entre janelas de captura.
    assertEquals(deUmaVez.size, emDuasPartes.size)
    deUmaVez.indices.forEach { i ->
      assertEquals("amostra $i", deUmaVez[i], emDuasPartes[i], 1e-6f)
    }
  }

  // -----------------------------------------------------------------------------------
  // Auxiliares
  // -----------------------------------------------------------------------------------

  /** Ganho do filtro na frequência dada, como razão entre o RMS de saída e o de entrada. */
  private fun ganhoEm(frequenciaHz: Double): Double {
    val entrada = senoide(frequenciaHz, AMOSTRAS)
    val saida = DegradacaoCanalTelefonico(SAMPLE_RATE_ENTRADA).processar(entrada)

    // Descarta o começo dos dois sinais: os biquads partem com estado zerado e levam alguns
    // milissegundos para assentar, e esse transitório não representa o regime permanente.
    return rms(saida, descartar = saida.size / 4) / rms(entrada, descartar = entrada.size / 4)
  }

  private fun senoide(frequenciaHz: Double, amostras: Int) =
      FloatArray(amostras) { i -> sin(2.0 * PI * frequenciaHz * i / SAMPLE_RATE_ENTRADA).toFloat() }

  private fun rms(sinal: FloatArray, descartar: Int): Double {
    val considerado = sinal.drop(descartar)
    return sqrt(considerado.sumOf { it.toDouble() * it.toDouble() } / considerado.size)
  }

  private companion object {
    const val SAMPLE_RATE_ENTRADA = 16_000

    /** 0,5 s — folgado o bastante para o transitório dos filtros ser irrelevante no RMS. */
    const val AMOSTRAS = 8_000
  }
}
