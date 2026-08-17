package com.agvtronic.pickvoice.ui.operation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agvtronic.pickvoice.audio.output.DiagnosticoSaidaAudio
import com.agvtronic.pickvoice.data.PickingRepository
import com.agvtronic.pickvoice.data.model.Ordem
import com.agvtronic.pickvoice.domain.statemachine.PickingActor
import com.agvtronic.pickvoice.vision.DiagnosticoVisao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Combina os fluxos que a tela do operador precisa e devolve um [OperationUiState] imutável.
 *
 * **Só consome.** Não chama `reduce`, não envia `PickingEvent` e não conhece `Surface`, câmera
 * ou codec: quem produz evento continua sendo voz, visão e DAT (design.md — Decisão 1). A
 * prévia é anexada pelo `MirrorViewModel`, que segue dono das chamadas ao controlador de visão.
 */
class OperationViewModel(
    actor: PickingActor,
    private val repository: PickingRepository,
    diagnosticoVisao: StateFlow<DiagnosticoVisao>,
    diagnosticoAudio: StateFlow<DiagnosticoSaidaAudio>,
    private val projetor: ProjetorDeOperacao = ProjetorDeOperacao(),
) : ViewModel() {

  private val ordemFlow = MutableStateFlow<Ordem?>(null)

  val uiState: StateFlow<OperationUiState> =
      combine(actor.state, ordemFlow, diagnosticoVisao, diagnosticoAudio) {
              estado,
              ordem,
              visao,
              audio ->
            projetor.projetar(estado, ordem, visao, audio)
          }
          .stateIn(
              scope = viewModelScope,
              started = SharingStarted.WhileSubscribed(TIMEOUT_ASSINATURA_MS),
              initialValue =
                  projetor.projetar(
                      actor.state.value,
                      null,
                      diagnosticoVisao.value,
                      diagnosticoAudio.value,
                  ),
          )

  init {
    // A ordem mockada é carregada uma vez; a seleção de ordem por voz é de outra fatia.
    viewModelScope.launch {
      val resumo = repository.ordensDisponiveis().first()
      ordemFlow.value = repository.ordem(resumo.id)
    }
  }

  private companion object {
    const val TIMEOUT_ASSINATURA_MS = 5_000L
  }
}
