package com.agvtronic.pickvoice.ui.operation

import com.agvtronic.pickvoice.domain.statemachine.ItemEmAndamento
import com.agvtronic.pickvoice.domain.statemachine.MotivoExcecao
import com.agvtronic.pickvoice.domain.statemachine.PickingState
import com.agvtronic.pickvoice.domain.statemachine.TipoCheckDigit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DicaDeComandoDeVozTest {

  private val item = ItemEmAndamento("274K5010000-408176", 0, "Rua D, prédio 118, andar B", 2)

  @Test
  fun `dica cobre todo estado com uma unica palavra de avanco`() {
    assertEquals(
        "Diga: iniciar",
        DicaDeComandoDeVoz.dica(PickingState.OrdemCarregada("274K5010000-408176", 3)),
    )
    assertEquals(
        "Diga: cheguei",
        DicaDeComandoDeVoz.dica(PickingState.NavegandoParaEndereco(item)),
    )
    assertEquals(
        "Diga: alocado",
        DicaDeComandoDeVoz.dica(PickingState.AlocandoCarrinho(item, 12)),
    )
    assertEquals(
        "Diga: confirmar ou corrigir",
        DicaDeComandoDeVoz.dica(PickingState.ReadbackQuantidade(item, 12)),
    )
    assertEquals(
        "Diga: próximo",
        DicaDeComandoDeVoz.dica(PickingState.ItemConcluido(item)),
    )
    assertEquals(
        "Diga: concluir",
        DicaDeComandoDeVoz.dica(PickingState.ConferenciaFinal("274K5010000-408176")),
    )
    assertEquals(
        "Diga: encerrar",
        DicaDeComandoDeVoz.dica(PickingState.OrdemConcluida("274K5010000-408176")),
    )
  }

  @Test
  fun `sem dica em estados que nao avancam por uma palavra fixa`() {
    // Dígitos, número ou avanço por câmera/rede — nenhuma palavra fixa.
    assertNull(
        DicaDeComandoDeVoz.dica(PickingState.AguardandoCheckDigit(item, TipoCheckDigit.POSICAO))
    )
    assertNull(DicaDeComandoDeVoz.dica(PickingState.ConfirmandoQuantidade(item, 12)))
    assertNull(DicaDeComandoDeVoz.dica(PickingState.EscaneandoProduto(item)))
  }

  @Test
  fun `a ocorrencia mostra a saida curta`() {
    // A gramática do estado fechou em "próximo" (add-voice-recognition-reliability - Decisão 2),
    // então a dica é a mesma dos demais avanços de uma palavra: mostrar um relato livre que o
    // ASR não aceita mais mandaria o operador para um caminho que não existe.
    assertEquals(
        "Diga: próximo",
        DicaDeComandoDeVoz.dica(PickingState.TratandoExcecao(MotivoExcecao.AVARIA, item)),
    )
  }
}
