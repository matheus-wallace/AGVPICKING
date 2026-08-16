package com.agvtronic.pickvoice.vision

import android.media.Image
import android.util.Log
import com.agvtronic.pickvoice.domain.statemachine.PickingActor
import com.agvtronic.pickvoice.domain.statemachine.PickingEvent
import com.agvtronic.pickvoice.domain.statemachine.PickingState
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * O produtor de eventos por câmera — passo 1 da cascata do doc §6.3.
 *
 * Mesmo papel que `DatSessionController` tem para a sessão e `ReconhecedorDeComando` para a voz:
 * transforma um sensor real em [PickingEvent] e **nunca** escreve estado direto. A diferença é
 * que este é o primeiro componente que também **lê** o estado do ator, e por um motivo que o doc
 * trata como requisito, não como otimização: a câmera só pode estar ligada em
 * [PickingState.EscaneandoProduto] (§3.4.3), tanto por bateria (§8) quanto porque é isso que a
 * afirmação de privacidade da §9.2 diz em público.
 *
 * O caminho de um frame, do começo ao fim:
 *
 * ```
 * Stream.videoStream (HEVC comprimido)
 *   -> DecodificadorHevc            (thread do codec)
 *   -> recorte de 60% para NV21     (mesma thread; libera a Image em seguida — doc §4.4)
 *   -> LeitorDeCodigo / ML Kit      (thread do leitor)
 *   -> PickingActor.send(DecodificacaoConcluida)
 * ```
 *
 * Nenhuma dessas etapas roda no dispatcher do ator nem na UI (doc §4.1).
 *
 * @param actor destino do evento e, aqui, também fonte do estado que liga e desliga a câmera.
 * @param sessoes a sessão viva publicada pelo `DatSessionController`. O doc §2.3 só permite uma
 *   sessão por dispositivo, então esta é a única forma de chegar na câmera.
 * @param scope escopo em [Dispatchers.Main], pela mesma razão do `datScope`: quem é observado
 *   aqui é o SDK.
 */
class ControladorDeVisao(
    private val actor: PickingActor,
    private val sessoes: StateFlow<DeviceSession?>,
    private val ajustes: AjustesVisao,
    private val scope: CoroutineScope,
) {

  private val leitor = LeitorDeCodigo(ajustes)

  /**
   * A proteção contra falso positivo medida em bancada (design.md - "Falso positivo de
   * leitura"). Confinado na thread do leitor, que é única.
   */
  private val consenso = ConsensoDeLeitura(ajustes.confirmacoesDeLeitura)

  /**
   * `true` enquanto um recorte está no leitor. Frame que chega nesse meio-tempo é **descartado**,
   * não enfileirado: a 7 fps sobra tempo de sobra para o próximo, e uma fila de frames de câmera
   * é o caminho conhecido para estourar memória (design.md - Decisão 6).
   */
  private val analiseEmAndamento = AtomicBoolean(false)

  private var jobPrincipal: Job? = null
  private val jobsDoStream = mutableListOf<Job>()

  private var camera: Camera? = null
  private var stream: Stream? = null
  private var decodificador: DecodificadorHevc? = null

  /** Guarda para publicar no máximo um evento por escaneamento. */
  @Volatile private var jaPublicou = false

  /**
   * Solicitação da permissão de câmera **do DAT** (que redireciona para o app Meta AI), fornecida
   * pela `MainActivity` porque só uma `Activity` pode registrar o contrato. Anulada em [parar]
   * para não segurar a `Activity` viva.
   */
  private var solicitarPermissao: (suspend (Permission) -> PermissionStatus)? = null

  /**
   * Passa a observar estado e sessão. Idempotente — a `MainActivity` chama a cada volta ao
   * primeiro plano.
   */
  fun iniciar(solicitarPermissao: suspend (Permission) -> PermissionStatus) {
    this.solicitarPermissao = solicitarPermissao
    if (jobPrincipal != null) return

    jobPrincipal =
        scope.launch {
          combine(actor.state, sessoes) { estado, sessao ->
                // A câmera existe exatamente na conjunção das duas coisas. Qualquer saída de
                // EscaneandoProduto — leitura, pausa, exceção, queda de Bluetooth — e qualquer
                // troca de sessão derrubam esta referência, e o `collect` desliga.
                if (estado is PickingState.EscaneandoProduto) sessao else null
              }
              .distinctUntilChanged()
              .collect { sessao -> if (sessao != null) ligar(sessao) else desligar() }
        }
  }

  /**
   * Desliga a câmera e para de observar.
   *
   * Chamado no `onStop` da `MainActivity`: a câmera não pode ficar ligada com o app em segundo
   * plano (doc §8), diferente da sessão DAT, que é de escopo de processo (doc §2.3).
   */
  fun parar() {
    jobPrincipal?.cancel()
    jobPrincipal = null
    solicitarPermissao = null
    desligar()
  }

  // -----------------------------------------------------------------------------------
  // Ciclo de vida do stream
  // -----------------------------------------------------------------------------------

  private suspend fun ligar(sessao: DeviceSession) {
    if (camera != null) return
    if (!temPermissaoDeCamera()) {
      // Degradação graciosa, mesma postura de `RECORD_AUDIO` na fatia de áudio: não existe
      // PickingEvent de "câmera indisponível", e o fluxo continua dirigível pelo painel de dev.
      Log.w(TAG, "Sem permissão de câmera do DAT; o escaneamento segue sem stream")
      return
    }

    jaPublicou = false
    analiseEmAndamento.set(false)
    consenso.reiniciar()

    val novaCamera =
        sessao
            .addCamera(
                StreamConfiguration(
                    videoQuality = paraSdk(ajustes.qualidade),
                    frameRate = ajustes.fps,
                    // Comprimido: o formato de pixel do caminho não comprimido não é declarado
                    // por nenhuma API pública do SDK (design.md - Decisão 2).
                    compressVideo = true,
                )
            )
            .onFailure { erro, _ -> Log.e(TAG, "addCamera falhou: ${erro.description}") }
            .getOrNull() ?: return

    camera = novaCamera
    val novoStream = novaCamera.stream
    stream = novoStream

    val novoDecodificador = DecodificadorHevc(::aoFrameDecodificado)
    novoDecodificador.iniciar(ajustes.qualidade.largura, ajustes.qualidade.altura)
    decodificador = novoDecodificador

    // Assinar antes de start(), senão as primeiras transições e os primeiros frames passam
    // despercebidos — mesma ordem do sample CameraAccess e do DatSessionController.
    observarStream(novoStream, novoDecodificador)

    novoStream.start().onFailure { erro, _ ->
      Log.e(TAG, "start do stream falhou: ${erro.description}")
      desligar()
    }
    Log.i(
        TAG,
        "Stream de câmera iniciado: ${ajustes.qualidade} @ ${ajustes.fps}fps, " +
            "recorte ${ajustes.fatorRecorte}",
    )
  }

  private fun observarStream(streamObservado: Stream, decodificadorAtual: DecodificadorHevc) {
    jobsDoStream +=
        scope.launch(Dispatchers.Default) {
          // Fora da main thread: aqui se copia o buffer de cada frame, no ritmo do stream.
          streamObservado.videoStream.collect { frame ->
            aoFrameComprimido(frame, decodificadorAtual)
          }
        }
    jobsDoStream +=
        scope.launch { streamObservado.state.collect { Log.d(TAG, "StreamState = $it") } }
    jobsDoStream +=
        scope.launch {
          streamObservado.errorStream.collect { Log.e(TAG, "Erro de stream: ${it.description}") }
        }
  }

  private fun desligar() {
    if (camera == null && jobsDoStream.isEmpty()) return

    jobsDoStream.forEach { it.cancel() }
    jobsDoStream.clear()
    decodificador?.parar()
    decodificador = null
    stream = null
    // `close()` e não só `stop()`: o sample registra que sem isso o próximo addCamera falha com
    // "a capability of this type is already active", e a próxima linha da ordem não teria câmera.
    runCatching { camera?.close() }.onFailure { Log.w(TAG, "Falha ao fechar a câmera", it) }
    camera = null
    analiseEmAndamento.set(false)
    Log.i(TAG, "Stream de câmera encerrado")
  }

  // -----------------------------------------------------------------------------------
  // Caminho do frame
  // -----------------------------------------------------------------------------------

  private fun aoFrameComprimido(frame: VideoFrame, decodificadorAtual: DecodificadorHevc) {
    if (!frame.isCompressed) return
    if (jaPublicou) return

    val buffer = frame.buffer
    val posicaoOriginal = buffer.position()
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    buffer.position(posicaoOriginal)

    decodificadorAtual.enfileirar(bytes, frame.presentationTimeUs)
  }

  /**
   * Chamado na thread do decodificador, com a [Image] válida só durante a chamada.
   *
   * O recorte acontece aqui, síncrono, e é a última coisa que enxerga o quadro completo: quando
   * este método retorna, o [DecodificadorHevc] fecha a imagem e devolve o buffer ao codec
   * (doc §4.4).
   */
  private fun aoFrameDecodificado(imagem: Image) {
    if (jaPublicou) return
    // Descarta em vez de enfileirar enquanto o leitor está ocupado.
    if (!analiseEmAndamento.compareAndSet(false, true)) return

    val recorte =
        runCatching { recortar(imagem) }
            .onFailure {
              Log.e(TAG, "Falha ao recortar o frame: ${it.message}", it)
              analiseEmAndamento.set(false)
            }
            .getOrNull() ?: return

    leitor.ler(recorte) { codigo ->
      analiseEmAndamento.set(false)
      // Uma leitura isolada não basta: só publica o que aparecer em frames consecutivos.
      if (codigo != null && consenso.registrar(codigo)) publicar(codigo)
    }
  }

  private fun recortar(imagem: Image): RecorteNv21 {
    val planos = imagem.planes
    return recortarParaNv21(
        y = planos[0].comoPlano(),
        u = planos[1].comoPlano(),
        v = planos[2].comoPlano(),
        largura = imagem.width,
        altura = imagem.height,
        fatorRecorte = ajustes.fatorRecorte,
    )
  }

  private fun publicar(codigo: String) {
    // A leitura vale uma vez por escaneamento: o código continua no campo de visão por vários
    // frames depois de lido, e o estado só sai de EscaneandoProduto quando o ator processar o
    // evento — uma janela curta, mas suficiente para publicar em duplicata.
    if (jaPublicou) return
    jaPublicou = true
    Log.i(TAG, "Código lido no stream: $codigo")
    actor.send(PickingEvent.DecodificacaoConcluida(codigo))
  }

  // -----------------------------------------------------------------------------------
  // Permissão
  // -----------------------------------------------------------------------------------

  /**
   * A permissão de câmera **do óculos**, que é do DAT e não do Android — a do Android cobre a
   * câmera do celular, usada só pelo MockDeviceKit em bancada.
   *
   * A consulta não redireciona; a solicitação leva o operador ao app Meta AI, e por isso só
   * acontece quando a câmera é de fato necessária (primeira entrada em `EscaneandoProduto`), e
   * não na abertura do app.
   */
  private suspend fun temPermissaoDeCamera(): Boolean {
    val status =
        Wearables.checkPermissionStatus(Permission.CAMERA)
            .onFailure { erro, _ -> Log.e(TAG, "checkPermissionStatus falhou: ${erro.description}") }
            .getOrNull()
    if (status == PermissionStatus.Granted) return true

    val solicitar = solicitarPermissao ?: return false
    Log.i(TAG, "Permissão de câmera do DAT ainda não concedida ($status); solicitando")
    return solicitar(Permission.CAMERA) == PermissionStatus.Granted
  }

  private companion object {
    const val TAG = "ControladorDeVisao"

    fun paraSdk(qualidade: QualidadeStream): VideoQuality =
        when (qualidade) {
          QualidadeStream.ALTA -> VideoQuality.HIGH
          QualidadeStream.MEDIA -> VideoQuality.MEDIUM
          QualidadeStream.BAIXA -> VideoQuality.LOW
        }

    fun Image.Plane.comoPlano(): PlanoImagem = PlanoImagem(buffer, rowStride, pixelStride)
  }
}
