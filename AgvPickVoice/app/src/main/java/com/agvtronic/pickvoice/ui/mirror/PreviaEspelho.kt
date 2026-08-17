package com.agvtronic.pickvoice.ui.mirror

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agvtronic.pickvoice.vision.DiagnosticoVisao
import com.agvtronic.pickvoice.vision.calcularGeometriaPreview

/**
 * A prévia espelho do doc §12 como componente reutilizável: a superfície de vídeo e a moldura
 * de ROI alinhada ao frame efetivamente renderizado.
 *
 * Extraída de `MirrorScreen` para que a tela operacional hospede a mesma prévia durante a
 * validação de produto sem duplicar as regras de anexar/remover `Surface` já verificadas em
 * bancada: quem sai de composição destrói o `TextureView`, o callback chama [aoRemover] e o
 * renderizador para. Nenhum frame é retido aqui — a `Surface` só embrulha a `SurfaceTexture` do
 * `TextureView`, e o componente não guarda imagem, buffer nem `Bitmap`.
 */
@Composable
fun PreviaEspelho(
    diagnostico: DiagnosticoVisao,
    aoAnexar: (Surface) -> Unit,
    aoRemover: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val largura = diagnostico.larguraEfetiva ?: diagnostico.qualidade.largura
  val altura = diagnostico.alturaEfetiva ?: diagnostico.qualidade.altura
  val proporcao = largura.toFloat() / altura.toFloat()

  Box(modifier.fillMaxWidth().aspectRatio(proporcao).background(Color.Black)) {
    SuperficieDePrevia(aoAnexar = aoAnexar, aoRemover = aoRemover)
    MolduraRoi(
        larguraFrame = largura,
        alturaFrame = altura,
        fatorRecorte = diagnostico.fatorRecorte,
    )
  }
}

/**
 * A mesma prévia ligada ao [MirrorViewModel], para quem já tem o ViewModel em mãos.
 *
 * Existe para que a tela operacional receba a prévia como slot sem conhecer o controlador de
 * visão: a `MainActivity` passa este componente e o ViewModel continua sendo o único dono das
 * chamadas de anexar/remover.
 */
@Composable
fun PreviaEspelho(viewModel: MirrorViewModel, modifier: Modifier = Modifier) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  PreviaEspelho(
      diagnostico = uiState.diagnostico,
      aoAnexar = viewModel::anexar,
      aoRemover = viewModel::remover,
      modifier = modifier,
  )
}

/**
 * A superfície de vídeo em si.
 *
 * `TextureView` e não `SurfaceView`: o segundo é uma camada de composição própria, um buraco
 * recortado na janela, e não acompanha de forma confiável um componente que é movido ou
 * transformado dentro da árvore Compose — em bancada isso aparece como tela preta ao arrastar a
 * miniatura. O `TextureView` desenha como qualquer outra View, então segue o arraste.
 *
 * A `Surface` aqui é construída pelo componente a partir da `SurfaceTexture`, e por isso é ele
 * quem precisa liberá-la: são dois objetos distintos, e soltar só a textura vazaria a Surface.
 */
@Composable
private fun SuperficieDePrevia(
    aoAnexar: (Surface) -> Unit,
    aoRemover: () -> Unit,
) {
  val anexarAtual by rememberUpdatedState(aoAnexar)
  val removerAtual by rememberUpdatedState(aoRemover)
  val callback =
      remember {
        object : TextureView.SurfaceTextureListener {
          private var surfaceAtual: Surface? = null

          override fun onSurfaceTextureAvailable(
              surfaceTexture: SurfaceTexture,
              width: Int,
              height: Int,
          ) {
            anexarAtual(Surface(surfaceTexture).also { surfaceAtual = it })
          }

          // O callback antigo de `SurfaceHolder` reanexava a cada mudança de tamanho; manter o
          // mesmo comportamento evita que o renderizador continue escrevendo na geometria velha.
          override fun onSurfaceTextureSizeChanged(
              surfaceTexture: SurfaceTexture,
              width: Int,
              height: Int,
          ) {
            surfaceAtual?.let(anexarAtual)
          }

          override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
            removerAtual()
            surfaceAtual?.release()
            surfaceAtual = null
            // `true` devolve a textura para o próprio TextureView liberar.
            return true
          }

          override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
        }
      }

  AndroidView(
      factory = { contexto -> TextureView(contexto).also { it.surfaceTextureListener = callback } },
      modifier = Modifier.fillMaxSize(),
  )

  DisposableEffect(callback) {
    onDispose { removerAtual() }
  }
}

@Composable
private fun MolduraRoi(
    larguraFrame: Int,
    alturaFrame: Int,
    fatorRecorte: Float,
) {
  val espessura = with(LocalDensity.current) { 3.dp.toPx() }
  Canvas(Modifier.fillMaxSize()) {
    val geometria =
        calcularGeometriaPreview(
            larguraContainer = size.width,
            alturaContainer = size.height,
            larguraFrame = larguraFrame,
            alturaFrame = alturaFrame,
            fatorRecorte = fatorRecorte,
        ) ?: return@Canvas
    drawRect(
        color = Color(0xFF00E676),
        topLeft = Offset(geometria.roi.esquerda, geometria.roi.topo),
        size = Size(geometria.roi.largura, geometria.roi.altura),
        style = Stroke(width = espessura),
    )
  }
}
