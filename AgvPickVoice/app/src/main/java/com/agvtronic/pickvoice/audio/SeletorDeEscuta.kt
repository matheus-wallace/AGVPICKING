package com.agvtronic.pickvoice.audio

import com.agvtronic.pickvoice.domain.statemachine.PickingState

/**
 * A tabela "Mapeamento de entrada" do design.md como função pura: estado -> o que escutar.
 *
 * Função pura e não configuração no reconhecedor porque é ela que responde, sem aparelho e sem
 * microfone, à pergunta que a spec faz: *quais palavras existem neste estado?* Um teste de JVM
 * cobre a tabela inteira; o [ReconhecedorDeComando] só executa a decisão já tomada aqui.
 *
 * ### Por que alguns estados devolvem `null`
 *
 * `null` significa **não escutar**. Vale para os estados de pré-sessão, para o [Erro], para a
 * seleção de ordem — que continua por toque (design.md - Decisão 4) — e para os estados em que
 * quem trabalha é a câmera ou a rede. Nesses últimos o produtor que iniciou o processamento é o
 * responsável pelo resultado; uma fala de avanço ali atropelaria a cascata de visão.
 *
 * Exceção deliberada: [PickingState.EscaneandoProduto] escuta, mas **só os transversais**. O
 * operador precisa poder dizer "parar" ou "avaria" com a câmera ligada, e nenhum comando dali
 * confirma código — isso é da visão (spec: "Visão e voz mantêm responsabilidades separadas").
 */
object SeletorDeEscuta {

  fun para(estado: PickingState): ConfiguracaoDeEscuta? =
      when (estado) {
        // Pré-sessão e erro: não há operação para comandar.
        PickingState.Ocioso,
        PickingState.Registrando,
        PickingState.PreparandoSessao,
        is PickingState.Erro -> null

        // A escolha da ordem é contingência de tela (design.md - Decisão 4).
        PickingState.AguardandoOrdem -> null

        // "próximo" soma a "iniciar" e a "cheguei" sem substituí-las (design.md - Decisão 8 de
        // add-operator-feedback-improvements): como a gramática é fechada, sem a palavra aqui o
        // Vosk nunca a transcreve e o sinônimo já aceito pelo [InterpretadorDeFala] fica morto.
        is PickingState.OrdemCarregada ->
            comando(VocabularioDeVoz.INICIAR, VocabularioDeVoz.PROXIMO)
        is PickingState.NavegandoParaEndereco ->
            comando(VocabularioDeVoz.CHEGUEI, VocabularioDeVoz.PROXIMO)

        // Dois dígitos: o perfil longo do doc §5.1, que tolera a micropausa entre eles.
        //
        // Extenso e dígito a dígito, os dois: a leitura por extenso ("quarenta e sete") chegou a
        // cair na bancada de 17/08/2026 (add-voice-recognition-reliability - Decisão 1) por dois
        // motivos — a fala grudada registrava incompleta, e a gramática de 0-999 inteira somava
        // ~30 palavras de dezena/centena que confundiam o dígito a dígito com "quatrocentos". Na
        // bancada de 18/08/2026, o inverso se provou: só dígito a dígito também falha, muitas
        // vezes só um dos dois algarismos é entendido. `CHECK_DIGIT_POR_EXTENSO` reintroduz o
        // extenso sem repetir o segundo motivo — é [VocabularioDeVoz.QUANTIDADES] sem as palavras
        // de centena, que não fazem sentido para um check digit de dois algarismos de qualquer
        // forma.
        is PickingState.AguardandoCheckDigit ->
            ConfiguracaoDeEscuta(
                palavras =
                    VocabularioDeVoz.DIGITOS +
                        VocabularioDeVoz.CHECK_DIGIT_POR_EXTENSO +
                        VocabularioDeVoz.TRANSVERSAIS,
                perfil = PerfilEndpoint.DIGITOS,
            )

        // Câmera ligada: só transversais, nenhum comando de avanço.
        is PickingState.EscaneandoProduto -> comando()

        // Cascata, VLM e validação em curso — quem começou responde pelo resultado.
        is PickingState.DecodificandoProduto,
        is PickingState.VerificacaoAssistida,
        is PickingState.ValidandoContraDados -> null

        // Extenso ("doze") e dígito a dígito ("um dois"), os dois — essa gramática nunca falhou
        // em bancada; saiu por um dia (add-voice-recognition-reliability - Decisão 7, por
        // consistência com o check digit) e voltou na bancada de 18/08/2026, quando o mesmo
        // corte que atrapalhava o check digit apareceu aqui: só dígito a dígito, "a todo momento
        // é entendido somente um deles". "meia" fica de fora — em quantidade ela é ambígua com
        // "meia dúzia".
        is PickingState.ConfirmandoQuantidade ->
            ConfiguracaoDeEscuta(
                palavras = VocabularioDeVoz.QUANTIDADES + VocabularioDeVoz.TRANSVERSAIS,
                perfil = PerfilEndpoint.DIGITOS,
                contextoRhino = TipoContextoRhino.QUANTIDADE,
            )

        // "próximo" é sinônimo aditivo de "confirmar" aqui (design.md - Decisão 8): o passo só
        // avança sem dado novo, então a mesma palavra que fecha `AlocandoCarrinho` e
        // `ItemConcluido` também serve para o readback. "corrigir" não ganha sinônimo.
        is PickingState.ReadbackQuantidade ->
            comando(VocabularioDeVoz.CONFIRMAR, VocabularioDeVoz.PROXIMO, VocabularioDeVoz.CORRIGIR)

        // Idem: "próximo" soma a "alocado" sem substituí-la (design.md - Decisão 8).
        is PickingState.AlocandoCarrinho ->
            comando(VocabularioDeVoz.ALOCADO, VocabularioDeVoz.PROXIMO)
        is PickingState.ItemConcluido -> comando(VocabularioDeVoz.PROXIMO)

        // Era o único estado de vocabulário aberto, e passou a ser gramática fechada como
        // qualquer outro avanço de uma palavra (add-voice-recognition-reliability - Decisão 2).
        // O log de bancada de 17/08/2026 mostra o decodificador aberto transcrevendo "próximo"
        // como "prós", "aqui", "faria" e "o próximo" — enquanto todo estado de gramática
        // fechada do mesmo log reconhece a palavra de avanço de primeira tentativa. O texto do
        // relato livre nunca foi consumido pelo domínio, então fechar aqui não perde
        // funcionalidade: quem registra detalhe é a ação de toque da tela do operador.
        is PickingState.TratandoExcecao -> comando(VocabularioDeVoz.PROXIMO)

        // Idem: "próximo" soma a "concluir" e a "encerrar" (design.md - Decisão 8).
        is PickingState.ConferenciaFinal ->
            comando(VocabularioDeVoz.CONCLUIR, VocabularioDeVoz.PROXIMO)
        is PickingState.OrdemConcluida ->
            comando(VocabularioDeVoz.ENCERRAR, VocabularioDeVoz.PROXIMO)

        // Contrapartida de "parar": sem ela a sessão pausada só sairia do lugar por toque, e o
        // ciclo hands-free ficaria aberto. Nenhum transversal aqui — o estado não é operacional
        // e o reducer os ignoraria.
        is PickingState.SessaoPausada ->
            ConfiguracaoDeEscuta(
                palavras = listOf(VocabularioDeVoz.RETOMAR),
                perfil = PerfilEndpoint.COMANDO_CURTO,
            )
      }

  /** Gramática de comando curto: as palavras do estado mais os transversais do doc §3.3. */
  private fun comando(vararg palavras: String) =
      ConfiguracaoDeEscuta(
          palavras = palavras.toList() + VocabularioDeVoz.TRANSVERSAIS,
          perfil = PerfilEndpoint.COMANDO_CURTO,
      )
}
