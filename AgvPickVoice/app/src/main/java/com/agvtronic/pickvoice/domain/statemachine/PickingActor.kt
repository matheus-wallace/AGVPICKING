package com.agvtronic.pickvoice.domain.statemachine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * O ator único do doc §4.3: um [Channel] recebe **todos** os eventos de picking — VAD, ASR
 * final, frame de câmera, resultado de decode, lifecycle do DAT, painel de dev — e uma
 * corrotina única os processa sequencialmente, aplicando [reduce].
 *
 * Confinamento por consumidor único elimina a maior parte dos bugs de concorrência antes de
 * existirem e torna o log de transições trivialmente ordenado, que é o que a auditoria de
 * rastreabilidade precisa. **Ninguém escreve [state] diretamente**; todo mundo chama [send].
 *
 * O [scope] vem de fora de propósito: quem é dono do ciclo de vida do ator ainda não está
 * decidido (ver `design.md` — Open Questions). A mudança `add-dev-event-panel` conecta o ator
 * a uma tela real e escolhe o escopo.
 *
 * @param scope escopo que hospeda a corrotina consumidora; cancelá-lo encerra o ator.
 * @param estadoInicial estado de partida, [PickingState.Ocioso] em produção.
 * @param reducer a transição a aplicar. Parametrizado só para permitir substituir por um
 *   duplo em teste — o padrão é o [reduce] real, e nada em produção passa outro valor.
 */
class PickingActor(
    scope: CoroutineScope,
    estadoInicial: PickingState = PickingState.Ocioso,
    private val reducer: (PickingState, PickingEvent) -> PickingState = ::reduce,
) {

  /**
   * Capacidade ilimitada por decisão explícita (`design.md` — Decisions): um channel de
   * rendezvous faria uma rajada de eventos transversais aplicar backpressure no produtor, e
   * um dos produtores é a thread de áudio, que o doc §4.2 proíbe de bloquear em qualquer
   * coisa que não seja o próprio loop de frame.
   */
  private val eventos = Channel<PickingEvent>(capacity = Channel.UNLIMITED)

  private val _state = MutableStateFlow(estadoInicial)

  /** Estado corrente do fluxo de picking. Só a corrotina consumidora escreve aqui. */
  val state: StateFlow<PickingState> = _state.asStateFlow()

  /** A corrotina consumidora única. Exposta para que o dono do escopo possa aguardá-la. */
  val job: Job =
      scope.launch {
        for (evento in eventos) {
          _state.value = reducer(_state.value, evento)
        }
      }

  /**
   * Enfileira um evento. Nunca bloqueia e nunca suspende — o channel é ilimitado, então
   * `trySend` só falha depois de [close], e um evento chegando depois do encerramento da
   * sessão é ruído a descartar, não erro a propagar.
   */
  fun send(event: PickingEvent) {
    eventos.trySend(event)
  }

  /** Encerra a fila. A corrotina consumidora termina depois de drenar o que já foi enviado. */
  fun close() {
    eventos.close()
  }
}
