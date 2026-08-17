package com.agvtronic.pickvoice.audio

import com.agvtronic.pickvoice.domain.statemachine.PickingActor
import com.agvtronic.pickvoice.domain.statemachine.PickingState
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Do texto reconhecido ao `PickingEvent`, atravessando as duas fronteiras que a thread de áudio
 * não pode atravessar: o I/O do repositório e a versão do estado.
 *
 * ### A versão do estado
 *
 * O contador de [versaoAtual] é a "versão de estado" da Decisão 3 do design.md. Ele avança a
 * cada transição observada, e um resultado de ASR carrega a versão que valia quando o
 * `Recognizer` que o produziu foi construído. Se a versão mudou no meio, o resultado é
 * descartado — é o que impede uma fala iniciada em `ConfirmandoQuantidade` de avançar o
 * `ReadbackQuantidade` que já entrou no lugar dela.
 *
 * A comparação acontece **duas vezes**: ao receber o texto e de novo depois da consulta ao
 * repositório, porque entre uma e outra existe suspensão — e a câmera, o DAT ou o painel podem
 * ter mudado o estado nesse intervalo.
 *
 * `AtomicLong` e não `var` porque quem incrementa é a corrotina que observa o ator e quem lê são
 * a thread de áudio e a corrotina de resolução: três contextos, um contador.
 *
 * ### Por que existe uma fila
 *
 * [publicar] é chamado da thread dedicada de áudio, que o doc §4.2 proíbe de bloquear, e a
 * resolução é `suspend`. O `Channel` ilimitado tira o trabalho dali sem esperar, exatamente como
 * o `PickingActor` faz com os eventos.
 *
 * @param scope escopo da corrotina de resolução. Nunca o da thread de áudio.
 * @param aoFalhar hook de log. A classe é Kotlin puro para poder ser testada na JVM, então quem
 *   sabe escrever no logcat é quem a constrói.
 */
class PublicadorDeVoz(
    private val actor: PickingActor,
    private val resolvedor: ResolvedorDeIntencao,
    private val scope: CoroutineScope,
    private val aoFalhar: (Throwable) -> Unit = {},
) {

  private val versao = AtomicLong()
  private val fila = Channel<Pedido>(capacity = Channel.UNLIMITED)
  private var consumo: Job? = null

  /** A versão em vigor. Um resultado de ASR de versão diferente desta é descartado. */
  val versaoAtual: Long
    get() = versao.get()

  /**
   * Marca uma transição observada e devolve a versão nova.
   *
   * Chamado pelo [ReconhecedorDeComando] ao ver um estado novo, **antes** de trocar a gramática:
   * a invalidação precisa valer já para o resultado que o decodificador anterior ainda pode
   * cuspir.
   */
  fun novaVersao(): Long = versao.incrementAndGet()

  /** Idempotente, como todo `iniciar` do projeto. */
  fun iniciar() {
    if (consumo != null) return
    consumo = scope.launch { for (pedido in fila) atender(pedido) }
  }

  fun parar() {
    consumo?.cancel()
    consumo = null
  }

  /**
   * Interpreta o texto no contexto do estado e enfileira o que dele resultar.
   *
   * Nunca bloqueia e nunca suspende — pode ser chamado da thread de áudio.
   *
   * @param versaoDoResultado a versão que valia quando este resultado começou a ser decodificado.
   * @return o desfecho da publicação — aceito ou descartado, com o motivo do descarte. Serve ao
   *   log de calibração do doc §10; nada do fluxo depende dele.
   */
  fun publicar(estado: PickingState, texto: String, versaoDoResultado: Long): ResultadoDePublicacao {
    if (versaoDoResultado != versao.get()) return ResultadoDePublicacao.VersaoObsoleta
    val intencao =
        InterpretadorDeFala.interpretar(estado, texto) ?: return ResultadoDePublicacao.ForaDaGramatica
    fila.trySend(Pedido(estado, intencao, versaoDoResultado))
    return ResultadoDePublicacao.Aceito(intencao)
  }

  private suspend fun atender(pedido: Pedido) {
    if (pedido.versao != versao.get()) return

    // Uma falha de repositório não pode derrubar a corrotina: ela levaria a voz junto, e o
    // estado ficaria intacto sem ninguém para reagir (task 3.3). O cancelamento é reerguido —
    // `runCatching` o engoliria, e a corrotina seguiria trabalhando depois de encerrada.
    val evento =
        try {
          resolvedor.resolver(pedido.estado, pedido.intencao)
        } catch (cancelamento: CancellationException) {
          throw cancelamento
        } catch (falha: Throwable) {
          aoFalhar(falha)
          return
        } ?: return

    // Segunda checagem: a resolução suspendeu, e o mundo pode ter mudado embaixo dela.
    if (pedido.versao != versao.get()) return
    actor.send(evento)
  }

  private data class Pedido(
      val estado: PickingState,
      val intencao: IntencaoDeVoz,
      val versao: Long,
  )
}
