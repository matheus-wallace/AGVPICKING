package com.agvtronic.pickvoice.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.agvtronic.pickvoice.domain.statemachine.PickingActor
import com.agvtronic.pickvoice.domain.statemachine.PickingEvent
import com.agvtronic.pickvoice.domain.statemachine.PickingState
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService

/**
 * O produtor de [PickingEvent] por voz — a superfície do Vosk, e só ela.
 *
 * A gramática deixou de ser fixa: a cada transição do [PickingActor] esta classe pergunta ao
 * [SeletorDeEscuta] o que escutar naquele estado e reconstrói o `Recognizer` na thread dedicada
 * de áudio. **Nada de domínio mora aqui** — o que a fala significa é do [InterpretadorDeFala], o
 * que ela vale contra o dado operacional é do [ResolvedorDeIntencao], e o envio ao ator é do
 * [PublicadorDeVoz]. Esta classe observa `actor.state` e nunca chama `actor.send`
 * (design.md - Decisão 1).
 *
 * ### Confinamento de thread
 *
 * `Model` e `Recognizer` do Vosk **não são thread-safe**. Carga do modelo, construção do
 * reconhecedor, captura e decodificação rodam todas em [dispatcherAudio], uma thread só.
 *
 * A observação do estado é a única coisa que roda fora dela, em [Dispatchers.Default], e por um
 * motivo concreto: `AudioRecord.read` **bloqueia** a thread de áudio, e uma corrotina bloqueada
 * num dispatcher de thread única nunca cede a vez. Um coletor de `actor.state` hospedado ali
 * jamais seria escalonado. O observador então só anota o que passou a valer em [solicitada]; a
 * troca do reconhecedor acontece na thread de áudio, na janela seguinte.
 *
 * @param appContext contexto de aplicação — este componente vive além de qualquer `Activity`.
 * @param fonteAudio de onde vêm as amostras; [AudioMicrofoneSimulado] hoje, `AudioHfpOculos`
 *   no dia em que o óculos entrar (doc §5.2).
 * @param actor observado, nunca escrito.
 * @param publicador destino do texto reconhecido e dono da versão do estado.
 * @param falaEmCurso `SaidaDeAudio.falando`: enquanto `true`, nenhum resultado é aceito
 *   (design.md - Decisão 6).
 * @param ajustes calibração de bancada; ver [AjustesAsr]. Os defaults são o comportamento de
 *   produção.
 */
class ReconhecedorDeComando(
    private val appContext: Context,
    private val fonteAudio: FonteAudio,
    private val actor: PickingActor,
    private val publicador: PublicadorDeVoz,
    private val falaEmCurso: StateFlow<Boolean>,
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
  private var observacao: Job? = null

  /**
   * O que o estado atual pede, publicado pelo observador e consumido pela thread de áudio.
   *
   * `@Volatile` porque atravessa threads: escrito em [Dispatchers.Default], lido na thread de
   * áudio. É uma referência imutável trocada inteira, então não há estado meio-atualizado para
   * um leitor enxergar.
   */
  @Volatile private var solicitada: EscutaSolicitada? = null

  /** O reconhecedor em uso. Confinado na thread de áudio — nenhuma outra o toca. */
  private var ativa: EscutaAtiva? = null

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

    publicador.iniciar()
    observacao = escopoAudio.launch(Dispatchers.Default) { observarEstado() }
    escuta = escopoAudio.launch { escutar() }
  }

  /** Encerra a captura. O modelo continua carregado — recarregá-lo custa segundos. */
  fun parar() {
    escuta?.cancel()
    escuta = null
    observacao?.cancel()
    observacao = null
    publicador.parar()
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
  // Observação do estado
  // -----------------------------------------------------------------------------------

  /**
   * Anota, a cada estado novo, a versão e a configuração de escuta correspondente.
   *
   * A versão sobe **aqui**, no instante da transição, e não quando a thread de áudio troca de
   * reconhecedor: entre uma coisa e outra o decodificador antigo ainda pode fechar uma
   * elocução, e é exatamente esse resultado que precisa ser invalidado (design.md - Decisão 3).
   *
   * `StateFlow` entrega o valor corrente na assinatura, então o estado em vigor no momento do
   * `iniciar` também passa por aqui.
   */
  private suspend fun observarEstado() {
    actor.state.collect { estado ->
      solicitada = EscutaSolicitada(publicador.novaVersao(), estado, SeletorDeEscuta.para(estado))
    }
  }

  // -----------------------------------------------------------------------------------
  // Loop de escuta
  // -----------------------------------------------------------------------------------

  private suspend fun escutar() {
    val modeloCarregado = modelo.await() ?: return

    val medidor = MedidorDeNivel(fonteAudio.sampleRate)

    // O último parcial visto, guardado por dois motivos: evitar logar a mesma hipótese
    // repetida a cada janela, e dar contexto quando o endpointer fecha uma elocução sem
    // texto final — saber que o decodificador tinha "pa" na mão é o que distingue
    // "silêncio" de "comando cortado no meio".
    var ultimoParcial = ""

    // A janela de endpoint precisa recomeçar quando o sistema para de falar; até lá, o que
    // entrou no decodificador é o próprio TTS vazando pelo microfone (design.md - Decisão 6).
    var reiniciarAposFala = false

    try {
      fonteAudio.fluxo(TAMANHO_JANELA).collect { janela ->
        // Trocou de estado nesta janela: a hipótese parcial pertencia ao decodificador que
        // acabou de ser fechado.
        if (sincronizarEscuta(modeloCarregado) != null) ultimoParcial = ""

        val recognizer = ativa?.recognizer ?: return@collect

        if (falaEmCurso.value) {
          reiniciarAposFala = true
          ultimoParcial = ""
          return@collect
        }
        if (reiniciarAposFala) {
          recognizer.reset()
          reiniciarAposFala = false
        }

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
    } finally {
      // Roda também no cancelamento: o `Recognizer` é um ponteiro nativo e precisa ser fechado.
      ativa?.recognizer?.close()
      ativa = null
    }
  }

  /**
   * Aplica a configuração pedida pelo estado, se ela mudou. Roda na thread de áudio.
   *
   * @return a escuta recém-criada, ou `null` quando nada mudou desde a janela anterior.
   */
  private fun sincronizarEscuta(modelo: Model): EscutaAtiva? {
    val pedido = solicitada ?: return null
    if (pedido === ativa?.solicitacao) return null

    ativa?.recognizer?.close()

    val config = pedido.configuracao
    if (config == null) {
      Log.i(TAG, "Estado ${nomeDoEstado(pedido.estado)} não escuta comando de voz")
      ativa = EscutaAtiva(pedido, recognizer = null)
      return ativa
    }

    // Uma gramática que o modelo rejeite não pode derrubar a captura nem mexer no estado
    // (task 3.3): sem reconhecedor, o app segue no mesmo estado e o painel continua servindo.
    val recognizer =
        runCatching { criarRecognizer(modelo, config) }
            .onFailure { Log.e(TAG, "Falha ao criar o reconhecedor; estado segue intacto", it) }
            .getOrNull()

    Log.i(
        TAG,
        "Escutando ${nomeDoEstado(pedido.estado)} v${pedido.versao} " +
            "(gramática=${config.gramatica ?: "aberta"}, perfil=${config.perfil}, " +
            "${fonteAudio.sampleRate}Hz)",
    )

    ativa = EscutaAtiva(pedido, recognizer)
    return ativa
  }

  private fun criarRecognizer(modelo: Model, config: ConfiguracaoDeEscuta): Recognizer {
    val gramatica = config.gramatica
    val recognizer =
        if (gramatica == null) Recognizer(modelo, fonteAudio.sampleRate.toFloat())
        else Recognizer(modelo, fonteAudio.sampleRate.toFloat(), gramatica)

    recognizer.setEndpointerDelays(
        ajustes.silencioAntesDaFalaMs / MS_POR_SEGUNDO,
        silencioFinalDe(config.perfil) / MS_POR_SEGUNDO,
        ajustes.duracaoMaximaMs / MS_POR_SEGUNDO,
    )
    // Não precisamos de timestamps por palavra; só do texto final.
    recognizer.setWords(false)
    return recognizer
  }

  /**
   * O `t_end` do estado, com o arquivo de bancada tendo a última palavra.
   *
   * O perfil do doc §5.1 é quem manda no fluxo normal — 280 ms para um comando de uma palavra,
   * 700 ms para dígitos. Mas [AjustesAsr.silencioFinalMs] existe para calibrar sem recompilar
   * (o APK de debug tem 127 MB), então quando ele foi de fato alterado no arquivo passa a valer
   * para todos os estados; enquanto estiver no default, quem decide é o estado.
   */
  private fun silencioFinalDe(perfil: PerfilEndpoint): Float {
    val padrao = PerfilEndpoint.COMANDO_CURTO.silencioFinalMs
    return if (ajustes.silencioFinalMs == padrao) perfil.silencioFinalMs.toFloat()
    else ajustes.silencioFinalMs.toFloat()
  }

  /**
   * Entrega o resultado final ao [PublicadorDeVoz], com a versão sob a qual ele foi decodificado.
   *
   * Silêncio devolve `{"text": ""}` e fala fora da gramática devolve `[unk]`; os dois casos não
   * publicam nada, que é o que a spec exige em "texto fora do contrato não produz evento".
   *
   * @param parcialAnterior a última hipótese parcial antes de o endpointer fechar. Só serve
   *   para o log; ver por que em [escutar].
   */
  private fun publicar(resultadoJson: String, parcialAnterior: String) {
    val escuta = ativa ?: return
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

    val resultado =
        publicador.publicar(escuta.solicitacao.estado, texto, escuta.solicitacao.versao)

    // Loga sempre, inclusive o descartado: é o insumo do plano de calibração do doc §10, que
    // precisa saber o que o ASR ouviu, não só o que virou evento. O check digit esperado
    // nunca aparece aqui — quem o conhece é o ResolvedorDeIntencao, que não loga. O motivo do
    // descarte vem separado (task 2.2): fora da gramática do estado atual não é o mesmo sintoma
    // de bancada que resultado de versão de estado obsoleta.
    val desfecho =
        when (resultado) {
          is ResultadoDePublicacao.Aceito -> resultado.intencao::class.simpleName
          ResultadoDePublicacao.ForaDaGramatica -> "descartado (fora da gramática)"
          ResultadoDePublicacao.VersaoObsoleta -> "descartado (versão de estado obsoleta)"
        }
    Log.i(TAG, "ASR[${nomeDoEstado(escuta.solicitacao.estado)}]: \"$texto\" -> $desfecho")
  }

  /**
   * Extrai um campo de texto do JSON do Vosk. `{"text": "..."}` no resultado final e
   * `{"partial": "..."}` no parcial — mesma forma, campos diferentes.
   */
  private fun textoDoJson(json: String, campo: String): String =
      runCatching { JSONObject(json).optString(campo).trim() }
          .onFailure { Log.e(TAG, "Resultado do Vosk não era JSON: $json", it) }
          .getOrDefault("")

  private fun nomeDoEstado(estado: PickingState) = estado::class.simpleName

  /** O que um estado pede: a versão que ele inaugurou e a configuração de escuta dele. */
  private class EscutaSolicitada(
      val versao: Long,
      val estado: PickingState,
      val configuracao: ConfiguracaoDeEscuta?,
  )

  /**
   * O reconhecedor construído para uma solicitação.
   *
   * [recognizer] é `null` nos estados que não escutam e quando a criação falhou — nos dois
   * casos as amostras continuam sendo lidas e simplesmente não alimentam decodificador nenhum.
   */
  private class EscutaAtiva(val solicitacao: EscutaSolicitada, val recognizer: Recognizer?)

  private companion object {
    const val TAG = "ReconhecedorDeComando"

    /** Diretório do modelo dentro de `assets/` e também dentro do armazenamento do app. */
    const val DIRETORIO_MODELO = "modelo-vosk-pt"

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
