package com.agvtronic.pickvoice.ui.devpanel

import com.agvtronic.pickvoice.audio.output.DiagnosticoSaidaAudio
import com.agvtronic.pickvoice.data.model.Linha
import com.agvtronic.pickvoice.data.model.Ordem
import com.agvtronic.pickvoice.domain.statemachine.PickingEvent
import com.agvtronic.pickvoice.domain.statemachine.PickingState

/**
 * O que a tela do painel de dev mostra: o estado corrente do ator e, quando o estado
 * referencia um item em andamento, os dados reais daquela linha vindos do repositório.
 *
 * [linhaEmAndamento] é resolvida no `ViewModel` a partir do `indiceLinha` do estado, nunca
 * codificada na tela — o painel tem que exibir o mesmo dado que a voz vai falar depois.
 */
data class DevPanelUiState(
    val estado: PickingState = PickingState.Ocioso,
    val ordem: Ordem? = null,
    val linhaEmAndamento: Linha? = null,
    val acoes: List<AcaoDev> = emptyList(),
    val diagnosticoAudio: DiagnosticoSaidaAudio = DiagnosticoSaidaAudio(),
) {
  /** Nome curto do estado, o que o desenvolvedor lê de relance. */
  val nomeEstado: String
    get() = estado::class.simpleName ?: "?"

  /** `toString()` do estado, com os payloads — o detalhe que explica *por que* está ali. */
  val detalheEstado: String
    get() = estado.toString()

  val carregando: Boolean
    get() = ordem == null
}

/**
 * Um botão do painel: um rótulo e **exatamente um** [PickingEvent], sem transformação.
 *
 * [aplicavel] vem de rodar o reducer puro contra o estado atual e comparar o resultado — se o
 * evento não muda o estado, ele não se aplica agora. Não há tabela de habilitação duplicada
 * na UI: a única fonte de verdade continua sendo o `reduce` do domínio.
 */
data class AcaoDev(
    val rotulo: String,
    val evento: PickingEvent,
    val aplicavel: Boolean,
    val grupo: GrupoAcao,
)

/** Só para a tela agrupar os botões; não tem significado de domínio. */
enum class GrupoAcao(val titulo: String) {
  /** O caminho feliz de um item, na ordem do diagrama do doc §3.2. */
  FLUXO_PRINCIPAL("Fluxo principal"),

  /** Transversais do doc §3.3, válidas a partir de qualquer estado operacional. */
  TRANSVERSAL("Transversais"),

  /** A volta de uma pausa ou exceção — sem elas o painel vira uso único. */
  RECUPERACAO("Recuperação"),
}
