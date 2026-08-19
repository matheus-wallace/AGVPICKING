package com.agvtronic.pickvoice.audio.output

import com.agvtronic.pickvoice.domain.statemachine.CausaErro
import com.agvtronic.pickvoice.domain.statemachine.PickingState
import com.agvtronic.pickvoice.domain.statemachine.TipoCheckDigit

/** Projeta estado de domínio em fala, sem Android, corrotinas ou I/O. */
class ProjetorDeFalaPicking {

  fun projetar(estado: PickingState): MensagemFalavel? =
      when (estado) {
        PickingState.PreparandoSessao -> mensagem("sessao-pronta", "Sessão pronta")
        is PickingState.OrdemCarregada ->
            mensagem(
                chave = "ordem-carregada:${estado.ordemId}:${estado.totalLinhas}",
                texto =
                    "Ordem ${numeroOrdemFalado(estado.ordemId)} carregada. ${estado.totalLinhas} " +
                        if (estado.totalLinhas == 1) "item" else "itens",
            )
        is PickingState.NavegandoParaEndereco ->
            mensagem(
                chave = "navegar:${estado.itemEmAndamento.endereco}",
                texto = "Siga para ${estado.itemEmAndamento.endereco}",
            )
        is PickingState.AguardandoCheckDigit ->
            when (estado.tipo) {
              TipoCheckDigit.POSICAO ->
                  mensagem("confirmar-endereco", "Confirme o endereço")
              TipoCheckDigit.PRODUTO ->
                  mensagem("confirmar-produto", "Confirme o produto")
            }
        is PickingState.EscaneandoProduto -> mensagem("escanear-produto", "Escaneando produto")
        is PickingState.VerificacaoAssistida -> mensagem("verificando-produto", "Verificando")
        is PickingState.ConfirmandoQuantidade ->
            mensagem(
                chave = "coletar-quantidade:${estado.quantidadeEsperada}",
                texto =
                    "Colete ${estado.quantidadeEsperada} " +
                        if (estado.quantidadeEsperada == 1) "unidade" else "unidades",
            )
        is PickingState.ReadbackQuantidade ->
            mensagem(
                chave = "readback-quantidade:${estado.quantidadeInformada}",
                texto = "Confirma ${estado.quantidadeInformada}?",
            )
        is PickingState.ItemConcluido -> mensagem("item-concluido", "Item concluído")
        is PickingState.TratandoExcecao ->
            mensagem(
                chave = "excecao:${estado.motivo.name}",
                texto = "Atenção. Diga próximo para registrar a ocorrência",
                prioridade = PrioridadeFala.CRITICA,
            )
        is PickingState.OrdemConcluida ->
            mensagem(
                chave = "ordem-concluida:${estado.ordemId}",
                texto = "Ordem ${numeroOrdemFalado(estado.ordemId)} concluída",
            )
        is PickingState.Erro ->
            mensagem(
                chave = "erro:${estado.causa.name}",
                texto = "Erro. ${descricao(estado.causa)}",
                prioridade = PrioridadeFala.CRITICA,
            )
        PickingState.Ocioso,
        PickingState.Registrando,
        PickingState.AguardandoOrdem,
        is PickingState.DecodificandoProduto,
        is PickingState.ValidandoContraDados,
        is PickingState.AlocandoCarrinho,
        is PickingState.ConferenciaFinal,
        is PickingState.SessaoPausada -> null
      }

  private fun descricao(causa: CausaErro): String =
      when (causa) {
        CausaErro.BLUETOOTH_DESCONECTADO -> "Bluetooth desconectado"
        CausaErro.FALHA_REGISTRO -> "Falha no registro"
        CausaErro.FALHA_SESSAO -> "Falha na sessão"
      }

  private fun mensagem(
      chave: String,
      texto: String,
      prioridade: PrioridadeFala = PrioridadeFala.ROTINA,
  ) = MensagemFalavel(chave, texto, prioridade)

  /**
   * `ordemId` é a chave achatada `praca-pedido` (`Ordem.id`), opaca para o resto do domínio —
   * mas falar `274K5010000-408176` é um número longo demais para o operador acompanhar de
   * ouvido. O `pedido` sozinho (depois do último `-`) é o número que a operação reconhece.
   */
  private fun numeroOrdemFalado(ordemId: String): String = ordemId.substringAfterLast("-")
}
