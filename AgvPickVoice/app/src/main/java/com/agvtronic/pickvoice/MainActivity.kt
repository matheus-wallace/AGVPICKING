package com.agvtronic.pickvoice

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.agvtronic.pickvoice.ui.devpanel.DevPanelViewModel
import com.agvtronic.pickvoice.ui.debugsettings.ConfiguracoesDebugScreen
import com.agvtronic.pickvoice.ui.mirror.MiniaturaDeCamera
import com.agvtronic.pickvoice.ui.mirror.MirrorScreen
import com.agvtronic.pickvoice.ui.mirror.MirrorViewModel
import com.agvtronic.pickvoice.ui.operation.OperationScreen
import com.agvtronic.pickvoice.ui.operation.OperationViewModel
import com.agvtronic.pickvoice.ui.theme.AgvPickVoiceTheme
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
        container.controladorDeVisao.iniciar(::solicitarPermissaoDoDat)
        container.controladorDeFala.iniciar()
      }

  /**
   * A permissão de câmera **do óculos** é do DAT, não do Android, e concedê-la manda o operador
   * para o app Meta AI.
   *
   * Fica aqui porque só uma `Activity` pode registrar o contrato, e é passada ao
   * `ControladorDeVisao` como função — ele a solicita no momento em que a câmera é de fato
   * necessária (primeira entrada em `EscaneandoProduto`), não na abertura do app. O `Mutex`
   * segue o sample `CameraAccess`: duas solicitações simultâneas sobrescreveriam a continuação.
   */
  private var continuacaoDePermissao: CancellableContinuation<PermissionStatus>? = null
  private val mutexDePermissao = Mutex()

  private val permissaoDatLauncher =
      registerForActivityResult(Wearables.RequestPermissionContract()) { resultado ->
        val status = resultado.getOrDefault(PermissionStatus.Denied)
        continuacaoDePermissao?.resume(status)
        continuacaoDePermissao = null
      }

  private suspend fun solicitarPermissaoDoDat(permissao: Permission): PermissionStatus =
      mutexDePermissao.withLock {
        suspendCancellableCoroutine { continuacao ->
          continuacaoDePermissao = continuacao
          continuacao.invokeOnCancellation { continuacaoDePermissao = null }
          permissaoDatLauncher.launch(permissao)
        }
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // DI manual, mesma convenção do AppContainer — sem Hilt (design.md - Decisions).
    val factory = viewModelFactory {
      initializer {
        DevPanelViewModel(
            container.pickingActor,
            container.pickingRepository,
            container.controladorDeFala.diagnostico,
        )
      }
      initializer { MirrorViewModel(container.controladorDeVisao) }
      initializer {
        OperationViewModel(
            container.pickingActor,
            container.pickingRepository,
            container.controladorDeVisao.diagnostico,
            container.controladorDeFala.diagnostico,
        )
      }
    }

    setContent {
      AgvPickVoiceTheme {
        Surface {
          // Os três ViewModels são resolvidos aqui, fora do `when`: eles vivem no
          // `ViewModelStore` da `Activity`, então alternar de superfície não recria nenhum
          // deles — não há segunda assinatura de estado, nem `iniciar`/`parar` de sessão,
          // áudio ou visão nessa troca.
          val operationViewModel: OperationViewModel = viewModel(factory = factory)
          val mirrorViewModel: MirrorViewModel = viewModel(factory = factory)
          val devPanelViewModel: DevPanelViewModel = viewModel(factory = factory)
          var superficie by rememberSaveable { mutableStateOf(Superficie.OPERACAO) }

          // A miniatura da câmera é irmã do `when`, e não filha de uma das telas: hospedada
          // aqui, ela sobrevive à troca de superfície e à troca de etapa, guardando posição e
          // dispensa enquanto o stream estiver ativo. Dentro das telas, cada composição tinha o
          // próprio `remember` e a miniatura voltava ao lugar de origem a cada passo.
          BoxWithConstraints(Modifier.fillMaxSize()) {
            val limiteLarguraPx = with(LocalDensity.current) { maxWidth.toPx() }
            val limiteAlturaPx = with(LocalDensity.current) { maxHeight.toPx() }

            when (superficie) {
              Superficie.OPERACAO ->
                  OperationScreen(
                      viewModel = operationViewModel,
                      aoAbrirDebug = { superficie = Superficie.DEBUG },
                  )
              Superficie.DEBUG ->
                  MirrorScreen(
                      viewModel = mirrorViewModel,
                      devPanelViewModel = devPanelViewModel,
                      aoVoltarParaOperacao = { superficie = Superficie.OPERACAO },
                      aoAbrirConfiguracoes = { superficie = Superficie.CONFIGURACOES_DEBUG },
                  )
              Superficie.CONFIGURACOES_DEBUG ->
                  ConfiguracoesDebugScreen(
                      repositorio = container.repositorioConfiguracoesDebug,
                      aoVoltar = { superficie = Superficie.DEBUG },
                  )
            }

            MiniaturaDeCamera(
                viewModel = mirrorViewModel,
                limiteLarguraPx = limiteLarguraPx,
                limiteAlturaPx = limiteAlturaPx,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
          }
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
    // Mesma razão para a câmera, com um agravante: ela é o maior consumo de bateria do óculos
    // (doc §8), e deixá-la ligada em segundo plano contradiria a §9.2, que promete captura só
    // nos estados de escaneamento. Também solta a referência à `Activity` que o solicitante de
    // permissão captura.
    container.controladorDeVisao.parar()
    // Cancela qualquer instrução em curso e libera o motor TTS. A deduplicação permanece no
    // controlador, portanto voltar ao app não repete a última mensagem do mesmo estado.
    container.controladorDeFala.parar()
  }

  private companion object {
    /**
     * `BLUETOOTH` e `INTERNET` do manifesto são de instalação e não aparecem aqui.
     *
     * `CAMERA` **não é usada pelo app em release** — quem tem câmera é o óculos, e a permissão
     * daquela câmera é do DAT, não do Android (`Wearables.checkPermissionStatus`). Ela é pedida
     * porque em debug o MockDeviceKit transmite a câmera traseira do celular como se fosse o
     * feed do óculos, que é como a fatia de visão é exercitada em bancada. Pedir sempre, em vez
     * de só em debug, mantém `MainActivity` sem `BuildConfig.DEBUG` — mesma razão pela qual o
     * bootstrap do dispositivo simulado vive em `src/debug/`.
     */
    val PERMISSOES =
        arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
        )
  }
}

/**
 * Qual superfície está visível.
 *
 * Estado só de apresentação: a escolha vive na composição, não no `PickingActor`. Trocar de
 * superfície é uma troca de composable e nada mais — nenhum `PickingEvent`, nenhuma sessão
 * nova, nenhum reinício de áudio ou de visão.
 */
private enum class Superficie {
  /** A tela do separador, aberta por padrão. */
  OPERACAO,

  /** A prévia espelho com o painel de eventos, para bancada e diagnóstico. */
  DEBUG,

  /** Configura o hardware e os pipelines da próxima execução sem editar o código. */
  CONFIGURACOES_DEBUG,
}
