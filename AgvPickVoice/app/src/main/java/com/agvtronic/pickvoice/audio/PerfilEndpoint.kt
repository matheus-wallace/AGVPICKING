package com.agvtronic.pickvoice.audio

/**
 * Os perfis de endpoint do doc §5.1 — quanto silêncio depois da fala fecha uma elocução.
 *
 * O valor certo depende do que se espera ouvir, e não é um detalhe: o doc registra que os
 * 700 ms de [DIGITOS] existem para corrigir um bug real de `572 -> 570`, em que o endpointer
 * padrão fechava a elocução na micropausa antes do último dígito. Um perfil curto demais come
 * dígito; um longo demais faz o operador esperar à toa depois de dizer "parar".
 *
 * Kotlin puro, sem Android e sem Vosk: é só a tabela do doc virada em código, e é o que a
 * fatia de gramática por estado (Marco 2, doc §13.1) vai consumir ao trocar de perfil a cada
 * transição. Esta fatia usa só [COMANDO_CURTO], como default de [AjustesAsr.silencioFinalMs] —
 * a conversão para os segundos que o `setEndpointerDelays` do Vosk espera mora no
 * [ReconhecedorDeComando], junto do resto da superfície do Vosk.
 *
 * @property silencioFinalMs silêncio após a fala que fecha a elocução.
 */
enum class PerfilEndpoint(val silencioFinalMs: Int) {

  /** Confirmar, cancelar, repetir — respostas de uma palavra, onde esperar incomoda. */
  COMANDO_CURTO(280),

  /** Quantidade e check digit: precisa tolerar a micropausa entre dígitos (doc §5.1). */
  DIGITOS(700),

  /**
   * Relato de exceção, onde o operador formula a frase enquanto fala.
   *
   * **Sem estado que o use hoje.** `TratandoExcecao` era o único, e passou a gramática fechada
   * com [COMANDO_CURTO] (add-voice-recognition-reliability - Decisão 2). O valor fica aqui, e
   * não é removido, porque a tabela é a do doc §5.1 e o perfil volta a ter dono na fatia de
   * relato de ocorrência via LLM (doc §5.4) — apagá-lo agora só faria a tabela deixar de
   * espelhar o documento.
   */
  TEXTO_LIVRE(900),
}
