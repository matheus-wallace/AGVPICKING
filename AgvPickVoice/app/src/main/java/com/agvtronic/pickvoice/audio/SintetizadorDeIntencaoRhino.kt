package com.agvtronic.pickvoice.audio

/**
 * A intenção estruturada do Rhino virada em texto, para o resto do pipeline não saber que ela
 * existiu (add-picovoice-asr-engine - Decisão 1).
 *
 * ### Por que sintetizar em vez de consumir a intenção direto
 *
 * O Rhino não decodifica texto: ele devolve `isUnderstood` + `intent` + `slots`. Isso não cabe em
 * [ResultadoDeAsr.Fechada], que carrega `String` — e a alternativa, abrir um segundo caminho de
 * consumo que aceitasse intenção estruturada, quebraria a promessa que o [MotorDeAsr] documenta
 * ("nada de domínio atravessa esta fronteira") e colocaria a decisão de comando em dois lugares
 * ao mesmo tempo: o contexto `.rhn` de um lado, o [VocabularioDeVoz] do outro. Dois lugares que
 * divergem em silêncio é exatamente o defeito que o [VocabularioDeVoz] existe para evitar.
 *
 * Então a intenção vira texto aqui, na fronteira do motor, e o [InterpretadorDeFala] continua
 * sendo a única camada que decide se aquilo "conta" no estado atual. O custo é jogar fora a
 * estrutura que o Rhino já tinha entregue pronta, só para o [InterpretadorDeFala] reconstruí-la
 * — aceito de propósito (add-sherpa-onnx-asr-engine - Decisão 1).
 *
 * ### O contrato com o Picovoice Console
 *
 * Este objeto é a **outra metade** do contexto `.rhn`: o contexto define os nomes de intenção, e
 * [INTENCOES] define o que cada nome vira em texto. Um nome que não estiver aqui é sintetizado
 * como vazio — nenhum evento é publicado e a linha aparece no log —, então acrescentar uma
 * expressão no Console sem acrescentar a intenção aqui falha de forma visível, não silenciosa.
 *
 * A regra de nomenclatura é uma só: **o nome da intenção é a palavra do [VocabularioDeVoz] sem
 * acento**, porque nome de intenção no Console é identificador (`próximo` não é aceito,
 * `proximo` é). Um teste desta unidade confere essa regra entrada por entrada, para que a tabela
 * não vire uma lista de pares arbitrários que só a bancada descobriria estarem errados.
 *
 * Kotlin puro: sem Android e sem `ai.picovoice.rhino`. É o que torna a síntese testável na JVM
 * sem carregar `libpv_rhino.so` — mesma separação que o [ReamostradorLinear] tem em relação ao
 * ONNX Runtime.
 */
object SintetizadorDeIntencaoRhino {

  /**
   * Nome da intenção no contexto `.rhn` -> texto que o [InterpretadorDeFala] espera.
   *
   * Só os comandos de **palavra única**. Check digit não entra: ele chega como intenção com slot,
   * e quem vira texto é o valor do slot, não o nome da intenção (ver [sintetizar]). Quantidade é
   * tratada separadamente por [sintetizarQuantidade].
   *
   * `linkedMapOf` pelo mesmo motivo do [VocabularioDeVoz]: a ordem de inserção é a ordem em que
   * as intenções aparecem no log de carga do motor, e uma lista legível no logcat é o que permite
   * conferi-la contra o Console sem abrir o navegador.
   */
  val INTENCOES: Map<String, String> =
      linkedMapOf(
          // Fluxo — a coluna "Fala aceita" do design.md do fluxo de voz.
          "iniciar" to VocabularioDeVoz.INICIAR,
          "cheguei" to VocabularioDeVoz.CHEGUEI,
          "confirmar" to VocabularioDeVoz.CONFIRMAR,
          "corrigir" to VocabularioDeVoz.CORRIGIR,
          "alocado" to VocabularioDeVoz.ALOCADO,
          "proximo" to VocabularioDeVoz.PROXIMO,
          "concluir" to VocabularioDeVoz.CONCLUIR,
          "encerrar" to VocabularioDeVoz.ENCERRAR,
          "retomar" to VocabularioDeVoz.RETOMAR,
          // Transversais — doc §3.3, aceitos em todo estado operacional.
          "parar" to VocabularioDeVoz.PARAR,
          "emergencia" to VocabularioDeVoz.EMERGENCIA,
          "repetir" to VocabularioDeVoz.REPETIR,
          "avaria" to VocabularioDeVoz.AVARIA,
          "ruptura" to VocabularioDeVoz.RUPTURA,
          "divergencia" to VocabularioDeVoz.DIVERGENCIA,
      )

  /**
   * O texto correspondente a uma inferência do Rhino, ou vazio quando não há o que publicar.
   *
   * @param entendido `RhinoInference.getIsUnderstood()`. `false` é o caso comum no galpão — fala
   *   fora do contexto, conversa ao lado, ruído de empilhadeira — e devolve texto vazio, a mesma
   *   convenção de "nada decodificado" que o [MotorVosk] e o `MotorSherpaOnnx` já usam para
   *   silêncio. Vazio não publica evento nenhum.
   * @param intencao `RhinoInference.getIntent()`, ou `null` quando não houve inferência.
   * @param slots `RhinoInference.getSlots()`, os argumentos da intenção já preenchidos.
   * @return o texto **já normalizado** ([NormalizadorDeTextoAsr]), como o contrato de
   *   [ResultadoDeAsr.Fechada] exige de todo motor.
   */
  fun sintetizar(entendido: Boolean, intencao: String?, slots: Map<String, String>): String {
    if (!entendido) return ""

    val bruto = if (slots.isEmpty()) INTENCOES[intencao].orEmpty() else textoDosSlots(slots)

    return NormalizadorDeTextoAsr.normalizar(bruto)
  }

  /**
   * Converte a inferência do contexto de quantidade no texto numérico consumido pelo fluxo atual.
   * Detalhes dos slots ficam nesta fronteira e não vazam para a máquina de estados.
   */
  fun sintetizarQuantidade(slots: Map<String, String>): String =
      InterpretadorDeQuantidadeRhino.interpretar(slots)?.toString().orEmpty()

  /**
   * O texto de uma intenção com argumento do contexto principal — check digit, hoje.
   *
   * **O valor do slot é o texto inteiro, e o nome da intenção não entra.** Um check digit falado
   * vira `"47"` (ou `"quarenta e sete"`, dependendo de como o slot foi enumerado no Console), e
   * as duas formas funcionam sem este objeto saber qual delas veio: o [InterpretadorDeFala] já
   * tenta [VocabularioDeVoz.checkDigitExtenso] antes de [VocabularioDeVoz.digitos], exatamente
   * porque o operador usa as duas leituras. Acrescentar o nome da intenção ao texto quebraria as
   * duas — `"check_digit 47"` não é número em leitura nenhuma.
   *
   * Ordenado por nome de slot porque `RhinoInference.getSlots()` devolve um `Map` sem ordem
   * garantida: com um slot só isso não muda nada, e no dia em que houver dois o texto não pode
   * depender da ordem de iteração de um `HashMap`.
   */
  private fun textoDosSlots(slots: Map<String, String>): String =
      slots.toSortedMap().values.joinToString(separator = " ")
}
