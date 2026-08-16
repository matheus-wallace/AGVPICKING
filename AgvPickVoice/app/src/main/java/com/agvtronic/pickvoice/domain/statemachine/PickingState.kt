package com.agvtronic.pickvoice.domain.statemachine

/**
 * Estados do fluxo de picking — tabela do doc §3.1.
 *
 * `sealed interface`, não enum nem `sealed class`: cada estado carrega o payload que
 * o seu próprio momento do fluxo exige (o [Erro] precisa de uma causa, o
 * [ReadbackQuantidade] precisa do valor lido de volta) sem forçar um construtor base
 * artificial entre variantes que não compartilham campo nenhum.
 *
 * Kotlin puro: nenhum import de Android, nenhuma corrotina, nenhum I/O. Toda transição
 * daqui passa por [reduce] e é aplicada por um consumidor único (doc §4.3).
 */
sealed interface PickingState {

  /**
   * Item de ordem em curso neste estado, ou `null` quando nenhum item está sendo
   * trabalhado (estados de sessão e de seleção de ordem).
   *
   * É o que precisa sobreviver a uma queda de Bluetooth para permitir retomada no mesmo
   * item (doc §3.3).
   */
  val itemEmAndamento: ItemEmAndamento?
    get() = null

  /**
   * `true` quando a sessão está viva e o operador está efetivamente operando — ou seja,
   * quando as transições transversais do doc §3.3 se aplicam.
   *
   * Falso nos estados de pré-sessão ([Ocioso], [Registrando], [PreparandoSessao]) e nos
   * estados já terminais de interrupção ([SessaoPausada], [Erro]), que não podem ser
   * pausados nem interrompidos de novo.
   */
  val ehOperacional: Boolean
    get() = true

  /** Sem sessão DAT, sem óculos pareado. Ponto de partida do app. */
  data object Ocioso : PickingState {
    override val ehOperacional: Boolean get() = false
  }

  /** Registro/pareamento do óculos em curso (`Wearables.startRegistration`). */
  data object Registrando : PickingState {
    override val ehOperacional: Boolean get() = false
  }

  /** Sessão DAT criada, modelos carregados, earcon de pronto na fila. */
  data object PreparandoSessao : PickingState {
    override val ehOperacional: Boolean get() = false
  }

  /** Sessão viva, aguardando o operador escolher uma ordem de separação. */
  data object AguardandoOrdem : PickingState

  /** Ordem escolhida e resumida por voz, ainda sem navegação iniciada. */
  data class OrdemCarregada(
      val ordemId: String,
      val totalLinhas: Int,
  ) : PickingState

  /** Guiando o operador até o endereço da linha atual, turn-by-turn. */
  data class NavegandoParaEndereco(
      override val itemEmAndamento: ItemEmAndamento,
  ) : PickingState

  /**
   * Operador declarou chegada; sistema pede os dois dígitos de confirmação.
   *
   * [tipo] distingue os dois usos do mesmo estado: confirmar a posição na estante
   * (doc §7.1) e o fallback final da cascata de visão, que confirma o produto pelos dois
   * últimos dígitos do lote impresso (doc §7.2, passo 7 da cascata em §6.3).
   */
  data class AguardandoCheckDigit(
      override val itemEmAndamento: ItemEmAndamento,
      val tipo: TipoCheckDigit = TipoCheckDigit.POSICAO,
  ) : PickingState

  /** Único estado, junto de [ConferenciaFinal], em que a câmera liga (doc §3.4.3). */
  data class EscaneandoProduto(
      override val itemEmAndamento: ItemEmAndamento,
  ) : PickingState

  /** Cascata de decodificação rodando sobre o recorte da ROI (doc §6.3). */
  data class DecodificandoProduto(
      override val itemEmAndamento: ItemEmAndamento,
  ) : PickingState

  /** Cascata local falhou; verificação por VLM em curso — único passo de rede (§6.4). */
  data class VerificacaoAssistida(
      override val itemEmAndamento: ItemEmAndamento,
  ) : PickingState

  /** Comparando o que foi lido contra o que a ordem mockada esperava. */
  data class ValidandoContraDados(
      override val itemEmAndamento: ItemEmAndamento,
      val codigoLido: String,
  ) : PickingState

  /** "colete N unidades" — gramática e endpoint de dígitos (doc §3.1). */
  data class ConfirmandoQuantidade(
      override val itemEmAndamento: ItemEmAndamento,
      val quantidadeEsperada: Int,
  ) : PickingState

  /**
   * Readback dígito a dígito do valor entendido, antes de qualquer registro —
   * o invariante "nada é registrado sem readback confirmado" do doc §3.4.2.
   */
  data class ReadbackQuantidade(
      override val itemEmAndamento: ItemEmAndamento,
      val quantidadeInformada: Int,
  ) : PickingState

  /** Instruindo o compartimento do carrinho e o progresso da ordem. */
  data class AlocandoCarrinho(
      override val itemEmAndamento: ItemEmAndamento,
      val quantidadeColetada: Int,
  ) : PickingState

  /** Linha coletada e registrada; earcon de sucesso. */
  data class ItemConcluido(
      override val itemEmAndamento: ItemEmAndamento,
  ) : PickingState

  /**
   * Único estado com gramática livre (doc §3.1). [itemEmAndamento] é `null` quando a
   * exceção é da ordem inteira e não de uma linha específica.
   */
  data class TratandoExcecao(
      val motivo: MotivoExcecao,
      override val itemEmAndamento: ItemEmAndamento?,
  ) : PickingState

  /** Conferência de fechamento da ordem — segundo e último estado com câmera ligada. */
  data class ConferenciaFinal(
      val ordemId: String,
  ) : PickingState

  /** Ordem fechada, resumo falado. */
  data class OrdemConcluida(
      val ordemId: String,
  ) : PickingState

  /**
   * Sessão interrompida por comando de voz, emergência ou evento de lifecycle do DAT.
   *
   * Guarda [estadoAnterior] inteiro para que a retomada volte exatamente ao ponto em que
   * o operador parou, sem perder o item em curso.
   */
  data class SessaoPausada(
      val estadoAnterior: PickingState,
      val motivo: MotivoPausa,
  ) : PickingState {
    override val itemEmAndamento: ItemEmAndamento? get() = estadoAnterior.itemEmAndamento
    override val ehOperacional: Boolean get() = false
  }

  /**
   * Falha que tirou o sistema do fluxo — tipicamente perda de Bluetooth (doc §3.3).
   *
   * [estadoAnterior] preserva o item em andamento para retomada no mesmo ponto assim que
   * a conexão voltar; é `null` quando a falha aconteceu antes de qualquer operação.
   */
  data class Erro(
      val causa: CausaErro,
      val estadoAnterior: PickingState? = null,
      val detalhe: String? = null,
  ) : PickingState {
    override val itemEmAndamento: ItemEmAndamento? get() = estadoAnterior?.itemEmAndamento
    override val ehOperacional: Boolean get() = false
  }
}

/**
 * Identificação do item de ordem em curso.
 *
 * Só identificadores e o rótulo do endereço já renderizado: a máquina de estados é Kotlin
 * puro e não conhece o repositório. Quem precisa dos detalhes da linha (GTIN, lote,
 * validade) resolve pelo par [ordemId] + [indiceLinha] no `PickingRepository`.
 */
data class ItemEmAndamento(
    val ordemId: String,
    val indiceLinha: Int,
    /** Etiqueta legível da posição, ex.: `R04-P12-N03-A05`. */
    val endereco: String,
    /** Linhas que ainda restam depois desta. Zero significa que esta é a última. */
    val itensRestantes: Int,
)

/** Qual dos dois check digits do doc §7 está sendo pedido. */
enum class TipoCheckDigit {
  /** Dois dígitos arbitrários da etiqueta da posição (§7.1). */
  POSICAO,

  /** Dois últimos dígitos do lote impresso na embalagem (§7.2). */
  PRODUTO,
}

/** Gatilhos de exceção do doc §3.3. */
enum class MotivoExcecao {
  AVARIA,
  RUPTURA,
  DIVERGENCIA,
}

/** Por que a sessão foi pausada (doc §3.3). */
enum class MotivoPausa {
  /** Comando de voz "parar". */
  COMANDO_PARAR,

  /** Comando de voz "emergência". */
  EMERGENCIA,

  /** Evento de lifecycle do DAT: hastes fechadas, óculos removido ou toque. */
  LIFECYCLE_DAT,
}

/** Causas de [PickingState.Erro]. */
enum class CausaErro {
  /** Perda de conexão Bluetooth com o óculos (doc §3.3). */
  BLUETOOTH_DESCONECTADO,

  /** Falha no registro/pareamento do dispositivo. */
  FALHA_REGISTRO,

  /** Falha ao criar ou manter a sessão DAT. */
  FALHA_SESSAO,
}
