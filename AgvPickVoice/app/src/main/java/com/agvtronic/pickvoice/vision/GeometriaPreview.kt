package com.agvtronic.pickvoice.vision

/** Retângulo em pixels lógicos dentro do contêiner da prévia. */
data class RetanguloPreview(
    val esquerda: Float,
    val topo: Float,
    val largura: Float,
    val altura: Float,
)

/** Área efetivamente ocupada pelo vídeo e a ROI central desenhada dentro dela. */
data class GeometriaPreview(
    val video: RetanguloPreview,
    val roi: RetanguloPreview,
)

/**
 * Calcula a geometria de `ContentScale.Fit` sem depender do Compose ou do Android.
 *
 * A ROI é aplicada ao retângulo do vídeo, não ao contêiner inteiro. Isso mantém a moldura
 * alinhada quando a proporção do frame cria barras laterais ou superior/inferior.
 */
fun calcularGeometriaPreview(
    larguraContainer: Float,
    alturaContainer: Float,
    larguraFrame: Int,
    alturaFrame: Int,
    fatorRecorte: Float,
): GeometriaPreview? {
  if (larguraContainer <= 0f || alturaContainer <= 0f || larguraFrame <= 0 || alturaFrame <= 0) {
    return null
  }

  val escala =
      minOf(larguraContainer / larguraFrame.toFloat(), alturaContainer / alturaFrame.toFloat())
  val larguraVideo = larguraFrame * escala
  val alturaVideo = alturaFrame * escala
  val video =
      RetanguloPreview(
          esquerda = (larguraContainer - larguraVideo) / 2f,
          topo = (alturaContainer - alturaVideo) / 2f,
          largura = larguraVideo,
          altura = alturaVideo,
      )

  val fator = fatorRecorte.coerceIn(0f, 1f)
  val larguraRoi = video.largura * fator
  val alturaRoi = video.altura * fator
  val roi =
      RetanguloPreview(
          esquerda = video.esquerda + (video.largura - larguraRoi) / 2f,
          topo = video.topo + (video.altura - alturaRoi) / 2f,
          largura = larguraRoi,
          altura = alturaRoi,
      )

  return GeometriaPreview(video = video, roi = roi)
}
