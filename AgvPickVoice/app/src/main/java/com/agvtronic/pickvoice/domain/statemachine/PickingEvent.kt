package com.agvtronic.pickvoice.domain.statemachine

/**
 * Tudo que pode acontecer com o fluxo de picking — fluxo principal do doc §3.2 e
 * transições transversais do §3.3.
 *
 * Um `Channel` único recebe todos estes eventos (VAD, ASR final, frame de câmera,
 * resultado de decode, lifecycle do DAT, painel de dev) e uma corrotina única os processa
 * sequencialmente (doc §4.3). Ninguém escreve estado diretamente.
 *
 * `sealed interface` pelo mesmo motivo de [PickingState]: [QuantidadeInformada] carrega o
 * número entendido, [CheckDigitCorreto] não carrega nada, e nenhum construtor base
 * artificial precisa existir para acomodar as duas formas.
 */
sealed interface PickingEvent {

  // ---------------------------------------------------------------------------------
  // Ciclo de vida da sessão — os estados de pré-operação da tabela do doc §3.1.
  // ---------------------------------------------------------------------------------

  /** Operador iniciou o pareamento do óculos. */
  data object RegistroIniciado : PickingEvent

  /** Óculos pareado com sucesso. */
  data object RegistroConcluido : PickingEvent

  /** Pareamento falhou. */
  data class RegistroFalhou(val detalhe: String? = null) : PickingEvent

  /** Sessão DAT criada e modelos carregados; sistema pronto para receber comando. */
  data object SessaoPreparada : PickingEvent

  /** Sessão DAT não pôde ser criada ou mantida. */
  data class SessaoFalhou(val detalhe: String? = null) : PickingEvent

  // ---------------------------------------------------------------------------------
  // Fluxo principal — diagrama do doc §3.2.
  // ---------------------------------------------------------------------------------

  /** Operador escolheu e confirmou a ordem de separação. */
  data class OrdemConfirmada(
      val ordemId: String,
      val totalLinhas: Int,
  ) : PickingEvent

  /** Resumo da ordem falado; começa a guiar até a primeira linha. */
  data class NavegacaoIniciada(val item: ItemEmAndamento) : PickingEvent

  /** Operador declarou chegada no endereço; sistema pede o check digit da posição. */
  data object EnderecoAlcancado : PickingEvent

  /** Dois dígitos falados batem com o valor armazenado (validação exata, §7.1). */
  data object CheckDigitCorreto : PickingEvent

  /**
   * Dois dígitos falados divergem. Nunca revela o valor correto: o sistema apenas repete
   * o endereço (doc §7.1).
   */
  data object CheckDigitIncorreto : PickingEvent

  /** Gatilho de captura disparou (doc §6.2); a cascata de decodificação começa. */
  data object CapturaDisparada : PickingEvent

  /** Algum passo local da cascata leu o código (doc §6.3, passos 1–5). */
  data class DecodificacaoConcluida(val codigoLido: String) : PickingEvent

  /** Cascata local esgotada sem leitura; cai para verificação assistida (§6.4). */
  data object DecodificacaoFalhou : PickingEvent

  /** VLM confirmou os valores esperados (doc §6.4). */
  data class VerificacaoAssistidaConcluida(val codigoLido: String) : PickingEvent

  /**
   * Sem rede para o passo de VLM. Degradação obrigatória do doc §6.4: cai para o check
   * digit do produto por voz (§7.2).
   */
  data object VerificacaoAssistidaIndisponivel : PickingEvent

  /** O que foi lido bate com o que a ordem esperava. */
  data class ValidacaoOk(val quantidadeEsperada: Int) : PickingEvent

  /** O que foi lido diverge da ordem; vira exceção (doc §3.2). */
  data class ValidacaoDivergente(
      val motivo: MotivoExcecao = MotivoExcecao.DIVERGENCIA,
  ) : PickingEvent

  /** Quantidade entendida pelo ASR, ainda não confirmada por readback. */
  data class QuantidadeInformada(val quantidade: Int) : PickingEvent

  /** Operador confirmou o readback; agora sim o valor pode ser registrado (§3.4.2). */
  data object ReadbackConfirmado : PickingEvent

  /** Operador disse "corrigir" durante o readback. */
  data object ReadbackCorrecaoSolicitada : PickingEvent

  /** Itens depositados no compartimento indicado do carrinho. */
  data object ItemAlocado : PickingEvent

  /**
   * Linha registrada. [proximoItem] é o próximo item a coletar, ou `null` quando a ordem
   * acabou — quem produz o evento é o único que conhece o repositório e sabe qual é a
   * próxima linha.
   */
  data class ItemFinalizado(val proximoItem: ItemEmAndamento? = null) : PickingEvent

  /** Conferência final fechada sem divergência. */
  data object ConferenciaConcluida : PickingEvent

  /** Relato de exceção registrado; volta para o fluxo. */
  data object ExcecaoRegistrada : PickingEvent

  /** Operador encerrou a ordem concluída e voltou para a lista. */
  data object OrdemEncerrada : PickingEvent

  // ---------------------------------------------------------------------------------
  // Transversais — doc §3.3, válidos a partir de qualquer estado operacional.
  // ---------------------------------------------------------------------------------

  /** Comando de voz "parar". */
  data object ComandoParar : PickingEvent

  /** Comando de voz "emergência". */
  data object ComandoEmergencia : PickingEvent

  /** Comando de voz "repetir": repete a última fala sem mudar de estado (doc §3.3). */
  data object ComandoRepetir : PickingEvent

  /** Comando de voz "avaria" / "ruptura" / "divergência". */
  data class ExcecaoSolicitada(val motivo: MotivoExcecao) : PickingEvent

  /** Evento de lifecycle do DAT: hastes fechadas, óculos removido ou toque. */
  data class PausaDat(val gatilho: GatilhoPausaDat) : PickingEvent

  /** Conexão Bluetooth com o óculos caiu. */
  data object ConexaoBluetoothPerdida : PickingEvent

  // ---------------------------------------------------------------------------------
  // Recuperação — a contrapartida das transversais acima.
  // ---------------------------------------------------------------------------------

  /** Operador retomou a sessão pausada; volta ao estado exato de antes da pausa. */
  data object SessaoRetomada : PickingEvent

  /** Bluetooth voltou; retoma no mesmo item que estava em andamento (doc §3.3). */
  data object ConexaoBluetoothRestabelecida : PickingEvent
}

/** Qual evento de lifecycle do DAT causou a pausa (doc §3.3). */
enum class GatilhoPausaDat {
  HASTES_FECHADAS,
  OCULOS_REMOVIDO,
  TOQUE,
}
