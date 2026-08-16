package com.agvtronic.pickvoice.dat

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.agvtronic.pickvoice.dat.mockdevice.prepararDispositivoSimulado
import com.agvtronic.pickvoice.domain.statemachine.GatilhoPausaDat
import com.agvtronic.pickvoice.domain.statemachine.PickingActor
import com.agvtronic.pickvoice.domain.statemachine.PickingEvent
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * O produtor real dos eventos de sessão do doc §3.1 — o ciclo de vida do DAT traduzido para
 * [PickingEvent].
 *
 * Substitui o bootstrap simulado que vivia no `DevPanelViewModel`: a partir daqui, sair de
 * `Ocioso` depende de um registro e de uma [DeviceSession] de verdade. Em debug o dispositivo
 * por trás é o óculos do MockDeviceKit (ver `prepararDispositivoSimulado`); em release é o
 * óculos físico. O código abaixo é idêntico nos dois casos — é o que o doc §13.3 exige da
 * manhã de 18/09.
 *
 * **Só publica evento, nunca lê estado do ator.** Toda decisão de transição continua no
 * `reduce`, e o que este controlador guarda ([fase]) é apenas o que o SDK não conta sozinho:
 * `DeviceSessionState.STARTED` significa coisas diferentes conforme o que veio antes.
 *
 * @param appContext contexto de aplicação — o controlador vive além de qualquer `Activity`.
 * @param actor destino de todos os eventos publicados.
 * @param scope escopo de processo em `Dispatchers.Main`; ver `AppContainer` para o porquê.
 */
class DatSessionController(
    private val appContext: Context,
    private val actor: PickingActor,
    private val scope: CoroutineScope,
) {

  /**
   * O que o SDK não distingue sozinho: um `STARTED` pode ser a primeira subida da sessão, a
   * volta de uma pausa, ou a reconexão depois de uma queda — e cada um publica um evento
   * diferente.
   *
   * Confinada ao [scope] de `Dispatchers.Main` (consumidor único), então não precisa de
   * sincronização.
   */
  private enum class Fase {
    /** Sessão criada, ainda sem nenhum `STARTED`. */
    PREPARANDO,

    /** Sessão viva. */
    ATIVA,

    /** `PAUSED` observado; a mesma sessão ainda pode voltar. */
    PAUSADA,

    /** `STOPPED` inesperado; aguardando o dispositivo voltar para abrir uma sessão nova. */
    PERDIDA,

    /** A sessão não subiu e já publicamos `SessaoFalhou`. Não publica mais nada por ela. */
    FALHOU,
  }

  private var iniciado = false
  private var fase = Fase.PREPARANDO

  /**
   * A sessão viva. Guardada para manter a referência forte enquanto ela existir e para ser o
   * ponto de acoplamento das capabilities que virão (câmera, na fatia de visão).
   */
  private var sessao: DeviceSession? = null

  /** Collectors da sessão corrente, cancelados em bloco quando ela é substituída. */
  private val jobsDaSessao = mutableListOf<Job>()

  /**
   * Ponto de entrada único, chamado pela `MainActivity` depois de resolver as permissões
   * Android.
   *
   * Idempotente de propósito: `onStart` roda de novo a cada volta para o primeiro plano e a
   * cada recriação da `Activity`, e reiniciar o registro ali derrubaria uma sessão viva.
   *
   * @param activity necessária só para `Wearables.startRegistration`, que abre o fluxo do
   *   app Meta AI. Não é retida em lugar nenhum.
   */
  fun iniciar(activity: Activity) {
    if (iniciado) return
    iniciado = true

    // No-op em release. Em debug, deixa um óculos simulado pareado e vestido antes de
    // qualquer coisa — sem ele o AutoDeviceSelector não teria o que selecionar.
    prepararDispositivoSimulado(appContext)

    actor.send(PickingEvent.RegistroIniciado)

    if (!temPermissaoBluetooth()) {
      // Não existe estado "sem permissão" na máquina (design.md - Decisão 6): chamar
      // startRegistration assim falharia de forma opaca, então falhamos explicitamente.
      Log.e(TAG, "BLUETOOTH_CONNECT negada; registro não será iniciado")
      actor.send(PickingEvent.RegistroFalhou(DETALHE_SEM_PERMISSAO))
      return
    }

    observarRegistro(activity)
  }

  // Context.checkSelfPermission direto, sem ContextCompat: minSdk é 31 e o app não depende de
  // androidx.core explicitamente — não vale puxar a dependência por uma chamada só.
  private fun temPermissaoBluetooth(): Boolean =
      appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
          PackageManager.PERMISSION_GRANTED

  // -----------------------------------------------------------------------------------
  // Registro
  // -----------------------------------------------------------------------------------

  private fun observarRegistro(activity: Activity) {
    scope.launch {
      var registroPublicado = false
      var registroSolicitado = false

      Wearables.registrationState.collect { estado ->
        Log.d(TAG, "registrationState = $estado")
        when (estado) {
          RegistrationState.REGISTERED ->
              // Só na primeira vez: o flow reemite em toda recoleta, e um segundo
              // RegistroConcluido no meio de uma ordem em andamento não significa nada.
              if (!registroPublicado) {
                registroPublicado = true
                actor.send(PickingEvent.RegistroConcluido)
                // Em corrotina separada: abrirSessao espera até TIMEOUT_SESSAO_MS, e segurar
                // este collector por 20s deixaria de observar o registro nesse meio-tempo.
                scope.launch { abrirSessao(primeira = true) }
              }

          // AVAILABLE = dá para registrar, mas ainda não registrou. Em debug o MockDeviceKit
          // já entrega REGISTERED e este ramo não roda.
          RegistrationState.AVAILABLE ->
              if (!registroSolicitado) {
                registroSolicitado = true
                Wearables.startRegistration(activity)
              }

          RegistrationState.UNAVAILABLE,
          RegistrationState.REGISTERING,
          RegistrationState.UNREGISTERING -> Unit
        }
      }
    }

    scope.launch {
      // Canal separado do state flow: o registro que falha não vira um RegistrationState,
      // ele emite aqui.
      Wearables.registrationErrorStream.collect { erro ->
        Log.e(TAG, "Erro de registro: ${erro.description}")
        actor.send(PickingEvent.RegistroFalhou(erro.description))
      }
    }
  }

  // -----------------------------------------------------------------------------------
  // Sessão
  // -----------------------------------------------------------------------------------

  /**
   * Cria, observa e inicia uma [DeviceSession] nova.
   *
   * @param primeira `true` na subida inicial (publica `SessaoPreparada` ao ficar pronta),
   *   `false` quando é uma reconexão (publica `ConexaoBluetoothRestabelecida`).
   */
  private suspend fun abrirSessao(primeira: Boolean) {
    encerrarObservacaoDaSessao()
    fase = if (primeira) Fase.PREPARANDO else Fase.PERDIDA

    val nova =
        Wearables.createSession(AutoDeviceSelector())
            .onFailure { erro, _ ->
              Log.e(TAG, "createSession falhou: ${erro.description}")
              fase = Fase.FALHOU
              actor.send(PickingEvent.SessaoFalhou(erro.description))
            }
            .getOrNull() ?: return

    sessao = nova
    // Assinar antes de start(), senão as primeiras transições passam despercebidas —
    // mesma ordem do sample CameraAccess.
    observarSessao(nova)
    nova.start()

    val subiu =
        withTimeoutOrNull(TIMEOUT_SESSAO_MS) {
          nova.state.first { it == DeviceSessionState.STARTED }
        } != null

    if (!subiu && fase != Fase.ATIVA) {
      Log.e(TAG, "Sessão não atingiu STARTED em ${TIMEOUT_SESSAO_MS}ms")
      fase = Fase.FALHOU
      actor.send(PickingEvent.SessaoFalhou(DETALHE_TIMEOUT))
    }
  }

  private fun observarSessao(sessaoObservada: DeviceSession) {
    jobsDaSessao +=
        scope.launch {
          sessaoObservada.state.collect { estado ->
            Log.d(TAG, "DeviceSessionState = $estado (fase=$fase)")
            when (estado) {
              DeviceSessionState.STARTED -> aoIniciar()
              DeviceSessionState.PAUSED -> aoPausar()
              DeviceSessionState.STOPPED -> aoParar()
              DeviceSessionState.IDLE,
              DeviceSessionState.STARTING,
              DeviceSessionState.STOPPING -> Unit
            }
          }
        }

    jobsDaSessao +=
        scope.launch {
          sessaoObservada.errors.collect { erro ->
            Log.e(TAG, "Erro de sessão: ${erro.description} (fase=$fase)")
            // Só enquanto a sessão ainda não subiu. Depois que ela está viva, uma falha que
            // a derruba chega também como transição para STOPPED, e é ela quem manda o fluxo
            // para o caminho de retomada.
            //
            // Verificado na bancada: desparear o dispositivo emite SESSION_ENDED_BY_DEVICE
            // *e* UNEXPECTED_ERROR no mesmo instante do STOPPED. Filtrar por tipo de erro
            // não separa os dois casos — UNEXPECTED_ERROR é genérico demais —, então quem
            // separa é a fase.
            if (fase == Fase.PREPARANDO) {
              actor.send(PickingEvent.SessaoFalhou(erro.description))
            }
          }
        }
  }

  private fun aoIniciar() {
    when (fase) {
      Fase.PREPARANDO -> actor.send(PickingEvent.SessaoPreparada)
      Fase.PAUSADA -> actor.send(PickingEvent.SessaoRetomada)
      Fase.PERDIDA -> actor.send(PickingEvent.ConexaoBluetoothRestabelecida)
      // Já estávamos ativos (reemissão do StateFlow), ou a sessão já foi dada como falha.
      Fase.ATIVA,
      Fase.FALHOU -> return
    }
    fase = Fase.ATIVA
  }

  private fun aoPausar() {
    if (fase != Fase.ATIVA) return
    fase = Fase.PAUSADA
    // O SDK não diz se foram as hastes, a remoção ou o toque (doc §2.3) — ver o KDoc de
    // GatilhoPausaDat.NAO_ESPECIFICADO.
    actor.send(PickingEvent.PausaDat(GatilhoPausaDat.NAO_ESPECIFICADO))
  }

  private fun aoParar() {
    if (fase != Fase.ATIVA && fase != Fase.PAUSADA) return
    fase = Fase.PERDIDA
    actor.send(PickingEvent.ConexaoBluetoothPerdida)

    scope.launch {
      // STOPPED é terminal: a sessão morta não volta, tem que nascer outra
      // (design.md - Decisão 4).
      Wearables.devices.first { it.isNotEmpty() }
      abrirSessao(primeira = false)
    }
  }

  private fun encerrarObservacaoDaSessao() {
    jobsDaSessao.forEach { it.cancel() }
    jobsDaSessao.clear()
    sessao = null
  }

  private companion object {
    const val TAG = "DatSessionController"

    /** Folga generosa: o pareamento Bluetooth real é lento e falhar cedo demais é pior. */
    const val TIMEOUT_SESSAO_MS = 20_000L

    const val DETALHE_SEM_PERMISSAO = "Permissão BLUETOOTH_CONNECT não concedida"
    const val DETALHE_TIMEOUT = "Sessão não ficou pronta dentro do tempo limite"
  }
}
