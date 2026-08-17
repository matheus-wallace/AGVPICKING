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

        is PickingState.OrdemCarregada -> comando(VocabularioDeVoz.INICIAR)
        is PickingState.NavegandoParaEndereco -> comando(VocabularioDeVoz.CHEGUEI)

        // Dois dígitos: o perfil longo do doc §5.1, que tolera a micropausa entre eles.
        is PickingState.AguardandoCheckDigit ->
            ConfiguracaoDeEscuta(
                palavras = VocabularioDeVoz.DIGITOS + VocabularioDeVoz.TRANSVERSAIS,
                perfil = PerfilEndpoint.DIGITOS,
            )

        // Câmera ligada: só transversais, nenhum comando de avanço.
        is PickingState.EscaneandoProduto -> comando()

        // Cascata, VLM e validação em curso — quem começou responde pelo resultado.
        is PickingState.DecodificandoProduto,
        is PickingState.VerificacaoAssistida,
        is PickingState.ValidandoContraDados -> null

        is PickingState.ConfirmandoQuantidade ->
            ConfiguracaoDeEscuta(
                palavras = VocabularioDeVoz.QUANTIDADES + VocabularioDeVoz.TRANSVERSAIS,
                perfil = PerfilEndpoint.DIGITOS,
            )

        is PickingState.ReadbackQuantidade ->
            comando(VocabularioDeVoz.CONFIRMAR, VocabularioDeVoz.CORRIGIR)

        is PickingState.AlocandoCarrinho -> comando(VocabularioDeVoz.ALOCADO)
        is PickingState.ItemConcluido -> comando(VocabularioDeVoz.PROXIMO)

        // O único estado de vocabulário aberto (design.md - Decisão 2).
        is PickingState.TratandoExcecao ->
            ConfiguracaoDeEscuta(palavras = emptyList(), perfil = PerfilEndpoint.TEXTO_LIVRE)

        is PickingState.ConferenciaFinal -> comando(VocabularioDeVoz.CONCLUIR)
        is PickingState.OrdemConcluida -> comando(VocabularioDeVoz.ENCERRAR)

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
