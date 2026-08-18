package com.agvtronic.pickvoice.ui.operation

import com.agvtronic.pickvoice.audio.output.DiagnosticoSaidaAudio
import com.agvtronic.pickvoice.data.PickingRepository
import com.agvtronic.pickvoice.data.mock.MockPickingRepository
import com.agvtronic.pickvoice.data.model.ResumoOrdem
import com.agvtronic.pickvoice.domain.statemachine.ItemEmAndamento
import com.agvtronic.pickvoice.domain.statemachine.MotivoExcecao
import com.agvtronic.pickvoice.domain.statemachine.PickingActor
import com.agvtronic.pickvoice.domain.statemachine.PickingState
import com.agvtronic.pickvoice.vision.DiagnosticoVisao
import com.agvtronic.pickvoice.vision.QualidadeStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Cobre as duas ações por toque da tela do operador — as únicas transições que ela publica.
 *
 * O ator aqui é o real, com o [reduce][com.agvtronic.pickvoice.domain.statemachine.reduce]
 * de produção: o que se afirma é que o toque leva o fluxo ao estado seguinte de verdade, não
 * que um duplo recebeu uma chamada.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OperationViewModelTest {

  private val dispatcher = UnconfinedTestDispatcher()
  private val escoposCriados = mutableListOf<CoroutineScope>()

  private val item = ItemEmAndamento("274K5010000-408176", 0, "Rua D, prédio 118, andar B", 2)

  private val visao =
      DiagnosticoVisao(qualidade = QualidadeStream.MEDIA, fpsConfigurado = 7, fatorRecorte = 0.6f)

  @Before
  fun montarMainDispatcher() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun desmontar() {
    escoposCriados.forEach { it.cancel() }
    escoposCriados.clear()
    Dispatchers.resetMain()
  }

  @Test
  fun `confirmarOrdem carrega a ordem mockada com o total de linhas certo`() = runTest {
    val repositorio = MockPickingRepository()
    val primeira = repositorio.ordem(repositorio.ordensDisponiveis().first().id)
    val actor = actorDeTeste(PickingState.AguardandoOrdem)
    val viewModel = viewModelDeTeste(actor, repositorio)
    // A ordem é carregada no `init`; sem ela não há o que confirmar.
    viewModel.uiState.first { it.podeConfirmarOrdem && it.ordem != null }

    viewModel.confirmarOrdem()
    advanceUntilIdle()

    val estado = actor.state.value
    assertTrue("estado ficou em $estado", estado is PickingState.OrdemCarregada)
    estado as PickingState.OrdemCarregada
    assertEquals(primeira.id, estado.ordemId)
    assertEquals(primeira.linhas.size, estado.totalLinhas)
  }

  @Test
  fun `confirmarOrdem nao faz nada antes de a ordem carregar`() = runTest {
    val actor = actorDeTeste(PickingState.AguardandoOrdem)
    val viewModel = viewModelDeTeste(actor, RepositorioQueNuncaResponde())

    viewModel.confirmarOrdem()
    advanceUntilIdle()

    // Sem ordem em mãos não há `ordemId` nem total de linhas para publicar: melhor um toque
    // sem efeito do que um evento com dado inventado.
    assertEquals(PickingState.AguardandoOrdem, actor.state.value)
  }

  @Test
  fun `confirmarOrdem repetido nao mexe no fluxo ja em andamento`() = runTest {
    // O reducer só aceita `OrdemConfirmada` a partir de `AguardandoOrdem`, então o toque
    // atrasado que chega depois do fluxo já ter seguido é descartado por construção.
    val actor = actorDeTeste(PickingState.EscaneandoProduto(item))
    val viewModel = viewModelDeTeste(actor, MockPickingRepository())
    viewModel.uiState.first { it.ordem != null }

    viewModel.confirmarOrdem()
    advanceUntilIdle()

    assertEquals(PickingState.EscaneandoProduto(item), actor.state.value)
  }

  @Test
  fun `registrarOcorrencia sai de TratandoExcecao`() = runTest {
    val actor = actorDeTeste(PickingState.TratandoExcecao(MotivoExcecao.AVARIA, item))
    val viewModel = viewModelDeTeste(actor, MockPickingRepository())

    viewModel.registrarOcorrencia()
    advanceUntilIdle()

    assertTrue(
        "estado ficou em ${actor.state.value}",
        actor.state.value !is PickingState.TratandoExcecao,
    )
  }

  private fun actorDeTeste(estadoInicial: PickingState): PickingActor {
    val scope = CoroutineScope(dispatcher)
    escoposCriados += scope
    return PickingActor(scope, estadoInicial)
  }

  private fun viewModelDeTeste(actor: PickingActor, repository: PickingRepository) =
      OperationViewModel(
          actor = actor,
          repository = repository,
          diagnosticoVisao = MutableStateFlow(visao),
          diagnosticoAudio = MutableStateFlow(DiagnosticoSaidaAudio()),
      )

  /**
   * Repositório cuja consulta de ordens nunca responde, para segurar o `init` do ViewModel na
   * janela em que `ordemFlow` ainda é `null`.
   *
   * `by base` delega todo o resto da interface ao mock real — só o método que interessa ao
   * teste é sobrescrito, e nenhum stub inútil precisa ser escrito à mão.
   */
  private class RepositorioQueNuncaResponde(
      private val base: PickingRepository = MockPickingRepository(),
  ) : PickingRepository by base {
    override suspend fun ordensDisponiveis(): List<ResumoOrdem> = awaitCancellation()
  }
}
