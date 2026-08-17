package com.agvtronic.pickvoice.ui.mirror

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
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
 * bancada: quem sai de composição destrói o `SurfaceView`, o callback chama [aoRemover] e o
 * renderizador para. Nenhum frame é retido aqui — a `Surface` pertence ao `SurfaceView`, e o
 * componente não guarda imagem, buffer nem `Bitmap`.
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

@Composable
private fun SuperficieDePrevia(
    aoAnexar: (Surface) -> Unit,
    aoRemover: () -> Unit,
) {
  val anexarAtual by rememberUpdatedState(aoAnexar)
  val removerAtual by rememberUpdatedState(aoRemover)
  val callback =
      remember {
        object : SurfaceHolder.Callback {
          override fun surfaceCreated(holder: SurfaceHolder) {
            anexarAtual(holder.surface)
          }

          override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            anexarAtual(holder.surface)
          }

          override fun surfaceDestroyed(holder: SurfaceHolder) {
            removerAtual()
          }
        }
      }

  AndroidView(
      factory = { contexto -> SurfaceView(contexto).also { it.holder.addCallback(callback) } },
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
