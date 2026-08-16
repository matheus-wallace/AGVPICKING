package com.agvtronic.pickvoice.vision

/**
 * Exige que o mesmo código seja lido em frames consecutivos antes de valer como leitura.
 *
 * ### Por que isso existe
 *
 * A primeira bancada desta fatia produziu falso positivo em 3 de 5 execuções: pedaços do código
 * de barras certo decodificando como outro código — e dois deles eram EAN-13 com **dígito
 * verificador válido**, ou seja, indistinguíveis de uma leitura boa quando vistos isolados
 * (design.md - "Falso positivo de leitura"). O check digit do EAN-13 só pega cerca de 90% dos
 * erros aleatórios, e o restante passa.
 *
 * Em separação farmacêutica esse é o pior erro possível: o sistema confirma o produto errado com
 * a mesma confiança do produto certo. O doc §10.4 manda medir falso positivo justamente por
 * isso.
 *
 * ### Por que funciona
 *
 * Leitura correta se repete — o código continua no campo de visão, frame após frame. Falso
 * positivo é acidente de um frame específico (borrão de movimento, recorte cortando o símbolo,
 * ângulo), e o acidente seguinte, se houver, tende a produzir **outro** valor errado. Exigir
 * repetição do mesmo valor derruba a classe inteira de erro, e não só a que veio do Code 128.
 *
 * Um valor diferente **reinicia a contagem** em vez de decrementá-la: duas leituras alternando
 * entre dois valores não deve publicar nenhum dos dois.
 *
 * ### Custo
 *
 * [confirmacoes] frames a 7 fps. Com o valor padrão 2, cerca de 0,3 s a mais — desprezível perto
 * dos 1,9 s medidos entre subir o stream e a primeira leitura, e barato perto de confirmar a
 * caixa errada.
 *
 * Não é thread-safe por si: vive confinado na thread do leitor, que é única (ver
 * `LeitorDeCodigo`).
 */
class ConsensoDeLeitura(private val confirmacoes: Int = 2) {

  init {
    require(confirmacoes >= 1) { "confirmacoes precisa ser >= 1: $confirmacoes" }
  }

  private var ultimoCodigo: String? = null
  private var repeticoes = 0

  /**
   * Registra uma leitura e diz se ela já pode valer.
   *
   * @return `true` quando [codigo] atingiu o número de confirmações exigido. Continua devolvendo
   *   `true` enquanto o mesmo código seguir chegando — quem publica uma vez só é o chamador, que
   *   é também quem sabe o que é "um escaneamento".
   */
  fun registrar(codigo: String): Boolean {
    if (codigo == ultimoCodigo) {
      // Satura para não estourar o Int numa cena parada com o código no quadro.
      if (repeticoes < confirmacoes) repeticoes++
    } else {
      ultimoCodigo = codigo
      repeticoes = 1
    }
    return repeticoes >= confirmacoes
  }

  /** Esquece o que foi lido. Chamado a cada novo escaneamento. */
  fun reiniciar() {
    ultimoCodigo = null
    repeticoes = 0
  }
}
