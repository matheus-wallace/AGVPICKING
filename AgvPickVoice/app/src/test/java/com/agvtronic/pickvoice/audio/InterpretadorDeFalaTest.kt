package com.agvtronic.pickvoice.audio

import com.agvtronic.pickvoice.domain.statemachine.ItemEmAndamento
import com.agvtronic.pickvoice.domain.statemachine.MotivoExcecao
import com.agvtronic.pickvoice.domain.statemachine.MotivoPausa
import com.agvtronic.pickvoice.domain.statemachine.PickingEvent
import com.agvtronic.pickvoice.domain.statemachine.PickingState
import com.agvtronic.pickvoice.domain.statemachine.TipoCheckDigit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Cobre a task 1.2 e as fronteiras da task 1.3: números pt-BR, dígitos isolados, ruído e as
 * Requirements "Voz dirige os passos manuais normais da separação" e "Visão e voz mantêm
 * responsabilidades separadas".
 */
class InterpretadorDeFalaTest {

  private val item = ItemEmAndamento("274K5010000-408176", 0, "Rua D, prédio 118, andar B", 2)

  // -----------------------------------------------------------------------------------
  // Fluxo
  // -----------------------------------------------------------------------------------

  @Test
  fun `cada estado aceita a sua fala de avanco`() {
    assertEquals(
        IntencaoDeVoz.IniciarNavegacao,
        interpretar(PickingState.OrdemCarregada("274K5010000-408176", 3), "iniciar"),
    )
    assertEquals(
        IntencaoDeVoz.Direta(PickingEvent.EnderecoAlcancado),
        interpretar(PickingState.NavegandoParaEndereco(item), "cheguei"),
    )
    assertEquals(
        IntencaoDeVoz.Direta(PickingEvent.ItemAlocado),
        interpretar(PickingState.AlocandoCarrinho(item, 12), "alocado"),
    )
    assertEquals(
        IntencaoDeVoz.AvancarParaProximoItem,
        interpretar(PickingState.ItemConcluido(item), "próximo"),
    )
    assertEquals(
        IntencaoDeVoz.Direta(PickingEvent.ConferenciaConcluida),
        interpretar(PickingState.ConferenciaFinal("274K5010000-408176"), "concluir"),
    )
    assertEquals(
        IntencaoDeVoz.Direta(PickingEvent.OrdemEncerrada),
        interpretar(PickingState.OrdemConcluida("274K5010000-408176"), "encerrar"),
    )
  }

  @Test
  fun `readback so aceita confirmar, proximo e corrigir`() {
    val estado = PickingState.ReadbackQuantidade(item, 12)

    assertEquals(
        IntencaoDeVoz.Direta(PickingEvent.ReadbackConfirmado),
        interpretar(estado, "confirmar"),
    )
    assertEquals(
        IntencaoDeVoz.Direta(PickingEvent.ReadbackCorrecaoSolicitada),
        interpretar(estado, "corrigir"),
    )
    assertNull(interpretar(estado, "doze"))
  }

  @Test
  fun `proximo e sinonimo aditivo em readback e alocando carrinho`() {
    // design.md - Decisão 8 de add-state-driven-voice-flow: "próximo" soma às palavras
    // existentes, não as substitui.
    assertEquals(
        IntencaoDeVoz.Direta(PickingEvent.ReadbackConfirmado),
        interpretar(PickingState.ReadbackQuantidade(item, 12), "próximo"),
    )
    assertEquals(
        IntencaoDeVoz.Direta(PickingEvent.ItemAlocado),
        interpretar(PickingState.AlocandoCarrinho(item, 12), "próximo"),
    )
    // As palavras originais continuam funcionando sem regressão.
    assertEquals(
        IntencaoDeVoz.Direta(PickingEvent.ItemAlocado),
        interpretar(PickingState.AlocandoCarrinho(item, 12), "alocado"),
    )
    // "corrigir" não ganha sinônimo: só a palavra original produz ReadbackCorrecaoSolicitada.
    assertEquals(
        IntencaoDeVoz.Direta(PickingEvent.ReadbackCorrecaoSolicitada),
        interpretar(PickingState.ReadbackQuantidade(item, 12), "corrigir"),
    )
  }

  @Test
  fun `proximo e sinonimo aditivo em todo estado com uma palavra de avanco`() {
    // design.md - Decisão 6 de add-operator-feedback-improvements: estende o padrão da
    // Decisão 8 acima a todo estado que só espera uma palavra de avanço.
    assertEquals(
        IntencaoDeVoz.IniciarNavegacao,
        interpretar(PickingState.OrdemCarregada("274K5010000-408176", 3), "próximo"),
    )
    assertEquals(
        IntencaoDeVoz.Direta(PickingEvent.EnderecoAlcancado),
        interpretar(PickingState.NavegandoParaEndereco(item), "próximo"),
    )
    assertEquals(
        IntencaoDeVoz.Direta(PickingEvent.ConferenciaConcluida),
        interpretar(PickingState.ConferenciaFinal("274K5010000-408176"), "próximo"),
    )
    assertEquals(
        IntencaoDeVoz.Direta(PickingEvent.OrdemEncerrada),
        interpretar(PickingState.OrdemConcluida("274K5010000-408176"), "próximo"),
    )
    // As palavras originais continuam funcionando sem regressão.
    assertEquals(
        IntencaoDeVoz.IniciarNavegacao,
        interpretar(PickingState.OrdemCarregada("274K5010000-408176", 3), "iniciar"),
    )
    assertEquals(
        IntencaoDeVoz.Direta(PickingEvent.EnderecoAlcancado),
        interpretar(PickingState.NavegandoParaEndereco(item), "cheguei"),
    )
    assertEquals(
        IntencaoDeVoz.Direta(PickingEvent.ConferenciaConcluida),
        interpretar(PickingState.ConferenciaFinal("274K5010000-408176"), "concluir"),
    )
    assertEquals(
        IntencaoDeVoz.Direta(PickingEvent.OrdemEncerrada),
        interpretar(PickingState.OrdemConcluida("274K5010000-408176"), "encerrar"),
    )
  }

  @Test
  fun `proximo nao se estende a estados fora da lista`() {
    // design.md - Decisão 6: só estados com uma única palavra de avanço ganham o sinônimo.
    assertNull(interpretar(PickingState.AguardandoCheckDigit(item, TipoCheckDigit.POSICAO), "próximo"))
    assertNull(interpretar(PickingState.ConfirmandoQuantidade(item, 12), "próximo"))
    // `TratandoExcecao` saiu desta lista de propósito — ver o teste da saída da ocorrência.
  }

  @Test
  fun `a mesma palavra nao vale em outro estado`() {
    // "cheguei" só avança a navegação; no readback é ruído.
    assertNull(interpretar(PickingState.ReadbackQuantidade(item, 12), "cheguei"))
    assertNull(interpretar(PickingState.NavegandoParaEndereco(item), "confirmar"))
  }

  // -----------------------------------------------------------------------------------
  // Quantidade
  // -----------------------------------------------------------------------------------

  @Test
  fun `quantidade aceita numero por extenso e composto`() {
    val estado = PickingState.ConfirmandoQuantidade(item, 12)

    assertEquals(quantidade(12), interpretar(estado, "doze"))
    assertEquals(quantidade(4), interpretar(estado, "quatro"))
    assertEquals(quantidade(30), interpretar(estado, "trinta"))
    assertEquals(quantidade(106), interpretar(estado, "cento e seis"))
    assertEquals(quantidade(123), interpretar(estado, "cento e vinte e três"))
    assertEquals(quantidade(21), interpretar(estado, "vinte e um"))
    // O modelo pode devolver o algarismo já escrito.
    assertEquals(quantidade(14), interpretar(estado, "14"))
  }

  @Test
  fun `quantidade aceita leitura digito a digito`() {
    val estado = PickingState.ConfirmandoQuantidade(item, 12)

    assertEquals(quantidade(12), interpretar(estado, "um dois"))
    assertEquals(quantidade(22), interpretar(estado, "dois dois"))
    assertEquals(quantidade(106), interpretar(estado, "um zero seis"))
    // Zero à esquerda cai: aqui o resultado é número, não código como no check digit.
    assertEquals(quantidade(5), interpretar(estado, "zero cinco"))
  }

  @Test
  fun `quantidade recusa sequencia longa, magnitude repetida, zero e valor fora do intervalo`() {
    val estado = PickingState.ConfirmandoQuantidade(item, 12)

    // Acima de três algarismos passa do teto de 999 — não há leitura plausível.
    assertNull(interpretar(estado, "um dois três quatro"))
    // Nem por extenso nem dígito a dígito: "trinta" não é algarismo.
    assertNull(interpretar(estado, "vinte trinta"))
    // Zero unidade não é coleta: é ruptura, e tem comando próprio.
    assertNull(interpretar(estado, "zero"))
    assertNull(interpretar(estado, "1000"))
    assertNull(interpretar(estado, ""))
  }

  @Test
  fun `quantidade divergente da esperada continua sendo aceita`() {
    // O operador pode achar menos do que a ordem pedia; quem protege é o readback, não o filtro.
    assertEquals(
        quantidade(3),
        interpretar(PickingState.ConfirmandoQuantidade(item, 12), "três"),
    )
  }

  // -----------------------------------------------------------------------------------
  // Check digit
  // -----------------------------------------------------------------------------------

  @Test
  fun `check digit aceita exatamente dois digitos`() {
    val estado = PickingState.AguardandoCheckDigit(item, TipoCheckDigit.POSICAO)

    assertEquals(IntencaoDeVoz.CheckDigitFalado("47"), interpretar(estado, "quatro sete"))
    assertEquals(IntencaoDeVoz.CheckDigitFalado("06"), interpretar(estado, "zero meia"))
    assertEquals(IntencaoDeVoz.CheckDigitFalado("82"), interpretar(estado, "82"))
    // Zero à esquerda é significativo: não pode virar número.
    assertEquals(IntencaoDeVoz.CheckDigitFalado("07"), interpretar(estado, "zero sete"))
  }

  @Test
  fun `check digit recusa fala cortada, longa demais ou fora de digito`() {
    val estado = PickingState.AguardandoCheckDigit(item, TipoCheckDigit.POSICAO)

    assertNull(interpretar(estado, "quatro"))
    assertNull(interpretar(estado, "quatro sete dois"))
    assertNull(interpretar(estado, "quarenta e sete"))
    assertNull(interpretar(estado, "[unk]"))
  }

  // -----------------------------------------------------------------------------------
  // Visão e voz
  // -----------------------------------------------------------------------------------

  /** Scenario: Código só vem do produtor óptico */
  @Test
  fun `sequencia parecida com codigo durante o escaneamento nao vira intencao`() {
    listOf(
            PickingState.EscaneandoProduto(item),
            PickingState.DecodificandoProduto(item),
            PickingState.ValidandoContraDados(item, "7896523202204"),
        )
        .forEach { estado ->
          assertNull(interpretar(estado, "sete oito nove seis cinco dois três"))
          assertNull(interpretar(estado, "confirmar"))
        }
  }

  // -----------------------------------------------------------------------------------
  // Transversais e exceção
  // -----------------------------------------------------------------------------------

  @Test
  fun `transversais valem em qualquer estado operacional`() {
    val estados =
        listOf(
            PickingState.NavegandoParaEndereco(item),
            PickingState.AguardandoCheckDigit(item, TipoCheckDigit.POSICAO),
            PickingState.EscaneandoProduto(item),
            PickingState.ConfirmandoQuantidade(item, 12),
        )

    estados.forEach { estado ->
      assertEquals(
          IntencaoDeVoz.Direta(PickingEvent.ComandoParar),
          interpretar(estado, "parar"),
      )
      assertEquals(
          IntencaoDeVoz.Direta(PickingEvent.ComandoRepetir),
          interpretar(estado, "repetir"),
      )
      assertEquals(
          IntencaoDeVoz.Direta(PickingEvent.ExcecaoSolicitada(MotivoExcecao.RUPTURA)),
          interpretar(estado, "ruptura"),
      )
    }
  }

  @Test
  fun `transversal nao vale em estado que o reducer ignoraria`() {
    val pausada =
        PickingState.SessaoPausada(
            PickingState.NavegandoParaEndereco(item),
            MotivoPausa.COMANDO_PARAR,
        )

    assertNull(interpretar(pausada, "parar"))
    assertEquals(
        IntencaoDeVoz.Direta(PickingEvent.SessaoRetomada),
        interpretar(pausada, "retomar"),
    )
  }

  @Test
  fun `relato de excecao precisa ter forma de relato`() {
    val estado = PickingState.TratandoExcecao(MotivoExcecao.AVARIA, item)

    assertEquals(
        IntencaoDeVoz.Direta(PickingEvent.ExcecaoRegistrada),
        interpretar(estado, "caixa molhada no fundo da prateleira"),
    )
    // Palavra solta em vocabulário aberto é ruído do galpão, não relato.
    assertNull(interpretar(estado, "caixa"))
    // ...menos "próximo", que é a saída curta da ocorrência (bancada de 17/08/2026).
    assertEquals(
        IntencaoDeVoz.Direta(PickingEvent.ExcecaoRegistrada),
        interpretar(estado, "próximo"),
    )
    // Um transversal continua funcionando mesmo com o vocabulário aberto.
    assertEquals(
        IntencaoDeVoz.Direta(PickingEvent.ComandoParar),
        interpretar(estado, "parar"),
    )
  }

  @Test
  fun `ruido e silencio nunca viram intencao`() {
    val estado = PickingState.NavegandoParaEndereco(item)

    assertNull(interpretar(estado, ""))
    assertNull(interpretar(estado, "   "))
    assertNull(interpretar(estado, "[unk]"))
    assertNull(interpretar(estado, "che"))
  }

  private fun interpretar(estado: PickingState, texto: String) =
      InterpretadorDeFala.interpretar(estado, texto)

  private fun quantidade(valor: Int) =
      IntencaoDeVoz.Direta(PickingEvent.QuantidadeInformada(valor))
}
