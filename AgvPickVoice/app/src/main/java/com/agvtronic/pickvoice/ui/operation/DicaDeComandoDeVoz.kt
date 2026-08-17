package com.agvtronic.pickvoice.ui.operation

import com.agvtronic.pickvoice.audio.VocabularioDeVoz
import com.agvtronic.pickvoice.domain.statemachine.PickingState

/**
 * A palavra de voz que avança o estado atual, para exibir em tela sem o operador precisar
 * decorar o vocabulário (spec `voice-command-hint`).
 *
 * Mesmo padrão do `ProjetorDeFalaPicking`: Kotlin puro, sem Android, sem corrotina. Lê as
 * mesmas constantes que `InterpretadorDeFala` usa para reconhecer a fala — duas fontes de
 * verdade para o vocabulário divergiriam cedo ou tarde (design.md - Decisão 8).
 *
 * Cobre os estados que avançam por uma palavra de voz (design.md - Decisão 6). Estados que
 * esperam dígitos/número (`AguardandoCheckDigit`, `ConfirmandoQuantidade`) ou que avançam por
 * câmera/rede não têm dica — não há uma palavra fixa para mostrar. `TratandoExcecao` é o caso
 * misto: o relato é livre, mas a saída curta por "próximo" é uma palavra fixa e vale mostrar.
 */
object DicaDeComandoDeVoz {

  fun dica(estado: PickingState): String? =
      when (estado) {
        is PickingState.OrdemCarregada -> dizer(VocabularioDeVoz.INICIAR)
        is PickingState.NavegandoParaEndereco -> dizer(VocabularioDeVoz.CHEGUEI)
        is PickingState.AlocandoCarrinho -> dizer(VocabularioDeVoz.ALOCADO)
        is PickingState.ReadbackQuantidade ->
            "Diga: ${VocabularioDeVoz.CONFIRMAR} ou ${VocabularioDeVoz.CORRIGIR}"
        is PickingState.ItemConcluido -> dizer(VocabularioDeVoz.PROXIMO)
        // O vocabulário aqui é aberto — a dica mostra a saída curta, não o relato, que é livre
        // por definição e não tem palavra fixa para exibir.
        is PickingState.TratandoExcecao ->
            "Descreva a ocorrência ou diga: ${VocabularioDeVoz.PROXIMO}"
        is PickingState.ConferenciaFinal -> dizer(VocabularioDeVoz.CONCLUIR)
        is PickingState.OrdemConcluida -> dizer(VocabularioDeVoz.ENCERRAR)
        else -> null
      }

  private fun dizer(palavra: String) = "Diga: $palavra"
}
