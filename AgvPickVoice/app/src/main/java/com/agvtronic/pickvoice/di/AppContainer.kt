package com.agvtronic.pickvoice.di

import android.content.Context
import com.agvtronic.pickvoice.data.PickingRepository
import com.agvtronic.pickvoice.data.mock.MockPickingRepository

/**
 * Manual dependency container — constructor injection wired by hand, no Hilt.
 *
 * Each OpenSpec change proposal that introduces a swappable dependency
 * (FonteAudio, PickingRepository, ...) should add its wiring here: build the
 * concrete implementation once, expose it through an interface-typed val.
 */
class AppContainer(private val appContext: Context) {
  // TODO(#audio-source-abstraction): expose `val fonteAudio: FonteAudio`

  /**
   * The only mocked layer in the system (doc §1.2). Interface-typed on purpose: swapping in
   * an `HttpPickingRepository` when the real WMS integration lands is this line and nothing
   * else.
   */
  val pickingRepository: PickingRepository = MockPickingRepository()

  // TODO(#dev-event-panel): expose the `PickingActor`. Deliberately not wired here yet —
  // the actor needs a CoroutineScope, and who owns its lifecycle (a ViewModel's
  // viewModelScope vs. an application-scoped CoroutineScope that survives Activity
  // recreation) is an open question this change defers, see the change's design.md.
}
