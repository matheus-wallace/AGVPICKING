package com.agvtronic.pickvoice.audio

import com.agvtronic.pickvoice.data.PickingRepository
import com.agvtronic.pickvoice.data.mock.MockPickingRepository
import com.agvtronic.pickvoice.data.model.Coleta
import com.agvtronic.pickvoice.data.model.Excecao
import com.agvtronic.pickvoice.domain.statemachine.ItemEmAndamento
import com.agvtronic.pickvoice.domain.statemachine.PickingActor
import com.agvtronic.pickvoice.domain.statemachine.PickingEvent
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
 * A integração com o ator (task 4.1): fala -> intenção -> repositório -> `PickingEvent` ->
 * `reduce`, com o `MockPickingRepository` e o reducer de verdade no caminho.
 *
 * Aqui mora a prova do `#### Scenario: Ensaio hands-free completo` — uma ordem mockada de três
 * linhas concluída sem nenhum evento de botão de avanço — e do `#### Scenario: Resultado
 * atrasado não avança o próximo estado`.
 *
 * O que este teste **não** cobre é o Vosk: reconhecer áudio é bancada com voz humana
 * (tasks 4.3 e 4.4). O que entra aqui é o texto que o decodificador teria devolvido.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PublicadorDeVozTest {

  private val escoposCriados = mutableListOf<CoroutineScope>()

  @After
  fun encerrarEscopos() {
    escoposCriados.forEach { it.cancel() }
    escoposCriados.clear()
  }

  /** Scenario: Ensaio hands-free completo */
  @Test
  fun `uma ordem de tres linhas e concluida so por voz, camera e validacao`() = runTest {
    val bancada = bancada()

    // A seleção da ordem continua sendo toque (design.md - Decisão 4). Daqui em diante, não.
    bancada.tocar(PickingEvent.OrdemConfirmada(ORDEM_ID, totalLinhas = 3))
    assertEquals(PickingState.OrdemCarregada(ORDEM_ID, 3), bancada.estado())

    bancada.falar(VocabularioDeVoz.INICIAR)
    assertEquals(item(0), bancada.itemAtual())

    LINHAS.forEachIndexed { indice, linha ->
      assertTrue(
          "linha $indice deveria estar navegando",
          bancada.estado() is PickingState.NavegandoParaEndereco,
      )

      bancada.falar(VocabularioDeVoz.CHEGUEI)
      assertTrue(bancada.estado() is PickingState.AguardandoCheckDigit)

      bancada.falar(linha.checkDigitFalado)
      assertTrue(
          "check digit correto liga a câmera",
          bancada.estado() is PickingState.EscaneandoProduto,
      )

      // O código vem da câmera, nunca da voz.
      bancada.camera(PickingEvent.DecodificacaoConcluida(linha.ean))
      bancada.camera(PickingEvent.ValidacaoOk(linha.quantidade))
      assertTrue(bancada.estado() is PickingState.ConfirmandoQuantidade)

      bancada.falar(linha.quantidadeFalada)
      assertEquals(
          PickingState.ReadbackQuantidade(item(indice), linha.quantidade),
          bancada.estado(),
      )

      bancada.falar(VocabularioDeVoz.CONFIRMAR)
      assertEquals(PickingState.AlocandoCarrinho(item(indice), linha.quantidade), bancada.estado())

      bancada.falar(VocabularioDeVoz.ALOCADO)
      assertEquals(PickingState.ItemConcluido(item(indice)), bancada.estado())

      bancada.falar(VocabularioDeVoz.PROXIMO)
    }

    assertEquals(PickingState.ConferenciaFinal(ORDEM_ID), bancada.estado())

    bancada.falar(VocabularioDeVoz.CONCLUIR)
    assertEquals(PickingState.OrdemConcluida(ORDEM_ID), bancada.estado())

    bancada.falar(VocabularioDeVoz.ENCERRAR)
    assertEquals(PickingState.AguardandoOrdem, bancada.estado())

    // Nenhum botão de avanço: só a confirmação da ordem, a câmera e a validação.
    assertEquals(1, bancada.toques)
  }

  /** Scenario: Resultado atrasado não avança o próximo estado */
  @Test
  fun `resultado de uma versao anterior de estado e descartado`() = runTest {
    val bancada = bancada(PickingState.ConfirmandoQuantidade(item(0), quantidadeEsperada = 12))

    // A elocução começa em ConfirmandoQuantidade e é decodificada sob esta versão...
    val versaoDaFala = bancada.publicador.novaVersao()
    val estadoDaFala = bancada.estado()

    // ...mas o estado avança antes de o endpointer fechar.
    bancada.tocar(PickingEvent.QuantidadeInformada(12))
    bancada.publicador.novaVersao()
    assertEquals(PickingState.ReadbackQuantidade(item(0), 12), bancada.estado())

    val resultado = bancada.publicador.publicar(estadoDaFala, "quatro", versaoDaFala)
    advanceUntilIdle()

    assertEquals(
        "o resultado atrasado nem chega a ser interpretado",
        ResultadoDePublicacao.VersaoObsoleta,
        resultado,
    )
    assertEquals(PickingState.ReadbackQuantidade(item(0), 12), bancada.estado())
  }

  /**
   * Task 2.3: os dois motivos de descarte (task 2.2) são distinguíveis no retorno de
   * `publicar`, que é o dado que `ReconhecedorDeComando` loga — o `Log.i` em si não é
   * testável em JVM (mesmo padrão do gate `BuildConfig.DEBUG` do painel de dev), então a
   * verificação do texto do log em bancada fica para a task 4.2.
   */
  @Test
  fun `fora da gramatica e versao obsoleta sao descartes distintos e nenhum publica evento`() =
      runTest {
        val bancada = bancada(PickingState.ConfirmandoQuantidade(item(0), quantidadeEsperada = 12))

        val versaoDaFala = bancada.publicador.novaVersao()
        val estadoDaFala = bancada.estado()
        val foraDaGramatica = bancada.publicador.publicar(estadoDaFala, "abacate", versaoDaFala)
        advanceUntilIdle()

        assertEquals(ResultadoDePublicacao.ForaDaGramatica, foraDaGramatica)
        assertEquals(estadoDaFala, bancada.estado())

        // Estado avança antes do endpointer fechar: a versão que valia na fala virou obsoleta.
        bancada.tocar(PickingEvent.QuantidadeInformada(12))
        bancada.publicador.novaVersao()
        val estadoAposAvanco = bancada.estado()
        val versaoObsoleta =
            bancada.publicador.publicar(estadoDaFala, "doze", versaoDaFala)
        advanceUntilIdle()

        assertEquals(ResultadoDePublicacao.VersaoObsoleta, versaoObsoleta)
        assertEquals(estadoAposAvanco, bancada.estado())
      }

  @Test
  fun `fala fora do contrato do estado nao muda nada`() = runTest {
    val bancada = bancada(PickingState.NavegandoParaEndereco(item(0)))

    listOf("", "[unk]", "confirmar", "sete oito nove", "che").forEach { texto ->
      bancada.falar(texto)
      assertEquals(PickingState.NavegandoParaEndereco(item(0)), bancada.estado())
    }
  }

  @Test
  fun `check digit divergente volta a navegar sem revelar o valor`() = runTest {
    val bancada = bancada(PickingState.NavegandoParaEndereco(item(0)))

    bancada.falar(VocabularioDeVoz.CHEGUEI)
    bancada.falar("quatro oito")

    assertEquals(PickingState.NavegandoParaEndereco(item(0)), bancada.estado())
  }

  /** Task 3.3: falha na resolução deixa o estado intacto e não derruba a voz. */
  @Test
  fun `falha ao consultar o repositorio nao muda o estado nem mata o publicador`() = runTest {
    val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
    escoposCriados += scope
    val actor = PickingActor(scope, PickingState.ItemConcluido(item(0)))
    val falhas = mutableListOf<Throwable>()
    val publicador =
        PublicadorDeVoz(
                actor,
                ResolvedorDeIntencao(RepositorioQueFalha()),
                scope,
                aoFalhar = { falhas += it },
            )
            .apply { iniciar() }

    publicador.publicar(actor.state.value, VocabularioDeVoz.PROXIMO, publicador.novaVersao())
    advanceUntilIdle()

    assertEquals(PickingState.ItemConcluido(item(0)), actor.state.value)
    assertEquals(1, falhas.size)

    // A corrotina de resolução continua viva: o comando seguinte ainda é atendido.
    publicador.publicar(actor.state.value, VocabularioDeVoz.PARAR, publicador.novaVersao())
    advanceUntilIdle()

    assertTrue(actor.state.value is PickingState.SessaoPausada)
  }

  @Test
  fun `parar pausa e retomar volta ao mesmo item`() = runTest {
    val bancada = bancada(PickingState.NavegandoParaEndereco(item(0)))

    bancada.falar(VocabularioDeVoz.PARAR)
    assertTrue(bancada.estado() is PickingState.SessaoPausada)

    bancada.falar(VocabularioDeVoz.RETOMAR)
    assertEquals(PickingState.NavegandoParaEndereco(item(0)), bancada.estado())
  }

  // -----------------------------------------------------------------------------------
  // Bancada
  // -----------------------------------------------------------------------------------

  private fun TestScope.bancada(
      estadoInicial: PickingState = PickingState.AguardandoOrdem
  ): Bancada {
    val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
    escoposCriados += scope
    val actor = PickingActor(scope, estadoInicial)
    val publicador =
        PublicadorDeVoz(actor, ResolvedorDeIntencao(RepositorioDeBancada()), scope).apply {
          iniciar()
        }
    return Bancada(this, actor, publicador)
  }

  /**
   * O `MockPickingRepository` de verdade, sem o salto de dispatcher que o tempo virtual do teste
   * não enxerga.
   *
   * O mock troca para `Dispatchers.Default` em toda leitura (é a tabela de contextos do doc
   * §4.3), e `advanceUntilIdle()` só conhece a fila do escalonador do teste: a resolução
   * terminaria depois da asserção. O `runBlocking` prende a thread do teste até a leitura voltar,
   * o que torna a sequência determinística sem trocar o dado por um duplo — a integração com o
   * mock em si é o que o `ResolvedorDeIntencaoTest` cobre.
   */
  private class RepositorioDeBancada(private val real: MockPickingRepository = MockPickingRepository()) :
      PickingRepository {

    override suspend fun operadorAtual() = runBlocking { real.operadorAtual() }

    override suspend fun ordensDisponiveis() = runBlocking { real.ordensDisponiveis() }

    override suspend fun ordem(id: String) = runBlocking { real.ordem(id) }

    override suspend fun registrarColeta(ordemId: String, linha: Int, coleta: Coleta) = runBlocking {
      real.registrarColeta(ordemId, linha, coleta)
    }

    override suspend fun registrarExcecao(ordemId: String, excecao: Excecao) = runBlocking {
      real.registrarExcecao(ordemId, excecao)
    }

    override suspend fun fecharConferencia(ordemId: String) = runBlocking {
      real.fecharConferencia(ordemId)
    }
  }

  /** O WMS fora do ar, quando ele deixar de ser um mapa em memória. */
  private class RepositorioQueFalha : PickingRepository {

    override suspend fun operadorAtual() = throw IllegalStateException("indisponível")

    override suspend fun ordensDisponiveis() = throw IllegalStateException("indisponível")

    override suspend fun ordem(id: String) = throw IllegalStateException("indisponível")

    override suspend fun registrarColeta(ordemId: String, linha: Int, coleta: Coleta) =
        throw IllegalStateException("indisponível")

    override suspend fun registrarExcecao(ordemId: String, excecao: Excecao) =
        throw IllegalStateException("indisponível")

    override suspend fun fecharConferencia(ordemId: String) =
        throw IllegalStateException("indisponível")
  }

  /**
   * O que o `ReconhecedorDeComando` faria no aparelho, sem Vosk e sem microfone.
   *
   * [falar] repete o ciclo real: uma versão nova por transição observada, o texto publicado sob
   * a versão que valia quando a elocução foi decodificada.
   */
  private class Bancada(
      private val teste: TestScope,
      val actor: PickingActor,
      val publicador: PublicadorDeVoz,
  ) {
    var toques = 0
      private set

    fun estado(): PickingState = actor.state.value

    fun itemAtual(): ItemEmAndamento? = actor.state.value.itemEmAndamento

    fun falar(texto: String) {
      val versao = publicador.novaVersao()
      publicador.publicar(actor.state.value, texto, versao)
      teste.advanceUntilIdle()
    }

    /** Um botão do painel de dev. Contado para provar que o fluxo normal não precisa deles. */
    fun tocar(evento: PickingEvent) {
      toques++
      actor.send(evento)
      teste.advanceUntilIdle()
    }

    /** Um evento do pipeline de visão/validação — não é toque nem voz. */
    fun camera(evento: PickingEvent) {
      actor.send(evento)
      teste.advanceUntilIdle()
    }
  }

  private data class LinhaDeBancada(
      val checkDigitFalado: String,
      val ean: String,
      val quantidade: Int,
      val quantidadeFalada: String,
      val endereco: String,
  )

  private companion object {
    const val ORDEM_ID = "274K5010000-408176"

    val LINHAS =
        listOf(
            LinhaDeBancada("quatro sete", "7896523202204", 12, "doze", "Rua D, prédio 118, andar B"),
            LinhaDeBancada("quatro sete", "7891456120779", 4, "quatro", "Rua D, prédio 118, andar B"),
            LinhaDeBancada("oito dois", "7890243719035", 30, "trinta", "Rua G, prédio 233, andar C"),
        )

    fun item(indice: Int) =
        ItemEmAndamento(
            ordemId = ORDEM_ID,
            indiceLinha = indice,
            endereco = LINHAS[indice].endereco,
            itensRestantes = LINHAS.lastIndex - indice,
        )
  }
}
