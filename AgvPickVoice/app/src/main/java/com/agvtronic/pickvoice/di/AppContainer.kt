package com.agvtronic.pickvoice.di

import android.content.Context

/**
 * Manual dependency container — constructor injection wired by hand, no Hilt.
 *
 * Each OpenSpec change proposal that introduces a swappable dependency
 * (FonteAudio, PickingRepository, ...) should add its wiring here: build the
 * concrete implementation once, expose it through an interface-typed val.
 */
class AppContainer(private val appContext: Context) {
  // TODO(#audio-source-abstraction): expose `val fonteAudio: FonteAudio`
  // TODO(#mock-repository): expose `val pickingRepository: PickingRepository`
}
