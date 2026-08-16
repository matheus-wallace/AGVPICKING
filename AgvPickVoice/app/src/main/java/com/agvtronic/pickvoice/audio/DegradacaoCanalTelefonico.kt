package com.agvtronic.pickvoice.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Transforma áudio de banda larga do microfone do celular no áudio de banda estreita que o
 * óculos entrega — band-pass 300–3400 Hz seguido de decimação para 8 kHz (doc §5.2, §10.1).
 *
 * É a peça que faz [AudioMicrofoneSimulado] valer a pena: sem ela, o desenvolvimento até
 * 18/09 aconteceria contra um sinal muito melhor do que o real, e todo ajuste de
 * reconhecimento feito em agosto teria que ser refeito no dia. Com ela, o Vosk vê em bancada
 * aproximadamente o mesmo sinal que verá no HFP.
 *
 * Kotlin puro, sem nenhum import de Android, exatamente como `domain/` — é o que permite
 * testar a resposta em frequência num teste de JVM comum, sem emulador (ver
 * `DegradacaoCanalTelefonicoTest`).
 *
 * **Guarda estado** (os atrasos dos biquads), então cada fluxo de captura precisa da sua
 * própria instância. Reaproveitar uma entre dois fluxos vazaria o final de um áudio no começo
 * do outro.
 *
 * @param sampleRateEntrada taxa de amostragem da captura, múltipla de [SAMPLE_RATE_SAIDA].
 */
class DegradacaoCanalTelefonico(private val sampleRateEntrada: Int) {

  init {
    require(sampleRateEntrada % SAMPLE_RATE_SAIDA == 0) {
      "sampleRateEntrada ($sampleRateEntrada) precisa ser múltiplo de $SAMPLE_RATE_SAIDA"
    }
  }

  /** Quantas amostras de entrada viram uma de saída. 2, para 16 kHz -> 8 kHz. */
  private val fatorDecimacao = sampleRateEntrada / SAMPLE_RATE_SAIDA

  private val passaAlta = Biquad.passaAlta(CORTE_INFERIOR_HZ, sampleRateEntrada)

  /**
   * **Dois** estágios passa-baixa em cascata, não um.
   *
   * Medido pelo teste: um biquad só deixa 6 kHz passar a -19,5 dB, e essa energia volta
   * dobrada para 2 kHz na decimação — bem no meio da banda de voz. Com o segundo estágio a
   * atenuação dobra em dB e o resíduo de aliasing deixa de importar. Custa duas multiplicações
   * por amostra, o que é irrelevante perto do próprio Vosk.
   */
  private val passaBaixa1 = Biquad.passaBaixa(CORTE_SUPERIOR_HZ, sampleRateEntrada)
  private val passaBaixa2 = Biquad.passaBaixa(CORTE_SUPERIOR_HZ, sampleRateEntrada)

  /**
   * Filtra e decima um bloco de amostras.
   *
   * **A ordem importa e não é arbitrária.** O passa-baixa de 3400 Hz não está aqui só para
   * imitar a banda do telefone: ele também é o filtro anti-aliasing da decimação. A 8 kHz de
   * saída, Nyquist é 4000 Hz, e qualquer energia acima disso que sobrevivesse até a decimação
   * voltaria dobrada para dentro da banda de voz, como ruído que nenhum ajuste posterior
   * remove. Filtrar depois de decimar seria tarde demais.
   *
   * @param entrada amostras normalizadas em `-1.0..1.0` a [sampleRateEntrada].
   * @return amostras normalizadas a [SAMPLE_RATE_SAIDA], com `entrada.size / fatorDecimacao`
   *   elementos.
   */
  fun processar(entrada: FloatArray): FloatArray {
    val saida = FloatArray(entrada.size / fatorDecimacao)
    var escrita = 0

    for (i in entrada.indices) {
      val filtrada = passaBaixa2.processar(passaBaixa1.processar(passaAlta.processar(entrada[i])))
      // Decimação: todas as amostras passam pelos filtros (o estado deles depende disso),
      // mas só uma a cada `fatorDecimacao` é guardada.
      if (i % fatorDecimacao == 0 && escrita < saida.size) {
        saida[escrita++] = filtrada
      }
    }
    return saida
  }

  companion object {
    /** Taxa do HFP do óculos (doc §2.1) e, por consequência, de toda a [FonteAudio]. */
    const val SAMPLE_RATE_SAIDA = 8_000

    /** Banda do canal telefônico do doc §5.2/§10.1. */
    const val CORTE_INFERIOR_HZ = 300.0
    const val CORTE_SUPERIOR_HZ = 3_400.0
  }
}

/**
 * Filtro biquad de segunda ordem, forma direta II transposta.
 *
 * Coeficientes pelas fórmulas do "Audio EQ Cookbook" (Robert Bristow-Johnson), a referência
 * padrão para esse tipo de filtro. Q fixo em `1/sqrt(2)`, que dá resposta Butterworth — a mais
 * plana possível dentro da banda, sem ressonância na frequência de corte.
 *
 * `internal` porque só [DegradacaoCanalTelefonico] usa, mas não `private`: o teste unitário
 * verifica a resposta em frequência de cada estágio separadamente quando algo dá errado.
 */
internal class Biquad(
    private val b0: Float,
    private val b1: Float,
    private val b2: Float,
    private val a1: Float,
    private val a2: Float,
) {
  // Os dois atrasos da forma transposta. É todo o estado do filtro.
  private var z1 = 0f
  private var z2 = 0f

  /** Processa uma amostra. Chamada uma vez por amostra, então é o caminho quente do áudio. */
  fun processar(x: Float): Float {
    val y = b0 * x + z1
    z1 = b1 * x - a1 * y + z2
    z2 = b2 * x - a2 * y
    return y
  }

  companion object {
    /** Butterworth: o Q que deixa a banda passante o mais plana possível. */
    private val Q = 1.0 / sqrt(2.0)

    fun passaAlta(corteHz: Double, sampleRate: Int): Biquad {
      val w0 = 2.0 * PI * corteHz / sampleRate
      val alpha = sin(w0) / (2.0 * Q)
      val cosW0 = cos(w0)
      val a0 = 1.0 + alpha
      return Biquad(
          b0 = (((1.0 + cosW0) / 2.0) / a0).toFloat(),
          b1 = ((-(1.0 + cosW0)) / a0).toFloat(),
          b2 = (((1.0 + cosW0) / 2.0) / a0).toFloat(),
          a1 = ((-2.0 * cosW0) / a0).toFloat(),
          a2 = ((1.0 - alpha) / a0).toFloat(),
      )
    }

    fun passaBaixa(corteHz: Double, sampleRate: Int): Biquad {
      val w0 = 2.0 * PI * corteHz / sampleRate
      val alpha = sin(w0) / (2.0 * Q)
      val cosW0 = cos(w0)
      val a0 = 1.0 + alpha
      return Biquad(
          b0 = (((1.0 - cosW0) / 2.0) / a0).toFloat(),
          b1 = ((1.0 - cosW0) / a0).toFloat(),
          b2 = (((1.0 - cosW0) / 2.0) / a0).toFloat(),
          a1 = ((-2.0 * cosW0) / a0).toFloat(),
          a2 = ((1.0 - alpha) / a0).toFloat(),
      )
    }
  }
}
