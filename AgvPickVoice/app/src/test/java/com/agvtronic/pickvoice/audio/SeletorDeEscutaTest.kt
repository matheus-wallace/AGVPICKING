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
  fun `estados de avanco aceitam proximo alem da palavra original`() {
    // Gramática fechada: sem "próximo" listada aqui o Vosk nunca transcreve a palavra, e o
    // sinônimo que o InterpretadorDeFala já aceita nunca chega a ser exercido em bancada.
    val estados =
        mapOf(
            PickingState.OrdemCarregada("274K5010000-408176", 3) to VocabularioDeVoz.INICIAR,
            PickingState.NavegandoParaEndereco(item) to VocabularioDeVoz.CHEGUEI,
            PickingState.ConferenciaFinal("274K5010000-408176") to VocabularioDeVoz.CONCLUIR,
            PickingState.OrdemConcluida("274K5010000-408176") to VocabularioDeVoz.ENCERRAR,
        )

    estados.forEach { (estado, palavra) ->
      val palavras = SeletorDeEscuta.para(estado)?.palavras
      assertTrue("$estado deveria aceitar $palavra", palavras?.contains(palavra) == true)
      assertTrue(
          "$estado deveria aceitar ${VocabularioDeVoz.PROXIMO}",
          palavras?.contains(VocabularioDeVoz.PROXIMO) == true,
      )
    }
  }

  @Test
  fun `readback aceita confirmar, proximo e corrigir`() {
    val config = SeletorDeEscuta.para(PickingState.ReadbackQuantidade(item, 12))

    assertTrue(config?.palavras?.contains(VocabularioDeVoz.CONFIRMAR) == true)
    assertTrue(config?.palavras?.contains(VocabularioDeVoz.PROXIMO) == true)
    assertTrue(config?.palavras?.contains(VocabularioDeVoz.CORRIGIR) == true)
  }

  @Test
  fun `alocando carrinho aceita alocado e proximo`() {
    val config = SeletorDeEscuta.para(PickingState.AlocandoCarrinho(item, 12))

    assertTrue(config?.palavras?.contains(VocabularioDeVoz.ALOCADO) == true)
    assertTrue(config?.palavras?.contains(VocabularioDeVoz.PROXIMO) == true)
  }

  @Test
  fun `a ocorrencia escuta gramatica fechada de proximo, como os demais avancos`() {
    // add-voice-recognition-reliability - Decisão 2: era o único estado de vocabulário aberto,
    // e o log de bancada mostrou o decodificador aberto errando "próximo" onde toda gramática
    // fechada do mesmo log acertava de primeira.
    val config = SeletorDeEscuta.para(PickingState.TratandoExcecao(MotivoExcecao.AVARIA, item))

    assertFalse("a gramática da ocorrência não é mais aberta", config!!.aberta)
    assertEquals(PerfilEndpoint.COMANDO_CURTO, config.perfil)
    assertTrue(config.palavras.contains(VocabularioDeVoz.PROXIMO))
    assertTrue(config.palavras.containsAll(VocabularioDeVoz.TRANSVERSAIS))
    assertNotNull(config.gramatica)
  }

  @Test
  fun `nenhum estado usa vocabulario aberto`() {
    // A mecânica de vocabulário aberto continua existindo em `ConfiguracaoDeEscuta` para a
    // fatia de relato via LLM (doc §5.4), mas hoje nenhum estado a usa.
    val estados =
        listOf(
            PickingState.OrdemCarregada("274K5010000-408176", 3),
            PickingState.NavegandoParaEndereco(item),
            PickingState.AguardandoCheckDigit(item, TipoCheckDigit.POSICAO),
            PickingState.EscaneandoProduto(item),
            PickingState.ConfirmandoQuantidade(item, 12),
            PickingState.ReadbackQuantidade(item, 12),
            PickingState.AlocandoCarrinho(item, 12),
            PickingState.ItemConcluido(item),
            PickingState.TratandoExcecao(MotivoExcecao.AVARIA, item),
            PickingState.ConferenciaFinal("274K5010000-408176"),
            PickingState.OrdemConcluida("274K5010000-408176"),
            PickingState.SessaoPausada(
                PickingState.NavegandoParaEndereco(item),
                MotivoPausa.COMANDO_PARAR,
            ),
        )

    estados.forEach { estado ->
      val config = SeletorDeEscuta.para(estado)
      assertFalse("$estado não deveria ter vocabulário aberto", config!!.aberta)
    }
  }

  @Test
  fun `check digit escuta digitos, extenso restrito a 0 e 99, e transversais`() {
    // A gramática somou as palavras de dezena/centena, para aceitar "quarenta e sete", e a
    // bancada de 17/08/2026 reverteu (add-voice-recognition-reliability - Decisão 1): a
    // gramática de 0-999 inteira fazia "quatro" ser revisado para "quatrocentos" no meio da
    // fala. Voltou na bancada de 18/08/2026 sem repetir o motivo: `CHECK_DIGIT_POR_EXTENSO` é
    // restrito a 0..99, sem palavra de centena.
    val palavras =
        SeletorDeEscuta.para(PickingState.AguardandoCheckDigit(item, TipoCheckDigit.POSICAO))
            ?.palavras

    assertEquals(
        VocabularioDeVoz.DIGITOS +
            VocabularioDeVoz.CHECK_DIGIT_POR_EXTENSO +
            VocabularioDeVoz.TRANSVERSAIS,
        palavras,
    )
    assertTrue("não deve ter palavra de centena", palavras?.none { it == "cem" || it == "cento" } == true)
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
