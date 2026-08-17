package com.agvtronic.pickvoice.audio

import com.agvtronic.pickvoice.domain.statemachine.PickingEvent

/**
 * O que a fala do operador quis dizer, antes de virar `PickingEvent`.
 *
 * Existe por causa da Decisão 5 do design.md: não há confirmação cega. Parte das falas já é um
 * evento pronto ([Direta]), mas check digit e "próximo" só viram evento **depois** de uma
 * consulta ao `PickingRepository` — dois dígitos falados não são um `CheckDigitCorreto`, são um
 * palpite a conferir. Separar as duas coisas mantém o [InterpretadorDeFala] puro e síncrono e
 * deixa o I/O inteiro no [ResolvedorDeIntencao].
 */
sealed interface IntencaoDeVoz {

  /** A fala já corresponde a um evento do domínio; nada a consultar. */
  data class Direta(val evento: PickingEvent) : IntencaoDeVoz

  /**
   * Dígitos falados em `AguardandoCheckDigit`, ainda não conferidos.
   *
   * String e não `Int` porque zero à esquerda é significativo: `"07"` e `"7"` são coisas
   * diferentes na senha do endereço.
   */
  data class CheckDigitFalado(val digitos: String) : IntencaoDeVoz

  /** "iniciar": a primeira linha da ordem precisa ser resolvida no repositório. */
  data object IniciarNavegacao : IntencaoDeVoz

  /** "próximo": a linha seguinte precisa ser resolvida no repositório. */
  data object AvancarParaProximoItem : IntencaoDeVoz
}
