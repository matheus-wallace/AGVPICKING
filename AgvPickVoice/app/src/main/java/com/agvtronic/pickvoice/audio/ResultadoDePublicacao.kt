package com.agvtronic.pickvoice.audio

/**
 * O desfecho de um resultado final do ASR ao passar por [PublicadorDeVoz.publicar].
 *
 * Existe para o log de calibração do doc §10 (task 2.2 de `add-operator-feedback-improvements`)
 * poder distinguir os dois motivos de descarte em vez de um "descartado" genérico: fora da
 * gramática do estado atual não é a mesma falha de bancada que resultado de versão obsoleta
 * (design.md - Decisão 3 de `add-state-driven-voice-flow`), e medir a taxa de reconhecimento
 * real exige separar os dois.
 */
sealed interface ResultadoDePublicacao {

  /** O texto virou uma [IntencaoDeVoz] e foi enfileirado para o [ResolvedorDeIntencao]. */
  data class Aceito(val intencao: IntencaoDeVoz) : ResultadoDePublicacao

  /** O texto não corresponde a nenhum comando aceito na gramática do estado atual. */
  data object ForaDaGramatica : ResultadoDePublicacao

  /** O resultado foi decodificado sob uma versão de estado anterior à atual. */
  data object VersaoObsoleta : ResultadoDePublicacao
}
