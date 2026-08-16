package com.agvtronic.pickvoice.vision

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifica o recorte da ROI contra planos sintéticos.
 *
 * Vale o esforço porque este é o único ponto do pipeline de visão que pode falhar **sem**
 * aparecer no logcat: um recorte enviesado por `rowStride` ignorado, ou com o croma meio pixel
 * fora, produz uma imagem que continua parecendo uma imagem e simplesmente não decodifica. O
 * sintoma seria idêntico ao de "o código está longe demais" — e a bancada gastaria horas na
 * hipótese errada, exatamente como aconteceu na fatia de áudio.
 *
 * Cada plano é preenchido com valores derivados da posição (`linha * 10 + coluna`), então o
 * conteúdo esperado do recorte é conferível na mão.
 */
class RecorteRoiTest {

  @Test
  fun `recorte de 60 por cento centraliza e alinha em numeros pares`() {
    val recorte = recortar(largura = 10, altura = 10, fator = 0.6f)

    // 60% de 10 = 6, já par; sobra 4, então o deslocamento é 2 em cada eixo.
    assertEquals(6, recorte.largura)
    assertEquals(6, recorte.altura)
    // Primeiro pixel do recorte: linha 2, coluna 2.
    assertEquals(luma(2, 2), recorte.bytes[0])
    // Último pixel da primeira linha do recorte: linha 2, coluna 7.
    assertEquals(luma(2, 7), recorte.bytes[5])
    // Primeiro pixel da segunda linha: linha 3, coluna 2.
    assertEquals(luma(3, 2), recorte.bytes[6])
  }

  @Test
  fun `dimensao impar do recorte e arredondada para baixo`() {
    // 70% de 10 = 7, ímpar: precisa cair para 6, senão o croma desalinha da luminância.
    val recorte = recortar(largura = 10, altura = 10, fator = 0.7f)

    assertEquals(6, recorte.largura)
    assertEquals(6, recorte.altura)
  }

  @Test
  fun `tamanho do buffer e o de um nv21 do tamanho do recorte`() {
    val recorte = recortar(largura = 10, altura = 10, fator = 0.6f)

    // 6x6 de luminância + metade disso de croma intercalado.
    assertEquals(36 + 18, recorte.bytes.size)
  }

  @Test
  fun `croma sai com V antes de U`() {
    val recorte = recortar(largura = 10, altura = 10, fator = 0.6f)

    val inicioCroma = recorte.largura * recorte.altura
    // Primeira amostra de croma do recorte: linha 1, coluna 1 do plano de meia resolução.
    assertEquals(croma(1, 1, base = V_BASE), recorte.bytes[inicioCroma])
    assertEquals(croma(1, 1, base = U_BASE), recorte.bytes[inicioCroma + 1])
  }

  @Test
  fun `croma semiplanar e croma planar produzem o mesmo nv21`() {
    // O mesmo conteúdo, entregue dos dois jeitos que um decodificador pode entregar:
    // planos separados (pixelStride 1) e planos intercalados (pixelStride 2, o caso do NV12
    // que a maioria dos codecs de hardware produz).
    val planar = recortar(largura = 10, altura = 10, fator = 0.6f, pixelStrideCroma = 1)
    val semiplanar = recortar(largura = 10, altura = 10, fator = 0.6f, pixelStrideCroma = 2)

    assertArrayEquals(planar.bytes, semiplanar.bytes)
  }

  @Test
  fun `padding de fim de linha nao vaza para o recorte`() {
    // rowStride maior que a largura é o caso normal, não a exceção: o decodificador alinha
    // cada linha e o resto do buffer é lixo. Se o recorte ignorasse isso, a imagem sairia
    // enviesada em diagonal — e continuaria "parecendo" uma imagem.
    val semPadding = recortar(largura = 10, altura = 10, fator = 0.6f, paddingLinha = 0)
    val comPadding = recortar(largura = 10, altura = 10, fator = 0.6f, paddingLinha = 7)

    assertArrayEquals(semPadding.bytes, comPadding.bytes)
  }

  @Test
  fun `fator de 100 por cento devolve o quadro inteiro`() {
    val recorte = recortar(largura = 10, altura = 10, fator = 1f)

    assertEquals(10, recorte.largura)
    assertEquals(10, recorte.altura)
    assertEquals(luma(0, 0), recorte.bytes[0])
    assertEquals(luma(9, 9), recorte.bytes[99])
  }

  @Test
  fun `luminancia com pixelStride maior que um e lida saltando`() {
    // Alguns codecs entregam até a luminância intercalada. Raro, mas o layout é declarado
    // pelo plano e não custa nada respeitá-lo.
    val normal = recortar(largura = 10, altura = 10, fator = 0.6f, pixelStrideLuma = 1)
    val saltando = recortar(largura = 10, altura = 10, fator = 0.6f, pixelStrideLuma = 2)

    assertArrayEquals(normal.bytes, saltando.bytes)
  }

  @Test
  fun `a posicao do buffer de origem nao e alterada`() {
    // Os buffers vêm dos planos da Image do codec. Mexer na posição deles seria mexer em estado
    // que é do decodificador, não nosso.
    val y = planoLuma(10, 10, paddingLinha = 0, pixelStride = 1)
    val u = planoCroma(10, 10, paddingLinha = 0, pixelStride = 1, base = U_BASE)
    val v = planoCroma(10, 10, paddingLinha = 0, pixelStride = 1, base = V_BASE)

    recortarParaNv21(y, u, v, largura = 10, altura = 10, fatorRecorte = 0.6f)

    assertEquals(0, y.buffer.position())
    assertEquals(0, u.buffer.position())
    assertEquals(0, v.buffer.position())
  }

  @Test(expected = IllegalArgumentException::class)
  fun `fator fora do intervalo e erro de programacao`() {
    recortar(largura = 10, altura = 10, fator = 1.5f)
  }

  // -----------------------------------------------------------------------------------
  // Fábricas de planos sintéticos
  // -----------------------------------------------------------------------------------

  private fun recortar(
      largura: Int,
      altura: Int,
      fator: Float,
      paddingLinha: Int = 0,
      pixelStrideLuma: Int = 1,
      pixelStrideCroma: Int = 1,
  ): RecorteNv21 =
      recortarParaNv21(
          y = planoLuma(largura, altura, paddingLinha, pixelStrideLuma),
          u = planoCroma(largura, altura, paddingLinha, pixelStrideCroma, U_BASE),
          v = planoCroma(largura, altura, paddingLinha, pixelStrideCroma, V_BASE),
          largura = largura,
          altura = altura,
          fatorRecorte = fator,
      )

  private fun planoLuma(
      largura: Int,
      altura: Int,
      paddingLinha: Int,
      pixelStride: Int,
  ): PlanoImagem {
    val rowStride = largura * pixelStride + paddingLinha
    val bytes = ByteArray(rowStride * altura) { LIXO }
    for (linha in 0 until altura) {
      for (coluna in 0 until largura) {
        bytes[linha * rowStride + coluna * pixelStride] = luma(linha, coluna)
      }
    }
    return PlanoImagem(ByteBuffer.wrap(bytes), rowStride, pixelStride)
  }

  private fun planoCroma(
      largura: Int,
      altura: Int,
      paddingLinha: Int,
      pixelStride: Int,
      base: Int,
  ): PlanoImagem {
    val larguraCroma = largura / 2
    val alturaCroma = altura / 2
    val rowStride = larguraCroma * pixelStride + paddingLinha
    val bytes = ByteArray(rowStride * alturaCroma) { LIXO }
    for (linha in 0 until alturaCroma) {
      for (coluna in 0 until larguraCroma) {
        bytes[linha * rowStride + coluna * pixelStride] = croma(linha, coluna, base)
      }
    }
    return PlanoImagem(ByteBuffer.wrap(bytes), rowStride, pixelStride)
  }

  private companion object {
    /** Valor que não deve aparecer em recorte nenhum: marca padding e amostras puladas. */
    const val LIXO: Byte = -1

    const val U_BASE = 100
    const val V_BASE = 200

    fun luma(linha: Int, coluna: Int): Byte = (linha * 10 + coluna).toByte()

    fun croma(linha: Int, coluna: Int, base: Int): Byte = (base + linha * 5 + coluna).toByte()
  }
}
