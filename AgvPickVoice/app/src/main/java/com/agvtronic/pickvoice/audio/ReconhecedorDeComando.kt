package com.agvtronic.pickvoice.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.agvtronic.pickvoice.domain.statemachine.PickingActor
import com.agvtronic.pickvoice.domain.statemachine.PickingEvent
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService

/**
 * O primeiro produtor de [PickingEvent] por voz do projeto — a fatia mais fina possível do
 * pipeline do doc §5.
 *
 * Faz só o corte vertical do Marco 1 (doc §13.1): uma gramática fixa de duas palavras,
 * reconhecida a partir da [FonteAudio], virando os eventos transversais que o painel de dev já
 * dispara por botão. Sem VAD do Silero, sem troca de gramática por estado, sem reranking e sem
 * TTS — tudo isso é Marco 2, e cada peça entra sem mexer no que está aqui.
 *
 * ### Confinamento de thread
 *
 * `Model` e `Recognizer` do Vosk **não são thread-safe**, e a restrição vale para o projeto
 * inteiro, não só para esta classe. Tudo aqui — carga do modelo, captura e decodificação —
 * roda em [dispatcherAudio], uma thread só, criada e usada exclusivamente por este componente.
 * O `PickingActor` continua em `Dispatchers.Default` e a UI na main; a única coisa que
 * atravessa a fronteira é `actor.send`, que é `trySend` num channel ilimitado e portanto nunca
 * bloqueia a thread de áudio (doc §4.2 proíbe bloquear essa thread em qualquer coisa que não
 * seja o próprio loop de frame).
 *
 * @param appContext contexto de aplicação — este componente vive além de qualquer `Activity`.
 * @param fonteAudio de onde vêm as amostras; [AudioMicrofoneSimulado] hoje, `AudioHfpOculos`
 *   no dia em que o óculos entrar (doc §5.2).
 * @param actor destino dos eventos reconhecidos.
 * @param ajustes calibração de bancada; ver [AjustesAsr]. Os defaults são o comportamento de
 *   produção.
 */
class ReconhecedorDeComando(
    private val appContext: Context,
    private val fonteAudio: FonteAudio,
    private val actor: PickingActor,
    private val ajustes: AjustesAsr = AjustesAsr(),
) {

  /**
   * A thread de áudio dedicada do doc §4.2. Nomeada para aparecer legível no profiler e no
   * stack trace de qualquer ANR.
   */
  private val dispatcherAudio =
      Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "audio-asr") }
          .asCoroutineDispatcher()

  private val escopoAudio = CoroutineScope(SupervisorJob() + dispatcherAudio)

  /**
   * O modelo do doc §5.3: carregado uma vez na inicialização do app e mantido em memória pela
   * vida do processo, nunca ao criar a sessão — senão a primeira interação de voz travaria na
   * frente do operador.
   *
   * `Deferred` porque a carga leva segundos (são 51 MB copiados dos assets na primeira
   * execução) e quem chama [iniciar] não deve esperar por ela na main thread: o loop de escuta
   * simplesmente aguarda aqui, já dentro da thread de áudio.
   */
  private val modelo: Deferred<Model?> = escopoAudio.async { carregarModelo() }

  private var escuta: Job? = null

  /**
   * Começa a escutar. Idempotente — a `MainActivity` chama a cada volta ao primeiro plano.
   *
   * Sem `RECORD_AUDIO` este método não faz nada e nenhum evento é publicado (design.md -
   * Decisão 6): não existe `PickingEvent` de "áudio indisponível" no domínio, e o app segue
   * inteiramente operável pelos botões do painel de dev.
   */
  fun iniciar() {
    if (escuta != null) return

    if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
        PackageManager.PERMISSION_GRANTED) {
      Log.w(TAG, "RECORD_AUDIO negada; reconhecimento de voz desligado")
      return
    }

    escuta = escopoAudio.launch { escutar() }
  }

  /** Encerra a captura. O modelo continua carregado — recarregá-lo custa segundos. */
  fun parar() {
    escuta?.cancel()
    escuta = null
  }

  // -----------------------------------------------------------------------------------
  // Carga do modelo
  // -----------------------------------------------------------------------------------

  private fun carregarModelo(): Model? {
    LibVosk.setLogLevel(LogLevel.WARNINGS)

    val inicio = System.currentTimeMillis()
    return runCatching {
          // Copia os assets para o armazenamento do app e devolve o caminho — o construtor de
          // `Model` só aceita caminho de sistema de arquivos, não de asset. A cópia dos 51 MB
          // só acontece de fato quando o arquivo `uuid` muda (ver PROVENIENCIA.md do modelo).
          val caminho = StorageService.sync(appContext, DIRETORIO_MODELO, DIRETORIO_MODELO)
          Model(caminho)
        }
        .onSuccess { Log.i(TAG, "Modelo carregado em ${System.currentTimeMillis() - inicio}ms") }
        .onFailure { Log.e(TAG, "Falha ao carregar o modelo Vosk; voz desligada", it) }
        .getOrNull()
  }

  // -----------------------------------------------------------------------------------
  // Loop de escuta
  // -----------------------------------------------------------------------------------

  private suspend fun escutar() {
    val modeloCarregado = modelo.await() ?: return

    Recognizer(modeloCarregado, fonteAudio.sampleRate.toFloat(), GRAMATICA).use { recognizer ->
      // Perfil COMANDO_CURTO do doc §5.1 por padrão, sobrescrevível por AjustesAsr enquanto a
      // calibração de bancada não fecha. Quando o Marco 2 trocar de gramática por estado,
      // trocar de perfil é reconfigurar estes três números — não escrever lógica nova.
      recognizer.setEndpointerDelays(
          ajustes.silencioAntesDaFalaMs / MS_POR_SEGUNDO,
          ajustes.silencioFinalMs / MS_POR_SEGUNDO,
          ajustes.duracaoMaximaMs / MS_POR_SEGUNDO,
      )
      // Não precisamos de timestamps por palavra nesta fatia; só do texto final.
      recognizer.setWords(false)

      Log.i(TAG, "Escutando (gramática=$GRAMATICA, ${fonteAudio.sampleRate}Hz, $ajustes)")

      val medidor = MedidorDeNivel(fonteAudio.sampleRate)

      // O último parcial visto, guardado por dois motivos: evitar logar a mesma hipótese
      // repetida a cada janela, e dar contexto quando o endpointer fecha uma elocução sem
      // texto final — saber que o decodificador tinha "pa" na mão é o que distingue
      // "silêncio" de "comando cortado no meio".
      var ultimoParcial = ""

      fonteAudio.fluxo(TAMANHO_JANELA).collect { janela ->
        if (ajustes.logNivel) {
          medidor.acumular(janela)?.let { Log.d(TAG, "Nível: $it") }
        }

        // O Vosk espera as amostras na escala de int16 mesmo na sobrecarga de float[], e a
        // FonteAudio entrega normalizado em -1.0..1.0. Sem esta conversão não há erro: há
        // silêncio, porque tudo vira ~0 na escala que o decodificador espera.
        val paraVosk =
            FloatArray(janela.size) {
              // O clipping importa: com ganho > 1 uma amostra alta estouraria a escala e
              // viraria distorção, que o decodificador lê pior que o sinal fraco original.
              (janela[it] * ajustes.ganho).coerceIn(-1f, 1f) * ESCALA_INT16
            }

        // `true` = o endpointer do Vosk fechou a elocução (design.md - Decisão 2).
        if (recognizer.acceptWaveForm(paraVosk, paraVosk.size)) {
          publicar(recognizer.result, ultimoParcial)
          ultimoParcial = ""
        } else if (ajustes.logParciais) {
          val parcial = textoDoJson(recognizer.partialResult, CAMPO_PARCIAL)
          if (parcial != ultimoParcial) {
            if (parcial.isNotEmpty()) Log.d(TAG, "Parcial: \"$parcial\"")
            ultimoParcial = parcial
          }
        }
      }
    }
  }

  /**
   * Traduz o JSON de resultado do Vosk num evento, quando ele corresponde a um comando.
   *
   * Silêncio devolve `{"text": ""}` e fala fora da gramática devolve `[unk]` — os dois casos
   * não publicam nada, que é o que a spec exige em "Fala fora da gramática não produz evento".
   *
   * @param parcialAnterior a última hipótese parcial antes de o endpointer fechar. Só serve
   *   para o log; ver por que em [escutar].
   */
  private fun publicar(resultadoJson: String, parcialAnterior: String) {
    val texto = textoDoJson(resultadoJson, CAMPO_TEXTO)

    if (texto.isEmpty()) {
      // Elocução vazia é o caso comum: a cada `silencioAntesDaFala` sem ninguém falar, o
      // endpointer recicla o decodificador e devolve `{"text": ""}`. Logar isso sempre
      // inundaria o logcat com uma linha a cada poucos segundos. Quando havia um parcial,
      // porém, alguma coisa estava sendo decodificada e desapareceu no fim — esse caso é
      // exatamente o sintoma de endpoint cedo demais, e precisa aparecer.
      if (parcialAnterior.isNotEmpty()) {
        Log.w(TAG, "ASR: elocução fechada sem texto (parcial era \"$parcialAnterior\")")
      }
      return
    }

    val evento =
        when (texto) {
          COMANDO_PARAR -> PickingEvent.ComandoParar
          COMANDO_REPETIR -> PickingEvent.ComandoRepetir
          else -> null
        }

    // Loga sempre, inclusive o descartado: é o insumo do plano de calibração do doc §10, que
    // precisa saber o que o ASR ouviu, não só o que virou evento.
    Log.i(TAG, "ASR: \"$texto\" -> ${evento?.let { it::class.simpleName } ?: "descartado"}")

    evento?.let { actor.send(it) }
  }

  /**
   * Extrai um campo de texto do JSON do Vosk. `{"text": "..."}` no resultado final e
   * `{"partial": "..."}` no parcial — mesma forma, campos diferentes.
   */
  private fun textoDoJson(json: String, campo: String): String =
      runCatching { JSONObject(json).optString(campo).trim() }
          .onFailure { Log.e(TAG, "Resultado do Vosk não era JSON: $json", it) }
          .getOrDefault("")

  private companion object {
    const val TAG = "ReconhecedorDeComando"

    /** Diretório do modelo dentro de `assets/` e também dentro do armazenamento do app. */
    const val DIRETORIO_MODELO = "modelo-vosk-pt"

    const val COMANDO_PARAR = "parar"
    const val COMANDO_REPETIR = "repetir"

    /**
     * A gramática fixa desta fatia (design.md - Decisão 1).
     *
     * **`[unk]` não é opcional.** Uma gramática restrita sem ele obriga o decodificador a
     * devolver sempre a palavra mais parecida entre as que conhece — qualquer tosse ou
     * conversa ao lado viraria "parar", e o operador teria a sessão pausada do nada. Com
     * `[unk]`, o que não é comando é reconhecido como desconhecido e descartado.
     */
    const val GRAMATICA = """["$COMANDO_PARAR", "$COMANDO_REPETIR", "[unk]"]"""

    /** Campo do texto final no JSON do Vosk. */
    const val CAMPO_TEXTO = "text"

    /** Campo da hipótese em andamento no JSON do Vosk. */
    const val CAMPO_PARCIAL = "partial"

    /** 64 ms a 8 kHz — granularidade suficiente para os 280 ms do COMANDO_CURTO. */
    const val TAMANHO_JANELA = 512

    /** `setEndpointerDelays` fala em segundos; os [AjustesAsr] falam em ms, como o doc §5.1. */
    const val MS_POR_SEGUNDO = 1_000f

    /** -1.0..1.0 (contrato da FonteAudio) -> ±32767 (o que o Vosk decodifica). */
    const val ESCALA_INT16 = 32_767f
  }
}
