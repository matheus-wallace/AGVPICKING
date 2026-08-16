package com.agvtronic.pickvoice.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

/**
 * A [FonteAudio] de desenvolvimento do doc §5.2: microfone do celular a 16 kHz, degradado para
 * o canal de 8 kHz do óculos por [DegradacaoCanalTelefonico].
 *
 * Existe para que o pipeline de voz seja exercitado todo dia até 18/09 sem depender de
 * hardware, do mesmo jeito que o MockDeviceKit faz pela sessão DAT. A implementação de
 * produção (`AudioHfpOculos`) entra na fatia que trocar o device selector, e a única mudança
 * será a linha do `AppContainer` que escolhe uma das duas.
 *
 * ### Por que `VOICE_RECOGNITION` e não `VOICE_COMMUNICATION`
 *
 * O doc §5 pede `VOICE_COMMUNICATION + AEC`, e é isso que o `AudioHfpOculos` vai usar quando
 * existir — lá a fonte precisa ser a de comunicação porque o áudio vem pelo canal HFP.
 *
 * **Em bancada isso não funciona.** Medido no Galaxy S20 FE (SM-G780F) de desenvolvimento:
 * com `VOICE_COMMUNICATION` o `AudioRecord` inicializa, `read` devolve amostras e nenhum erro
 * aparece no log, mas o sinal é silêncio digital — pico de 0,0001 com fala alta na frente do
 * aparelho, contra 0,04+ na mesma condição com `VOICE_RECOGNITION`. A fonte de comunicação
 * parece depender de uma rota de voz ativa que não existe fora de uma chamada.
 *
 * `VOICE_RECOGNITION` é, de todo modo, a fonte que o Android recomenda para ASR: sem AGC
 * agressivo e com supressão de ruído ajustada para reconhecimento, não para inteligibilidade
 * humana.
 *
 * ### O AEC não é mais ligado por padrão
 *
 * A primeira versão desta classe ligava o [AcousticEchoCanceler] sempre que o aparelho
 * oferecia, antecipando o TTS do doc §5.4. Isso foi revertido: o AEC cancela o que está sendo
 * *tocado* a partir de uma referência de playback, e enquanto não existe TTS não há nada para
 * cancelar — só um estágio a mais no caminho do sinal, capaz de atenuá-lo. Ele volta por
 * [AjustesAsr.cancelamentoDeEco], e volta a ser padrão quando a saída por voz existir.
 *
 * @param ajustes calibração de bancada; ver [AjustesAsr]. Os defaults são o comportamento de
 *   produção, então construir sem argumento é o caminho normal.
 */
class AudioMicrofoneSimulado(private val ajustes: AjustesAsr = AjustesAsr()) : FonteAudio {

  /**
   * 8 kHz, não os 16 kHz da captura: o consumidor enxerga a taxa do óculos (doc §2.1), e a
   * captura em taxa maior é detalhe interno de como a degradação é feita.
   *
   * Com [AjustesAsr.degradarCanal] desligado a fonte passa a declarar os 16 kHz da captura —
   * é a única forma de o reconhecedor construir o `Recognizer` na taxa certa quando se quer
   * medir o pipeline sem a degradação no caminho.
   */
  override val sampleRate: Int =
      if (ajustes.degradarCanal) DegradacaoCanalTelefonico.SAMPLE_RATE_SAIDA
      else SAMPLE_RATE_CAPTURA

  /**
   * Abre o microfone e emite janelas já degradadas até o coletor cancelar.
   *
   * **Roda no contexto de quem coleta, de propósito** — sem `flowOn`. Quem coleta é a thread
   * dedicada de áudio do [ReconhecedorDeComando], e `AudioRecord.read` bloqueando nela é
   * exatamente o comportamento desejado: é o relógio do pipeline. Um `flowOn` aqui jogaria a
   * captura para outro thread e quebraria a garantia de thread única que o Vosk exige.
   *
   * @param tamanhoJanela amostras por emissão, **na saída** (8 kHz).
   */
  @SuppressLint("MissingPermission") // Quem chama garante RECORD_AUDIO — ver ReconhecedorDeComando.
  override fun fluxo(tamanhoJanela: Int): Flow<FloatArray> = flow {
    val fator = SAMPLE_RATE_CAPTURA / sampleRate
    val amostrasPorLeitura = tamanhoJanela * fator

    // O buffer interno precisa caber pelo menos uma leitura nossa; abaixo do mínimo do
    // dispositivo o AudioRecord nem inicializa.
    val bufferMinimo =
        AudioRecord.getMinBufferSize(SAMPLE_RATE_CAPTURA, CANAL, ENCODING).coerceAtLeast(0)
    val bufferBytes = maxOf(bufferMinimo, amostrasPorLeitura * BYTES_POR_AMOSTRA * 2)

    val recorder =
        AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE_CAPTURA,
            CANAL,
            ENCODING,
            bufferBytes,
        )

    if (recorder.state != AudioRecord.STATE_INITIALIZED) {
      // Sem exceção: o app segue operável pelo painel de dev, e um microfone indisponível não
      // é motivo para derrubar nada (design.md - Decisão 6).
      Log.e(TAG, "AudioRecord não inicializou (state=${recorder.state}); sem captura de áudio")
      recorder.release()
      return@flow
    }

    val aec =
        if (ajustes.cancelamentoDeEco) ligarCancelamentoDeEco(recorder.audioSessionId) else null

    try {
      recorder.startRecording()

      Log.i(
          TAG,
          "Captura aberta: ${SAMPLE_RATE_CAPTURA}Hz -> ${sampleRate}Hz " +
              "(degradação=${ajustes.degradarCanal}, aec=${aec != null})",
      )

      val bufferPcm = ShortArray(amostrasPorLeitura)
      // `null` quando a degradação está desligada: o consumidor recebe a captura crua a
      // 16 kHz, e `fator` já vale 1, então nenhuma amostra é descartada.
      val degradacao =
          if (ajustes.degradarCanal) DegradacaoCanalTelefonico(SAMPLE_RATE_CAPTURA) else null

      while (currentCoroutineContext().isActive) {
        val lidas = recorder.read(bufferPcm, 0, bufferPcm.size)
        if (lidas <= 0) {
          Log.w(TAG, "AudioRecord.read devolveu $lidas; encerrando a captura")
          break
        }

        // int16 -> -1.0..1.0, o contrato de escala da FonteAudio. 32768 e não 32767 para que
        // o valor mais negativo possível não estoure -1.0.
        val normalizadas = FloatArray(lidas) { bufferPcm[it] / 32_768f }
        emit(degradacao?.processar(normalizadas) ?: normalizadas)
      }
    } finally {
      // Roda também no cancelamento do coletor, que é justamente como este fluxo termina.
      aec?.release()
      runCatching { recorder.stop() }
      recorder.release()
    }
  }

  /**
   * Liga o cancelamento de eco de hardware quando o aparelho tem (doc §5).
   *
   * Só é chamado quando [AjustesAsr.cancelamentoDeEco] pede — ver a nota na documentação da
   * classe sobre por que deixou de ser padrão. Nem todo dispositivo oferece, e a ausência não é
   * erro. No óculos quem faz esse trabalho é o beamforming do próprio hardware (§2.1).
   */
  private fun ligarCancelamentoDeEco(audioSessionId: Int): AcousticEchoCanceler? {
    if (!AcousticEchoCanceler.isAvailable()) {
      Log.i(TAG, "AEC indisponível neste aparelho; seguindo sem")
      return null
    }
    return AcousticEchoCanceler.create(audioSessionId)?.apply { enabled = true }
  }

  private companion object {
    const val TAG = "AudioMicrofoneSimulado"

    /**
     * 16 kHz na captura, decimado para 8 kHz na saída. Capturar já a 8 kHz pareceria mais
     * simples, mas perderia o ponto: o band-pass do doc §10.1 precisa acontecer sobre o sinal
     * de banda larga para reproduzir a degradação, e não sobre um sinal que o Android já
     * reamostrou por conta própria com um filtro que não controlamos.
     */
    const val SAMPLE_RATE_CAPTURA = 16_000

    const val CANAL = AudioFormat.CHANNEL_IN_MONO
    const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    const val BYTES_POR_AMOSTRA = 2
  }
}
