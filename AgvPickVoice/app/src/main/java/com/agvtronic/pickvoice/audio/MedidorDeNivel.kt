package com.agvtronic.pickvoice.audio

import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Mede quanta energia está de fato chegando da [FonteAudio], em dBFS, e relata uma vez por
 * intervalo.
 *
 * ### Por que isto existe
 *
 * A verificação em bancada desta fatia já custou caro por falta desta medida. O
 * `VOICE_COMMUNICATION` devolvia silêncio digital sem nenhum erro no log, e a escala errada
 * (`±1.0` em vez de `±32767`) fazia o decodificador não reconhecer nada, também em silêncio.
 * Nos dois casos o sintoma era idêntico — "falo e não acontece nada" — e a causa só apareceu
 * depois de medir o sinal. Com o medidor no caminho quente, a primeira pergunta de qualquer
 * investigação futura ("o microfone está entregando alguma coisa?") se responde lendo o
 * logcat, não instrumentando o código de novo.
 *
 * ### Como ler o número
 *
 * `0 dBFS` é o máximo que a escala representa; tudo é negativo. Como referência do que já foi
 * medido neste projeto no Galaxy S20 FE: fala normal a um palmo do aparelho dá pico por volta
 * de **-28 dBFS**, o alto-falante do Mac a um metro dá cerca de **-30 dBFS** (fraco demais
 * para o ASR decidir) e o `VOICE_COMMUNICATION` quebrado dava **-80 dBFS**, que é silêncio.
 *
 * Kotlin puro, sem Android: mesma razão da [DegradacaoCanalTelefonico], dá para testar a
 * aritmética na JVM sem emulador.
 *
 * **Guarda estado** entre chamadas, então cada fluxo de captura precisa da sua instância.
 *
 * @param sampleRate taxa do fluxo medido, usada só para converter [intervaloMs] em amostras.
 * @param intervaloMs de quanto em quanto tempo [acumular] devolve uma leitura.
 */
class MedidorDeNivel(sampleRate: Int, intervaloMs: Int = INTERVALO_PADRAO_MS) {

  private val amostrasPorLeitura = sampleRate * intervaloMs / 1_000

  private var somaDosQuadrados = 0.0
  private var pico = 0f
  private var acumuladas = 0

  /**
   * Acumula uma janela e devolve a leitura quando o intervalo fecha.
   *
   * @return `null` na maioria das chamadas — só a janela que completa o intervalo produz uma
   *   [Leitura], e o acumulador reinicia em seguida.
   */
  fun acumular(janela: FloatArray): Leitura? {
    for (amostra in janela) {
      somaDosQuadrados += (amostra.toDouble() * amostra)
      val amplitude = abs(amostra)
      if (amplitude > pico) pico = amplitude
    }
    acumuladas += janela.size

    if (acumuladas < amostrasPorLeitura) return null

    val leitura =
        Leitura(
            rmsDbfs = emDbfs(sqrt(somaDosQuadrados / acumuladas).toFloat()),
            picoDbfs = emDbfs(pico),
        )

    somaDosQuadrados = 0.0
    pico = 0f
    acumuladas = 0

    return leitura
  }

  /** Nível do intervalo que acabou de fechar. */
  data class Leitura(val rmsDbfs: Float, val picoDbfs: Float) {
    // Locale.US fixo: este texto vai para o logcat, onde "-28.4" é o que se espera ler e
    // grepar, não o "-28,4" que a localidade pt-BR do aparelho produziria.
    override fun toString(): String =
        String.format(Locale.US, "rms=%.1f dBFS pico=%.1f dBFS", rmsDbfs, picoDbfs)
  }

  companion object {
    /** Um relato por segundo: legível no logcat sem inundá-lo. */
    const val INTERVALO_PADRAO_MS = 1_000

    /**
     * Piso da escala. `log10(0)` é `-infinito`, que polui o log e não diz nada além de
     * "silêncio absoluto"; -120 dBFS já está muito abaixo do ruído de fundo de qualquer
     * microfone real.
     */
    const val PISO_DBFS = -120f

    /** Amplitude linear (`0.0..1.0`) para decibéis relativos à escala cheia. */
    fun emDbfs(amplitude: Float): Float =
        if (amplitude <= 0f) PISO_DBFS
        else (20.0 * log10(amplitude.toDouble())).toFloat().coerceAtLeast(PISO_DBFS)
  }
}
