package com.agvtronic.pickvoice.audio

/**
 * Tira do texto reconhecido o que é peculiaridade do decodificador, antes de ele chegar ao resto
 * do pipeline (add-sherpa-onnx-asr-engine - Decisão 3).
 *
 * ### Por que isto existe
 *
 * O Vosk tinha gramática fechada: as únicas hipóteses possíveis eram as palavras da gramática, e
 * ele **nunca** devolveu pontuação nem maiúscula. O Whisper é de vocabulário aberto e devolve
 * texto escrito para leitura humana — "Próximo.", "quarenta e sete," — o que faria toda
 * comparação por igualdade exata do [InterpretadorDeFala] falhar por causa de um ponto final.
 *
 * A limpeza mora aqui, e não dentro do [InterpretadorDeFala], porque pontuação é característica
 * de **motor**, não de domínio: colocá-la na função pura que decide o que a fala significa
 * acoplaria o comportamento dela a um decodificador específico. É a mesma fronteira que o JSON
 * do Vosk já respeitava — ele nunca vazou para fora da superfície do motor.
 *
 * ### O que esta função não faz
 *
 * **Não tira acento.** O [VocabularioDeVoz] é acentuado ("próximo", "emergência",
 * "divergência"), e normalizar acento aqui quebraria exatamente as palavras que precisam bater.
 *
 * **Não converte algarismo em extenso.** O Whisper pode devolver "47" onde o operador falou
 * "quarenta e sete" — isso é uma tradução de domínio, não uma limpeza de motor, e o lugar dela
 * seria o [VocabularioDeVoz]. Fica registrado como um caso a medir na bancada (tarefa 6.5) antes
 * de virar código: sem dado real não dá para saber se o modelo escreve por extenso ou em
 * algarismo para uma elocução de dois dígitos soltos.
 *
 * ### Por que o [MotorVosk] não a usa
 *
 * [VocabularioDeVoz.DESCONHECIDA] é `[unk]`, o token que o Vosk devolve para fala fora da
 * gramática — e os colchetes são pontuação. Passá-lo por aqui daria `unk`, que não bate com a
 * constante, e o [InterpretadorDeFala] deixaria de reconhecer a rejeição que hoje funciona. O
 * [MotorVosk] entrega o texto como o Vosk o produz, que é o que ele sempre fez; esta limpeza é
 * do motor de vocabulário aberto, que é quem tem o problema que ela resolve.
 */
object NormalizadorDeTextoAsr {

  /**
   * Tudo que não é letra, algarismo ou espaço vira separador.
   *
   * Vira **espaço** e não vazio de propósito: "quarenta,sete" sem espaço no lugar da vírgula
   * viraria uma palavra só que não bate com nada. `\p{L}` cobre letra acentuada, que é o que
   * precisa sobreviver.
   */
  private val NAO_PALAVRA = Regex("[^\\p{L}\\p{Nd}\\s]")

  private val ESPACOS = Regex("\\s+")

  /**
   * Devolve o texto em minúsculas, sem pontuação e com espaços colapsados.
   *
   * Idempotente e sem efeito sobre um texto que já era limpo — é o que permite aplicá-la também
   * a motores que nunca produzem pontuação sem mudar o comportamento deles.
   */
  fun normalizar(texto: String): String =
      texto.replace(NAO_PALAVRA, " ").replace(ESPACOS, " ").trim().lowercase()
}
