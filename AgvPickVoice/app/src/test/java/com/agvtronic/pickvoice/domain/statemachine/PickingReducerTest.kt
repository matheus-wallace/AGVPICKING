package com.agvtronic.pickvoice.domain.statemachine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cobre os cenários de `openspec/changes/add-state-machine-and-mock-data/specs/`
 * `picking-state-machine/spec.md`, um teste por `#### Scenario`, mais a cobertura das
 * demais transições do diagrama do doc §3.2.
 */
class PickingReducerTest {

  private val item =
      ItemEmAndamento(
          ordemId = ORDEM_ID,
          indiceLinha = 0,
          endereco = "R04-P12-N03-A05",
          itensRestantes = 2,
      )

  private val ultimoItem = item.copy(indiceLinha = 2, itensRestantes = 0)

  // -----------------------------------------------------------------------------------
  // Requirement: Transições de estado determinísticas
  // -----------------------------------------------------------------------------------

  /** Scenario: Confirmação de ordem inicia navegação */
  @Test
  fun `confirmacao de ordem carrega a ordem e o inicio de navegacao move para o endereco`() {
    val carregada = reduce(PickingState.AguardandoOrdem, PickingEvent.OrdemConfirmada(ORDEM_ID, 3))

    assertEquals(PickingState.OrdemCarregada(ORDEM_ID, totalLinhas = 3), carregada)

    val navegando = reduce(carregada, PickingEvent.NavegacaoIniciada(item))

    assertEquals(PickingState.NavegandoParaEndereco(item), navegando)
  }

  /** Scenario: Check digit errado repete a navegação */
  @Test
  fun `check digit errado volta a navegar e repete o mesmo endereco`() {
    val aguardando = PickingState.AguardandoCheckDigit(item, TipoCheckDigit.POSICAO)

    val depois = reduce(aguardando, PickingEvent.CheckDigitIncorreto)

    assertEquals(PickingState.NavegandoParaEndereco(item), depois)
    // O endereço repetido é o mesmo de antes — o operador é reconduzido à mesma posição,
    // e o valor correto nunca é revelado (doc §7.1).
    assertEquals(item.endereco, depois.itemEmAndamento?.endereco)
  }

  /** Scenario: Check digit correto inicia o escaneamento */
  @Test
  fun `check digit de posicao correto inicia o escaneamento do produto`() {
    val aguardando = PickingState.AguardandoCheckDigit(item, TipoCheckDigit.POSICAO)

    val depois = reduce(aguardando, PickingEvent.CheckDigitCorreto)

    assertEquals(PickingState.EscaneandoProduto(item), depois)
  }

  /** Scenario: Validação divergente encaminha pro tratamento de exceção */
  @Test
  fun `validacao divergente encaminha para tratamento de excecao preservando o item`() {
    val validando = PickingState.ValidandoContraDados(item, codigoLido = "7896006200215")

    val depois =
        reduce(validando, PickingEvent.ValidacaoDivergente(MotivoExcecao.DIVERGENCIA))

    assertEquals(PickingState.TratandoExcecao(MotivoExcecao.DIVERGENCIA, item), depois)
  }

  /** Scenario: Correção de quantidade volta pra confirmação */
  @Test
  fun `corrigir durante o readback volta para a confirmacao de quantidade`() {
    val readback = PickingState.ReadbackQuantidade(item, quantidadeInformada = 12)

    val depois = reduce(readback, PickingEvent.ReadbackCorrecaoSolicitada)

    assertEquals(PickingState.ConfirmandoQuantidade(item, quantidadeEsperada = 12), depois)
  }

  /** Scenario: Conclusão do último item move pra conferência final */
  @Test
  fun `concluir o ultimo item move para a conferencia final`() {
    val concluido = PickingState.ItemConcluido(ultimoItem)

    val depois = reduce(concluido, PickingEvent.ItemFinalizado(proximoItem = null))

    assertEquals(PickingState.ConferenciaFinal(ORDEM_ID), depois)
  }

  // -----------------------------------------------------------------------------------
  // Requirement: Eventos transversais têm precedência sobre qualquer estado operacional
  // -----------------------------------------------------------------------------------

  /** Scenario: Parada de emergência a partir de qualquer estado operacional */
  @Test
  fun `emergencia pausa a sessao a partir de qualquer estado operacional`() {
    val operacionais = estadosOperacionais()

    // Vale a pena falhar alto aqui: se um estado novo entrar na tabela do §3.1 e não for
    // listado, o teste deixa de cobrir o que diz cobrir.
    assertEquals(15, operacionais.size)

    operacionais.forEach { estado ->
      val depois = reduce(estado, PickingEvent.ComandoEmergencia)

      assertEquals(
          "emergência a partir de $estado",
          PickingState.SessaoPausada(estado, MotivoPausa.EMERGENCIA),
          depois,
      )
    }
  }

  /** Scenario: Perda de Bluetooth preserva o item em andamento */
  @Test
  fun `perda de bluetooth vai para erro preservando o item em andamento`() {
    val emCurso = PickingState.ConfirmandoQuantidade(item, quantidadeEsperada = 12)

    val depois = reduce(emCurso, PickingEvent.ConexaoBluetoothPerdida)

    assertEquals(
        PickingState.Erro(CausaErro.BLUETOOTH_DESCONECTADO, estadoAnterior = emCurso),
        depois,
    )
    // O item permanece identificado para retomada.
    assertEquals(item, depois.itemEmAndamento)
    assertNotNull(depois.itemEmAndamento)

    // ...e a retomada volta exatamente ao mesmo ponto do fluxo.
    assertEquals(emCurso, reduce(depois, PickingEvent.ConexaoBluetoothRestabelecida))
  }

  // -----------------------------------------------------------------------------------
  // Demais transversais do doc §3.3
  // -----------------------------------------------------------------------------------

  @Test
  fun `comando parar pausa a sessao e a retomada volta ao estado anterior`() {
    val escaneando = PickingState.EscaneandoProduto(item)

    val pausada = reduce(escaneando, PickingEvent.ComandoParar)

    assertEquals(PickingState.SessaoPausada(escaneando, MotivoPausa.COMANDO_PARAR), pausada)
    assertEquals(escaneando, reduce(pausada, PickingEvent.SessaoRetomada))
  }

  @Test
  fun `pausa do dat pausa a sessao independente do gatilho`() {
    val navegando = PickingState.NavegandoParaEndereco(item)

    GatilhoPausaDat.entries.forEach { gatilho ->
      assertEquals(
          "gatilho $gatilho",
          PickingState.SessaoPausada(navegando, MotivoPausa.LIFECYCLE_DAT),
          reduce(navegando, PickingEvent.PausaDat(gatilho)),
      )
    }
  }

  @Test
  fun `repetir nao muda de estado`() {
    estadosOperacionais().forEach { estado ->
      assertSame("repetir a partir de $estado", estado, reduce(estado, PickingEvent.ComandoRepetir))
    }
  }

  @Test
  fun `gatilho de excecao por voz move para tratamento de excecao a partir de qualquer estado`() {
    listOf(MotivoExcecao.AVARIA, MotivoExcecao.RUPTURA, MotivoExcecao.DIVERGENCIA).forEach {
        motivo ->
      estadosOperacionais().forEach { estado ->
        assertEquals(
            "$motivo a partir de $estado",
            PickingState.TratandoExcecao(motivo, estado.itemEmAndamento),
            reduce(estado, PickingEvent.ExcecaoSolicitada(motivo)),
        )
      }
    }
  }

  @Test
  fun `transversais nao se aplicam a estados nao operacionais`() {
    val naoOperacionais =
        listOf(
            PickingState.Ocioso,
            PickingState.Registrando,
            PickingState.PreparandoSessao,
            PickingState.SessaoPausada(PickingState.AguardandoOrdem, MotivoPausa.COMANDO_PARAR),
            PickingState.Erro(CausaErro.BLUETOOTH_DESCONECTADO),
        )

    naoOperacionais.forEach { estado ->
      assertTrue("$estado deveria ser não operacional", !estado.ehOperacional)
      assertEquals("parar a partir de $estado", estado, reduce(estado, PickingEvent.ComandoParar))
    }
  }

  // -----------------------------------------------------------------------------------
  // Fluxo principal completo — diagrama do doc §3.2
  // -----------------------------------------------------------------------------------

  @Test
  fun `ciclo de vida da sessao vai de ocioso ate aguardando ordem`() {
    var estado: PickingState = PickingState.Ocioso

    estado = reduce(estado, PickingEvent.RegistroIniciado)
    assertEquals(PickingState.Registrando, estado)

    estado = reduce(estado, PickingEvent.RegistroConcluido)
    assertEquals(PickingState.PreparandoSessao, estado)

    estado = reduce(estado, PickingEvent.SessaoPreparada)
    assertEquals(PickingState.AguardandoOrdem, estado)
  }

  @Test
  fun `falha de registro e de sessao vao para erro`() {
    assertEquals(
        PickingState.Erro(CausaErro.FALHA_REGISTRO, detalhe = "deeplink cancelado"),
        reduce(PickingState.Registrando, PickingEvent.RegistroFalhou("deeplink cancelado")),
    )
    assertEquals(
        PickingState.Erro(CausaErro.FALHA_SESSAO, detalhe = "device indisponível"),
        reduce(PickingState.PreparandoSessao, PickingEvent.SessaoFalhou("device indisponível")),
    )
  }

  @Test
  fun `caminho feliz percorre o fluxo principal de ponta a ponta`() {
    var estado: PickingState = PickingState.AguardandoOrdem

    estado = reduce(estado, PickingEvent.OrdemConfirmada(ORDEM_ID, totalLinhas = 1))
    assertEquals(PickingState.OrdemCarregada(ORDEM_ID, 1), estado)

    estado = reduce(estado, PickingEvent.NavegacaoIniciada(ultimoItem))
    assertEquals(PickingState.NavegandoParaEndereco(ultimoItem), estado)

    estado = reduce(estado, PickingEvent.EnderecoAlcancado)
    assertEquals(
        PickingState.AguardandoCheckDigit(ultimoItem, TipoCheckDigit.POSICAO),
        estado,
    )

    estado = reduce(estado, PickingEvent.CheckDigitCorreto)
    assertEquals(PickingState.EscaneandoProduto(ultimoItem), estado)

    estado = reduce(estado, PickingEvent.CapturaDisparada)
    assertEquals(PickingState.DecodificandoProduto(ultimoItem), estado)

    estado = reduce(estado, PickingEvent.DecodificacaoConcluida("7896006200215"))
    assertEquals(PickingState.ValidandoContraDados(ultimoItem, "7896006200215"), estado)

    estado = reduce(estado, PickingEvent.ValidacaoOk(quantidadeEsperada = 12))
    assertEquals(PickingState.ConfirmandoQuantidade(ultimoItem, 12), estado)

    estado = reduce(estado, PickingEvent.QuantidadeInformada(12))
    assertEquals(PickingState.ReadbackQuantidade(ultimoItem, 12), estado)

    estado = reduce(estado, PickingEvent.ReadbackConfirmado)
    assertEquals(PickingState.AlocandoCarrinho(ultimoItem, 12), estado)

    estado = reduce(estado, PickingEvent.ItemAlocado)
    assertEquals(PickingState.ItemConcluido(ultimoItem), estado)

    estado = reduce(estado, PickingEvent.ItemFinalizado())
    assertEquals(PickingState.ConferenciaFinal(ORDEM_ID), estado)

    estado = reduce(estado, PickingEvent.ConferenciaConcluida)
    assertEquals(PickingState.OrdemConcluida(ORDEM_ID), estado)

    estado = reduce(estado, PickingEvent.OrdemEncerrada)
    assertEquals(PickingState.AguardandoOrdem, estado)
  }

  @Test
  fun `restando itens a conclusao de um item volta a navegar para o proximo`() {
    val proximo = item.copy(indiceLinha = 1, endereco = "R04-P15-N01-A02", itensRestantes = 1)

    val depois =
        reduce(PickingState.ItemConcluido(item), PickingEvent.ItemFinalizado(proximo))

    assertEquals(PickingState.NavegandoParaEndereco(proximo), depois)
  }

  @Test
  fun `leitura pelo stream conclui o escaneamento sem passar por decodificando`() {
    // Passo 1 da cascata (doc §6.2): o stream decodificou, então não houve foto — e sem foto
    // não há o que "decodificar" num estado à parte.
    val depois =
        reduce(
            PickingState.EscaneandoProduto(item),
            PickingEvent.DecodificacaoConcluida("7896006200215"),
        )

    assertEquals(PickingState.ValidandoContraDados(item, "7896006200215"), depois)
  }

  @Test
  fun `o caminho por foto continua passando por decodificando`() {
    // A transição acima não pode ter atalhado o caminho de escalonamento do doc §6.3: quando o
    // gatilho de captura dispara, o estado intermediário continua existindo — é ele que
    // distingue "leu pelo stream" de "precisou de foto" no log de calibração do §4.5.
    var estado: PickingState = PickingState.EscaneandoProduto(item)

    estado = reduce(estado, PickingEvent.CapturaDisparada)
    assertEquals(PickingState.DecodificandoProduto(item), estado)

    estado = reduce(estado, PickingEvent.DecodificacaoConcluida("7896006200215"))
    assertEquals(PickingState.ValidandoContraDados(item, "7896006200215"), estado)
  }

  @Test
  fun `falha de decodificacao cai para verificacao assistida`() {
    val depois =
        reduce(PickingState.DecodificandoProduto(item), PickingEvent.DecodificacaoFalhou)

    assertEquals(PickingState.VerificacaoAssistida(item), depois)
  }

  @Test
  fun `verificacao assistida bem sucedida segue para a validacao contra os dados`() {
    val depois =
        reduce(
            PickingState.VerificacaoAssistida(item),
            PickingEvent.VerificacaoAssistidaConcluida("7891456070326"),
        )

    assertEquals(PickingState.ValidandoContraDados(item, "7891456070326"), depois)
  }

  @Test
  fun `sem rede a verificacao assistida degrada para o check digit do produto`() {
    val depois =
        reduce(
            PickingState.VerificacaoAssistida(item),
            PickingEvent.VerificacaoAssistidaIndisponivel,
        )

    assertEquals(PickingState.AguardandoCheckDigit(item, TipoCheckDigit.PRODUTO), depois)

    // E, confirmado por voz, o produto segue direto para a validação — não volta a
    // escanear, porque a câmera já falhou nesta caixa (doc §6.3/§7.2).
    assertEquals(
        PickingState.ValidandoContraDados(item, CODIGO_CHECK_DIGIT_PRODUTO),
        reduce(depois, PickingEvent.CheckDigitCorreto),
    )
  }

  @Test
  fun `excecao registrada com item em andamento conclui o item`() {
    val tratando = PickingState.TratandoExcecao(MotivoExcecao.RUPTURA, item)

    assertEquals(
        PickingState.ItemConcluido(item),
        reduce(tratando, PickingEvent.ExcecaoRegistrada),
    )
  }

  @Test
  fun `excecao registrada sem item volta para a selecao de ordem`() {
    val tratando = PickingState.TratandoExcecao(MotivoExcecao.AVARIA, itemEmAndamento = null)

    assertEquals(
        PickingState.AguardandoOrdem,
        reduce(tratando, PickingEvent.ExcecaoRegistrada),
    )
  }

  @Test
  fun `evento que nao se aplica ao estado atual devolve o mesmo estado`() {
    val escaneando = PickingState.EscaneandoProduto(item)

    assertSame(escaneando, reduce(escaneando, PickingEvent.ReadbackConfirmado))
    assertSame(escaneando, reduce(escaneando, PickingEvent.ConferenciaConcluida))
    assertSame(PickingState.Ocioso, reduce(PickingState.Ocioso, PickingEvent.CheckDigitCorreto))
  }

  @Test
  fun `reduce e puro - mesma entrada produz sempre a mesma saida`() {
    val entrada = PickingState.ReadbackQuantidade(item, quantidadeInformada = 7)

    val primeira = reduce(entrada, PickingEvent.ReadbackConfirmado)
    val segunda = reduce(entrada, PickingEvent.ReadbackConfirmado)
    val terceira = reduce(entrada, PickingEvent.ReadbackConfirmado)

    assertEquals(primeira, segunda)
    assertEquals(segunda, terceira)
  }

  /** Os 15 estados operacionais da tabela do doc §3.1 (todos menos os 5 não operacionais). */
  private fun estadosOperacionais(): List<PickingState> =
      listOf(
          PickingState.AguardandoOrdem,
          PickingState.OrdemCarregada(ORDEM_ID, totalLinhas = 3),
          PickingState.NavegandoParaEndereco(item),
          PickingState.AguardandoCheckDigit(item, TipoCheckDigit.POSICAO),
          PickingState.EscaneandoProduto(item),
          PickingState.DecodificandoProduto(item),
          PickingState.VerificacaoAssistida(item),
          PickingState.ValidandoContraDados(item, codigoLido = "7896006200215"),
          PickingState.ConfirmandoQuantidade(item, quantidadeEsperada = 12),
          PickingState.ReadbackQuantidade(item, quantidadeInformada = 12),
          PickingState.AlocandoCarrinho(item, quantidadeColetada = 12),
          PickingState.ItemConcluido(item),
          PickingState.TratandoExcecao(MotivoExcecao.AVARIA, item),
          PickingState.ConferenciaFinal(ORDEM_ID),
          PickingState.OrdemConcluida(ORDEM_ID),
      )

  private companion object {
    const val ORDEM_ID = "SEP-2026-004821"
  }
}
