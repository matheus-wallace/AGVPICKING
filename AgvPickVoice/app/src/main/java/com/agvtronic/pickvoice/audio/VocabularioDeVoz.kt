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

  /** Quebra o texto do ASR em palavras minúsculas, sem espaços vazios. */
  fun palavras(texto: String): List<String> =
      texto.trim().lowercase().split(ESPACOS).filter { it.isNotEmpty() }
}
