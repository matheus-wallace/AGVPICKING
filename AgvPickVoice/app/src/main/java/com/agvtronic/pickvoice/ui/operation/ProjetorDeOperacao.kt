package com.agvtronic.pickvoice.ui.operation

import com.agvtronic.pickvoice.audio.output.DiagnosticoSaidaAudio
import com.agvtronic.pickvoice.data.model.Ordem
import com.agvtronic.pickvoice.domain.statemachine.CausaErro
import com.agvtronic.pickvoice.domain.statemachine.MotivoExcecao
import com.agvtronic.pickvoice.domain.statemachine.MotivoPausa
import com.agvtronic.pickvoice.domain.statemachine.PickingState
import com.agvtronic.pickvoice.domain.statemachine.TipoCheckDigit
import com.agvtronic.pickvoice.vision.DiagnosticoVisao
import com.agvtronic.pickvoice.vision.EstadoStreamVisao

/**
 * Projeta estado de domínio, linha da ordem e diagnósticos em [OperationUiState].
 *
 * Mesmo papel do `ProjetorDeFalaPicking` para a voz: Kotlin puro, sem Android, sem corrotina e
 * sem I/O. Ele **lê** o estado e nunca o altera — a tela não é fonte de transição
 * (design.md — Decisão 1), então aqui não existe `reduce` nem `send`.
 *
 * O que é deliberadamente omitido: `senhaEndereco` (o check digit esperado do doc §7.1), o lote
 * completo e qualquer código ainda em confirmação. O código lido só aparece depois que o fluxo
 * o aceitou, e vem do próprio estado, não da telemetria da câmera — assim uma leitura de ciclo
 * anterior nunca reaparece fora do escaneamento.
 */
class ProjetorDeOperacao {

  fun projetar(
      estado: PickingState,
      ordem: Ordem?,
      visao: DiagnosticoVisao,
      audio: DiagnosticoSaidaAudio,
  ): OperationUiState {
    val item = estado.itemEmAndamento
    val linha = item?.let { ordem?.linhas?.getOrNull(it.indiceLinha) }
    val base =
        OperationUiState(
            ordem = ordem?.let { "Pedido ${it.pedido} · ${it.cliente}" },
            progresso = progresso(estado),
            situacao = situacao(estado),
            produto = linha?.let { "${it.produto} — ${it.descricao}" },
            estadoFala = audio.estado,
            dicaDeVoz = DicaDeComandoDeVoz.dica(estado),
        )

    return when (estado) {
      PickingState.Ocioso ->
          base.mensagem(
              texto = "Óculos não pareado",
              instrucao = "Pareie o óculos no app Meta AI para começar",
              nomeEtapa = "Pareamento",
          )

      PickingState.Registrando ->
          base.mensagem(
              texto = "Pareamento em curso",
              instrucao = "Conclua o pareamento no app Meta AI",
              nomeEtapa = "Pareamento",
          )

      PickingState.PreparandoSessao ->
          base.mensagem(
              texto = "Preparando a sessão",
              instrucao = "Aguarde o aviso de sessão pronta",
              nomeEtapa = "Preparação da sessão",
          )

      PickingState.AguardandoOrdem ->
          base.mensagem(
              texto = "Sessão pronta",
              instrucao = "Escolha a ordem de separação",
              nomeEtapa = "Escolha da ordem",
              aguardandoVoz = true,
          )

      is PickingState.OrdemCarregada ->
          base.mensagem(
              texto = "Ordem ${estado.ordemId} carregada",
              instrucao = "${estado.totalLinhas} ${itens(estado.totalLinhas)} para separar",
              nomeEtapa = "Início da ordem",
          )

      is PickingState.NavegandoParaEndereco ->
          base.copy(
              etapa = EtapaOperacao.ENDERECO,
              endereco = estado.itemEmAndamento.endereco,
              instrucao = "Siga até a posição e avise a chegada",
              nomeEtapa = "Deslocamento até a posição",
              aguardandoVoz = true,
          )

      is PickingState.AguardandoCheckDigit ->
          base.copy(
              etapa = EtapaOperacao.ENDERECO,
              endereco = estado.itemEmAndamento.endereco,
              // Nunca o valor esperado: a tela pede os dígitos, quem confere é o fluxo (§7).
              instrucao =
                  when (estado.tipo) {
                    TipoCheckDigit.POSICAO -> "Fale os dois dígitos da etiqueta da posição"
                    TipoCheckDigit.PRODUTO -> "Fale os dois últimos dígitos do lote da embalagem"
                  },
              nomeEtapa =
                  when (estado.tipo) {
                    // Sem o valor esperado: o rótulo diz o que se confere, nunca a resposta.
                    TipoCheckDigit.POSICAO -> "Validação da posição"
                    TipoCheckDigit.PRODUTO -> "Validação do produto pelo lote"
                  },
              aguardandoVoz = true,
          )

      is PickingState.EscaneandoProduto ->
          base.copy(
              etapa = EtapaOperacao.PRODUTO,
              endereco = estado.itemEmAndamento.endereco,
              instrucao = "Enquadre o código do produto na moldura",
              nomeEtapa = "Leitura do código do produto",
              statusLeitura = statusLeitura(visao),
              orientacaoPendente = visao.orientacaoPendente,
          )

      is PickingState.DecodificandoProduto ->
          base.copy(
              etapa = EtapaOperacao.PRODUTO,
              instrucao = "Mantenha a caixa parada",
              nomeEtapa = "Decodificação do produto",
              statusLeitura = "Lendo o código",
          )

      is PickingState.VerificacaoAssistida ->
          base.copy(
              etapa = EtapaOperacao.PRODUTO,
              instrucao = "Mantenha a caixa parada",
              nomeEtapa = "Verificação assistida do produto",
              statusLeitura = "Verificação assistida em curso",
          )

      is PickingState.ValidandoContraDados ->
          base.copy(
              etapa = EtapaOperacao.PRODUTO,
              instrucao = "Conferindo o produto contra a ordem",
              nomeEtapa = "Validação do produto contra a ordem",
              ultimaConfirmacao = "Código ${estado.codigoLido} confirmado",
          )

      is PickingState.ConfirmandoQuantidade ->
          base.copy(
              etapa = EtapaOperacao.QUANTIDADE,
              quantidadeEsperada = estado.quantidadeEsperada,
              compartimento = linha?.dirStage,
              instrucao =
                  "Colete ${estado.quantidadeEsperada} ${unidades(estado.quantidadeEsperada)} " +
                      "e fale a quantidade",
              nomeEtapa = "Coleta e contagem",
              aguardandoVoz = true,
              ultimaConfirmacao = "Produto conferido",
          )

      is PickingState.ReadbackQuantidade ->
          base.copy(
              etapa = EtapaOperacao.QUANTIDADE,
              quantidadeEsperada = linha?.quantidade,
              quantidadeInformada = estado.quantidadeInformada,
              compartimento = linha?.dirStage,
              instrucao = "Confirme ou corrija a quantidade por voz",
              nomeEtapa = "Confirmação da quantidade",
              aguardandoVoz = true,
          )

      is PickingState.AlocandoCarrinho ->
          base.copy(
              etapa = EtapaOperacao.QUANTIDADE,
              quantidadeInformada = estado.quantidadeColetada,
              compartimento = linha?.dirStage,
              instrucao =
                  linha?.dirStage?.let { "Deposite no compartimento $it" }
                      ?: "Deposite no compartimento indicado",
              nomeEtapa = "Alocação no carrinho",
              ultimaConfirmacao = "Quantidade ${estado.quantidadeColetada} confirmada",
          )

      is PickingState.ItemConcluido ->
          base.copy(
              etapa = EtapaOperacao.QUANTIDADE,
              compartimento = linha?.dirStage,
              instrucao =
                  if (estado.itemEmAndamento.itensRestantes > 0) "Seguindo para o próximo item"
                  else "Última linha da ordem concluída",
              nomeEtapa = "Item concluído",
              ultimaConfirmacao = "Item registrado",
          )

      is PickingState.TratandoExcecao ->
          base
              .mensagem(
                  texto = "Ocorrência de ${descricao(estado.motivo)}",
                  instrucao = "Descreva a ocorrência por voz para registrar",
                  nomeEtapa = "Registro de ocorrência",
                  aguardandoVoz = true,
              )
              // O único ponto do fluxo em que a tela oferece um toque: aqui a voz precisa de um
              // relato inteiro, e é o estado em que o operador ficou preso em bancada.
              .copy(podeRegistrarOcorrencia = true)

      is PickingState.ConferenciaFinal ->
          base.mensagem(
              texto = "Conferência final da ordem ${estado.ordemId}",
              instrucao = "Confira o carrinho para fechar a ordem",
              nomeEtapa = "Conferência final",
          )

      is PickingState.OrdemConcluida ->
          base.mensagem(
              texto = "Ordem ${estado.ordemId} concluída",
              instrucao = "Encerre a ordem para receber a próxima",
              nomeEtapa = "Ordem concluída",
          )

      is PickingState.SessaoPausada ->
          base.mensagem(
              texto = "Separação pausada — ${descricao(estado.motivo)}",
              instrucao = "Retome a sessão para continuar do mesmo item",
              nomeEtapa = "Separação pausada",
          )

      is PickingState.Erro ->
          base.mensagem(
              // O `detalhe` do estado é técnico e pode carregar texto do SDK: fica no painel de
              // desenvolvimento, não na tela do operador.
              texto = "Falha: ${descricao(estado.causa)}",
              instrucao = recuperacao(estado.causa),
              nomeEtapa = "Falha de sessão",
          )
    }
  }

  /**
   * Cartão de mensagem: mesma estrutura da tela, sem uma quarta superfície operacional.
   *
   * [nomeEtapa] é obrigatório e **não tem valor padrão** de propósito (design.md - Risco): este
   * helper é o ponto único por onde passam os dez estados de mensagem, e um default aqui
   * silenciaria a distinção entre eles justamente onde o compilador não tem como cobrar.
   */
  private fun OperationUiState.mensagem(
      texto: String,
      instrucao: String,
      nomeEtapa: String,
      aguardandoVoz: Boolean = false,
  ) =
      copy(
          etapa = EtapaOperacao.MENSAGEM,
          mensagem = texto,
          instrucao = instrucao,
          nomeEtapa = nomeEtapa,
          aguardandoVoz = aguardandoVoz,
      )

  /**
   * `item atual / total` sem consultar o repositório: o total sai do próprio item em andamento
   * (índice + restantes + 1), então o cabeçalho continua correto antes de a ordem carregar.
   */
  private fun progresso(estado: PickingState): String? {
    val item = estado.itemEmAndamento ?: return null
    val total = item.indiceLinha + item.itensRestantes + 1
    return "Item ${item.indiceLinha + 1} de $total"
  }

  private fun situacao(estado: PickingState): String =
      when (estado) {
        PickingState.Ocioso -> "Sem sessão"
        PickingState.Registrando -> "Pareando"
        PickingState.PreparandoSessao -> "Preparando sessão"
        is PickingState.SessaoPausada -> "Sessão pausada"
        is PickingState.Erro -> "Sessão interrompida"
        else -> "Sessão ativa"
      }

  /**
   * Status da câmera sem revelar leitura em andamento.
   *
   * O código da tentativa corrente nunca aparece: até o consenso confirmar, ele é palpite, e
   * mostrar palpite na tela do operador é o mesmo erro que falar o palpite em voz alta.
   */
  private fun statusLeitura(visao: DiagnosticoVisao): String =
      when (visao.estadoStream) {
        EstadoStreamVisao.DESLIGADO -> "Câmera desligada"
        EstadoStreamVisao.INICIANDO -> "Abrindo a câmera"
        EstadoStreamVisao.ATIVO ->
            if (visao.ultimaTentativa?.codigo != null) "Código em confirmação"
            else "Procurando o código"
        EstadoStreamVisao.ERRO -> "Falha na câmera"
      }

  private fun descricao(motivo: MotivoExcecao): String =
      when (motivo) {
        MotivoExcecao.AVARIA -> "avaria"
        MotivoExcecao.RUPTURA -> "ruptura"
        MotivoExcecao.DIVERGENCIA -> "divergência"
      }

  private fun descricao(motivo: MotivoPausa): String =
      when (motivo) {
        MotivoPausa.COMANDO_PARAR -> "pausa solicitada"
        MotivoPausa.EMERGENCIA -> "emergência"
        MotivoPausa.LIFECYCLE_DAT -> "óculos removido ou hastes fechadas"
      }

  private fun descricao(causa: CausaErro): String =
      when (causa) {
        CausaErro.BLUETOOTH_DESCONECTADO -> "Bluetooth desconectado"
        CausaErro.FALHA_REGISTRO -> "falha no pareamento"
        CausaErro.FALHA_SESSAO -> "falha na sessão"
      }

  private fun recuperacao(causa: CausaErro): String =
      when (causa) {
        CausaErro.BLUETOOTH_DESCONECTADO ->
            "Reaproxime o óculos; a separação retoma no mesmo item"
        CausaErro.FALHA_REGISTRO -> "Refaça o pareamento no app Meta AI"
        CausaErro.FALHA_SESSAO -> "Reconecte o óculos para abrir uma nova sessão"
      }

  private fun itens(total: Int): String = if (total == 1) "item" else "itens"

  private fun unidades(quantidade: Int): String = if (quantidade == 1) "unidade" else "unidades"
}
