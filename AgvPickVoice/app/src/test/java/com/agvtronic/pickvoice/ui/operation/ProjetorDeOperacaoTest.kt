package com.agvtronic.pickvoice.ui.operation

import com.agvtronic.pickvoice.audio.output.DiagnosticoSaidaAudio
import com.agvtronic.pickvoice.audio.output.EstadoSaidaAudio
import com.agvtronic.pickvoice.data.model.Endereco
import com.agvtronic.pickvoice.data.model.Linha
import com.agvtronic.pickvoice.data.model.Ordem
import com.agvtronic.pickvoice.domain.statemachine.CausaErro
import com.agvtronic.pickvoice.domain.statemachine.ItemEmAndamento
import com.agvtronic.pickvoice.domain.statemachine.MotivoExcecao
import com.agvtronic.pickvoice.domain.statemachine.MotivoPausa
import com.agvtronic.pickvoice.domain.statemachine.PickingState
import com.agvtronic.pickvoice.domain.statemachine.TipoCheckDigit
import com.agvtronic.pickvoice.vision.DiagnosticoVisao
import com.agvtronic.pickvoice.vision.EstadoStreamVisao
import com.agvtronic.pickvoice.vision.QualidadeStream
import com.agvtronic.pickvoice.vision.TentativaDeLeitura
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjetorDeOperacaoTest {

  private val projetor = ProjetorDeOperacao()

  private val visao =
      DiagnosticoVisao(qualidade = QualidadeStream.MEDIA, fpsConfigurado = 7, fatorRecorte = 0.6f)
  private val audio = DiagnosticoSaidaAudio(estado = EstadoSaidaAudio.PRONTA)

  /**
   * Dados fictícios com a forma do WMS. A senha do endereço é `93` de propósito: nenhum outro
   * campo do fixture contém esses dígitos, então o teste de vazamento é conclusivo.
   */
  private val linha =
      Linha(
          produto = "531884",
          descricao = "LORATADINA 10MG COM 12 COMPRIMIDOS",
          endereco = Endereco(cd = "72", setor = "04", andar = "B", predio = "118", rua = "D"),
          senhaEndereco = "93",
          ean = "7896523202204",
          dun14 = "17896523202201",
          partida = "60318425",
          serie = "1002478351",
          validade = LocalDate.of(2027, 4, 30),
          quantidade = 12,
          ua = "39604512",
          recnum = "12480537",
          saldoEndereco = 240,
          dirStage = "ST01",
      )

  private val ordem =
      Ordem(
          praca = "274K5010000",
          pedido = "408176",
          cliente = "Drogaria Vila Ema — Loja 12",
          linhas = listOf(linha),
      )

  private val item =
      ItemEmAndamento(
          ordemId = ordem.id,
          indiceLinha = 0,
          endereco = linha.endereco.etiqueta,
          itensRestantes = 2,
      )

  private fun projetar(estado: PickingState, visao: DiagnosticoVisao = this.visao) =
      projetor.projetar(estado, ordem, visao, audio)

  @Test
  fun `navegacao e check digit ficam na etapa de endereco com o progresso da ordem`() {
    val navegando = projetar(PickingState.NavegandoParaEndereco(item))

    assertEquals(EtapaOperacao.ENDERECO, navegando.etapa)
    assertEquals(linha.endereco.etiqueta, navegando.endereco)
    assertEquals("Item 1 de 3", navegando.progresso)
    assertTrue(navegando.aguardandoVoz)

    val checkDigit =
        projetar(PickingState.AguardandoCheckDigit(item, TipoCheckDigit.POSICAO))

    assertEquals(EtapaOperacao.ENDERECO, checkDigit.etapa)
    assertTrue(checkDigit.aguardandoVoz)
  }

  @Test
  fun `check digit esperado nunca entra no estado de UI`() {
    val estados =
        listOf(
            PickingState.NavegandoParaEndereco(item),
            PickingState.AguardandoCheckDigit(item, TipoCheckDigit.POSICAO),
            PickingState.AguardandoCheckDigit(item, TipoCheckDigit.PRODUTO),
            PickingState.EscaneandoProduto(item),
            PickingState.ConfirmandoQuantidade(item, linha.quantidade),
        )

    estados.forEach { estado ->
      val projetado = projetar(estado).toString()
      assertFalse(projetado, projetado.contains(linha.senhaEndereco))
      assertFalse(projetado, projetado.contains("senha"))
    }
  }

  @Test
  fun `escaneamento mostra status sem revelar leitura em andamento`() {
    val emAnalise =
        visao.copy(
            estadoStream = EstadoStreamVisao.ATIVO,
            ultimaTentativa = TentativaDeLeitura(codigo = linha.ean, duracaoMs = 42),
            ultimoCodigoConfirmado = "7891456120779",
            orientacaoPendente = true,
        )

    val estado = projetar(PickingState.EscaneandoProduto(item), emAnalise)

    assertEquals(EtapaOperacao.PRODUTO, estado.etapa)
    assertTrue(estado.orientacaoPendente)
    assertEquals("Código em confirmação", estado.statusLeitura)
    // Nem a tentativa corrente nem o código do escaneamento anterior aparecem.
    assertNull(estado.ultimaConfirmacao)
    val projetado = estado.toString()
    assertFalse(projetado, projetado.contains(linha.ean))
    assertFalse(projetado, projetado.contains("7891456120779"))
  }

  @Test
  fun `saida do escaneamento mostra o codigo ja confirmado`() {
    val estado = projetar(PickingState.ValidandoContraDados(item, codigoLido = linha.ean))

    assertEquals(EtapaOperacao.PRODUTO, estado.etapa)
    assertEquals("Código ${linha.ean} confirmado", estado.ultimaConfirmacao)
  }

  @Test
  fun `quantidade mostra esperado, entendido, compartimento e progresso`() {
    val confirmando = projetar(PickingState.ConfirmandoQuantidade(item, linha.quantidade))

    assertEquals(EtapaOperacao.QUANTIDADE, confirmando.etapa)
    assertEquals(linha.quantidade, confirmando.quantidadeEsperada)
    assertEquals(linha.dirStage, confirmando.compartimento)
    assertTrue(confirmando.aguardandoVoz)

    val readback = projetar(PickingState.ReadbackQuantidade(item, quantidadeInformada = 11))

    assertEquals(EtapaOperacao.QUANTIDADE, readback.etapa)
    assertEquals(11, readback.quantidadeInformada)
    assertEquals(linha.quantidade, readback.quantidadeEsperada)
    assertTrue(readback.aguardandoVoz)

    val alocando = projetar(PickingState.AlocandoCarrinho(item, quantidadeColetada = 12))

    assertEquals("Quantidade 12 confirmada", alocando.ultimaConfirmacao)
    assertEquals("Deposite no compartimento ST01", alocando.instrucao)
  }

  @Test
  fun `pausa e erro reusam o cartao de mensagem com recuperacao`() {
    val pausada =
        projetar(
            PickingState.SessaoPausada(
                estadoAnterior = PickingState.EscaneandoProduto(item),
                motivo = MotivoPausa.LIFECYCLE_DAT,
            )
        )

    assertEquals(EtapaOperacao.MENSAGEM, pausada.etapa)
    assertEquals("Sessão pausada", pausada.situacao)
    assertEquals("Retome a sessão para continuar do mesmo item", pausada.instrucao)
    // O item em andamento sobrevive à pausa, então o progresso continua visível.
    assertEquals("Item 1 de 3", pausada.progresso)

    val erro =
        projetar(
            PickingState.Erro(
                causa = CausaErro.BLUETOOTH_DESCONECTADO,
                estadoAnterior = PickingState.EscaneandoProduto(item),
                detalhe = "handle=segredo-operacional",
            )
        )

    assertEquals(EtapaOperacao.MENSAGEM, erro.etapa)
    assertEquals("Sessão interrompida", erro.situacao)
    assertEquals("Reaproxime o óculos; a separação retoma no mesmo item", erro.instrucao)
    assertFalse(erro.toString().contains("segredo-operacional"))
  }

  @Test
  fun `ordem concluida encerra na mesma tela`() {
    val estado = projetar(PickingState.OrdemConcluida(ordem.id))

    assertEquals(EtapaOperacao.MENSAGEM, estado.etapa)
    assertEquals("Ordem ${ordem.id} concluída", estado.mensagem)
    assertNull(estado.progresso)
  }

  @Test
  fun `os quatro estados do cartao de quantidade tem nomes de etapa distintos`() {
    // O balde `QUANTIDADE` é onde o operador se perdia: quatro estados, um cartão só, sem rótulo.
    val nomes =
        listOf(
                PickingState.ConfirmandoQuantidade(item, linha.quantidade),
                PickingState.ReadbackQuantidade(item, quantidadeInformada = 11),
                PickingState.AlocandoCarrinho(item, quantidadeColetada = 12),
                PickingState.ItemConcluido(item),
            )
            .map { projetar(it) }

    assertTrue("todos deveriam estar no mesmo cartão", nomes.all { it.etapa == EtapaOperacao.QUANTIDADE })
    val rotulos = nomes.map { it.nomeEtapa }
    assertTrue("nenhum rótulo pode ficar vazio", rotulos.none { it.isBlank() })
    assertEquals("os quatro rótulos deveriam ser distintos", rotulos.size, rotulos.toSet().size)
  }

  @Test
  fun `os dois check digits tem nomes de etapa distintos e nao revelam a senha`() {
    val posicao = projetar(PickingState.AguardandoCheckDigit(item, TipoCheckDigit.POSICAO))
    val produto = projetar(PickingState.AguardandoCheckDigit(item, TipoCheckDigit.PRODUTO))

    // Confirmar que chegou no endereço certo e confirmar o produto pelo lote são coisas
    // diferentes (design.md - Decisão 3).
    assertTrue(posicao.nomeEtapa != produto.nomeEtapa)
    assertFalse(posicao.nomeEtapa.contains(linha.senhaEndereco))
    assertFalse(produto.nomeEtapa.contains(linha.senhaEndereco))
  }

  @Test
  fun `todo estado tem nome de etapa, inclusive fora dos tres cartoes`() {
    val estados =
        listOf(
            PickingState.Ocioso,
            PickingState.Registrando,
            PickingState.PreparandoSessao,
            PickingState.AguardandoOrdem,
            PickingState.OrdemCarregada(ordem.id, 3),
            PickingState.NavegandoParaEndereco(item),
            PickingState.AguardandoCheckDigit(item, TipoCheckDigit.POSICAO),
            PickingState.EscaneandoProduto(item),
            PickingState.DecodificandoProduto(item),
            PickingState.VerificacaoAssistida(item),
            PickingState.ValidandoContraDados(item, codigoLido = linha.ean),
            PickingState.ConfirmandoQuantidade(item, linha.quantidade),
            PickingState.ReadbackQuantidade(item, 11),
            PickingState.AlocandoCarrinho(item, 12),
            PickingState.ItemConcluido(item),
            PickingState.TratandoExcecao(MotivoExcecao.AVARIA, item),
            PickingState.ConferenciaFinal(ordem.id),
            PickingState.OrdemConcluida(ordem.id),
            PickingState.SessaoPausada(PickingState.EscaneandoProduto(item), MotivoPausa.COMANDO_PARAR),
            PickingState.Erro(CausaErro.BLUETOOTH_DESCONECTADO, estadoAnterior = null),
        )

    estados.forEach { estado ->
      val nome = projetar(estado).nomeEtapa
      assertTrue("$estado ficou sem nome de etapa", nome.isNotBlank())
      // Nenhum rótulo pode carregar a senha do endereço, em nenhum estado.
      assertFalse("$estado vazou a senha no rótulo", nome.contains(linha.senhaEndereco))
    }

    // Pausa, exceção, conferência e ordem concluída não podem se passar por uma das três
    // validações operacionais.
    val foraDosCartoes =
        listOf(
                PickingState.SessaoPausada(PickingState.EscaneandoProduto(item), MotivoPausa.COMANDO_PARAR),
                PickingState.TratandoExcecao(MotivoExcecao.AVARIA, item),
                PickingState.ConferenciaFinal(ordem.id),
                PickingState.OrdemConcluida(ordem.id),
            )
            .map { projetar(it).nomeEtapa }

    assertTrue(
        foraDosCartoes.toString(),
        foraDosCartoes.none { it == "Validação da posição" || it == "Coleta e contagem" },
    )
  }

  @Test
  fun `so a ocorrencia oferece saida por toque`() {
    val ocorrencia =
        projetar(PickingState.TratandoExcecao(MotivoExcecao.AVARIA, item))

    assertTrue(ocorrencia.podeRegistrarOcorrencia)
    assertEquals(EtapaOperacao.MENSAGEM, ocorrencia.etapa)

    // Todo o resto do fluxo continua sem botão de avanço: a saída por toque é uma válvula de
    // escape da ocorrência, não uma volta ao painel de botões.
    assertFalse(projetar(PickingState.NavegandoParaEndereco(item)).podeRegistrarOcorrencia)
    assertFalse(projetar(PickingState.EscaneandoProduto(item)).podeRegistrarOcorrencia)
    assertFalse(projetar(PickingState.ReadbackQuantidade(item, 12)).podeRegistrarOcorrencia)
    assertFalse(projetar(PickingState.ItemConcluido(item)).podeRegistrarOcorrencia)
  }

  @Test
  fun `so a espera pela ordem oferece o toque de confirmar`() {
    val esperando = projetar(PickingState.AguardandoOrdem)

    assertTrue(esperando.podeConfirmarOrdem)
    assertEquals(EtapaOperacao.MENSAGEM, esperando.etapa)
    // O estado não escuta (design.md — Decisão 4): anunciar espera por voz aqui seria mentira.
    assertFalse(esperando.aguardandoVoz)

    // Todo o resto do fluxo avança por voz, câmera ou ciclo de vida — nunca por este botão.
    val demais =
        listOf(
            PickingState.Ocioso,
            PickingState.Registrando,
            PickingState.PreparandoSessao,
            PickingState.OrdemCarregada(ordem.id, 3),
            PickingState.NavegandoParaEndereco(item),
            PickingState.AguardandoCheckDigit(item, TipoCheckDigit.POSICAO),
            PickingState.AguardandoCheckDigit(item, TipoCheckDigit.PRODUTO),
            PickingState.EscaneandoProduto(item),
            PickingState.DecodificandoProduto(item),
            PickingState.VerificacaoAssistida(item),
            PickingState.ValidandoContraDados(item, codigoLido = linha.ean),
            PickingState.ConfirmandoQuantidade(item, linha.quantidade),
            PickingState.ReadbackQuantidade(item, 11),
            PickingState.AlocandoCarrinho(item, 12),
            PickingState.ItemConcluido(item),
            PickingState.TratandoExcecao(MotivoExcecao.AVARIA, item),
            PickingState.ConferenciaFinal(ordem.id),
            PickingState.OrdemConcluida(ordem.id),
            PickingState.SessaoPausada(PickingState.EscaneandoProduto(item), MotivoPausa.COMANDO_PARAR),
            PickingState.Erro(CausaErro.BLUETOOTH_DESCONECTADO, estadoAnterior = null),
        )

    demais.forEach { estado ->
      assertFalse("$estado não deveria oferecer o toque", projetar(estado).podeConfirmarOrdem)
    }

    // E o botão da ocorrência continua exclusivo do seu estado: um não pode ligar o outro.
    assertFalse(esperando.podeRegistrarOcorrencia)
  }

  @Test
  fun `sem ordem carregada a tela ainda descreve a sessao`() {
    val estado = projetor.projetar(PickingState.Ocioso, ordem = null, visao = visao, audio = audio)

    assertEquals(EtapaOperacao.MENSAGEM, estado.etapa)
    assertEquals("Sem sessão", estado.situacao)
    assertNull(estado.ordem)
    assertNull(estado.produto)
  }
}
