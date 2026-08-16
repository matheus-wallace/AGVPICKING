package com.agvtronic.pickvoice.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.meta.wearable.dat.camera.types.PhotoData
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Converte uma foto do DAT diretamente em uma ROI orientada.
 *
 * A implementação nunca cria arquivo. Para HEIC, a única cópia intermediária dos bytes é zerada
 * no `finally`; o bitmap completo é reciclado assim que o recorte central existe.
 */
fun prepararRoiDaFoto(photo: PhotoData, fatorRecorte: Float): Bitmap {
  require(fatorRecorte > 0f && fatorRecorte <= 1f) { "fatorRecorte fora de (0,1]" }
  return when (photo) {
    is PhotoData.Bitmap -> recortarEOrientar(photo.bitmap, fatorRecorte, Matrix())
    is PhotoData.HEIC -> {
      val buffer = photo.data.duplicate().apply { rewind() }
      val bytes = ByteArray(buffer.remaining())
      try {
        buffer.get(bytes)
        val bitmap =
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: error("Foto HEIC não pôde ser decodificada")
        recortarEOrientar(bitmap, fatorRecorte, matrizExif(bytes))
      } finally {
        bytes.fill(0)
      }
    }
  }
}

/** Libera uma foto recebida antes que o preparo pudesse assumir sua propriedade. */
fun descartarFotoOriginal(photo: PhotoData?) {
  val bitmap = (photo as? PhotoData.Bitmap)?.bitmap ?: return
  if (!bitmap.isRecycled) bitmap.recycle()
}

/** Geometria pura do recorte, separada para teste sem Android. */
data class RetanguloRecorte(val x: Int, val y: Int, val largura: Int, val altura: Int)

fun calcularRecorteCentral(largura: Int, altura: Int, fator: Float): RetanguloRecorte {
  require(largura > 0 && altura > 0)
  require(fator > 0f && fator <= 1f)
  val larguraRoi = (largura * fator).toInt().coerceIn(1, largura)
  val alturaRoi = (altura * fator).toInt().coerceIn(1, altura)
  return RetanguloRecorte(
      x = (largura - larguraRoi) / 2,
      y = (altura - alturaRoi) / 2,
      largura = larguraRoi,
      altura = alturaRoi,
  )
}

private fun recortarEOrientar(origem: Bitmap, fator: Float, matriz: Matrix): Bitmap {
  require(!origem.isRecycled) { "Bitmap da foto já foi reciclado" }
  val area = calcularRecorteCentral(origem.width, origem.height, fator)
  var roi: Bitmap? = null
  try {
    roi =
        Bitmap.createBitmap(
            origem,
            area.x,
            area.y,
            area.largura,
            area.altura,
            matriz,
            true,
        )
    // Com fator 1 e matriz identidade, Android pode devolver a própria origem. O leitor precisa
    // de uma instância independente porque a foto completa será descartada agora.
    if (roi === origem) {
      roi = origem.copy(origem.config ?: Bitmap.Config.ARGB_8888, false)
    }
    return checkNotNull(roi)
  } catch (erro: Throwable) {
    roi?.takeIf { it !== origem && !it.isRecycled }?.recycle()
    throw erro
  } finally {
    if (!origem.isRecycled) origem.recycle()
  }
}

private fun matrizExif(bytes: ByteArray): Matrix {
  val orientacao =
      try {
        ByteArrayInputStream(bytes).use {
          ExifInterface(it)
              .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
      } catch (_: IOException) {
        ExifInterface.ORIENTATION_NORMAL
      }
  return Matrix().apply {
    when (orientacao) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
      ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        postRotate(90f)
        postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        postRotate(270f)
        postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
    }
  }
}
