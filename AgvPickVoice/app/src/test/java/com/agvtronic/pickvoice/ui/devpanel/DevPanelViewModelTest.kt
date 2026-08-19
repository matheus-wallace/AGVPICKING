package com.agvtronic.pickvoice.ui.devpanel

import com.agvtronic.pickvoice.audio.output.DiagnosticoSaidaAudio
import com.agvtronic.pickvoice.data.mock.MockPickingRepository
import com.agvtronic.pickvoice.domain.statemachine.ItemEmAndamento
import com.agvtronic.pickvoice.domain.statemachine.PickingActor
import com.agvtronic.pickvoice.domain.statemachine.PickingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Cobre a parte plumbing da task 5.3: `checkDigitEsperado` reflete `Linha.senhaEndereco` da
 * linha em andamento (design.md - Decisão 7).
 *
 * O gate por `BuildConfig.DEBUG` em si não é testável em JVM: é uma constante travada em
 * tempo de build, e `testDebugUnitTest` sempre compila com `DEBUG = true`. Confirmar que o
 * valor some em release é checagem manual do APK, não deste teste.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DevPanelViewModelTest {

  private val dispatcher = UnconfinedTestDispatcher()
  private val escoposCriados = mutableListOf<CoroutineScope>()

  private val item = ItemEmAndamento("274K5010000-408176", 0, "Rua D, prédio 118, andar B", 2)

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
  fun `checkDigitEsperado reflete a senha da linha em andamento`() = runTest {
    val viewModel = viewModelDeTeste(PickingState.AlocandoCarrinho(item, 12))

    val estado = viewModel.uiState.first { it.ordem != null }

    // "40" é a senha cadastrada para a primeira linha da ordem mockada (MockPickingRepository).
    assertEquals("40", estado.checkDigitEsperado)
  }

  @Test
  fun `checkDigitEsperado e nulo sem item em andamento`() = runTest {
    val viewModel = viewModelDeTeste(PickingState.OrdemConcluida("274K5010000-408176"))

    val estado = viewModel.uiState.first { it.ordem != null }

    assertNull(estado.checkDigitEsperado)
  }

  private fun viewModelDeTeste(estadoInicial: PickingState): DevPanelViewModel {
    val scope = CoroutineScope(dispatcher)
    escoposCriados += scope
    val actor = PickingActor(scope, estadoInicial)
    return DevPanelViewModel(
        actor = actor,
        repository = MockPickingRepository(),
        diagnosticoAudio = MutableStateFlow(DiagnosticoSaidaAudio()),
    )
  }
}
