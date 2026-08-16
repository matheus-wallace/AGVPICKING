package com.agvtronic.pickvoice.ui.devpanel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agvtronic.pickvoice.audio.output.DiagnosticoSaidaAudio
import com.agvtronic.pickvoice.data.PickingRepository
import com.agvtronic.pickvoice.data.model.Linha
import com.agvtronic.pickvoice.data.model.Ordem
import com.agvtronic.pickvoice.domain.statemachine.ItemEmAndamento
import com.agvtronic.pickvoice.domain.statemachine.MotivoExcecao
import com.agvtronic.pickvoice.domain.statemachine.PickingActor
import com.agvtronic.pickvoice.domain.statemachine.PickingEvent
import com.agvtronic.pickvoice.domain.statemachine.PickingState
import com.agvtronic.pickvoice.domain.statemachine.reduce
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Dirige o [PickingActor] por toque, no lugar da voz e da câmera que ainda não existem.
 *
 * Substituto declaradamente temporário: quando os pipelines de áudio e visão chegarem, quem
 * publica evento passa a ser eles, e a tela espelho do doc §12 toma o lugar desta. O valor
 * aqui é provar o padrão de ator num processo Android real e exercitar o fluxo do §3.2 de
 * ponta a ponta com dados de verdade do repositório.
 *
 * Cobre só o fluxo operacional. Os eventos de ciclo de vida da sessão vêm do
 * `DatSessionController`, que observa o SDK de verdade — o painel não sobe mais a sessão
 * sozinho, então os botões só ficam úteis depois que a sessão real chega em `AguardandoOrdem`.
 */
class DevPanelViewModel(
    private val actor: PickingActor,
    private val repository: PickingRepository,
    diagnosticoAudio: StateFlow<DiagnosticoSaidaAudio>,
) : ViewModel() {

  private val ordemFlow = MutableStateFlow<Ordem?>(null)

  val uiState: StateFlow<DevPanelUiState> =
      combine(actor.state, ordemFlow, diagnosticoAudio) { estado, ordem, audio ->
            DevPanelUiState(
                estado = estado,
                ordem = ordem,
                linhaEmAndamento = linhaDe(estado, ordem),
                acoes = acoesPara(estado, ordem),
                diagnosticoAudio = audio,
            )
          }
          .stateIn(
              scope = viewModelScope,
              started = SharingStarted.WhileSubscribed(TIMEOUT_ASSINATURA_MS),
              initialValue = DevPanelUiState(estado = actor.state.value),
          )

  init {
    viewModelScope.launch {
      val resumo = repository.ordensDisponiveis().first()
      ordemFlow.value = repository.ordem(resumo.id)
    }
  }

  /** Publica o evento do botão no channel do ator. Nada mais — sem transformação. */
  fun disparar(evento: PickingEvent) {
    actor.send(evento)
  }

  private fun linhaDe(estado: PickingState, ordem: Ordem?): Linha? {
    val indice = estado.itemEmAndamento?.indiceLinha ?: return null
    return ordem?.linhas?.getOrNull(indice)
  }

  /**
   * Monta os botões do estado corrente.
   *
   * [AcaoDev.aplicavel] sai de rodar o próprio [reduce] contra o estado atual: se o evento
   * não muda nada, o botão aparece apagado. É o reducer que decide, não uma cópia da tabela
   * de transições escrita na UI — que divergiria na primeira mudança do domínio.
   */
  private fun acoesPara(estado: PickingState, ordem: Ordem?): List<AcaoDev> {
    if (ordem == null) return emptyList()
    val linha = linhaDe(estado, ordem) ?: ordem.linhas.firstOrNull()

    val principais =
        listOfNotNull(
            "Confirmar ordem" to PickingEvent.OrdemConfirmada(ordem.id, ordem.linhas.size),
            "Iniciar navegação" to PickingEvent.NavegacaoIniciada(itemDaLinha(ordem, 0)),
            "Cheguei no endereço" to PickingEvent.EnderecoAlcancado,
            "Check digit correto" to PickingEvent.CheckDigitCorreto,
            "Disparar captura" to PickingEvent.CapturaDisparada,
            linha?.let { "Decodificação OK (${it.ean})" to PickingEvent.DecodificacaoConcluida(it.ean) },
            linha?.let { "Validação OK" to PickingEvent.ValidacaoOk(it.quantidade) },
            linha?.let {
              "Informar quantidade (${it.quantidade})" to PickingEvent.QuantidadeInformada(it.quantidade)
            },
            "Confirmar readback" to PickingEvent.ReadbackConfirmado,
            "Item alocado" to PickingEvent.ItemAlocado,
            "Finalizar item" to PickingEvent.ItemFinalizado(proximoItem(estado, ordem)),
            "Conferência concluída" to PickingEvent.ConferenciaConcluida,
            "Encerrar ordem" to PickingEvent.OrdemEncerrada,
        )

    val transversais =
        listOf(
            "Emergência" to PickingEvent.ComandoEmergencia,
            "Avaria" to PickingEvent.ExcecaoSolicitada(MotivoExcecao.AVARIA),
        )

    val recuperacao =
        listOf(
            "Retomar sessão" to PickingEvent.SessaoRetomada,
            "Registrar exceção" to PickingEvent.ExcecaoRegistrada,
        )

    return principais.map { it.paraAcao(estado, GrupoAcao.FLUXO_PRINCIPAL) } +
        transversais.map { it.paraAcao(estado, GrupoAcao.TRANSVERSAL) } +
        recuperacao.map { it.paraAcao(estado, GrupoAcao.RECUPERACAO) }
  }

  private fun Pair<String, PickingEvent>.paraAcao(estado: PickingState, grupo: GrupoAcao) =
      AcaoDev(
          rotulo = first,
          evento = second,
          aplicavel = reduce(estado, second) != estado,
          grupo = grupo,
      )

  /** O item da linha seguinte à do estado atual, ou `null` quando esta era a última. */
  private fun proximoItem(estado: PickingState, ordem: Ordem): ItemEmAndamento? {
    val atual = estado.itemEmAndamento?.indiceLinha ?: return null
    val proximo = atual + 1
    return if (proximo <= ordem.linhas.lastIndex) itemDaLinha(ordem, proximo) else null
  }

  private fun itemDaLinha(ordem: Ordem, indice: Int): ItemEmAndamento =
      ItemEmAndamento(
          ordemId = ordem.id,
          indiceLinha = indice,
          endereco = ordem.linhas[indice].endereco.etiqueta,
          itensRestantes = ordem.linhas.lastIndex - indice,
      )

  private companion object {
    const val TIMEOUT_ASSINATURA_MS = 5_000L
  }
}
