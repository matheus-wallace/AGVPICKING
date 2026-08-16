package com.agvtronic.pickvoice.di

import android.content.Context
import com.agvtronic.pickvoice.audio.AjustesAsr
import com.agvtronic.pickvoice.audio.AudioMicrofoneSimulado
import com.agvtronic.pickvoice.audio.FonteAudio
import com.agvtronic.pickvoice.audio.ReconhecedorDeComando
import com.agvtronic.pickvoice.dat.DatSessionController
import com.agvtronic.pickvoice.data.PickingRepository
import com.agvtronic.pickvoice.data.mock.MockPickingRepository
import com.agvtronic.pickvoice.domain.statemachine.PickingActor
import com.agvtronic.pickvoice.vision.AjustesVisao
import com.agvtronic.pickvoice.vision.ControladorDeVisao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency container — constructor injection wired by hand, no Hilt.
 *
 * Each OpenSpec change proposal that introduces a swappable dependency
 * (FonteAudio, PickingRepository, ...) should add its wiring here: build the
 * concrete implementation once, expose it through an interface-typed val.
 */
class AppContainer(private val appContext: Context) {

  /**
   * Escopo de vida do processo, dono da corrotina consumidora do [pickingActor].
   *
   * **Não é um `viewModelScope` de propósito.** O doc §4.3 associa o ator à sessão DAT, que é
   * de escopo de processo: uma sessão Bluetooth/câmera viva não deveria reiniciar só porque a
   * `Activity` foi recriada numa rotação de tela. Amarrar o ator a um `ViewModel` perderia
   * estado e eventos em toda mudança de configuração, e obrigaria toda mudança futura que
   * tocar sessão ou áudio a refazer essa fiação.
   *
   * `SupervisorJob` para que a falha de uma corrotina filha não derrube as irmãs, e
   * [Dispatchers.Default] porque o ator só faz CPU — o reducer é função pura, sem I/O.
   *
   * Nunca é cancelado: o app é de processo único e a vida do container é a vida do processo.
   */
  private val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /**
   * The only mocked layer in the system (doc §1.2). Interface-typed on purpose: swapping in
   * an `HttpPickingRepository` when the real WMS integration lands is this line and nothing
   * else.
   */
  val pickingRepository: PickingRepository = MockPickingRepository()

  /**
   * O ator único do doc §4.3. Toda fonte de evento — painel de dev hoje, voz, câmera e
   * lifecycle do DAT nas mudanças seguintes — publica aqui via `send`, e ninguém escreve
   * estado direto.
   */
  val pickingActor: PickingActor = PickingActor(appScope)

  /**
   * Escopo do controlador de sessão, separado do [appScope] por causa do dispatcher.
   *
   * `Wearables.startRegistration` abre o fluxo do app Meta AI a partir de uma `Activity`, e o
   * `WearablesRepository` do sample `DisplayAccess` observa o SDK em [Dispatchers.Main] pelo
   * mesmo motivo. O ator continua em [Dispatchers.Default] — ele só faz CPU, e misturar as
   * duas coisas num escopo só colocaria o reducer na main thread sem necessidade.
   *
   * Também nunca é cancelado: a sessão DAT é de escopo de processo (doc §2.3), não de tela.
   */
  private val datScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

  /**
   * O produtor real dos eventos de ciclo de vida da sessão. Quem chama `iniciar` é a
   * `MainActivity`, depois de resolver as permissões Android.
   */
  val datSessionController: DatSessionController =
      DatSessionController(appContext, pickingActor, datScope)

  /**
   * Calibração do pipeline de voz, lida uma vez e compartilhada pelas duas peças de áudio.
   *
   * Uma instância só de propósito: a taxa de amostragem que a [fonteAudio] declara e a que o
   * `Recognizer` do [reconhecedorDeComando] usa **têm que ser a mesma**, e as duas saem daqui.
   * Ler o arquivo duas vezes abriria a porta para elas divergirem se ele mudasse no meio.
   */
  private val ajustesAsr: AjustesAsr = AjustesAsr.carregar(appContext)

  /**
   * A fonte de áudio do doc §5.2, interface-tipada pelo mesmo motivo do [pickingRepository]:
   * trocar o microfone do celular pelo HFP do óculos na manhã de 18/09 é **esta linha e mais
   * nenhuma** — é literalmente o que o doc §13.3 exige.
   */
  val fonteAudio: FonteAudio = AudioMicrofoneSimulado(ajustesAsr)

  /**
   * O produtor de eventos por voz.
   *
   * Construí-lo já dispara a carga do modelo Vosk na thread de áudio dele, ainda no
   * `onCreate` da `Application` — é o doc §5.3 ("carregar na inicialização do app, não ao
   * criar a sessão"). A construção não bloqueia: a carga é assíncrona e roda em paralelo com
   * a subida da sessão DAT, que acontece no [datScope]. Quem chama `iniciar` é a
   * `MainActivity`, depois de resolver `RECORD_AUDIO`.
   */
  val reconhecedorDeComando: ReconhecedorDeComando =
      ReconhecedorDeComando(appContext, fonteAudio, pickingActor, ajustesAsr)

  /** Calibração do pipeline de visão (recorte, resolução, taxa de quadros), lida uma vez. */
  private val ajustesVisao: AjustesVisao = AjustesVisao.carregar(appContext)

  /**
   * Escopo do controlador de visão, em [Dispatchers.Main] pelo mesmo motivo do [datScope]: quem
   * ele observa é o SDK, e a câmera é ligada a partir da mesma sessão que o `datScope` mantém.
   *
   * O trabalho pesado não acontece aqui — a coleta dos frames vai para [Dispatchers.Default], a
   * decodificação para a thread do codec e a leitura para a thread do ML Kit.
   */
  private val visaoScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

  /**
   * O produtor de eventos por câmera.
   *
   * Recebe o `StateFlow` de sessão do [datSessionController] em vez de criar a própria: o doc
   * §2.3 permite uma sessão por dispositivo, e uma segunda `createSession` falharia. Quem chama
   * `iniciar` é a `MainActivity`, que é a única capaz de registrar o contrato de permissão do
   * DAT.
   */
  val controladorDeVisao: ControladorDeVisao =
      ControladorDeVisao(
          actor = pickingActor,
          sessoes = datSessionController.sessaoAtiva,
          ajustes = ajustesVisao,
          scope = visaoScope,
      )
}
