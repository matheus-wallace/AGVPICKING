package com.agvtronic.pickvoice.audio

import kotlin.math.floor

/**
 * Reamostra o fluxo de áudio de uma taxa para outra por interpolação linear, mantendo a fase
 * entre janelas consecutivas.
 *
 * ### Por que precisa existir
 *
 * O Silero VAD do sherpa-onnx **só aceita 16 kHz**: `silero-vad-model.cc` compara a taxa e chama
 * `SHERPA_ONNX_EXIT(-1)` — que é `_Exit`, ou seja, mata o processo — para qualquer outro valor.
 * A [FonteAudio] deste projeto entrega **8 kHz** nas duas implementações, porque é a taxa do
 * canal HFP do óculos (doc §2.1). Sem esta conversão o app não degradaria: ele morreria, sem
 * exceção e sem stack trace (design.md de add-sherpa-onnx-asr-engine, "Verificação da API do
 * sherpa-onnx", itens (a) e (b)).
 *
 * O decodificador Whisper, ao contrário do VAD, reamostra sozinho quando recebe outra taxa. Mas
 * como o trecho já sai do VAD em 16 kHz, ele é entregue nessa taxa e nenhuma reamostragem dupla
 * acontece.
 *
 * ### O que a interpolação linear é e não é
 *
 * É a opção mais simples que atende o contrato, e é **ponto de partida documentado, não medição**
 * — interpolar linearmente equivale a um filtro passa-baixa bem suave, então sobra imagem
 * espectral acima de 4 kHz que um interpolador polifásico removeria. Para o VAD, que decide
 * presença de voz, isso tende a não importar; se a bancada mostrar o VAD disparando em ruído,
 * este é o primeiro lugar a olhar.
 *
 * O que nenhum reamostrador conserta: um sinal que passou por 8 kHz não tem conteúdo acima de
 * 4 kHz, e subir a taxa não devolve o que a decimação tirou. Essa perda vem do canal, não daqui.
 *
 * ### Continuidade entre janelas
 *
 * A classe guarda estado ([posicao] e [ultimaAmostra]) porque as janelas chegam picadas e a
 * interpolação da primeira amostra de uma janela precisa da última amostra da anterior. Sem isso
 * apareceria uma descontinuidade a cada 64 ms — um clique periódico, exatamente na cadência que
 * um detector de voz confunde com início de fala.
 *
 * Confinada na thread de áudio, como todo o resto do pipeline: não é thread-safe e não precisa
 * ser.
 *
 * @param taxaEntrada Hz do que chega (o que a [FonteAudio] declara).
 * @param taxaSaida Hz do que sai.
 */
class ReamostradorLinear(private val taxaEntrada: Int, private val taxaSaida: Int) {

  init {
    require(taxaEntrada > 0 && taxaSaida > 0) {
      "Taxas precisam ser positivas: $taxaEntrada -> $taxaSaida"
    }
  }

  /** Quantas amostras de entrada avançam por amostra de saída. 0,5 na subida de 8 para 16 kHz. */
  private val passo: Double = taxaEntrada.toDouble() / taxaSaida

  /** `true` quando não há nada a fazer — o caminho de `degradarCanal=false`, que já dá 16 kHz. */
  private val transparente: Boolean = taxaEntrada == taxaSaida

  /**
   * Onde a próxima amostra de saída cai, em índice de amostra de entrada da janela atual.
   *
   * Fica negativa ao fim de uma janela quando a próxima saída cai *entre* a última amostra dela e
   * a primeira da janela seguinte — é justamente esse caso que [ultimaAmostra] atende.
   */
  private var posicao: Double = 0.0

  /** A última amostra da janela anterior, o vizinho à esquerda da posição negativa. */
  private var ultimaAmostra: Float = 0f

  /**
   * Reamostra uma janela, continuando de onde a anterior parou.
   *
   * O tamanho da saída **varia de uma janela para outra** (1023 e depois 1024 amostras, na subida
   * de 8 para 16 kHz), porque a fase não é múltipla inteira do tamanho da janela. Isso não é
   * problema para o consumidor: o `VoiceActivityDetector` do sherpa-onnx acumula internamente e
   * fatia em janelas do tamanho que o modelo exige.
   */
  fun processar(janela: FloatArray): FloatArray {
    if (transparente || janela.isEmpty()) return janela

    val ultimoIndice = janela.size - 1
    // Quantas saídas cabem antes de a interpolação precisar da janela seguinte.
    val quantidade = floor((ultimoIndice - posicao) / passo).toInt() + 1
    if (quantidade <= 0) {
      posicao -= janela.size
      ultimaAmostra = janela[ultimoIndice]
      return FloatArray(0)
    }

    val saida = FloatArray(quantidade)
    var p = posicao
    for (i in 0 until quantidade) {
      val esquerda = floor(p).toInt()
      val fracao = (p - esquerda).toFloat()
      // `esquerda` só chega a -1, e nesse caso o vizinho da esquerda é a janela anterior.
      val a = if (esquerda < 0) ultimaAmostra else janela[esquerda]
      // Quando a posição cai exatamente sobre a última amostra, `fracao` é 0 e o vizinho da
      // direita não pesa — usar `a` evita ler uma amostra além do fim da janela, que é onde a
      // aritmética de ponto flutuante coloca o último passo quando ele fecha redondo.
      val b = if (esquerda < ultimoIndice) janela[esquerda + 1] else a
      saida[i] = a + (b - a) * fracao
      p += passo
    }

    posicao = p - janela.size
    ultimaAmostra = janela[ultimoIndice]
    return saida
  }

  /** Esquece a fase e o vizinho guardado. Usado quando a elocução recomeça do zero. */
  fun reiniciar() {
    posicao = 0.0
    ultimaAmostra = 0f
  }
}
