package com.agvtronic.pickvoice.di

import android.content.Context
import android.util.Log
import com.agvtronic.pickvoice.audio.AjustesAsr
// Importada só pelos links de KDoc acima de `fonteAudio`: é a fonte para onde se volta quando
// houver óculos físico na bancada.
import com.agvtronic.pickvoice.audio.AudioHfpOculos
import com.agvtronic.pickvoice.audio.AudioMicrofoneSimulado
import com.agvtronic.pickvoice.audio.FonteAudio
import com.agvtronic.pickvoice.audio.MotorDeAsr
import com.agvtronic.pickvoice.audio.MotorPicovoiceRhino
// Importado só pelos links de KDoc acima de `motorDeAsr`: bancada de 18/08/2026 no TC21
// derrubou a hipótese a favor dele (ver KDoc), volta a ser candidato numa próxima rodada.
import com.agvtronic.pickvoice.audio.MotorSherpaOnnx
import com.agvtronic.pickvoice.audio.MotorVosk
import com.agvtronic.pickvoice.audio.PublicadorDeVoz
import com.agvtronic.pickvoice.audio.ReconhecedorDeComando
import com.agvtronic.pickvoice.audio.ResolvedorDeIntencao
import com.agvtronic.pickvoice.audio.output.ControladorDeFala
import com.agvtronic.pickvoice.audio.output.SaidaDeAudio
import com.agvtronic.pickvoice.audio.output.SaidaTextToSpeechAndroid
import com.agvtronic.pickvoice.dat.DatSessionController
import com.agvtronic.pickvoice.data.PickingRepository
import com.agvtronic.pickvoice.data.mock.MockPickingRepository
import com.agvtronic.pickvoice.domain.statemachine.PickingActor
import com.agvtronic.pickvoice.vision.AjustesVisao
import com.agvtronic.pickvoice.vision.ComparadorDeCodigo
import com.agvtronic.pickvoice.vision.ControladorDeVisao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

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
   * trocar o microfone do celular pelo HFP do óculos é **esta linha e mais nenhuma** — é
   * literalmente o que o doc §13.3 exige.
   *
   * **As duas implementações existem e estão prontas.** A ativa hoje é
   * [AudioMicrofoneSimulado], que captura pelo microfone do próprio aparelho, porque o
   * desenvolvimento está rodando sem óculos físico: o `MockDeviceKit` simula a câmera, não o
   * áudio, e sem óculos pareado não há dispositivo Bluetooth SCO nenhum. [AudioHfpOculos]
   * degrada em silêncio nesse cenário — não quebra, mas também não captura nada, e a voz
   * simplesmente emudece na bancada.
   *
   * **Quando houver óculos real para bancada, a volta é uma linha:** `AudioHfpOculos(appContext)`.
   * Nada mais muda aqui — cada implementação declara a própria taxa de amostragem, e é dela
   * que o [reconhecedorDeComando] parte.
   */
  val fonteAudio: FonteAudio = AudioMicrofoneSimulado(ajustesAsr)

  /**
   * Qual decodificador de fala roda, interface-tipado pelo mesmo motivo do [fonteAudio]: trocar
   * Vosk por sherpa-onnx é **esta linha e mais nenhuma** (add-sherpa-onnx-asr-engine - Decisão 1).
   *
   * [MotorVosk] e [MotorSherpaOnnx] permanecem disponíveis como alternativas. A bancada de
   * 18/08/2026 testou o segundo no TC21 com `degradarCanal=false` (áudio cru a 16 kHz, sinal
   * forte, pico ~-14 dBFS) e o Whisper-tiny, que era o decodificador dele na época, alucinou nos
   * comandos curtos da gramática (`"iniciar"`/`"próximo"` saíram embutidos em texto extra, nunca
   * isolados). Não foi problema de ganho nem de canal degradado — as duas hipóteses foram
   * descartadas nessa mesma bancada.
   *
   * O [MotorSherpaOnnx] **já trocou aquele decodificador** por Omnilingual ASR CTC
   * (`add-sherpa-onnx-omnilingual-decoder`), justamente porque CTC é frame-síncrono e não tem o
   * mecanismo de geração livre que produzia a alucinação — e a bancada de 18/08/2026 (mesmo dia,
   * SM-G780F) **também falhou**, por um motivo diferente: sem campo de idioma na API (confirmado
   * no binding Kotlin e no `.h` do C++ oficial, não é limitação só do binding), o modelo decodifica
   * elocuções curtas de pt-BR em scripts de outros idiomas (chinês, grego, devanágari) em vez de
   * alucinar texto plausível. Nenhuma tentativa passou da gramática. `hotwordsFile`/`hotwordsScore`
   * foi descartado sem implementar — só funciona em modelo transducer, não em CTC.
   *
   * **Via sherpa-onnx encerrada para este projeto** até que o model zoo publique um transducer
   * pt-BR (único tipo que suporta restrição por gramática) ou o Omnilingual ASR ganhe seletor de
   * idioma via API (`k2-fsa/sherpa-onnx#2812`, ainda em aberto). Ver `tasks.md` de
   * `add-sherpa-onnx-omnilingual-decoder`, seção 6, para o log real da bancada.
   *
   * [MotorPicovoiceRhino] é fala-para-intenção de vocabulário fechado — categoria diferente das
   * duas tentativas de fala-para-texto acima, e mais próxima do que faz o Vosk funcionar hoje
   * (gramática fechada por estado).
   *
   * **O Rhino é o motor ativo.** O contexto principal preserva comandos/check digit e o contexto
   * dedicado reconhece quantidades de 1 a 9999. Ambos são carregados na subida; o estado de
   * picking seleciona qual recebe áudio sem reconstruir engines durante a operação.
   */
  val motorDeAsr: MotorDeAsr = MotorPicovoiceRhino(appContext, ajustesAsr)

  /** Saída substituível: TTS local nesta fatia, Piper/HFP quando essa rota existir. */
  val saidaDeAudio: SaidaDeAudio = SaidaTextToSpeechAndroid(appContext)

  /**
   * Do texto reconhecido ao `PickingEvent`, com a versão de estado no meio.
   *
   * Vive no [appScope] e não na thread de áudio de propósito: ele consulta o repositório, e o
   * doc §4.2 proíbe qualquer espera na thread que alimenta o decodificador. O log fica aqui
   * porque o publicador é Kotlin puro — assim ele continua testável na JVM.
   */
  private val publicadorDeVoz: PublicadorDeVoz =
      PublicadorDeVoz(
          actor = pickingActor,
          resolvedor = ResolvedorDeIntencao(pickingRepository),
          scope = appScope,
          aoFalhar = { Log.e("PublicadorDeVoz", "Falha ao resolver intenção de voz", it) },
      )

  /**
   * O produtor de eventos por voz.
   *
   * Construí-lo já dispara a carga do modelo Vosk na thread de áudio dele, ainda no
   * `onCreate` da `Application` — é o doc §5.3 ("carregar na inicialização do app, não ao
   * criar a sessão"). A construção não bloqueia: a carga é assíncrona e roda em paralelo com
   * a subida da sessão DAT, que acontece no [datScope]. Quem chama `iniciar` é a
   * `MainActivity`, depois de resolver `RECORD_AUDIO`.
   *
   * Recebe `SaidaDeAudio.falando` para não disputar a instrução com o TTS (design.md -
   * Decisão 6); é por isso que a saída de áudio é construída acima dele.
   */
  val reconhecedorDeComando: ReconhecedorDeComando =
      ReconhecedorDeComando(
          appContext = appContext,
          fonteAudio = fonteAudio,
          motor = motorDeAsr,
          actor = pickingActor,
          publicador = publicadorDeVoz,
          falaEmCurso = saidaDeAudio.falando,
          ajustes = ajustesAsr,
      )

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
          diretorioTemporarioCapturas = File(appContext.cacheDir, "capturas-visao"),
      )

  /**
   * O que faltava para `ValidandoContraDados` sair sozinho do lugar: compara o código lido com o
   * dado da linha e publica `ValidacaoOk`/`ValidacaoDivergente`.
   *
   * Vive no [appScope], e não no [visaoScope]: ele não fala com o SDK nem com a câmera — só
   * consulta o repositório, exatamente como o [publicadorDeVoz]. Começa a observar já na
   * construção do container porque não depende de permissão nenhuma.
   */
  val comparadorDeCodigo: ComparadorDeCodigo =
      ComparadorDeCodigo(
              actor = pickingActor,
              repository = pickingRepository,
              scope = appScope,
              aoRegistrar = { Log.i("ComparadorDeCodigo", it) },
          )
          .also { it.iniciar() }

  /**
   * Observador de processo que transforma estado e orientação de visão em fala. O controlador
   * apenas lê os fluxos: não publica eventos e não conhece pixels.
   */
  val controladorDeFala: ControladorDeFala =
      ControladorDeFala(
          actor = pickingActor,
          diagnosticoVisao = controladorDeVisao.diagnostico,
          saida = saidaDeAudio,
          scope = datScope,
      )
}
