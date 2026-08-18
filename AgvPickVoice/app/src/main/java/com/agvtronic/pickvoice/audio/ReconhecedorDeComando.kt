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

/**
 * O produtor de [PickingEvent] por voz — o ciclo de escuta, sem saber qual decodificador roda.
 *
 * A gramática não é fixa: a cada transição do [PickingActor] esta classe pergunta ao
 * [SeletorDeEscuta] o que escutar naquele estado e reabre a sessão do motor na thread dedicada de
 * áudio. **Nada de domínio mora aqui** — o que a fala significa é do [InterpretadorDeFala], o que
 * ela vale contra o dado operacional é do [ResolvedorDeIntencao], e o envio ao ator é do
 * [PublicadorDeVoz]. Esta classe observa `actor.state` e nunca chama `actor.send`
 * (add-state-driven-voice-flow - Decisão 1).
 *
 * ### Nem o Vosk nem o sherpa-onnx aparecem aqui
 *
 * Até a troca de motor, esta classe *era* a superfície do Vosk: importava `org.vosk.*`, montava o
 * `Recognizer` e parseava o JSON dele. Isso saiu inteiro para [MotorVosk], atrás da interface
 * [MotorDeAsr] (add-sherpa-onnx-asr-engine - Decisão 1). O que sobrou aqui — thread dedicada,
 * observação de estado, versionamento, publicação, log — nunca dependeu de qual decodificador
 * roda; a mudança formalizou uma fronteira que já existia de fato.
 *
 * ### Confinamento de thread
 *
 * Nenhum motor de ASR deste projeto é thread-safe: `Model`/`Recognizer` do Vosk não são, e
 * sessões do ONNX Runtime também não. Carga do modelo, abertura de sessão, captura e
 * decodificação rodam todas em [dispatcherAudio], uma thread só.
 *
 * A observação do estado é a única coisa que roda fora dela, em [Dispatchers.Default], e por um
 * motivo concreto: `AudioRecord.read` **bloqueia** a thread de áudio, e uma corrotina bloqueada
 * num dispatcher de thread única nunca cede a vez. Um coletor de `actor.state` hospedado ali
 * jamais seria escalonado. O observador então só anota o que passou a valer em [solicitada]; a
 * troca de sessão acontece na thread de áudio, na janela seguinte.
 *
 * @param appContext contexto de aplicação — este componente vive além de qualquer `Activity`.
 * @param fonteAudio de onde vêm as amostras; [AudioMicrofoneSimulado] hoje, [AudioHfpOculos]
 *   no dia em que o óculos entrar (doc §5.2).
 * @param motor qual decodificador roda; escolhido em um ponto único no `AppContainer`, mesmo
 *   padrão do [fonteAudio].
 * @param actor observado, nunca escrito.
 * @param publicador destino do texto reconhecido e dono da versão do estado.
 * @param falaEmCurso `SaidaDeAudio.falando`: enquanto `true`, nenhum resultado é aceito
 *   (add-state-driven-voice-flow - Decisão 6).
 * @param ajustes calibração de bancada; ver [AjustesAsr]. Os defaults são o comportamento de
 *   produção.
 */
class ReconhecedorDeComando(
    private val appContext: Context,
    private val fonteAudio: FonteAudio,
    private val motor: MotorDeAsr,
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
   * `Deferred` porque a carga leva segundos (dezenas de MB de modelo, qualquer que seja o motor)
   * e quem chama [iniciar] não deve esperar por ela na main thread: o loop de escuta simplesmente
   * aguarda aqui, já dentro da thread de áudio.
   */
  private val motorPronto: Deferred<Boolean> = escopoAudio.async { motor.carregar() }

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

  /** A sessão em uso. Confinada na thread de áudio — nenhuma outra a toca. */
  private var ativa: EscutaAtiva? = null

  /**
   * Começa a escutar. Idempotente — a `MainActivity` chama a cada volta ao primeiro plano.
   *
   * Sem `RECORD_AUDIO` este método não faz nada e nenhum evento é publicado
   * (add-audio-single-grammar-slice - Decisão 6): não existe `PickingEvent` de "áudio
   * indisponível" no domínio, e o app segue inteiramente operável pelos botões do painel de dev.
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
  // Observação do estado
  // -----------------------------------------------------------------------------------

  /**
   * Anota, a cada estado novo, a versão e a configuração de escuta correspondente.
   *
   * A versão sobe **aqui**, no instante da transição, e não quando a thread de áudio troca de
   * sessão: entre uma coisa e outra o decodificador antigo ainda pode fechar uma elocução, e é
   * exatamente esse resultado que precisa ser invalidado (add-state-driven-voice-flow -
   * Decisão 3).
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
    if (!motorPronto.await()) {
      Log.e(TAG, "Motor ${motor.nome} não carregou; reconhecimento de voz desligado")
      return
    }

    val medidor = MedidorDeNivel(fonteAudio.sampleRate)

    // O último parcial visto, guardado por dois motivos: evitar logar a mesma hipótese
    // repetida a cada janela, e dar contexto quando a elocução fecha sem texto final — saber
    // que o decodificador tinha "pa" na mão é o que distingue "silêncio" de "comando cortado
    // no meio". Motores sem hipótese parcial (VAD-então-ASR) simplesmente nunca o preenchem.
    var ultimoParcial = ""

    // A elocução precisa recomeçar quando o sistema para de falar; até lá, o que entrou no
    // decodificador é o próprio TTS vazando pelo microfone (add-state-driven-voice-flow -
    // Decisão 6).
    var reiniciarAposFala = false

    try {
      fonteAudio.fluxo(TAMANHO_JANELA).collect { janela ->
        // Trocou de estado nesta janela: a hipótese parcial pertencia à sessão que acabou de
        // ser fechada.
        if (sincronizarEscuta() != null) ultimoParcial = ""

        val sessao = ativa?.sessao ?: return@collect

        if (falaEmCurso.value) {
          reiniciarAposFala = true
          ultimoParcial = ""
          return@collect
        }
        if (reiniciarAposFala) {
          sessao.reiniciar()
          reiniciarAposFala = false
        }

        if (ajustes.logNivel) {
          medidor.acumular(janela)?.let { Log.d(TAG, "Nível: $it") }
        }

        when (val resultado = sessao.aceitar(comGanho(janela))) {
          is ResultadoDeAsr.Fechada -> {
            publicar(resultado.texto, ultimoParcial)
            ultimoParcial = ""
          }
          is ResultadoDeAsr.EmAndamento -> {
            if (resultado.parcial != ultimoParcial) {
              if (resultado.parcial.isNotEmpty()) Log.d(TAG, "Parcial: \"${resultado.parcial}\"")
              ultimoParcial = resultado.parcial
            }
          }
        }
      }
    } finally {
      // Roda também no cancelamento: toda sessão segura ponteiro nativo e precisa ser fechada.
      ativa?.sessao?.close()
      ativa = null
    }
  }

  /**
   * Aplica o ganho de bancada, mantendo o contrato de escala da [FonteAudio].
   *
   * O ganho é calibração, não peculiaridade de motor: vale para qualquer decodificador, e por
   * isso fica deste lado da fronteira. O clipping importa — com ganho > 1 uma amostra alta
   * estouraria a escala e viraria distorção, que o decodificador lê pior que o sinal fraco
   * original. Com o ganho no default não há cópia: a janela segue direto.
   */
  private fun comGanho(janela: FloatArray): FloatArray =
      if (ajustes.ganho == 1f) janela
      else FloatArray(janela.size) { (janela[it] * ajustes.ganho).coerceIn(-1f, 1f) }

  /**
   * Aplica a configuração pedida pelo estado, se ela mudou. Roda na thread de áudio.
   *
   * @return a escuta recém-criada, ou `null` quando nada mudou desde a janela anterior.
   */
  private fun sincronizarEscuta(): EscutaAtiva? {
    val pedido = solicitada ?: return null
    if (pedido === ativa?.solicitacao) return null

    ativa?.sessao?.close()

    val config = pedido.configuracao
    if (config == null) {
      Log.i(TAG, "Estado ${nomeDoEstado(pedido.estado)} não escuta comando de voz")
      ativa = EscutaAtiva(pedido, sessao = null)
      return ativa
    }

    // Uma sessão que o motor recuse não pode derrubar a captura nem mexer no estado: sem
    // sessão, o app segue no mesmo estado e o painel continua servindo.
    val sessao = motor.abrirSessao(config, fonteAudio.sampleRate)

    Log.i(
        TAG,
        "Escutando ${nomeDoEstado(pedido.estado)} v${pedido.versao} " +
            "(motor=${motor.nome}, " +
            "vocabulário=${if (config.aberta) "aberto" else "${config.palavras.size} palavras"}, " +
            "perfil=${config.perfil}, ${fonteAudio.sampleRate}Hz)",
    )

    ativa = EscutaAtiva(pedido, sessao)
    return ativa
  }

  /**
   * Entrega o resultado final ao [PublicadorDeVoz], com a versão sob a qual ele foi decodificado.
   *
   * Silêncio devolve texto vazio e fala fora do contrato devolve algo que o
   * [InterpretadorDeFala] não reconhece; os dois casos não publicam nada, que é o que a spec
   * exige em "texto fora do contrato não produz evento".
   *
   * @param parcialAnterior a última hipótese parcial antes de a elocução fechar. Só serve para o
   *   log; ver por que em [escutar].
   */
  private fun publicar(texto: String, parcialAnterior: String) {
    val escuta = ativa ?: return

    if (texto.isEmpty()) {
      // Elocução vazia é o caso comum: a cada janela de silêncio sem ninguém falar, o motor
      // recicla o decodificador e devolve texto vazio. Logar isso sempre inundaria o logcat com
      // uma linha a cada poucos segundos. Quando havia um parcial, porém, alguma coisa estava
      // sendo decodificada e desapareceu no fim — esse caso é exatamente o sintoma de endpoint
      // cedo demais, e precisa aparecer.
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
    // descarte vem separado: fora da gramática do estado atual não é o mesmo sintoma de
    // bancada que resultado de versão de estado obsoleta.
    val desfecho =
        when (resultado) {
          is ResultadoDePublicacao.Aceito -> resultado.intencao::class.simpleName
          ResultadoDePublicacao.ForaDaGramatica -> "descartado (fora da gramática)"
          ResultadoDePublicacao.VersaoObsoleta -> "descartado (versão de estado obsoleta)"
        }
    Log.i(TAG, "ASR[${nomeDoEstado(escuta.solicitacao.estado)}]: \"$texto\" -> $desfecho")
  }

  private fun nomeDoEstado(estado: PickingState) = estado::class.simpleName

  /** O que um estado pede: a versão que ele inaugurou e a configuração de escuta dele. */
  private class EscutaSolicitada(
      val versao: Long,
      val estado: PickingState,
      val configuracao: ConfiguracaoDeEscuta?,
  )

  /**
   * A sessão construída para uma solicitação.
   *
   * [sessao] é `null` nos estados que não escutam e quando a criação falhou — nos dois casos as
   * amostras continuam sendo lidas e simplesmente não alimentam decodificador nenhum.
   */
  private class EscutaAtiva(val solicitacao: EscutaSolicitada, val sessao: SessaoDeAsr?)

  private companion object {
    const val TAG = "ReconhecedorDeComando"

    /** 64 ms a 8 kHz — granularidade suficiente para os 280 ms do COMANDO_CURTO. */
    const val TAMANHO_JANELA = 512
  }
}
