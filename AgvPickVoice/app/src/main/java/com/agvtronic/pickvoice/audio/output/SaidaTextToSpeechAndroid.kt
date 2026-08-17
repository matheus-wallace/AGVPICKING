package com.agvtronic.pickvoice.audio.output

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.ArrayDeque
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Saída local de desenvolvimento baseada no sintetizador do Android. */
class SaidaTextToSpeechAndroid(context: Context) : SaidaDeAudio {

  private val appContext = context.applicationContext
  private val lock = Any()
  private val pendentes = ArrayDeque<MensagemFalavel>()
  private var motor: TextToSpeech? = null
  private var geracao = 0L

  private val _diagnostico = MutableStateFlow(DiagnosticoSaidaAudio())
  override val diagnostico: StateFlow<DiagnosticoSaidaAudio> = _diagnostico.asStateFlow()

  private val _falando = MutableStateFlow(false)
  override val falando: StateFlow<Boolean> = _falando.asStateFlow()

  /**
   * Elocuções entregues ao motor e ainda não encerradas.
   *
   * Contador e não booleano porque `QUEUE_ADD` permite mais de uma na fila: com um booleano, o
   * `onDone` da primeira desligaria o [falando] no meio da segunda e o ASR voltaria a escutar
   * o próprio TTS. Sobe já em [reproduzir], antes do `onStart`, para fechar a janela entre
   * enfileirar e começar a falar.
   */
  private var elocucoesEmAndamento = 0

  override fun iniciar() {
    val geracaoAtual: Long
    synchronized(lock) {
      if (motor != null || _diagnostico.value.estado == EstadoSaidaAudio.INICIALIZANDO) return
      geracaoAtual = ++geracao
      _diagnostico.update {
        it.copy(estado = EstadoSaidaAudio.INICIALIZANDO, categoriaErro = null)
      }
    }

    val novoMotor = TextToSpeech(appContext) { status -> aoInicializar(geracaoAtual, status) }
    synchronized(lock) {
      if (geracaoAtual == geracao) motor = novoMotor else novoMotor.shutdown()
    }
  }

  override fun falar(mensagem: MensagemFalavel) {
    synchronized(lock) {
      _diagnostico.update { it.copy(ultimaChaveMensagem = mensagem.chave) }
      val tts = motor
      if (_diagnostico.value.estado != EstadoSaidaAudio.PRONTA || tts == null) {
        if (_diagnostico.value.estado == EstadoSaidaAudio.INICIALIZANDO) {
          if (mensagem.prioridade == PrioridadeFala.CRITICA) pendentes.clear()
          pendentes.addLast(mensagem)
        }
        return
      }
      reproduzir(tts, mensagem)
    }
  }

  override fun parar() {
    synchronized(lock) {
      pendentes.clear()
      motor?.stop()
      // `stop()` descarta o que estava na fila do motor; os callbacks das elocuções abortadas
      // podem nunca chegar. Zerar aqui é o que impede o [falando] de ficar preso em `true` e
      // deixar o reconhecimento surdo pelo resto da sessão.
      zerarElocucoes()
    }
  }

  override fun fechar() {
    val motorParaFechar: TextToSpeech?
    synchronized(lock) {
      geracao++
      pendentes.clear()
      zerarElocucoes()
      motorParaFechar = motor
      motor = null
      _diagnostico.update { it.copy(estado = EstadoSaidaAudio.PARADA, categoriaErro = null) }
    }
    motorParaFechar?.stop()
    motorParaFechar?.shutdown()
  }

  private fun aoInicializar(geracaoDaChamada: Long, status: Int) {
    synchronized(lock) {
      if (geracaoDaChamada != geracao) return
      val tts = motor ?: return
      if (status != TextToSpeech.SUCCESS) {
        indisponibilizar(tts, CategoriaErroSaidaAudio.FALHA_INICIALIZACAO)
        return
      }

      val resultadoIdioma = tts.setLanguage(Locale.forLanguageTag("pt-BR"))
      if (
          resultadoIdioma == TextToSpeech.LANG_MISSING_DATA ||
              resultadoIdioma == TextToSpeech.LANG_NOT_SUPPORTED
      ) {
        indisponibilizar(tts, CategoriaErroSaidaAudio.IDIOMA_INDISPONIVEL)
        return
      }
      selecionarMelhorVozPtBr(tts)

      @Suppress("OVERRIDE_DEPRECATION")
      tts.setOnUtteranceProgressListener(
          object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
              encerrarElocucao()
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
              encerrarElocucao()
            }

            override fun onError(utteranceId: String?) {
              encerrarElocucao()
              registrarFalhaDeReproducao()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
              encerrarElocucao()
              registrarFalhaDeReproducao()
            }
          }
      )
      _diagnostico.update {
        it.copy(estado = EstadoSaidaAudio.PRONTA, categoriaErro = null)
      }
      while (pendentes.isNotEmpty()) reproduzir(tts, pendentes.removeFirst())
    }
  }

  private fun reproduzir(tts: TextToSpeech, mensagem: MensagemFalavel) {
    val modoFila =
        if (mensagem.prioridade == PrioridadeFala.CRITICA) {
          tts.stop()
          // O flush apagou a fila do motor: o que estava em andamento não conta mais.
          zerarElocucoes()
          TextToSpeech.QUEUE_FLUSH
        } else {
          TextToSpeech.QUEUE_ADD
        }
    val resultado = tts.speak(mensagem.texto, modoFila, null, mensagem.chave)
    if (resultado == TextToSpeech.ERROR) registrarFalhaDeReproducao() else iniciarElocucao()
  }

  private fun iniciarElocucao() {
    synchronized(lock) {
      elocucoesEmAndamento++
      _falando.value = true
    }
  }

  private fun encerrarElocucao() {
    synchronized(lock) {
      // `coerceAtLeast` porque um `stop()` já zerou o contador e os callbacks das elocuções
      // abortadas ainda chegam depois, do thread do motor.
      elocucoesEmAndamento = (elocucoesEmAndamento - 1).coerceAtLeast(0)
      _falando.value = elocucoesEmAndamento > 0
    }
  }

  /** Só de dentro de `synchronized(lock)`. */
  private fun zerarElocucoes() {
    elocucoesEmAndamento = 0
    _falando.value = false
  }

  /** Seleciona a melhor voz que o motor já disponibiliza; não baixa nem envia dados. */
  private fun selecionarMelhorVozPtBr(tts: TextToSpeech) {
    // Depois de setLanguage, esta é a voz escolhida pelo próprio Google TTS. No Galaxy ela
    // corresponde à "variante 3" configurada pelo operador; o nome interno não é estável entre
    // versões do motor, por isso nunca o codificamos aqui.
    val nomeDaVozPadrao = tts.voice?.name
    val porNome = tts.voices.orEmpty().associateBy { it.name }
    val selecionada =
        SeletorDeVoz.melhorPtBr(
            porNome.values.map { voz ->
              CandidataDeVoz(
                  nome = voz.name,
                  idioma = voz.locale.language,
                  pais = voz.locale.country,
                  qualidade = voz.quality,
                  latencia = voz.latency,
                  requerRede = voz.isNetworkConnectionRequired,
              )
            },
            nomePreferido = nomeDaVozPadrao,
        ) ?: return

    val voz = porNome[selecionada.nome] ?: return
    tts.voice = voz
    Log.i(
        TAG,
        "Voz TTS selecionada: ${voz.name}; padrãoGoogle=${voz.name == nomeDaVozPadrao}; " +
            "qualidade=${voz.quality}; " +
            "latência=${voz.latency}; rede=${voz.isNetworkConnectionRequired}",
    )
  }

  private fun indisponibilizar(tts: TextToSpeech, categoria: CategoriaErroSaidaAudio) {
    Log.e(TAG, "Saída TTS indisponível: $categoria")
    pendentes.clear()
    zerarElocucoes()
    motor = null
    _diagnostico.update {
      it.copy(estado = EstadoSaidaAudio.INDISPONIVEL, categoriaErro = categoria)
    }
    tts.shutdown()
  }

  private fun registrarFalhaDeReproducao() {
    Log.e(TAG, "Falha na reprodução TTS")
    _diagnostico.update { it.copy(categoriaErro = CategoriaErroSaidaAudio.FALHA_REPRODUCAO) }
  }

  private companion object {
    const val TAG = "SaidaDeAudio"
  }
}
