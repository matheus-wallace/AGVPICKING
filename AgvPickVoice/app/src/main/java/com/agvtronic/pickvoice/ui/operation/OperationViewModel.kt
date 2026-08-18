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
 * As exceções são [registrarOcorrencia] e [confirmarOrdem], os dois estados em que a voz não
 * tira o operador do lugar — ver o porquê em cada uma.
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
   * deliberada, não uma volta ao painel de botões: `TratandoExcecao` é o estado em que o
   * operador ficou preso em bancada, e esta tela não tem botão de avanço em nenhum outro lugar.
   * Continua existindo depois de a gramática do estado fechar em "próximo"
   * (add-voice-recognition-reliability - Decisão 2), porque é por aqui que o detalhe da
   * ocorrência entra — a voz resolve só o avanço. Publica o mesmo evento que "próximo".
   *
   * O reducer ignora o evento em qualquer outro estado, então um toque atrasado não tem efeito.
   */
  fun registrarOcorrencia() {
    actor.send(PickingEvent.ExcecaoRegistrada)
  }

  /**
   * Confirma a ordem mockada já carregada e sai de `AguardandoOrdem`.
   *
   * `AguardandoOrdem` é surdo por decisão de projeto — a escolha da ordem é por toque, não por
   * voz (design.md — Decisão 4), e `SeletorDeEscuta` nem abre escuta ali. Até aqui a confirmação
   * só existia no painel de desenvolvimento, o que deixava o operador preso na tela principal.
   *
   * O reducer só aceita `OrdemConfirmada` a partir de `AguardandoOrdem`, então um toque repetido
   * ou atrasado não tem efeito. Antes de a ordem carregar não há o que confirmar e o toque é
   * simplesmente ignorado.
   */
  fun confirmarOrdem() {
    ordemFlow.value?.let { actor.send(PickingEvent.OrdemConfirmada(it.id, it.linhas.size)) }
  }

  private companion object {
    const val TIMEOUT_ASSINATURA_MS = 5_000L
  }
}
