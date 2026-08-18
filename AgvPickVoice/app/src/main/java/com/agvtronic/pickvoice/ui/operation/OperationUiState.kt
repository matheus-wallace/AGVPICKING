package com.agvtronic.pickvoice.ui.operation

import com.agvtronic.pickvoice.audio.output.EstadoSaidaAudio

/**
 * Qual das validações do WMS ocupa o cartão central da tela operacional.
 *
 * Não é destino de navegação: a tela é uma só e apenas troca o conteúdo do cartão conforme o
 * `PickingState` avança (design.md — Estrutura).
 */
enum class EtapaOperacao {
  /** `NavegandoParaEndereco` e `AguardandoCheckDigit`. */
  ENDERECO,

  /** `EscaneandoProduto`, `DecodificandoProduto`, `VerificacaoAssistida`, `ValidandoContraDados`. */
  PRODUTO,

  /** `ConfirmandoQuantidade`, `ReadbackQuantidade`, `AlocandoCarrinho`, `ItemConcluido`. */
  QUANTIDADE,

  /** Sessão, pausa, erro, exceção e ordem concluída — mesma estrutura, sem quarta tela. */
  MENSAGEM,
}

/**
 * O que a tela do operador mostra, já resolvido: nenhuma decisão de estado sobra para a UI.
 *
 * Só valores pequenos e imutáveis. Nada aqui carrega `Surface`, `Image`, buffer ou objeto do
 * SDK, e nada aqui expõe dado protegido: senha/check digit esperado, lote completo e código
 * ainda não confirmado ficam de fora por construção (design.md — Decisão 2).
 *
 * @property instrucao o que o operador deve fazer agora. Nos estados de pausa, erro e exceção
 *   é a mensagem de recuperação.
 * @property ultimaConfirmacao o último resultado já confirmado pelo fluxo — nunca uma leitura
 *   em andamento.
 * @property dicaDeVoz a palavra que o operador precisa dizer para avançar, ou `null` nos
 *   estados que avançam por câmera, rede ou por dígitos falados ([DicaDeComandoDeVoz]).
 * @property podeRegistrarOcorrencia `true` só em `TratandoExcecao`, onde a tela oferece uma
 *   saída por toque. Existe porque a exceção é justamente o estado em que a voz pode não estar
 *   dando conta — ver [OperationViewModel].
 * @property podeConfirmarOrdem `true` só em `AguardandoOrdem`, onde a tela oferece o toque que
 *   confirma e carrega a ordem. Este estado é surdo por decisão de projeto — a escolha da ordem
 *   é por toque, não por voz (design.md — Decisão 4), e `SeletorDeEscuta` nem abre escuta aqui.
 *   Antes desta ação a confirmação só existia no painel de desenvolvimento, e o operador ficava
 *   preso na tela principal sem nenhum jeito de seguir.
 * @property nomeEtapa o passo da separação em curso, um por `PickingState` e não por [etapa]:
 *   quatro estados diferentes compartilham o cartão [EtapaOperacao.QUANTIDADE], e era ali que o
 *   operador se perdia. Nunca contém o check digit esperado, o lote completo nem código ainda não
 *   confirmado, pela mesma razão do resto deste estado.
 */
data class OperationUiState(
    val etapa: EtapaOperacao = EtapaOperacao.MENSAGEM,
    val ordem: String? = null,
    val progresso: String? = null,
    val situacao: String = "",
    val instrucao: String = "",
    val endereco: String? = null,
    val produto: String? = null,
    val quantidadeEsperada: Int? = null,
    val quantidadeInformada: Int? = null,
    val compartimento: String? = null,
    val ultimaConfirmacao: String? = null,
    val statusLeitura: String? = null,
    val orientacaoPendente: Boolean = false,
    val aguardandoVoz: Boolean = false,
    val estadoFala: EstadoSaidaAudio = EstadoSaidaAudio.PARADA,
    val mensagem: String? = null,
    val dicaDeVoz: String? = null,
    val podeRegistrarOcorrencia: Boolean = false,
    val podeConfirmarOrdem: Boolean = false,
    val nomeEtapa: String = "",
)
