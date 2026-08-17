package com.agvtronic.pickvoice.audio

import com.agvtronic.pickvoice.data.PickingRepository
import com.agvtronic.pickvoice.data.model.Ordem
import com.agvtronic.pickvoice.domain.statemachine.ItemEmAndamento
import com.agvtronic.pickvoice.domain.statemachine.PickingEvent
import com.agvtronic.pickvoice.domain.statemachine.PickingState
import com.agvtronic.pickvoice.domain.statemachine.TipoCheckDigit

/**
 * O único ponto em que a voz encosta no dado operacional — a Decisão 5 do design.md, "não
 * existe confirmação cega".
 *
 * Duas intenções não podem virar evento sozinhas:
 *
 * - **check digit**: os dígitos falados são comparados aqui com o valor cadastrado, e o que sai
 *   é apenas `CheckDigitCorreto` ou `CheckDigitIncorreto`. O valor esperado não é devolvido, não
 *   é falado e não é logado (spec: "O valor esperado NÃO DEVE aparecer na saída de áudio, painel
 *   ou logs").
 * - **avanço de item**: `NavegacaoIniciada` e `ItemFinalizado` carregam o item da linha, e só
 *   quem conhece o repositório sabe qual é a próxima.
 *
 * Sem Android e sem Vosk: dá para exercitar a classe inteira contra o `MockPickingRepository`
 * num teste de JVM. As chamadas são `suspend` porque a assinatura do repositório é a mesma que
 * uma implementação HTTP teria — por isso este resolvedor nunca roda na thread de áudio.
 */
class ResolvedorDeIntencao(private val repository: PickingRepository) {

  /**
   * @return o evento a enviar ao ator, ou `null` quando a intenção não se aplica ao estado —
   *   uma fala que chegou tarde demais, ou uma ordem sem linhas.
   */
  suspend fun resolver(estado: PickingState, intencao: IntencaoDeVoz): PickingEvent? =
      when (intencao) {
        is IntencaoDeVoz.Direta -> intencao.evento
        is IntencaoDeVoz.CheckDigitFalado -> conferirCheckDigit(estado, intencao.digitos)
        IntencaoDeVoz.IniciarNavegacao -> iniciarNavegacao(estado)
        IntencaoDeVoz.AvancarParaProximoItem -> finalizarItem(estado)
      }

  /**
   * Compara os dígitos falados com o dado da linha, sem revelá-lo.
   *
   * O valor esperado depende do tipo do check digit (doc §7): a senha cadastrada da posição
   * confirma que o operador chegou na prateleira certa; os dois últimos dígitos do lote
   * impresso confirmam o produto quando a cascata de visão se esgotou.
   */
  private suspend fun conferirCheckDigit(
      estado: PickingState,
      digitos: String,
  ): PickingEvent? {
    val aguardando = estado as? PickingState.AguardandoCheckDigit ?: return null
    val linha = linhaDoItem(aguardando.itemEmAndamento) ?: return null

    val esperado =
        when (aguardando.tipo) {
          TipoCheckDigit.POSICAO -> linha.senhaEndereco
          TipoCheckDigit.PRODUTO -> linha.partida.takeLast(InterpretadorDeFala.DIGITOS_DO_CHECK_DIGIT)
        }

    return if (digitos == esperado) PickingEvent.CheckDigitCorreto
    else PickingEvent.CheckDigitIncorreto
  }

  /** "iniciar" na ordem carregada: resolve a primeira linha a visitar. */
  private suspend fun iniciarNavegacao(estado: PickingState): PickingEvent? {
    val carregada = estado as? PickingState.OrdemCarregada ?: return null
    val ordem = ordem(carregada.ordemId) ?: return null
    if (ordem.linhas.isEmpty()) return null
    return PickingEvent.NavegacaoIniciada(itemDaLinha(ordem, 0))
  }

  /**
   * "próximo" no item concluído: resolve a linha seguinte, ou `null` quando esta era a última.
   *
   * `ItemFinalizado(null)` é o que leva o reducer à conferência final, então a última linha
   * também precisa publicar o evento — não é ausência de resposta.
   */
  private suspend fun finalizarItem(estado: PickingState): PickingEvent? {
    val concluido = estado as? PickingState.ItemConcluido ?: return null
    val item = concluido.itemEmAndamento
    val ordem = ordem(item.ordemId) ?: return null

    val proximo = item.indiceLinha + 1
    return PickingEvent.ItemFinalizado(
        proximoItem = if (proximo <= ordem.linhas.lastIndex) itemDaLinha(ordem, proximo) else null
    )
  }

  private suspend fun linhaDoItem(item: ItemEmAndamento) =
      ordem(item.ordemId)?.linhas?.getOrNull(item.indiceLinha)

  /**
   * A ordem, ou `null` quando o identificador não existe mais.
   *
   * O repositório lança `NoSuchElementException` para ordem desconhecida; aqui isso vira
   * ausência de evento, porque a task 3.3 exige que uma falha de reconhecimento deixe o estado
   * intacto em vez de derrubar o app.
   */
  private suspend fun ordem(ordemId: String): Ordem? =
      try {
        repository.ordem(ordemId)
      } catch (semOrdem: NoSuchElementException) {
        null
      }

  private fun itemDaLinha(ordem: Ordem, indice: Int): ItemEmAndamento =
      ItemEmAndamento(
          ordemId = ordem.id,
          indiceLinha = indice,
          endereco = ordem.linhas[indice].endereco.etiqueta,
          itensRestantes = ordem.linhas.lastIndex - indice,
      )
}
