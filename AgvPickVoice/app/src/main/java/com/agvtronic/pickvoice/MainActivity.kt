package com.agvtronic.pickvoice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.agvtronic.pickvoice.ui.devpanel.DevPanelScreen
import com.agvtronic.pickvoice.ui.devpanel.DevPanelViewModel

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // DI manual, mesma convenção do AppContainer — sem Hilt (design.md - Decisions).
    val container = (application as PickVoiceApplication).container
    val factory = viewModelFactory {
      initializer { DevPanelViewModel(container.pickingActor, container.pickingRepository) }
    }

    setContent {
      MaterialTheme {
        Surface {
          // TODO(#mirror-screen): substituir pela navegação real (pareamento -> lista de
          // ordens -> operação -> divergência) e pela tela espelho do doc §12. O painel de
          // dev existe só enquanto voz e câmera não publicam eventos.
          DevPanelScreen(viewModel = viewModel(factory = factory))
        }
      }
    }
  }
}
