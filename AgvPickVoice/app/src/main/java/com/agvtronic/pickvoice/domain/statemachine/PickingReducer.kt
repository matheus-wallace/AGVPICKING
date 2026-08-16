package com.agvtronic.pickvoice.domain.statemachine

/**
 * A tabela de transições do doc §3.2/§3.3 como uma função pura.
 *
 * Função de topo e não classe: sem campo mutável, sem I/O, sem corrotina. O único lugar do
 * app com estado mutável de picking é o [PickingActor], que chama esta função — de acordo
 * com o invariante de que nada escreve estado por canal lateral (doc §3.4.2).
 *
 * Total por construção: um evento que não se aplica ao estado atual devolve o mesmo
 * estado, nunca lança. Um comando de voz mal reconhecido chegando fora de hora é ruído
 * esperado no chão de armazém, não uma condição excepcional.
 *
 * @return o próximo estado, ou `state` inalterado se o evento não se aplica.
 */
fun reduce(state: PickingState, event: PickingEvent): PickingState =
    reduzirTransversal(state, event) ?: reduzirFluxoPrincipal(state, event)

/**
 * Transições transversais do doc §3.3 — têm precedência sobre o fluxo principal e valem a
 * partir de qualquer estado operacional.
 *
 * @return o próximo estado, ou `null` quando o evento não é transversal (ou o estado não é
 *   operacional), para que o fluxo principal decida.
 */
private fun reduzirTransversal(state: PickingState, event: PickingEvent): PickingState? {
  if (!state.ehOperacional) return null
  return when (event) {
    is PickingEvent.ComandoParar ->
        PickingState.SessaoPausada(state, MotivoPausa.COMANDO_PARAR)
    is PickingEvent.ComandoEmergencia ->
        PickingState.SessaoPausada(state, MotivoPausa.EMERGENCIA)
    is PickingEvent.PausaDat -> PickingState.SessaoPausada(state, MotivoPausa.LIFECYCLE_DAT)
    // "repetir" repete a última fala sem mudar de estado (doc §3.3).
    is PickingEvent.ComandoRepetir -> state
    is PickingEvent.ExcecaoSolicitada ->
        PickingState.TratandoExcecao(event.motivo, state.itemEmAndamento)
    // Erro guarda o estado inteiro: a retomada volta ao mesmo item (doc §3.3).
    is PickingEvent.ConexaoBluetoothPerdida ->
        PickingState.Erro(CausaErro.BLUETOOTH_DESCONECTADO, estadoAnterior = state)
    else -> null
  }
}

/** Fluxo principal do doc §3.2, mais o ciclo de vida de sessão que o antecede. */
private fun reduzirFluxoPrincipal(state: PickingState, event: PickingEvent): PickingState =
    when (state) {
      is PickingState.Ocioso ->
          when (event) {
            is PickingEvent.RegistroIniciado -> PickingState.Registrando
            else -> state
          }

      is PickingState.Registrando ->
          when (event) {
            is PickingEvent.RegistroConcluido -> PickingState.PreparandoSessao
            is PickingEvent.RegistroFalhou ->
                PickingState.Erro(CausaErro.FALHA_REGISTRO, detalhe = event.detalhe)
            else -> state
          }

      is PickingState.PreparandoSessao ->
          when (event) {
            is PickingEvent.SessaoPreparada -> PickingState.AguardandoOrdem
            is PickingEvent.SessaoFalhou ->
                PickingState.Erro(CausaErro.FALHA_SESSAO, detalhe = event.detalhe)
            else -> state
          }

      is PickingState.AguardandoOrdem ->
          when (event) {
            is PickingEvent.OrdemConfirmada ->
                PickingState.OrdemCarregada(event.ordemId, event.totalLinhas)
            else -> state
          }

      is PickingState.OrdemCarregada ->
          when (event) {
            is PickingEvent.NavegacaoIniciada -> PickingState.NavegandoParaEndereco(event.item)
            else -> state
          }

      is PickingState.NavegandoParaEndereco ->
          when (event) {
            is PickingEvent.EnderecoAlcancado ->
                PickingState.AguardandoCheckDigit(
                    state.itemEmAndamento,
                    TipoCheckDigit.POSICAO,
                )
            else -> state
          }

      is PickingState.AguardandoCheckDigit ->
          when (event) {
            is PickingEvent.CheckDigitCorreto ->
                when (state.tipo) {
                  // Posição confirmada: liga a câmera e escaneia o produto.
                  TipoCheckDigit.POSICAO ->
                      PickingState.EscaneandoProduto(state.itemEmAndamento)
                  // Fallback final da cascata (§7.2): o produto já está identificado,
                  // resta comparar contra a ordem.
                  TipoCheckDigit.PRODUTO ->
                      PickingState.ValidandoContraDados(
                          state.itemEmAndamento,
                          codigoLido = CODIGO_CHECK_DIGIT_PRODUTO,
                      )
                }
            // Nunca revela o valor correto: só repete o endereço (doc §7.1).
            is PickingEvent.CheckDigitIncorreto ->
                PickingState.NavegandoParaEndereco(state.itemEmAndamento)
            else -> state
          }

      is PickingState.EscaneandoProduto ->
          when (event) {
            is PickingEvent.CapturaDisparada ->
                PickingState.DecodificandoProduto(state.itemEmAndamento)
            else -> state
          }

      is PickingState.DecodificandoProduto ->
          when (event) {
            is PickingEvent.DecodificacaoConcluida ->
                PickingState.ValidandoContraDados(state.itemEmAndamento, event.codigoLido)
            is PickingEvent.DecodificacaoFalhou ->
                PickingState.VerificacaoAssistida(state.itemEmAndamento)
            else -> state
          }

      is PickingState.VerificacaoAssistida ->
          when (event) {
            is PickingEvent.VerificacaoAssistidaConcluida ->
                PickingState.ValidandoContraDados(state.itemEmAndamento, event.codigoLido)
            // Sem rede: degrada para o check digit do produto por voz (doc §6.4/§7.2).
            is PickingEvent.VerificacaoAssistidaIndisponivel ->
                PickingState.AguardandoCheckDigit(
                    state.itemEmAndamento,
                    TipoCheckDigit.PRODUTO,
                )
            else -> state
          }

      is PickingState.ValidandoContraDados ->
          when (event) {
            is PickingEvent.ValidacaoOk ->
                PickingState.ConfirmandoQuantidade(
                    state.itemEmAndamento,
                    event.quantidadeEsperada,
                )
            is PickingEvent.ValidacaoDivergente ->
                PickingState.TratandoExcecao(event.motivo, state.itemEmAndamento)
            else -> state
          }

      is PickingState.ConfirmandoQuantidade ->
          when (event) {
            is PickingEvent.QuantidadeInformada ->
                PickingState.ReadbackQuantidade(state.itemEmAndamento, event.quantidade)
            else -> state
          }

      is PickingState.ReadbackQuantidade ->
          when (event) {
            is PickingEvent.ReadbackConfirmado ->
                PickingState.AlocandoCarrinho(
                    state.itemEmAndamento,
                    state.quantidadeInformada,
                )
            is PickingEvent.ReadbackCorrecaoSolicitada ->
                PickingState.ConfirmandoQuantidade(
                    state.itemEmAndamento,
                    state.quantidadeInformada,
                )
            else -> state
          }

      is PickingState.AlocandoCarrinho ->
          when (event) {
            is PickingEvent.ItemAlocado -> PickingState.ItemConcluido(state.itemEmAndamento)
            else -> state
          }

      is PickingState.ItemConcluido ->
          when (event) {
            is PickingEvent.ItemFinalizado -> {
              val proximo = event.proximoItem
              // Restam itens -> volta a navegar; não restam -> conferência final (§3.2).
              if (state.itemEmAndamento.itensRestantes > 0 && proximo != null) {
                PickingState.NavegandoParaEndereco(proximo)
              } else {
                PickingState.ConferenciaFinal(state.itemEmAndamento.ordemId)
              }
            }
            else -> state
          }

      is PickingState.TratandoExcecao ->
          when (event) {
            is PickingEvent.ExcecaoRegistrada -> {
              val item = state.itemEmAndamento
              if (item != null) PickingState.ItemConcluido(item) else PickingState.AguardandoOrdem
            }
            else -> state
          }

      is PickingState.ConferenciaFinal ->
          when (event) {
            is PickingEvent.ConferenciaConcluida -> PickingState.OrdemConcluida(state.ordemId)
            else -> state
          }

      is PickingState.OrdemConcluida ->
          when (event) {
            is PickingEvent.OrdemEncerrada -> PickingState.AguardandoOrdem
            else -> state
          }

      is PickingState.SessaoPausada ->
          when (event) {
            is PickingEvent.SessaoRetomada -> state.estadoAnterior
            is PickingEvent.ConexaoBluetoothPerdida ->
                PickingState.Erro(CausaErro.BLUETOOTH_DESCONECTADO, state.estadoAnterior)
            else -> state
          }

      is PickingState.Erro ->
          when (event) {
            // Retoma exatamente no item que estava em andamento (doc §3.3).
            is PickingEvent.ConexaoBluetoothRestabelecida ->
                state.estadoAnterior ?: PickingState.Ocioso
            is PickingEvent.RegistroIniciado -> PickingState.Registrando
            else -> state
          }
    }

/**
 * Marcador de origem para uma validação que veio do check digit do produto por voz, e não
 * de uma leitura óptica — o `metodoValidacao` da coleta resultante é
 * `MetodoValidacao.CHECK_DIGIT_VOZ`, com peso probatório menor (doc §11.3).
 */
const val CODIGO_CHECK_DIGIT_PRODUTO: String = "CHECK_DIGIT_VOZ"
