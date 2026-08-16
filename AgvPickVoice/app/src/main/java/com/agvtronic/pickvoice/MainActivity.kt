package com.agvtronic.pickvoice

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.agvtronic.pickvoice.ui.devpanel.DevPanelScreen
import com.agvtronic.pickvoice.ui.devpanel.DevPanelViewModel

class MainActivity : ComponentActivity() {

  private val container by lazy { (application as PickVoiceApplication).container }

  /**
   * Permissões Android exigidas pelo SDK do DAT antes de registrar ou criar sessão.
   *
   * O resultado não é ramificado aqui de propósito: negada ou concedida, o
   * `DatSessionController` é chamado do mesmo jeito e ele próprio verifica a permissão,
   * publicando `RegistroFalhou` quando falta — assim a falha aparece como estado `Erro` no
   * painel, e não como um app parado em `Ocioso` sem explicação.
   */
  private val permissionLauncher =
      registerForActivityResult(RequestMultiplePermissions()) {
        container.datSessionController.iniciar(this)
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // DI manual, mesma convenção do AppContainer — sem Hilt (design.md - Decisions).
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

  override fun onStart() {
    super.onStart()
    // `iniciar` é idempotente, então repetir isso a cada volta ao primeiro plano não
    // reinicia uma sessão viva.
    permissionLauncher.launch(PERMISSOES_DAT)
  }

  private companion object {
    /**
     * `BLUETOOTH_CONNECT` é a única que precisa ser pedida em runtime aqui. `BLUETOOTH` e
     * `INTERNET` do manifesto são de instalação, e `CAMERA`/`RECORD_AUDIO` serão pedidas
     * pelas fatias de visão e áudio, no momento em que forem usadas.
     */
    val PERMISSOES_DAT = arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
  }
}
