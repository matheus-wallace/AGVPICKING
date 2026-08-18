package com.agvtronic.pickvoice.audio

/**
 * As palavras que o operador pode dizer e como elas viram número.
 *
 * Um objeto só para as duas pontas do reconhecimento: o [SeletorDeEscuta] monta a gramática do
 * Vosk a partir daqui e o [InterpretadorDeFala] compara o texto reconhecido contra as mesmas
 * constantes. Se as duas listas morassem em arquivos diferentes, a primeira palavra
 * acrescentada em uma delas viraria um comando que o decodificador entende e o interpretador
 * descarta — o tipo de divergência que só aparece em bancada, com o operador falando.
 *
 * Kotlin puro: sem Android, sem Vosk, sem corrotina. A ortografia é a do modelo pt-BR
 * (acentuada), porque uma palavra fora do léxico do modelo é ignorada ao montar a gramática.
 *
 * ### Ordem das declarações
 *
 * As tabelas privadas vêm antes das listas públicas de propósito: um `val` de objeto que lê
 * outro `val` declarado abaixo dele enxerga `null` durante a construção, e o defeito só
 * apareceria em runtime.
 */
object VocabularioDeVoz {

  // -----------------------------------------------------------------------------------
  // Comandos do fluxo — a coluna "Fala aceita" do design.md.
  // -----------------------------------------------------------------------------------

  const val INICIAR = "iniciar"
  const val CHEGUEI = "cheguei"
  const val CONFIRMAR = "confirmar"
  const val CORRIGIR = "corrigir"
  const val ALOCADO = "alocado"
  const val PROXIMO = "próximo"
  const val CONCLUIR = "concluir"
  const val ENCERRAR = "encerrar"
  const val RETOMAR = "retomar"

  // -----------------------------------------------------------------------------------
  // Transversais — doc §3.3, aceitos em todo estado operacional.
  // -----------------------------------------------------------------------------------

  const val PARAR = "parar"
  const val EMERGENCIA = "emergência"
  const val REPETIR = "repetir"
  const val AVARIA = "avaria"
  const val RUPTURA = "ruptura"
  const val DIVERGENCIA = "divergência"

  /**
   * O token que o Vosk devolve para fala que não está na gramática.
   *
   * Precisa entrar em toda gramática fechada e nunca pode virar evento: sem ele o
   * decodificador é obrigado a escolher a palavra conhecida mais parecida, e uma tosse vira
   * "parar".
   */
  const val DESCONHECIDA = "[unk]"

  /** O "e" de "vinte e três" — conectivo, não número. */
  private const val CONECTIVO = "e"

  private val ESPACOS = Regex("\\s+")

  /** Palavra falada -> algarismo, para leitura dígito a dígito. */
  private val VALOR_DIGITO: Map<String, Int> =
      mapOf(
          "zero" to 0,
          "um" to 1,
          "dois" to 2,
          "três" to 3,
          "quatro" to 4,
          "cinco" to 5,
          "seis" to 6,
          // Como se lê 6 em sequência de dígitos no Brasil. Só vale aqui: em quantidade,
          // "meia" seria ambígua com "meia dúzia".
          "meia" to 6,
          "sete" to 7,
          "oito" to 8,
          "nove" to 9,
      )

  /**
   * A mesma tabela de dígitos, sem "meia" — segue a regra de [VALOR_DIGITO]/[QUANTIDADES]: em
   * quantidade a palavra é ambígua, então só vale onde o que se lê é código.
   */
  private val VALOR_DIGITO_EM_QUANTIDADE: Map<String, Int> = VALOR_DIGITO - "meia"

  /** Teto de algarismos de uma quantidade falada dígito a dígito — 999 é o máximo aceito. */
  private const val DIGITOS_MAXIMOS_DA_QUANTIDADE = 3

  /**
   * Palavra falada -> valor, para números de 0 a 999.
   *
   * `linkedMapOf` porque a ordem de inserção vira a ordem das palavras na gramática, e uma
   * gramática legível no logcat é o que torna a calibração de bancada possível.
   */
  private val VALOR_NUMERO: Map<String, Int> =
      linkedMapOf(
          "zero" to 0,
          "um" to 1,
          "dois" to 2,
          "três" to 3,
          "quatro" to 4,
          "cinco" to 5,
          "seis" to 6,
          "sete" to 7,
          "oito" to 8,
          "nove" to 9,
          "dez" to 10,
          "onze" to 11,
          "doze" to 12,
          "treze" to 13,
          "catorze" to 14,
          "quatorze" to 14,
          "quinze" to 15,
          "dezesseis" to 16,
          "dezessete" to 17,
          "dezoito" to 18,
          "dezenove" to 19,
          "vinte" to 20,
          "trinta" to 30,
          "quarenta" to 40,
          "cinquenta" to 50,
          "sessenta" to 60,
          "setenta" to 70,
          "oitenta" to 80,
          "noventa" to 90,
          "cem" to 100,
          "cento" to 100,
          "duzentos" to 200,
          "trezentos" to 300,
          "quatrocentos" to 400,
          "quinhentos" to 500,
          "seiscentos" to 600,
          "setecentos" to 700,
          "oitocentos" to 800,
          "novecentos" to 900,
      )

  /**
   * [VALOR_NUMERO] restrita a 0..99 — check digit não tem casa de centena, e cada palavra de
   * centena a mais é um vizinho a mais para o decodificador confundir com um dígito isolado
   * ("quatro" revisado para "quatrocentos" no meio da fala, bancada de 17/08/2026).
   */
  private val VALOR_NUMERO_ATE_NOVENTA_E_NOVE: Map<String, Int> = VALOR_NUMERO.filterValues { it < 100 }

  /**
   * Valor mínimo de uma palavra falada para abrir uma leitura de check digit por extenso.
   *
   * "oito dois" é uma leitura dígito a dígito perfeitamente comum do check digit `82`; sem essa
   * exigência, [checkDigitExtenso] a leria por extenso e somaria 8 + 2 = 10 — dentro do
   * intervalo válido, e errado. Exigir que a primeira palavra já valha uma dezena resolve isso e,
   * de graça, rejeita um algarismo isolado por extenso ("sete" sozinho), ambíguo demais com fala
   * cortada no meio de um "quarenta e sete" que perdeu a primeira palavra.
   */
  private const val MENOR_DEZENA = 10

  val TRANSVERSAIS: List<String> =
      listOf(PARAR, EMERGENCIA, REPETIR, AVARIA, RUPTURA, DIVERGENCIA)

  /** Dígito a dígito, para check digit (doc §7.1 e §7.2). */
  val DIGITOS: List<String> = VALOR_DIGITO.keys.toList()

  /**
   * Números falados por extenso de 0 a 999 — o intervalo que cobre a quantidade de qualquer
   * linha de separação, incluindo as 106 unidades da ordem mockada de rateio.
   */
  val QUANTIDADES: List<String> = VALOR_NUMERO.keys.toList() + CONECTIVO

  /**
   * Números falados por extenso restritos a 0..99, para [checkDigitExtenso] — versão de
   * [QUANTIDADES] sem as ~10 palavras de centena, que não fazem sentido num check digit de dois
   * algarismos e só existem para confundir o decodificador (doc de design, Decisão 1).
   */
  val CHECK_DIGIT_POR_EXTENSO: List<String> =
      VALOR_NUMERO_ATE_NOVENTA_E_NOVE.keys.toList() + CONECTIVO

  /**
   * Os dígitos falados como uma string de algarismos, ou `null` se alguma palavra não for
   * dígito.
   *
   * Devolve string e não número de propósito: check digit é código, e `"07"` nunca pode virar
   * `7` — a comparação com a senha do endereço é literal (doc §7.1).
   */
  fun digitos(texto: String): String? {
    val palavras = palavras(texto)
    if (palavras.isEmpty()) return null

    // O modelo pode devolver o algarismo já escrito ("47") em vez das palavras.
    if (palavras.size == 1 && palavras.first().all(Char::isDigit)) return palavras.first()

    val algarismos = StringBuilder()
    for (palavra in palavras) {
      algarismos.append(VALOR_DIGITO[palavra] ?: return null)
    }
    return algarismos.toString()
  }

  /**
   * O número falado por extenso, ou `null` quando a sequência não é um número plausível.
   *
   * A soma dos valores resolve "cento e vinte e três" (100 + 20 + 3) sem tabela de casos, e a
   * exigência de que cada valor seja **estritamente menor** que o anterior é o que impede
   * "dois dois" de virar 4: em português, um número falado tem magnitudes decrescentes.
   */
  fun numero(texto: String): Int? {
    val palavras = palavras(texto).filter { it != CONECTIVO }
    if (palavras.isEmpty()) return null

    if (palavras.size == 1 && palavras.first().all(Char::isDigit)) {
      return palavras.first().toIntOrNull()
    }

    var total = 0
    var anterior = Int.MAX_VALUE
    for (palavra in palavras) {
      val valor = VALOR_NUMERO[palavra] ?: return null
      if (valor >= anterior) return null
      total += valor
      anterior = valor
    }
    return total
  }

  /**
   * O check digit falado por extenso ("quarenta e sete" -> `"47"`), ou `null` quando o texto não
   * é uma leitura por extenso válida de check digit.
   *
   * Reaproveita [numero] em vez de uma tabela própria, e formata com zero à esquerda pelo mesmo
   * motivo de [digitos]: a comparação com a senha do endereço é literal (doc §7.1). Dois cuidados
   * que [numero] sozinho não cobre: o intervalo tem que caber num check digit (0..99, checado
   * aqui) e a primeira palavra falada tem que já valer uma dezena ([MENOR_DEZENA]) — sem isso,
   * "oito dois" (leitura dígito a dígito de `82`) somaria 8 + 2 = 10 e passaria como extenso
   * válido.
   */
  fun checkDigitExtenso(texto: String): String? {
    val primeira = palavras(texto).firstOrNull { it != CONECTIVO } ?: return null
    if ((VALOR_NUMERO[primeira] ?: return null) < MENOR_DEZENA) return null

    val valor = numero(texto) ?: return null
    if (valor !in 0..99) return null
    return "%02d".format(valor)
  }

  /**
   * A quantidade falada algarismo por algarismo ("um dois" -> 12), ou `null` quando a sequência
   * não é lida assim.
   *
   * Complementa [numero], que só entende o número por extenso e recusa "um dois" de propósito —
   * lá magnitudes não decrescentes são ruído. Aqui a leitura é a mesma de [digitos], mas o
   * resultado é `Int` e não string: quantidade é número, e o zero à esquerda de "zero cinco"
   * pode cair sem perder informação.
   */
  fun numeroDigitoADigito(texto: String): Int? {
    val palavras = palavras(texto)
    if (palavras.isEmpty() || palavras.size > DIGITOS_MAXIMOS_DA_QUANTIDADE) return null

    val algarismos = StringBuilder()
    for (palavra in palavras) {
      algarismos.append(VALOR_DIGITO_EM_QUANTIDADE[palavra] ?: return null)
    }
    return algarismos.toString().toIntOrNull()
  }

  /** Quebra o texto do ASR em palavras minúsculas, sem espaços vazios. */
  fun palavras(texto: String): List<String> =
      texto.trim().lowercase().split(ESPACOS).filter { it.isNotEmpty() }
}
