package com.agvtronic.pickvoice.vision

import com.agvtronic.pickvoice.data.PickingRepository
import com.agvtronic.pickvoice.data.mock.MockPickingRepository
import com.agvtronic.pickvoice.domain.statemachine.CODIGO_CHECK_DIGIT_PRODUTO
import com.agvtronic.pickvoice.domain.statemachine.ItemEmAndamento
import com.agvtronic.pickvoice.domain.statemachine.MotivoExcecao
import com.agvtronic.pickvoice.domain.statemachine.MotivoPausa
import com.agvtronic.pickvoice.domain.statemachine.PickingActor
import com.agvtronic.pickvoice.domain.statemachine.PickingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A validação automática ponta a ponta: `ValidandoContraDados` -> repositório -> `PickingEvent` ->
 * `reduce`, com o `MockPickingRepository` e o reducer de verdade no caminho.
 *
 * O que este teste **não** cobre é a câmera: ler o código da caixa física é bancada (tasks 3.3 e
 * 3.4). O que entra aqui é o texto que a cascata de decodificação teria devolvido.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComparadorDeCodigoTest {

  private val escoposCriados = mutableListOf<CoroutineScope>()

  @After
  fun encerrarEscopos() {
    escoposCriados.forEach { it.cancel() }
    escoposCriados.clear()
  }

  /** Scenario: Código bate com o EAN esperado */
  @Test
  fun `codigo igual ao ean da linha avanca para a quantidade sem toque`() = runTest {
    val bancada = bancada(PickingState.ValidandoContraDados(item(0), EAN_LINHA_0))

    bancada.observar()

    assertEquals(PickingState.ConfirmandoQuantidade(item(0), QUANTIDADE_LINHA_0), bancada.estado())
  }

  @Test
  fun `a comparacao usa a linha em curso, nao a primeira da ordem`() = runTest {
    // O EAN da primeira linha não pode validar a terceira: seria confirmação cega por descuido.
    val bancada = bancada(PickingState.ValidandoContraDados(item(2), EAN_LINHA_0))

    bancada.observar()

    assertEquals(
        PickingState.TratandoExcecao(MotivoExcecao.DIVERGENCIA, item(2)),
        bancada.estado(),
    )
  }

  /** Scenario: Código diverge do EAN esperado */
  @Test
  fun `codigo diferente do ean vira excecao de divergencia`() = runTest {
    val bancada = bancada(PickingState.ValidandoContraDados(item(0), "7891234567895"))

    bancada.observar()

    assertEquals(
        PickingState.TratandoExcecao(MotivoExcecao.DIVERGENCIA, item(0)),
        bancada.estado(),
    )
    // O evento não carrega o valor esperado, e o log também não.
    assertTrue(bancada.registros.none { it.contains(EAN_LINHA_0) })
  }

  /** Scenario: ValidandoContraDados alcançado via check digit de produto */
  @Test
  fun `sentinela do check digit de produto valida sem comparar contra o ean`() = runTest {
    val bancada =
        bancada(PickingState.ValidandoContraDados(item(0), CODIGO_CHECK_DIGIT_PRODUTO))

    bancada.observar()

    // O produto já foi confirmado pelos dois últimos dígitos do lote falado (doc §7.2): comparar
    // o sentinela contra o EAN faria este caminho divergir sempre.
    assertEquals(PickingState.ConfirmandoQuantidade(item(0), QUANTIDADE_LINHA_0), bancada.estado())
  }

  /** Scenario: Estado muda durante a consulta ao repositório */
  @Test
  fun `resultado que chega depois de o estado mudar nao e publicado`() = runTest {
    val validando = PickingState.ValidandoContraDados(item(0), EAN_LINHA_0)
    // O operador disse "parar" enquanto a consulta ao repositório estava em curso.
    val pausado = PickingState.SessaoPausada(validando, MotivoPausa.COMANDO_PARAR)
    val bancada = bancada(pausado)

    bancada.comparador.comparar(validando)
    advanceUntilIdle()

    assertEquals(pausado, bancada.estado())
  }

  @Test
  fun `linha inexistente nao publica evento e registra a tentativa`() = runTest {
    val fantasma = ItemEmAndamento("000A0000000-000000", 0, "Rua Z", itensRestantes = 0)
    val estado = PickingState.ValidandoContraDados(fantasma, EAN_LINHA_0)
    val bancada = bancada(estado)

    bancada.observar()

    assertEquals(estado, bancada.estado())
    assertTrue(bancada.registros.any { it.contains("linha_nao_encontrada") })
  }

  // -----------------------------------------------------------------------------------
  // Bancada
  // -----------------------------------------------------------------------------------

  private fun TestScope.bancada(estadoInicial: PickingState): Bancada {
    val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
    escoposCriados += scope
    val actor = PickingActor(scope, estadoInicial)
    val registros = mutableListOf<String>()
    val comparador =
        ComparadorDeCodigo(actor, RepositorioDeBancada(), scope, aoRegistrar = { registros += it })
    return Bancada(this, actor, comparador, registros)
  }

  /**
   * O `MockPickingRepository` de verdade, sem o salto de dispatcher que o tempo virtual do teste
   * não enxerga — mesmo motivo (e mesmo remédio) do `PublicadorDeVozTest`: o mock troca para
   * `Dispatchers.Default` em toda leitura, e `advanceUntilIdle()` só conhece a fila do
   * escalonador do teste.
   */
  private class RepositorioDeBancada(private val real: PickingRepository = MockPickingRepository()) :
      PickingRepository by real {

    override suspend fun ordem(id: String) = runBlocking { real.ordem(id) }
  }

  private class Bancada(
      private val teste: TestScope,
      val actor: PickingActor,
      val comparador: ComparadorDeCodigo,
      val registros: List<String>,
  ) {

    fun estado(): PickingState = actor.state.value

    /** Liga a observação do ator e deixa o evento que dela sair ser processado. */
    fun observar() {
      comparador.iniciar()
      teste.advanceUntilIdle()
    }
  }

  private companion object {
    const val ORDEM_ID = "274K5010000-408176"
    const val EAN_LINHA_0 = "7896523202204"
    const val QUANTIDADE_LINHA_0 = 12

    val ENDERECOS =
        listOf(
            "Rua D, prédio 118, andar B",
            "Rua D, prédio 118, andar B",
            "Rua G, prédio 233, andar C",
        )

    fun item(indice: Int) =
        ItemEmAndamento(
            ordemId = ORDEM_ID,
            indiceLinha = indice,
            endereco = ENDERECOS[indice],
            itensRestantes = ENDERECOS.lastIndex - indice,
        )
  }
}
