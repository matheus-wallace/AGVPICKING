package com.agvtronic.pickvoice.audio.output

import com.agvtronic.pickvoice.domain.statemachine.CausaErro
import com.agvtronic.pickvoice.domain.statemachine.ItemEmAndamento
import com.agvtronic.pickvoice.domain.statemachine.MotivoExcecao
import com.agvtronic.pickvoice.domain.statemachine.PickingState
import com.agvtronic.pickvoice.domain.statemachine.TipoCheckDigit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ProjetorDeFalaPickingTest {
  private val projetor = ProjetorDeFalaPicking()
  private val item = ItemEmAndamento("408176", 0, "R04-P12-N03-A05", 2)

  @Test
  fun `projeta endereco e quantidade com os dados do estado`() {
    assertEquals(
        "Siga para R04-P12-N03-A05",
        projetor.projetar(PickingState.NavegandoParaEndereco(item))?.texto,
    )
    assertEquals(
        "Colete 12 unidades",
        projetor.projetar(PickingState.ConfirmandoQuantidade(item, 12))?.texto,
    )
    assertEquals(
        "Colete 1 unidade",
        projetor.projetar(PickingState.ConfirmandoQuantidade(item, 1))?.texto,
    )
  }

  @Test
  fun `pedido de check digit nunca inclui valor protegido`() {
    val posicao =
        projetor.projetar(PickingState.AguardandoCheckDigit(item, TipoCheckDigit.POSICAO))!!
    val produto =
        projetor.projetar(PickingState.AguardandoCheckDigit(item, TipoCheckDigit.PRODUTO))!!

    assertEquals("Confirme a posição", posicao.texto)
    assertEquals("Confirme o produto", produto.texto)
    assertFalse(posicao.texto.contains("05"))
  }

  @Test
  fun `erro e excecao sao criticos e nao expõem detalhe arbitrario`() {
    val erro =
        projetor.projetar(
            PickingState.Erro(
                causa = CausaErro.FALHA_SESSAO,
                detalhe = "token=segredo-operacional",
            )
        )!!
    val excecao = projetor.projetar(PickingState.TratandoExcecao(MotivoExcecao.AVARIA, item))!!

    assertEquals(PrioridadeFala.CRITICA, erro.prioridade)
    assertFalse(erro.texto.contains("segredo-operacional"))
    assertEquals(PrioridadeFala.CRITICA, excecao.prioridade)
  }

  @Test
  fun `estados de transicao definidos como silenciosos nao geram mensagem`() {
    assertNull(projetor.projetar(PickingState.Ocioso))
    assertNull(projetor.projetar(PickingState.DecodificandoProduto(item)))
    assertNull(projetor.projetar(PickingState.ValidandoContraDados(item, "7896523202204")))
  }
}
