package com.agvtronic.pickvoice.data

import com.agvtronic.pickvoice.data.mock.MockPickingRepository
import com.agvtronic.pickvoice.data.model.Coleta
import com.agvtronic.pickvoice.data.model.Conferencia
import com.agvtronic.pickvoice.data.model.Endereco
import com.agvtronic.pickvoice.data.model.Excecao
import com.agvtronic.pickvoice.data.model.Linha
import com.agvtronic.pickvoice.data.model.MetodoValidacao
import com.agvtronic.pickvoice.data.model.Operador
import com.agvtronic.pickvoice.data.model.Ordem
import com.agvtronic.pickvoice.data.model.ResumoOrdem
import com.agvtronic.pickvoice.domain.statemachine.MotivoExcecao
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Cobre os cenários de `openspec/changes/add-state-machine-and-mock-data/specs/`
 * `mock-picking-data/spec.md`, um teste por `#### Scenario`, mais o comportamento de
 * escrita do repositório (coleta, exceção, conferência).
 *
 * As asserções de formato são deliberadamente literais às convenções do WMS de produção
 * da AGV (contagem de dígitos, andar como letra, layout do código de barras do endereço,
 * dígito verificador de EAN-13 e DUN-14). É o que impede o dataset de voltar a ser
 * "plausível" em vez de estruturalmente real (doc §11.4).
 */
class MockPickingRepositoryTest {

  private val repositorio = MockPickingRepository()

  // -----------------------------------------------------------------------------------
  // Requirement: Zero chamada de rede pra dados de escopo WMS
  // -----------------------------------------------------------------------------------

  /**
   * Scenario: Ler uma ordem sem rede disponível
   *
   * Este teste roda como unit test de JVM, sem stack de rede do Android e sem servidor
   * algum no ar — qualquer acesso a rede aqui falharia ou penduraria. Ler operador, ordens
   * e a ordem completa funcionando é a evidência direta do cenário.
   */
  @Test
  fun `leitura funciona sem nenhuma conectividade de rede`() = runTest {
    val operador = repositorio.operadorAtual()
    val ordens = repositorio.ordensDisponiveis()
    val ordem = repositorio.ordem(ordens.first().id)

    assertTrue(operador.nome.isNotBlank())
    assertTrue(ordens.isNotEmpty())
    assertTrue(ordem.linhas.isNotEmpty())
    assertEquals(ordens.first().totalLinhas, ordem.linhas.size)
  }

  /**
   * Nenhum campo da implementação mockada é um cliente de rede.
   *
   * Complementa o teste acima com uma checagem estrutural: se alguém enfiar um OkHttp,
   * um Retrofit ou um `java.net.URL` na classe, este teste quebra antes de a chamada
   * chegar a acontecer em runtime.
   */
  @Test
  fun `a implementacao mockada nao declara nenhum campo de cliente de rede`() {
    val pacotesDeRede = listOf("java.net", "javax.net", "android.net", "okhttp3", "retrofit2", "io.ktor")

    MockPickingRepository::class.java.declaredFields.forEach { campo ->
      val nomeDoTipo = campo.type.name
      pacotesDeRede.forEach { pacote ->
        assertFalse(
            "campo ${campo.name} é do tipo de rede $nomeDoTipo",
            nomeDoTipo.startsWith("$pacote."),
        )
      }
    }
  }

  // -----------------------------------------------------------------------------------
  // Requirement: Dados mockados estruturalmente realistas
  // -----------------------------------------------------------------------------------

  /** Scenario: Ordem mockada bate com os formatos do WMS — cabeçalho da ordem. */
  @Test
  fun `toda ordem usa praca e pedido no formato do WMS`() = runTest {
    val resumos = repositorio.ordensDisponiveis()

    assertTrue("nenhuma ordem mockada", resumos.isNotEmpty())

    resumos.forEach { resumo ->
      // praça: código alfanumérico de 11 caracteres (wmsesto2.praca / wmsrf.praca).
      assertTrue(
          "praça ${resumo.praca} fora do formato",
          Regex("""^[0-9A-Z]{11}$""").matches(resumo.praca),
      )
      // pedido: numérico de 6 dígitos.
      assertTrue(
          "pedido ${resumo.pedido} fora do formato",
          Regex("""^\d{6}$""").matches(resumo.pedido),
      )
      assertEquals("${resumo.praca}-${resumo.pedido}", resumo.id)
      assertTrue("cliente vazio", resumo.cliente.isNotBlank())
    }
  }

  /** Scenario: Ordem mockada bate com os formatos do WMS — linhas de separação. */
  @Test
  fun `toda linha usa formatos reais de produto, ean, dun14, partida, ua e endereco`() = runTest {
    val linhas = todasAsLinhas()

    assertTrue("nenhuma linha mockada", linhas.isNotEmpty())

    linhas.forEach { linha ->
      val onde = "linha ${linha.produto}/${linha.recnum}"

      // produto (wmsprodu.cod): numérico curto de 6 dígitos — não "SKU-123".
      assertTrue("$onde: produto fora do formato", Regex("""^\d{6}$""").matches(linha.produto))

      // EAN-13 com prefixo GS1 Brasil e dígito verificador válido — o parser GS1 (§6.5)
      // e a cascata de decodificação vão ver exatamente esta forma em campo.
      assertTrue("$onde: ean não tem 13 dígitos", Regex("""^\d{13}$""").matches(linha.ean))
      assertTrue("$onde: ean sem prefixo GS1 Brasil", linha.ean.startsWith("789"))
      assertEquals(
          "$onde: dígito verificador do EAN-13 inválido",
          linha.ean.last(),
          digitoVerificadorGtin(linha.ean),
      )

      // DUN-14: indicador + os 12 dígitos base do EAN + dígito verificador recalculado.
      // O RF aceita ean OU dun14 como leitura válida da mesma linha, então o dataset
      // precisa dos dois consistentes entre si, não de dois números soltos.
      assertTrue("$onde: dun14 não tem 14 dígitos", Regex("""^\d{14}$""").matches(linha.dun14))
      assertTrue(
          "$onde: dígito indicador do DUN-14 fora de 1..8",
          linha.dun14.first() in '1'..'8',
      )
      assertEquals(
          "$onde: dun14 não deriva do ean da linha",
          linha.ean.take(12),
          linha.dun14.substring(1, 13),
      )
      assertEquals(
          "$onde: dígito verificador do DUN-14 inválido",
          linha.dun14.last(),
          digitoVerificadorGtin(linha.dun14),
      )

      // partida (wmsesto2.partida) — o lote da GS1 AI (10): numérico de 8 dígitos.
      assertTrue("$onde: partida fora do formato", Regex("""^\d{8}$""").matches(linha.partida))

      // Série numérica da AI (21).
      assertTrue("$onde: série fora do formato", Regex("""^\d{10}$""").matches(linha.serie))

      // UA (unidade de armazenagem) e recnum: numéricos, 8 dígitos.
      assertTrue("$onde: ua fora do formato", Regex("""^\d{8}$""").matches(linha.ua))
      assertTrue("$onde: recnum fora do formato", Regex("""^\d{8}$""").matches(linha.recnum))

      // Senha do endereço (wmscam2.senha_endereco): dois dígitos (doc §7.1).
      assertTrue(
          "$onde: senha do endereço fora do formato",
          Regex("""^\d{2}$""").matches(linha.senhaEndereco),
      )

      verificarEndereco(onde, linha.endereco)

      assertTrue("$onde: quantidade não positiva", linha.quantidade > 0)
      assertTrue("$onde: saldo menor que a quantidade pedida", linha.saldoEndereco >= linha.quantidade)
      assertTrue("$onde: descrição vazia", linha.descricao.isNotBlank())
      assertTrue("$onde: dir_stage vazio", linha.dirStage.isNotBlank())
    }
  }

  /**
   * O endereço é decomposto como o `wmscam2` decompõe, e o código de barras é montado
   * exatamente como o app de RF monta: `cd + setor + andar + predio(4) + rua`.
   */
  private fun verificarEndereco(onde: String, endereco: Endereco) {
    assertTrue("$onde: cd fora do formato", Regex("""^\d{2}$""").matches(endereco.cd))
    assertTrue("$onde: setor fora do formato", Regex("""^\d{2}$""").matches(endereco.setor))
    // andar é LETRA no WMS, não número — é o antigo "nível" do rascunho.
    assertTrue("$onde: andar não é uma letra", Regex("""^[A-Z]$""").matches(endereco.andar))
    // predio é guardado sem zeros à esquerda; quem preenche pra 4 é o código de barras.
    assertTrue("$onde: predio fora do formato", Regex("""^\d{1,4}$""").matches(endereco.predio))
    assertFalse("$onde: predio guardado com zero à esquerda", endereco.predio.startsWith("0"))
    assertTrue("$onde: rua fora do formato", Regex("""^[A-Z]+$""").matches(endereco.rua))

    assertEquals(
        "$onde: código de barras do endereço fora do layout do WMS",
        endereco.cd + endereco.setor + endereco.andar + endereco.predio.padStart(4, '0') + endereco.rua,
        endereco.codbarra,
    )
    assertTrue(
        "$onde: código de barras não bate com o layout cd/setor/andar/predio(4)/rua",
        Regex("""^\d{4}[A-Z]\d{4}[A-Z]+$""").matches(endereco.codbarra),
    )
  }

  @Test
  fun `a senha do endereco nao e derivavel do endereco`() = runTest {
    // Se a senha fosse função do endereço, o operador aprenderia a fórmula e confirmaria a
    // posição sem chegar lá — a verificação viraria teatro (doc §7.1). No WMS ela vem
    // cadastrada por posição em wmscam2, sem relação com os campos do endereço.
    todasAsLinhas().forEach { linha ->
      val camposDoEndereco =
          with(linha.endereco) { listOf(cd, setor, andar, predio, rua, codbarra) }

      assertFalse(
          "senha de ${linha.produto} repete um campo do endereço",
          linha.senhaEndereco in camposDoEndereco,
      )
    }
  }

  @Test
  fun `validades das linhas estao no futuro em relacao ao lote`() = runTest {
    // Produto farmacêutico separado hoje não sai do CD vencido; o dataset precisa refletir
    // isso para a validação contra dados fazer sentido.
    todasAsLinhas().forEach { linha ->
      assertTrue(
          "validade de ${linha.produto} é anterior a 2026",
          linha.validade.isAfter(LocalDate.of(2026, 1, 1)),
      )
    }
  }

  /**
   * `recnum` identifica a linha física de `wmsesto2`. Duas paradas com o mesmo recnum
   * seriam duplicata de verdade; duas paradas com recnums diferentes no mesmo endereço ou
   * com o mesmo produto+partida são rateio, que é comportamento esperado do WMS.
   */
  @Test
  fun `cada linha tem recnum proprio dentro da ordem`() = runTest {
    repositorio.ordensDisponiveis().forEach { resumo ->
      val recnums = repositorio.ordem(resumo.id).linhas.map { it.recnum }
      assertEquals("recnum repetido na ordem ${resumo.id}", recnums.size, recnums.toSet().size)
    }
  }

  /**
   * O dataset exercita os dois formatos de "parada repetida" que a operação reporta como
   * bug e que na verdade são comportamento do WMS — uma parada por linha, nunca por
   * endereço, e alocação rateada entre endereços.
   */
  @Test
  fun `o dataset cobre endereco repetido e produto rateado entre enderecos`() = runTest {
    val linhas = todasAsLinhas()

    val enderecoRepetido =
        linhas.groupBy { it.endereco.codbarra }.values.any { grupo -> grupo.size > 1 }
    assertTrue("nenhum endereço aparece em duas linhas", enderecoRepetido)

    val rateado =
        linhas
            .groupBy { it.produto to it.partida }
            .values
            .any { grupo -> grupo.map { it.endereco.codbarra }.toSet().size > 1 }
    assertTrue("nenhum produto+partida aparece em dois endereços", rateado)
  }

  // -----------------------------------------------------------------------------------
  // Requirement: Repositório trocável sem tocar nos consumidores
  // -----------------------------------------------------------------------------------

  /** Scenario: Trocando a implementação mockada */
  @Test
  fun `um consumidor da interface funciona igual com outra implementacao`() = runTest {
    // O consumidor só conhece PickingRepository — nem MockPickingRepository, nem a que
    // vier depois (HttpPickingRepository).
    val comMock = descreverPrimeiraLinha(repositorio)
    val comOutra = descreverPrimeiraLinha(RepositorioAlternativo)

    assertEquals("531884 @ 7204B0118D", comMock)
    assertEquals("999001 @ 0101A0001A", comOutra)
  }

  // -----------------------------------------------------------------------------------
  // Escrita: coleta, exceção e conferência
  // -----------------------------------------------------------------------------------

  @Test
  fun `ordem desconhecida falha com NoSuchElementException`() = runTest {
    try {
      repositorio.ordem("000A0000000-000000")
      fail("esperava NoSuchElementException")
    } catch (esperado: NoSuchElementException) {
      assertNotNull(esperado.message)
    }
  }

  @Test
  fun `coletas registradas fecham a conferencia como conforme`() = runTest {
    val ordem = repositorio.ordem(ORDEM_ID)

    ordem.linhas.forEachIndexed { indice, linha ->
      repositorio.registrarColeta(ORDEM_ID, indice, coletaDe(linha, linha.quantidade))
    }

    val conferencia = repositorio.fecharConferencia(ORDEM_ID)

    assertEquals(ordem.linhas.size, conferencia.totalLinhas)
    assertEquals(ordem.linhas.size, conferencia.linhasColetadas)
    assertEquals(emptyList<Int>(), conferencia.linhasDivergentes)
    assertTrue(conferencia.excecoes.isEmpty())
    assertTrue(conferencia.conforme)
  }

  @Test
  fun `quantidade coletada diferente da pedida aparece como linha divergente`() = runTest {
    val ordem = repositorio.ordem(ORDEM_ID)

    repositorio.registrarColeta(ORDEM_ID, 0, coletaDe(ordem.linhas[0], ordem.linhas[0].quantidade))
    repositorio.registrarColeta(
        ORDEM_ID,
        1,
        coletaDe(ordem.linhas[1], ordem.linhas[1].quantidade - 1),
    )

    val conferencia = repositorio.fecharConferencia(ORDEM_ID)

    assertEquals(listOf(1), conferencia.linhasDivergentes)
    assertFalse(conferencia.conforme)
  }

  @Test
  fun `excecao registrada aparece na conferencia`() = runTest {
    val excecao =
        Excecao(
            linha = 2,
            tipo = MotivoExcecao.RUPTURA,
            relatoTranscrito = "posicao vazia so tem tres caixas",
            quantidadeAproveitavel = 3,
            requerSupervisor = true,
            timestamp = Instant.now(),
        )

    repositorio.registrarExcecao(ORDEM_ID, excecao)

    val conferencia = repositorio.fecharConferencia(ORDEM_ID)

    assertEquals(listOf(excecao), conferencia.excecoes)
    assertFalse(conferencia.conforme)
  }

  @Test
  fun `registrar coleta em linha inexistente falha`() = runTest {
    try {
      repositorio.registrarColeta(ORDEM_ID, 99, coletaDe(repositorio.ordem(ORDEM_ID).linhas[0], 1))
      fail("esperava IllegalArgumentException")
    } catch (esperado: IllegalArgumentException) {
      assertNotNull(esperado.message)
    }
  }

  @Test
  fun `ordens nao compartilham estado de coleta`() = runTest {
    val outraOrdem = repositorio.ordensDisponiveis().last().id
    val linha = repositorio.ordem(ORDEM_ID).linhas[0]

    repositorio.registrarColeta(ORDEM_ID, 0, coletaDe(linha, linha.quantidade))

    assertEquals(0, repositorio.fecharConferencia(outraOrdem).linhasColetadas)
    assertEquals(1, repositorio.fecharConferencia(ORDEM_ID).linhasColetadas)
  }

  @Test
  fun `o resumo da ordem bate com as linhas`() = runTest {
    repositorio.ordensDisponiveis().forEach { resumo ->
      val ordem = repositorio.ordem(resumo.id)

      assertEquals(ordem.linhas.size, resumo.totalLinhas)
      assertEquals(ordem.linhas.sumOf { it.quantidade }, resumo.totalUnidades)
      assertEquals(ordem.cliente, resumo.cliente)
      assertEquals(ordem.praca, resumo.praca)
      assertEquals(ordem.pedido, resumo.pedido)
    }
  }

  private suspend fun todasAsLinhas(): List<Linha> =
      repositorio.ordensDisponiveis().flatMap { repositorio.ordem(it.id).linhas }

  /** Consumidor de exemplo: depende só da interface, nunca da implementação. */
  private suspend fun descreverPrimeiraLinha(repositorio: PickingRepository): String {
    val ordem = repositorio.ordem(repositorio.ordensDisponiveis().first().id)
    val linha = ordem.linhas.first()
    return "${linha.produto} @ ${linha.endereco.codbarra}"
  }

  private fun coletaDe(linha: Linha, quantidade: Int) =
      Coleta(
          quantidade = quantidade,
          partida = linha.partida,
          serie = linha.serie,
          timestamp = Instant.now(),
          metodoValidacao = MetodoValidacao.DATAMATRIX_STREAM,
          confianca = 0.98f,
          readbackConfirmado = true,
      )

  /**
   * Dígito verificador de GTIN (EAN-13 e DUN-14/GTIN-14 na mesma conta).
   *
   * Pesos 3/1 alternados **a partir da direita**, complemento para a próxima dezena. Fazer
   * a alternância pela direita é o que faz a mesma função servir para um payload de 12 e um
   * de 13 dígitos — a versão que alternava pela esquerda só valia pro EAN-13.
   */
  private fun digitoVerificadorGtin(gtin: String): Char {
    val payload = gtin.dropLast(1)
    val soma =
        payload.reversed().mapIndexed { indice, digito ->
          digito.digitToInt() * if (indice % 2 == 0) 3 else 1
        }
    return ((10 - soma.sum() % 10) % 10).digitToChar()
  }

  /**
   * Outra implementação da mesma interface. Existe só para provar que o consumidor acima
   * não muda quando a implementação muda — o que uma `HttpPickingRepository` vai precisar.
   */
  private object RepositorioAlternativo : PickingRepository {

    private val linha =
        Linha(
            produto = "999001",
            descricao = "PRODUTO DE TESTE",
            endereco = Endereco(cd = "01", setor = "01", andar = "A", predio = "1", rua = "A"),
            senhaEndereco = "99",
            ean = "7899999999999",
            dun14 = "17899999999996",
            partida = "99999999",
            serie = "0000000001",
            validade = LocalDate.of(2030, 1, 31),
            quantidade = 1,
            ua = "99999999",
            recnum = "99999999",
            saldoEndereco = 1,
            dirStage = "ST99",
        )

    private val ordem =
        Ordem(
            praca = "000A0000000",
            pedido = "000001",
            cliente = "Cliente Alternativo",
            linhas = listOf(linha),
        )

    override suspend fun operadorAtual(): Operador = Operador("OP-0001", "Operador Alternativo", "0001")

    override suspend fun ordensDisponiveis(): List<ResumoOrdem> = listOf(ordem.resumo)

    override suspend fun ordem(id: String): Ordem = ordem

    override suspend fun registrarColeta(ordemId: String, linha: Int, coleta: Coleta) = Unit

    override suspend fun registrarExcecao(ordemId: String, excecao: Excecao) = Unit

    override suspend fun fecharConferencia(ordemId: String): Conferencia =
        Conferencia(
            ordemId = ordemId,
            totalLinhas = 1,
            linhasColetadas = 0,
            linhasDivergentes = emptyList(),
            excecoes = emptyList(),
            fechadaEm = Instant.EPOCH,
        )
  }

  private companion object {
    const val ORDEM_ID = "274K5010000-408176"
  }
}
