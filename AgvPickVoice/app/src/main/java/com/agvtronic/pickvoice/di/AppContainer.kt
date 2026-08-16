package com.agvtronic.pickvoice.di

import android.content.Context
import com.agvtronic.pickvoice.data.PickingRepository
import com.agvtronic.pickvoice.data.mock.MockPickingRepository
import com.agvtronic.pickvoice.domain.statemachine.PickingActor
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
  // TODO(#audio-source-abstraction): expose `val fonteAudio: FonteAudio`

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
}
