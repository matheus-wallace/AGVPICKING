package com.agvtronic.pickvoice.vision

import java.nio.ByteBuffer

/**
 * Um plano de imagem, apontando direto para o buffer do decodificador.
 *
 * [ByteBuffer] e não `ByteArray` de propósito: copiar os três planos inteiros para o heap antes
 * de recortar faria existir, ainda que por um instante, uma segunda cópia do **quadro completo** —
 * exatamente o que o doc §4.4/§9.2 diz que não acontece. Lendo do buffer original, o único frame
 * completo que existe é o que o codec já tinha, e o que sai daqui é só a ROI.
 *
 * `rowStride` e `pixelStride` existem porque decodificador nenhum entrega os planos justos: sobra
 * padding no fim da linha, e o croma costuma vir intercalado (`pixelStride = 2`) em vez de
 * planar. Ignorar os dois é o jeito clássico de produzir uma imagem enviesada em diagonal.
 */
data class PlanoImagem(
    val buffer: ByteBuffer,
    val rowStride: Int,
    val pixelStride: Int,
)

/** O recorte pronto para o leitor de código: NV21 e as dimensões que ele tem de fato. */
data class RecorteNv21(
    val bytes: ByteArray,
    val largura: Int,
    val altura: Int,
) {
  override fun equals(other: Any?): Boolean =
      this === other ||
          (other is RecorteNv21 &&
              largura == other.largura &&
              altura == other.altura &&
              bytes.contentEquals(other.bytes))

  override fun hashCode(): Int = (bytes.contentHashCode() * 31 + largura) * 31 + altura
}

/**
 * Recorta a região central do frame e devolve **só ela**, em NV21.
 *
 * Este é o passo que o doc §6.3 chama de obrigatório — "capturar → recortar a região central →
 * descartar o quadro completo → rodar a cascata sobre o recorte" — e é também o que sustenta a
 * afirmação de privacidade do §9.2: depois desta função, quem chamou fecha a imagem original
 * (doc §4.4) e o quadro completo deixa de existir no processo. Nenhum passo de decodificação
 * roda antes daqui.
 *
 * Kotlin puro de propósito: recebe os planos já extraídos ([PlanoImagem]) em vez de uma
 * `android.media.Image`, então a conversão inteira é exercitável por teste de unidade comum, sem
 * emulador. Foi assim que o band-pass da fatia de áudio pegou um defeito real antes de chegar ao
 * aparelho.
 *
 * ### Sobre NV21
 *
 * É um dos dois formatos que o ML Kit aceita em buffer (o outro é YV12), e é o layout mais
 * próximo do que os decodificadores entregam: luminância inteira, seguida do croma **V antes de
 * U**, intercalado, com metade da resolução em cada eixo. A troca de ordem entre V e U é o erro
 * mais comum aqui e não quebra a decodificação de código de barras — que só olha luminância —,
 * mas deixaria qualquer preview com cor invertida.
 *
 * ### Alinhamento par
 *
 * Largura, altura e deslocamentos são forçados a números pares. Em 4:2:0 cada amostra de croma
 * cobre 2×2 pixels de luminância; um recorte ímpar deslocaria o croma meio pixel em relação à
 * luminância a cada linha.
 *
 * @param fatorRecorte fração central retida, em (0, 1]. Vem de [AjustesVisao.fatorRecorte].
 * @throws IllegalArgumentException se as dimensões ou o fator forem inválidos — é erro de
 *   programação, não condição de operação.
 */
fun recortarParaNv21(
    y: PlanoImagem,
    u: PlanoImagem,
    v: PlanoImagem,
    largura: Int,
    altura: Int,
    fatorRecorte: Float,
): RecorteNv21 {
  require(largura >= 2 && altura >= 2) { "Dimensões inválidas: ${largura}x$altura" }
  require(fatorRecorte > 0f && fatorRecorte <= 1f) { "fatorRecorte fora de (0,1]: $fatorRecorte" }

  val larguraRoi = parOuMenor((largura * fatorRecorte).toInt()).coerceAtLeast(2)
  val alturaRoi = parOuMenor((altura * fatorRecorte).toInt()).coerceAtLeast(2)
  val x0 = parOuMenor((largura - larguraRoi) / 2)
  val y0 = parOuMenor((altura - alturaRoi) / 2)

  val tamanhoLuma = larguraRoi * alturaRoi
  val nv21 = ByteArray(tamanhoLuma + tamanhoLuma / 2)

  // Duplicatas: leem e movem a própria posição sem tocar na do buffer original, que pertence ao
  // decodificador. Uma por plano, criada fora dos laços.
  val lumaBuffer = y.buffer.duplicate()
  val uBuffer = u.buffer.duplicate()
  val vBuffer = v.buffer.duplicate()

  // Luminância: uma linha do recorte por vez.
  var destino = 0
  for (linha in 0 until alturaRoi) {
    val origem = (y0 + linha) * y.rowStride + x0 * y.pixelStride
    if (y.pixelStride == 1) {
      // Caso comum: a linha do recorte é contígua na origem, então uma cópia em bloco resolve.
      lumaBuffer.position(origem)
      lumaBuffer.get(nv21, destino, larguraRoi)
      destino += larguraRoi
    } else {
      var posicao = origem
      for (coluna in 0 until larguraRoi) {
        nv21[destino++] = lumaBuffer.get(posicao)
        posicao += y.pixelStride
      }
    }
  }

  // Croma: V e U intercalados, meia resolução em cada eixo.
  val larguraCroma = larguraRoi / 2
  val alturaCroma = alturaRoi / 2
  val xCroma = x0 / 2
  val yCroma = y0 / 2
  for (linha in 0 until alturaCroma) {
    var origemV = (yCroma + linha) * v.rowStride + xCroma * v.pixelStride
    var origemU = (yCroma + linha) * u.rowStride + xCroma * u.pixelStride
    for (coluna in 0 until larguraCroma) {
      nv21[destino++] = vBuffer.get(origemV)
      nv21[destino++] = uBuffer.get(origemU)
      origemV += v.pixelStride
      origemU += u.pixelStride
    }
  }

  return RecorteNv21(nv21, larguraRoi, alturaRoi)
}

private fun parOuMenor(valor: Int): Int = valor and 1.inv()
