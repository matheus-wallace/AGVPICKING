package com.agvtronic.pickvoice.ui.mirror

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.agvtronic.pickvoice.ui.devpanel.DevPanelScreen
import com.agvtronic.pickvoice.ui.devpanel.DevPanelViewModel
import com.agvtronic.pickvoice.vision.calcularGeometriaPreview

/** Tela espelho do §12, ainda acompanhada pelos controles temporários do fluxo mockado. */
@Composable
fun MirrorScreen(
    viewModel: MirrorViewModel,
    devPanelViewModel: DevPanelViewModel,
    modifier: Modifier = Modifier,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  DevPanelScreen(viewModel = devPanelViewModel, modifier = modifier) {
    PreviewCard(
        uiState = uiState,
        aoAnexar = viewModel::anexar,
        aoRemover = viewModel::remover,
    )
    Spacer(Modifier.height(12.dp))
  }
}

@Composable
private fun PreviewCard(
    uiState: MirrorUiState,
    aoAnexar: (android.view.Surface) -> Unit,
    aoRemover: () -> Unit,
) {
  val diagnostico = uiState.diagnostico
  val largura = diagnostico.larguraEfetiva ?: diagnostico.qualidade.largura
  val altura = diagnostico.alturaEfetiva ?: diagnostico.qualidade.altura
  val proporcao = largura.toFloat() / altura.toFloat()

  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(12.dp)) {
      Text("Visão dos óculos", style = MaterialTheme.typography.titleMedium)
      Spacer(Modifier.height(8.dp))
      Box(
          Modifier.fillMaxWidth().aspectRatio(proporcao).background(Color.Black),
      ) {
        PreviewSurface(aoAnexar = aoAnexar, aoRemover = aoRemover)
        MolduraRoi(
            larguraFrame = largura,
            alturaFrame = altura,
            fatorRecorte = diagnostico.fatorRecorte,
        )
      }
      Spacer(Modifier.height(8.dp))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Stream: ${diagnostico.estadoStream}", style = MaterialTheme.typography.bodySmall)
        Text(
            "${uiState.dimensoes} · ${diagnostico.qualidade} @ ${diagnostico.fpsConfigurado} fps",
            style = MaterialTheme.typography.bodySmall,
        )
      }
      Text(
          "Última tentativa: ${uiState.ultimaTentativa}",
          style = MaterialTheme.typography.bodySmall,
      )
      Text(
          "Último código: ${diagnostico.ultimoCodigoConfirmado ?: "nenhum"}",
          style = MaterialTheme.typography.bodySmall,
      )
      diagnostico.detalheErro?.let {
        Text("Preview: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
      }
    }
  }
}

@Composable
private fun PreviewSurface(
    aoAnexar: (android.view.Surface) -> Unit,
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
