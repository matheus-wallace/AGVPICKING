package com.agvtronic.pickvoice.audio

import com.agvtronic.pickvoice.domain.statemachine.CausaErro
import com.agvtronic.pickvoice.domain.statemachine.ItemEmAndamento
import com.agvtronic.pickvoice.domain.statemachine.MotivoExcecao
import com.agvtronic.pickvoice.domain.statemachine.MotivoPausa
import com.agvtronic.pickvoice.domain.statemachine.PickingState
import com.agvtronic.pickvoice.domain.statemachine.TipoCheckDigit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cobre o `#### Scenario` implícito da Requirement "Gramática depende do estado operacional":
 * a tabela "Mapeamento de entrada" do design.md, estado por estado (task 1.1).
 */
class SeletorDeEscutaTest {

  private val item = ItemEmAndamento("274K5010000-408176", 0, "Rua D, prédio 118, andar B", 2)

  @Test
  fun `estados de pre-sessao, selecao de ordem e erro nao escutam`() {
    listOf(
            PickingState.Ocioso,
            PickingState.Registrando,
            PickingState.PreparandoSessao,
            PickingState.AguardandoOrdem,
            PickingState.Erro(CausaErro.BLUETOOTH_DESCONECTADO),
        )
        .forEach { assertNull("$it não deveria escutar", SeletorDeEscuta.para(it)) }
  }

  @Test
  fun `estados de processamento nao escutam comando de avanco`() {
    // Quem começou o processamento responde pelo resultado — a voz não atropela a cascata.
    listOf(
            PickingState.DecodificandoProduto(item),
            PickingState.VerificacaoAssistida(item),
            PickingState.ValidandoContraDados(item, "7896523202204"),
        )
        .forEach { assertNull("$it não deveria escutar", SeletorDeEscuta.para(it)) }
  }

  @Test
  fun `escaneando produto escuta so os transversais`() {
    val config = SeletorDeEscuta.para(PickingState.EscaneandoProduto(item))

    assertEquals(VocabularioDeVoz.TRANSVERSAIS, config?.palavras)
  }

  @Test
  fun `check digit e quantidade usam o perfil de digitos`() {
    assertEquals(
        PerfilEndpoint.DIGITOS,
        SeletorDeEscuta.para(PickingState.AguardandoCheckDigit(item, TipoCheckDigit.POSICAO))?.perfil,
    )
    assertEquals(
        PerfilEndpoint.DIGITOS,
        SeletorDeEscuta.para(PickingState.ConfirmandoQuantidade(item, 12))?.perfil,
    )
  }

  @Test
  fun `comandos de uma palavra usam o perfil curto e aceitam os transversais`() {
    val estados =
        mapOf(
            PickingState.OrdemCarregada("274K5010000-408176", 3) to VocabularioDeVoz.INICIAR,
            PickingState.NavegandoParaEndereco(item) to VocabularioDeVoz.CHEGUEI,
            PickingState.AlocandoCarrinho(item, 12) to VocabularioDeVoz.ALOCADO,
            PickingState.ItemConcluido(item) to VocabularioDeVoz.PROXIMO,
            PickingState.ConferenciaFinal("274K5010000-408176") to VocabularioDeVoz.CONCLUIR,
            PickingState.OrdemConcluida("274K5010000-408176") to VocabularioDeVoz.ENCERRAR,
        )

    estados.forEach { (estado, palavra) ->
      val config = SeletorDeEscuta.para(estado)
      assertNotNull("$estado deveria escutar", config)
      assertEquals(PerfilEndpoint.COMANDO_CURTO, config?.perfil)
      assertTrue("$estado deveria aceitar $palavra", config?.palavras?.contains(palavra) == true)
      assertTrue(config?.palavras?.containsAll(VocabularioDeVoz.TRANSVERSAIS) == true)
    }
  }

  @Test
  fun `readback aceita confirmar e corrigir`() {
    val config = SeletorDeEscuta.para(PickingState.ReadbackQuantidade(item, 12))

    assertTrue(config?.palavras?.contains(VocabularioDeVoz.CONFIRMAR) == true)
    assertTrue(config?.palavras?.contains(VocabularioDeVoz.CORRIGIR) == true)
  }

  @Test
  fun `excecao e o unico estado de vocabulario aberto`() {
    val config = SeletorDeEscuta.para(PickingState.TratandoExcecao(MotivoExcecao.AVARIA, item))

    assertTrue(config?.aberta == true)
    assertEquals(PerfilEndpoint.TEXTO_LIVRE, config?.perfil)
    assertNull("vocabulário aberto não tem gramática", config?.gramatica)
  }

  @Test
  fun `sessao pausada escuta apenas retomar`() {
    val pausada =
        PickingState.SessaoPausada(
            PickingState.NavegandoParaEndereco(item),
            MotivoPausa.COMANDO_PARAR,
        )

    assertEquals(listOf(VocabularioDeVoz.RETOMAR), SeletorDeEscuta.para(pausada)?.palavras)
  }

  @Test
  fun `toda gramatica fechada carrega o token de desconhecida`() {
    val config = SeletorDeEscuta.para(PickingState.NavegandoParaEndereco(item))

    assertFalse(config!!.aberta)
    assertTrue(config.gramatica!!.contains("\"[unk]\""))
    assertTrue(config.gramatica!!.startsWith("[\"cheguei\", "))
  }
}
