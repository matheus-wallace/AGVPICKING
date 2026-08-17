package com.agvtronic.pickvoice.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor

/**
 * A [FonteAudio] de produção do doc §5.2: o microfone do óculos, capturado pelo canal HFP.
 *
 * É a outra metade do par que a interface existe para permitir — [AudioMicrofoneSimulado] roda
 * o pipeline de voz todo dia sem hardware, esta classe roda com o hardware real, e trocar uma
 * pela outra é uma linha no `AppContainer`. [AudioMicrofoneSimulado] **continua existindo** e
 * continua sendo a fonte de desenvolvimento: sem óculos pareado, esta classe não captura nada.
 *
 * ### Por que não há degradação de canal aqui
 *
 * O HFP já entrega 8 kHz mono (doc §2.1) — é a taxa nativa do perfil, não uma escolha. A
 * decimação e o band-pass de [DegradacaoCanalTelefonico] existem só para que o microfone do
 * celular, que captura em banda larga, *soe* como este canal. Aqui o canal é o canal: aplicar
 * a degradação de novo seria filtrar duas vezes um sinal que já nasceu estreito.
 *
 * ### Por que `VOICE_COMMUNICATION` desta vez
 *
 * O KDoc de [AudioMicrofoneSimulado] registra a medição de bancada em que `VOICE_COMMUNICATION`
 * devolveu silêncio digital no microfone do celular: o `AudioRecord` inicializava, `read`
 * devolvia amostras, e o pico ficava em 0,0001 com fala alta. A hipótese registrada lá é que a
 * fonte de comunicação depende de uma rota de voz ativa — e é exatamente essa rota que esta
 * classe estabelece antes de abrir a captura. `VOICE_RECOGNITION` não serviria: ele abre o
 * microfone do próprio aparelho, não o link SCO.
 *
 * ### Por que a rota vem antes da captura, e por que esperar o callback
 *
 * O doc §2.1 é explícito: configurar HFP → **esperar a rota assentar** → só então seguir. "O
 * inverso faz a rota falhar em silêncio" — falha sem exceção, sem log de erro, só amostras
 * mudas, que é o modo de falha mais caro de diagnosticar em bancada. Por isso a espera usa
 * [AudioManager.OnCommunicationDeviceChangedListener] e não um `delay` fixo: o callback é o
 * sinal real de que o sistema trocou a rota, enquanto um `delay` é uma aposta que ora custa
 * tempo à toa, ora termina cedo demais e abre a captura na rota errada. O timeout de
 * [TIMEOUT_ROTA_MS] existe só como teto de segurança para o caso de o callback nunca vir.
 *
 * ### Sem `AcousticEchoCanceler`
 *
 * O AEC do Android cancela eco no caminho do microfone **do próprio aparelho**, subtraindo uma
 * referência do que o aparelho está tocando. Aqui o áudio chega por um link Bluetooth SCO
 * remoto que nunca passa pelo hardware de microfone do celular: não há referência de playback
 * local que corresponda ao que o óculos captou, então o cancelador não teria o que cancelar —
 * só um estágio a mais capaz de atenuar o sinal. Quem faz esse trabalho é o beamforming do
 * hardware do óculos (doc §2.1), que já isola a voz do usuário do ruído do galpão.
 *
 * ### Degradação graciosa
 *
 * Óculos ausente, SCO indisponível, rota que não assenta, `AudioRecord` que não inicializa: em
 * todos os casos o fluxo encerra sem emitir, com log, e sem exceção (design.md - Decisão 6). O
 * app segue inteiramente operável pelos botões do painel de dev — o mesmo que
 * [AudioMicrofoneSimulado] faz quando o microfone não abre e o que `ControladorDeVisao` faz
 * quando não há sessão de câmera.
 *
 * @param appContext contexto de aplicação, usado só para obter o [AudioManager].
 */
class AudioHfpOculos(private val appContext: Context) : FonteAudio {

  /** Fixo: é a taxa nativa da captura HFP (doc §2.1), não uma conversão nossa. */
  override val sampleRate: Int = SAMPLE_RATE_HFP

  /**
   * Roteia o áudio para o SCO do óculos, abre a captura e emite janelas até o coletor cancelar.
   *
   * **Roda no contexto de quem coleta, sem `flowOn`**, pelo mesmo motivo de
   * [AudioMicrofoneSimulado]: quem coleta é a thread dedicada de áudio do
   * [ReconhecedorDeComando], e `AudioRecord.read` bloqueando nela é o relógio do pipeline. Um
   * `flowOn` jogaria a captura para outra thread e quebraria a garantia de thread única que o
   * Vosk exige.
   *
   * @param tamanhoJanela amostras por emissão, a 8 kHz.
   */
  @SuppressLint("MissingPermission") // Quem chama garante RECORD_AUDIO — ver ReconhecedorDeComando.
  override fun fluxo(tamanhoJanela: Int): Flow<FloatArray> = flow {
    val audioManager = appContext.getSystemService(AudioManager::class.java)
    if (audioManager == null) {
      Log.e(TAG, "AudioManager indisponível; sem captura de voz")
      return@flow
    }

    val rotaAssentada = CompletableDeferred<Unit>()
    val ouvinteDeRota =
        AudioManager.OnCommunicationDeviceChangedListener { dispositivo ->
          if (dispositivo?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            rotaAssentada.complete(Unit)
          }
        }

    // Registrado *antes* de pedir a troca: entre a chamada e o registro caberia a notificação
    // que estamos justamente esperando, e perdê-la custaria o timeout inteiro.
    audioManager.addOnCommunicationDeviceChangedListener(EXECUTOR_DIRETO, ouvinteDeRota)

    var recorder: AudioRecord? = null
    try {
      if (!rotearParaSco(audioManager, rotaAssentada)) return@flow

      val bufferMinimo =
          AudioRecord.getMinBufferSize(SAMPLE_RATE_HFP, CANAL, ENCODING).coerceAtLeast(0)
      val bufferBytes = maxOf(bufferMinimo, tamanhoJanela * BYTES_POR_AMOSTRA * 2)

      val capturaHfp =
          AudioRecord(
              MediaRecorder.AudioSource.VOICE_COMMUNICATION,
              SAMPLE_RATE_HFP,
              CANAL,
              ENCODING,
              bufferBytes,
          )
      recorder = capturaHfp

      if (capturaHfp.state != AudioRecord.STATE_INITIALIZED) {
        Log.e(TAG, "AudioRecord não inicializou (state=${capturaHfp.state}); sem captura de voz")
        return@flow
      }

      capturaHfp.startRecording()
      Log.i(TAG, "Captura HFP aberta a ${SAMPLE_RATE_HFP}Hz sobre a rota SCO do óculos")

      val bufferPcm = ShortArray(tamanhoJanela)
      while (currentCoroutineContext().isActive) {
        val lidas = capturaHfp.read(bufferPcm, 0, bufferPcm.size)
        if (lidas <= 0) {
          Log.w(TAG, "AudioRecord.read devolveu $lidas; encerrando a captura")
          break
        }

        // int16 -> -1.0..1.0, o contrato de escala da FonteAudio. 32768 e não 32767 para que o
        // valor mais negativo possível não estoure -1.0.
        emit(FloatArray(lidas) { bufferPcm[it] / 32_768f })
      }
    } finally {
      // Roda também no cancelamento do coletor, que é justamente como este fluxo termina. A
      // rota precisa ser devolvida ao sistema: enquanto o SCO for o dispositivo de comunicação,
      // a saída do óculos fica presa nos 8 kHz do HFP (doc §2.1, perfis mutuamente exclusivos).
      audioManager.removeOnCommunicationDeviceChangedListener(ouvinteDeRota)
      runCatching { audioManager.clearCommunicationDevice() }
      recorder?.let {
        runCatching { it.stop() }
        it.release()
      }
    }
  }

  /**
   * Estabelece a rota SCO e só devolve `true` quando ela está confirmada como ativa.
   *
   * Cada saída negativa é um modo de falha diferente e ganha log próprio de propósito: em
   * bancada, "não achei o SCO" (óculos não pareado), "o sistema recusou a troca" e "a rota não
   * assentou a tempo" pedem ações completamente distintas, e um log genérico obrigaria a
   * descobrir qual foi por tentativa e erro.
   */
  private suspend fun rotearParaSco(
      audioManager: AudioManager,
      rotaAssentada: CompletableDeferred<Unit>,
  ): Boolean {
    val sco =
        audioManager.availableCommunicationDevices.firstOrNull {
          it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
    if (sco == null) {
      Log.e(TAG, "Nenhum dispositivo SCO disponível (óculos pareado e conectado?); sem captura")
      return false
    }

    val aceito =
        runCatching { audioManager.setCommunicationDevice(sco) }
            .onFailure { Log.e(TAG, "setCommunicationDevice lançou; sem captura de voz", it) }
            .getOrDefault(false)
    if (!aceito) {
      Log.e(TAG, "setCommunicationDevice recusou o SCO '${sco.productName}'; sem captura de voz")
      return false
    }

    // A rota pode já estar ativa — nesse caso não há mudança para o callback anunciar, e
    // esperar por ele seria esperar o timeout inteiro à toa.
    if (audioManager.communicationDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
      Log.i(TAG, "Rota SCO já ativa; abrindo a captura direto")
      return true
    }

    val confirmada = withTimeoutOrNull(TIMEOUT_ROTA_MS) { rotaAssentada.await() } != null
    if (!confirmada) {
      // Abrir a captura assim mesmo é a pior opção disponível: daria um fluxo mudo sem erro
      // nenhum, que é exatamente o modo de falha que o doc §2.1 manda evitar.
      Log.e(TAG, "Rota SCO não assentou em ${TIMEOUT_ROTA_MS}ms; sem captura de voz")
      return false
    }

    Log.i(TAG, "Rota SCO confirmada em '${sco.productName}'")
    return true
  }

  private companion object {
    const val TAG = "AudioHfpOculos"

    /** 8 kHz mono: a captura do perfil HFP, doc §2.1. */
    const val SAMPLE_RATE_HFP = 8_000

    const val CANAL = AudioFormat.CHANNEL_IN_MONO
    const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    const val BYTES_POR_AMOSTRA = 2

    /**
     * Teto de espera pela confirmação da rota SCO.
     *
     * **É uma estimativa, não um valor calibrado** — a rota HFP real ainda não foi medida em
     * bancada. Poucos segundos porque negociar SCO envolve o rádio Bluetooth, e um timeout
     * curto demais transformaria um óculos apenas lento num óculos aparentemente quebrado. Não
     * está em [AjustesAsr] de propósito: aquele arquivo existe para parâmetros que se ajustam
     * por tentativa com voz humana (limiar de endpoint, ganho), enquanto este é um teto de
     * segurança sem meio-termo — ou a rota assenta, ou não há captura. Se a bancada com os
     * óculos reais mostrar que a negociação varia por aparelho, aí sim ele vira ajuste.
     */
    const val TIMEOUT_ROTA_MS = 3_000L

    /**
     * Executa o callback na própria thread que o sistema usou para notificar.
     *
     * O corpo do ouvinte só compara um tipo e completa um `CompletableDeferred`, que é
     * thread-safe — não vale uma thread dedicada. Mandá-lo para a thread de áudio seria pior:
     * é justamente ela que espera a confirmação.
     */
    val EXECUTOR_DIRETO = Executor { tarefa -> tarefa.run() }
  }
}
