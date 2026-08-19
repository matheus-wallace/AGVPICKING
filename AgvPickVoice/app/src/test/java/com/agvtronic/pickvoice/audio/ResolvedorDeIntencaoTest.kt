package com.agvtronic.pickvoice.audio

import com.agvtronic.pickvoice.data.mock.MockPickingRepository
import com.agvtronic.pickvoice.domain.statemachine.ItemEmAndamento
import com.agvtronic.pickvoice.domain.statemachine.PickingEvent
import com.agvtronic.pickvoice.domain.statemachine.PickingState
import com.agvtronic.pickvoice.domain.statemachine.TipoCheckDigit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Cobre as tasks 2.1 a 2.3 e a Requirement "Check digit falado é validado localmente" contra o
 * `MockPickingRepository` de verdade — ordem de três linhas, última linha inclusive.
 */
class ResolvedorDeIntencaoTest {

  private val repositorio = MockPickingRepository()
  private val resolvedor = ResolvedorDeIntencao(repositorio)

  // A primeira ordem do mock: 3 linhas, senhas de endereço 40, 40 e 82.
  private val ordemId = "274K5010000-408176"

  private fun item(indice: Int) =
      ItemEmAndamento(
          ordemId = ordemId,
          indiceLinha = indice,
          endereco = "Rua D, prédio 118, andar B",
          itensRestantes = 2 - indice,
      )

  // -----------------------------------------------------------------------------------
  // Check digit
  // -----------------------------------------------------------------------------------

  @Test
  fun `check digit de posicao igual a senha cadastrada e correto`() = runTest {
    val estado = PickingState.AguardandoCheckDigit(item(0), TipoCheckDigit.POSICAO)

    assertEquals(
        PickingEvent.CheckDigitCorreto,
        resolvedor.resolver(estado, IntencaoDeVoz.CheckDigitFalado("40")),
    )
  }

  /** Scenario: Check digit divergente */
  @Test
  fun `check digit de posicao divergente nao revela o valor esperado`() = runTest {
    val estado = PickingState.AguardandoCheckDigit(item(0), TipoCheckDigit.POSICAO)

    val evento = resolvedor.resolver(estado, IntencaoDeVoz.CheckDigitFalado("48"))

    // O evento não carrega campo nenhum: não há por onde o valor esperado vazar.
    assertEquals(PickingEvent.CheckDigitIncorreto, evento)
  }

  @Test
  fun `check digit de posicao usa a senha da linha em curso, nao a da primeira`() = runTest {
    // A terceira linha tem senha 82; "40" acerta as duas primeiras e precisa falhar aqui.
    val estado = PickingState.AguardandoCheckDigit(item(2), TipoCheckDigit.POSICAO)

    assertEquals(
        PickingEvent.CheckDigitIncorreto,
        resolvedor.resolver(estado, IntencaoDeVoz.CheckDigitFalado("40")),
    )
    assertEquals(
        PickingEvent.CheckDigitCorreto,
        resolvedor.resolver(estado, IntencaoDeVoz.CheckDigitFalado("82")),
    )
  }

  @Test
  fun `check digit de produto compara os dois ultimos digitos do lote`() = runTest {
    // Fallback da cascata de visão (doc §7.2): a partida da primeira linha é 60318425.
    val estado = PickingState.AguardandoCheckDigit(item(0), TipoCheckDigit.PRODUTO)

    assertEquals(
        PickingEvent.CheckDigitCorreto,
        resolvedor.resolver(estado, IntencaoDeVoz.CheckDigitFalado("25")),
    )
    assertEquals(
        PickingEvent.CheckDigitIncorreto,
        resolvedor.resolver(estado, IntencaoDeVoz.CheckDigitFalado("47")),
    )
  }

  @Test
  fun `check digit fora do estado de check digit nao produz evento`() = runTest {
    val estado = PickingState.NavegandoParaEndereco(item(0))

    assertNull(resolvedor.resolver(estado, IntencaoDeVoz.CheckDigitFalado("47")))
  }

  // -----------------------------------------------------------------------------------
  // Resolução de item
  // -----------------------------------------------------------------------------------

  @Test
  fun `iniciar resolve a primeira linha da ordem`() = runTest {
    val estado = PickingState.OrdemCarregada(ordemId, totalLinhas = 3)

    val evento = resolvedor.resolver(estado, IntencaoDeVoz.IniciarNavegacao)

    val esperado = ItemEmAndamento(ordemId, 0, "Rua D, prédio 118, andar B", itensRestantes = 2)
    assertEquals(PickingEvent.NavegacaoIniciada(esperado), evento)
  }

  @Test
  fun `proximo resolve a linha seguinte numa ordem de multiplas linhas`() = runTest {
    val evento = resolvedor.resolver(PickingState.ItemConcluido(item(0)), PROXIMO)

    val esperado = ItemEmAndamento(ordemId, 1, "Rua D, prédio 118, andar B", itensRestantes = 1)
    assertEquals(PickingEvent.ItemFinalizado(esperado), evento)
  }

  @Test
  fun `proximo muda de endereco quando a linha seguinte esta em outra posicao`() = runTest {
    val evento = resolvedor.resolver(PickingState.ItemConcluido(item(1)), PROXIMO)

    val esperado = ItemEmAndamento(ordemId, 2, "Rua G, prédio 233, andar C", itensRestantes = 0)
    assertEquals(PickingEvent.ItemFinalizado(esperado), evento)
  }

  @Test
  fun `proximo na ultima linha finaliza sem proximo item`() = runTest {
    val evento = resolvedor.resolver(PickingState.ItemConcluido(item(2)), PROXIMO)

    assertEquals(PickingEvent.ItemFinalizado(proximoItem = null), evento)
  }

  @Test
  fun `ordem desconhecida nao produz evento em vez de lancar`() = runTest {
    val fantasma = ItemEmAndamento("000A0000000-000000", 0, "Rua Z", itensRestantes = 0)

    assertNull(resolvedor.resolver(PickingState.ItemConcluido(fantasma), PROXIMO))
  }

  @Test
  fun `intencao direta passa intacta`() = runTest {
    val evento =
        resolvedor.resolver(
            PickingState.NavegandoParaEndereco(item(0)),
            IntencaoDeVoz.Direta(PickingEvent.EnderecoAlcancado),
        )

    assertEquals(PickingEvent.EnderecoAlcancado, evento)
  }

  private companion object {
    val PROXIMO = IntencaoDeVoz.AvancarParaProximoItem
  }
}
