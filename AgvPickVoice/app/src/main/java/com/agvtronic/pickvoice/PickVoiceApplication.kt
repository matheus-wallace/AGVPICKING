package com.agvtronic.pickvoice

import android.app.Application
import android.util.Log
import com.agvtronic.pickvoice.di.AppContainer
import com.meta.wearable.dat.core.Wearables

class PickVoiceApplication : Application() {

  lateinit var container: AppContainer
    private set

  override fun onCreate() {
    super.onCreate()

    Wearables.initialize(this).onFailure { error, _ ->
      Log.e(TAG, "Failed to initialize DAT: ${error.description}")
    }

    container = AppContainer(this)

    // TODO(#state-machine): load the Vosk model here too, not on session creation —
    // Model(path) is disk I/O and takes seconds (doc §5.3).
  }

  private companion object {
    const val TAG = "PickVoiceApplication"
  }
}
