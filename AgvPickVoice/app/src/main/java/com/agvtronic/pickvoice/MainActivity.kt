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
   * Permissões Android exigidas antes de a sessão DAT e a captura de voz poderem começar.
   *
   * O resultado não é ramificado aqui de propósito: negada ou concedida, os dois componentes
   * são chamados do mesmo jeito e cada um verifica a permissão que lhe interessa. O
   * `DatSessionController` publica `RegistroFalhou` quando falta `BLUETOOTH_CONNECT` — assim
   * a falha aparece como estado `Erro` no painel, e não como um app parado em `Ocioso` sem
   * explicação. O `ReconhecedorDeComando` apenas não escuta quando falta `RECORD_AUDIO`
   * (design.md - Decisão 6): o painel de dev continua dirigindo o fluxo por toque.
   */
  private val permissionLauncher =
      registerForActivityResult(RequestMultiplePermissions()) {
        container.datSessionController.iniciar(this)
        container.reconhecedorDeComando.iniciar()
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
    // Ambos os `iniciar` são idempotentes, então repetir isso a cada volta ao primeiro plano
    // não reinicia uma sessão viva nem reabre o microfone.
    permissionLauncher.launch(PERMISSOES)
  }

  override fun onStop() {
    super.onStop()
    // Solta o microfone enquanto o app está em segundo plano: segurá-lo impediria outros apps
    // de gravar e queimaria bateria à toa (doc §8). A sessão DAT, ao contrário, é de escopo de
    // processo (doc §2.3) e continua viva de propósito.
    container.reconhecedorDeComando.parar()
  }

  private companion object {
    /**
     * `BLUETOOTH` e `INTERNET` do manifesto são de instalação e não aparecem aqui. `CAMERA`
     * será pedida pela fatia de visão, no momento em que for usada.
     */
    val PERMISSOES =
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO)
  }
}
