package com.agvtronic.pickvoice.domain.statemachine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cobre o `#### Scenario: Eventos são aplicados na ordem recebida` de
 * `specs/picking-state-machine/spec.md` (Requirement: Processamento sequencial único de
 * eventos) e a task 3.2: envio concorrente por várias corrotinas, aplicação um de cada
 * vez, e `state` refletindo a saída do reducer depois de cada evento.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PickingActorTest {

  private val escoposCriados = mutableListOf<CoroutineScope>()

  @After
  fun encerrarEscopos() {
    escoposCriados.forEach { it.cancel() }
    escoposCriados.clear()
  }

  private val item =
      ItemEmAndamento(
          ordemId = ORDEM_ID,
          indiceLinha = 0,
          endereco = "R04-P12-N03-A05",
          itensRestantes = 0,
      )

  /**
   * Reducer de teste que conta aplicações. Se dois eventos fossem aplicados
   * concorrentemente ao mesmo estado, um dos incrementos se perderia — que é exatamente o
   * que o consumidor único do doc §4.3 existe para impedir.
   */
  private val contador: (PickingState, PickingEvent) -> PickingState = { estado, _ ->
    val atual = estado as PickingState.OrdemCarregada
    atual.copy(totalLinhas = atual.totalLinhas + 1)
  }

  /** Reducer de teste que acumula a identidade de cada evento na ordem em que foi aplicado. */
  private val acumulador: (PickingState, PickingEvent) -> PickingState = { estado, evento ->
    val atual = estado as PickingState.OrdemCarregada
    val marcador = (evento as PickingEvent.OrdemConfirmada).ordemId
    atual.copy(ordemId = atual.ordemId + marcador)
  }

  /** Scenario: Eventos são aplicados na ordem recebida */
  @Test
  fun `o segundo evento e aplicado sobre o resultado do primeiro, nunca intercalado`() = runTest {
    val actor = atorDeTeste(estadoVazio(), acumulador)

    actor.send(PickingEvent.OrdemConfirmada("A", 0))
    actor.send(PickingEvent.OrdemConfirmada("B", 0))
    advanceUntilIdle()

    // "AB" e não "BA" nem "A"/"B" sozinhos: o segundo evento viu o estado que o primeiro
    // produziu, inteiro e já publicado.
    assertEquals("AB", marcadores(actor))
  }

  @Test
  fun `uma sequencia longa de eventos preserva a ordem de envio`() = runTest {
    val actor = atorDeTeste(estadoVazio(), acumulador)

    val marcadores = ('a'..'z').map { it.toString() }
    marcadores.forEach { actor.send(PickingEvent.OrdemConfirmada(it, 0)) }
    advanceUntilIdle()

    assertEquals(marcadores.joinToString(separator = ""), marcadores(actor))
  }

  @Test
  fun `eventos enviados concorrentemente por varias corrotinas sao todos aplicados`() = runTest {
    val actor = atorDeTeste(PickingState.OrdemCarregada(ORDEM_ID, totalLinhas = 0), contador)

    // Produtores reais em threads reais — voz, visão e lifecycle do DAT postam de contextos
    // diferentes (doc §4.3, tabela de contextos).
    coroutineScope {
      repeat(PRODUTORES) {
        launch(Dispatchers.Default) {
          repeat(EVENTOS_POR_PRODUTOR) { actor.send(PickingEvent.ComandoRepetir) }
        }
      }
    }
    advanceUntilIdle()

    // Sem consumidor único, incrementos concorrentes se perderiam e este número viria menor.
    assertEquals(PRODUTORES * EVENTOS_POR_PRODUTOR, aplicacoes(actor))
  }

  @Test
  fun `send nunca bloqueia mesmo com o consumidor parado`() = runTest {
    val actor = atorDeTeste(PickingState.OrdemCarregada(ORDEM_ID, totalLinhas = 0), contador)

    // O consumidor só roda em advanceUntilIdle(): tudo abaixo entra num channel cujo
    // consumidor ainda não foi escalonado uma única vez. Com channel de rendezvous, este
    // laço penduraria no primeiro envio — e penduraria a thread de áudio junto (doc §4.2).
    repeat(RAJADA) { actor.send(PickingEvent.ComandoRepetir) }

    assertEquals(0, aplicacoes(actor))

    advanceUntilIdle()

    assertEquals(RAJADA, aplicacoes(actor))
  }

  @Test
  fun `state reflete a saida do reducer real depois de cada evento`() = runTest {
    val actor = atorDeTeste(PickingState.AguardandoOrdem)

    val passos: List<Pair<PickingEvent, PickingState>> =
        listOf(
            PickingEvent.OrdemConfirmada(ORDEM_ID, totalLinhas = 1) to
                PickingState.OrdemCarregada(ORDEM_ID, 1),
            PickingEvent.NavegacaoIniciada(item) to PickingState.NavegandoParaEndereco(item),
            PickingEvent.EnderecoAlcancado to
                PickingState.AguardandoCheckDigit(item, TipoCheckDigit.POSICAO),
            PickingEvent.CheckDigitCorreto to PickingState.EscaneandoProduto(item),
            PickingEvent.CapturaDisparada to PickingState.DecodificandoProduto(item),
            PickingEvent.DecodificacaoConcluida(GTIN) to
                PickingState.ValidandoContraDados(item, GTIN),
            PickingEvent.ValidacaoOk(quantidadeEsperada = 12) to
                PickingState.ConfirmandoQuantidade(item, 12),
            PickingEvent.QuantidadeInformada(12) to PickingState.ReadbackQuantidade(item, 12),
            PickingEvent.ReadbackConfirmado to PickingState.AlocandoCarrinho(item, 12),
            PickingEvent.ItemAlocado to PickingState.ItemConcluido(item),
            PickingEvent.ItemFinalizado() to PickingState.ConferenciaFinal(ORDEM_ID),
            PickingEvent.ConferenciaConcluida to PickingState.OrdemConcluida(ORDEM_ID),
        )

    passos.forEach { (evento, esperado) ->
      actor.send(evento)
      advanceUntilIdle()

      assertEquals("depois de $evento", esperado, actor.state.value)
    }
  }

  @Test
  fun `estado inicial e ocioso por padrao`() = runTest {
    val actor = atorDeTeste()

    assertEquals(PickingState.Ocioso, actor.state.value)
  }

  @Test
  fun `close drena o que ja foi enviado e encerra a corrotina consumidora`() = runTest {
    val actor = atorDeTeste(PickingState.OrdemCarregada(ORDEM_ID, totalLinhas = 0), contador)

    repeat(5) { actor.send(PickingEvent.ComandoRepetir) }
    actor.close()
    advanceUntilIdle()

    assertEquals(5, aplicacoes(actor))
    assertTrue("a corrotina consumidora deveria ter terminado", actor.job.isCompleted)

    // Depois de fechado, um evento atrasado é descartado sem lançar.
    actor.send(PickingEvent.ComandoRepetir)
    advanceUntilIdle()

    assertEquals(5, aplicacoes(actor))
  }

  /**
   * Um ator num escopo próprio, dirigido pelo relógio virtual do teste.
   *
   * Não usa o `backgroundScope` do `runTest` de propósito: `advanceUntilIdle()` para de
   * avançar assim que só resta trabalho de background, então um ator hospedado lá nunca
   * consumiria nada. Aqui o escopo é comum, e o `@After` o cancela.
   */
  private fun TestScope.atorDeTeste(
      estadoInicial: PickingState = PickingState.Ocioso,
      reducer: (PickingState, PickingEvent) -> PickingState = ::reduce,
  ): PickingActor {
    val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
    escoposCriados += scope
    return PickingActor(scope, estadoInicial, reducer)
  }

  private fun estadoVazio() = PickingState.OrdemCarregada(ordemId = "", totalLinhas = 0)

  private fun aplicacoes(actor: PickingActor) =
      (actor.state.value as PickingState.OrdemCarregada).totalLinhas

  private fun marcadores(actor: PickingActor) =
      (actor.state.value as PickingState.OrdemCarregada).ordemId

  private companion object {
    const val ORDEM_ID = "SEP-2026-004821"
    const val GTIN = "7896006200215"
    const val PRODUTORES = 8
    const val EVENTOS_POR_PRODUTOR = 250
    const val RAJADA = 10_000
  }
}
