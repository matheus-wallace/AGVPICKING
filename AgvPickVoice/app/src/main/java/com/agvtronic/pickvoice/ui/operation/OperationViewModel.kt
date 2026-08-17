package com.agvtronic.pickvoice.ui.operation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agvtronic.pickvoice.audio.output.DiagnosticoSaidaAudio
import com.agvtronic.pickvoice.data.PickingRepository
import com.agvtronic.pickvoice.data.model.Ordem
import com.agvtronic.pickvoice.domain.statemachine.PickingActor
import com.agvtronic.pickvoice.domain.statemachine.PickingEvent
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
 * **Praticamente só consome.** Não chama `reduce`, não conhece `Surface`, câmera ou codec, e o
 * fluxo principal continua sendo avançado por voz, visão e DAT (design.md — Decisão 1). A prévia
 * é anexada pelo `MirrorViewModel`, que segue dono das chamadas ao controlador de visão.
 *
 * A única exceção é [registrarOcorrencia] — ver o porquê lá.
 */
class OperationViewModel(
    private val actor: PickingActor,
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

  /**
   * Registra a ocorrência em curso e sai de `TratandoExcecao`.
   *
   * É o único `PickingEvent` que a tela do operador publica, e é uma saída de emergência
   * deliberada, não uma volta ao painel de botões: em `TratandoExcecao` o vocabulário é aberto e
   * a saída por voz exige um relato inteiro reconhecido pelo ASR. Quando isso não acontece — e em
   * bancada não aconteceu — o operador fica sem nenhuma forma de seguir, porque esta tela não tem
   * botão de avanço. O mesmo evento que o relato falado produz.
   *
   * O reducer ignora o evento em qualquer outro estado, então um toque atrasado não tem efeito.
   */
  fun registrarOcorrencia() {
    actor.send(PickingEvent.ExcecaoRegistrada)
  }

  private companion object {
    const val TIMEOUT_ASSINATURA_MS = 5_000L
  }
}
