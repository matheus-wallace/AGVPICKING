package com.agvtronic.pickvoice.ui.mirror

import android.view.Surface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agvtronic.pickvoice.vision.DiagnosticoVisao
import com.agvtronic.pickvoice.vision.EstadoStreamVisao

/**
 * A [PreviaEspelho] como miniatura flutuante e dispensável, em vez de ocupar a largura total da
 * tela (spec `camera-preview-overlay`).
 *
 * Este overload sempre desenha quando chamado — quem decide **se** chama é a versão que recebe
 * o [MirrorViewModel] logo abaixo, que só compõe este composable enquanto `estadoStream ==
 * EstadoStreamVisao.ATIVO`. Deixar o gate fora deste `Composable` (em vez de um `if` no topo
 * daqui) é o que faz o estado de "dispensada"/posição de arraste resetar sozinho quando o stream
 * cai: sair da composição descarta o `remember` junto, sem precisar de um flag adicional para
 * "esquecer" que o operador dispensou na vez anterior. Enquanto o stream segue ativo, posição e
 * dispensa persistem — a miniatura é hospedada na raiz da composição (`MainActivity`), acima de
 * qualquer superfície, então trocar de etapa ou ir para o painel de debug não a recompõe.
 *
 * [limiteLarguraPx] e [limiteAlturaPx] são o tamanho do container que hospeda a miniatura: o
 * arraste é preso a eles para que ela não saia da tela, como a janela flutuante de um player.
 *
 * Dispensar só remove a superfície de exibição — a mesma garantia do `removerPreview` do
 * `ControladorDeVisao` já usada em toda a base: a câmera continua ligada e nenhum `PickingEvent`
 * é publicado.
 */
@Composable
fun MiniaturaDeCamera(
    diagnostico: DiagnosticoVisao,
    aoAnexar: (Surface) -> Unit,
    aoRemover: () -> Unit,
    limiteLarguraPx: Float,
    limiteAlturaPx: Float,
    modifier: Modifier = Modifier,
) {
  var dispensada by remember { mutableStateOf(false) }
  if (dispensada) return

  val transmitindo = diagnostico.estadoStream == EstadoStreamVisao.ATIVO

  var deslocamentoX by remember { mutableFloatStateOf(0f) }
  var deslocamentoY by remember { mutableFloatStateOf(0f) }

  // A âncora é o canto inferior direito, então o arraste só anda para trás: de 0 (ancorada) até
  // o negativo que encosta a miniatura na margem oposta.
  val densidade = LocalDensity.current
  val margemPx = with(densidade) { MARGEM.toPx() }
  val deslocamentoMinimoX =
      with(densidade) { -folgaDeArraste(limiteLarguraPx, LARGURA.toPx(), margemPx) }
  val deslocamentoMinimoY =
      with(densidade) { -folgaDeArraste(limiteAlturaPx, ALTURA.toPx(), margemPx) }

  Box(
      modifier
          .padding(MARGEM)
          .offset { IntOffset(deslocamentoX.toInt(), deslocamentoY.toInt()) }
          .size(LARGURA, ALTURA)
          .clip(RoundedCornerShape(12.dp))
          .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
          .background(MaterialTheme.colorScheme.surface)
          .pointerInput(deslocamentoMinimoX, deslocamentoMinimoY) {
            detectDragGestures { _, arraste ->
              deslocamentoX = (deslocamentoX + arraste.x).coerceIn(deslocamentoMinimoX, 0f)
              deslocamentoY = (deslocamentoY + arraste.y).coerceIn(deslocamentoMinimoY, 0f)
            }
          },
  ) {
    PreviaEspelho(diagnostico = diagnostico, aoAnexar = aoAnexar, aoRemover = aoRemover)
    // Enquanto o stream não transmite não há frame nenhum para desenhar, e a superfície fica
    // preta. Dizer o que está acontecendo é melhor do que um retângulo preto sem explicação —
    // foi o que o operador relatou em bancada como "a tela fica preta". Some sozinho no primeiro
    // frame, porque o vídeo passa a cobrir a superfície inteira.
    if (!transmitindo) {
      Text(
          "Iniciando câmera…",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.align(Alignment.Center),
      )
    }
    // 48dp é o alvo de toque mínimo do tema acessível (spec accessible-visual-identity).
    IconButton(
        onClick = { dispensada = true },
        modifier =
            Modifier.align(Alignment.TopEnd)
                .size(48.dp)
                .semantics { contentDescription = "Dispensar prévia da câmera" },
    ) {
      Text(
          "×",
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
      )
    }
  }
}

/**
 * A mesma miniatura ligada ao [MirrorViewModel], para quem já tem o ViewModel em mãos — o mesmo
 * papel que o overload equivalente de [PreviaEspelho] cumpre para a tela operacional.
 *
 * Compõe a miniatura desde o momento em que a câmera **começa** a subir, e não só quando ela já
 * está transmitindo (spec `camera-preview-overlay`). A diferença não é estética: o decodificador
 * do preview só é criado quando esta superfície aparece, e um decodificador HEVC criado no meio
 * do stream perde o VPS/SPS/PPS que veio no início — sem esses cabeçalhos ele nunca ativa e a
 * miniatura fica **permanentemente preta**, que foi o defeito visto em bancada em 17/08/2026
 * (só um "Formato de saída negociado" no logcat, o do decodificador de análise). Aparecendo já em
 * `INICIANDO`, a superfície existe antes do primeiro NAL e o preview recebe o stream inteiro,
 * igual ao decodificador de análise, que sempre funcionou.
 *
 * Com a câmera desligada ou em erro nada é desenhado — nem espaço vazio, nem superfície preta.
 */
@Composable
fun MiniaturaDeCamera(
    viewModel: MirrorViewModel,
    limiteLarguraPx: Float,
    limiteAlturaPx: Float,
    modifier: Modifier = Modifier,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val estado = uiState.diagnostico.estadoStream
  if (estado != EstadoStreamVisao.ATIVO && estado != EstadoStreamVisao.INICIANDO) return

  MiniaturaDeCamera(
      diagnostico = uiState.diagnostico,
      aoAnexar = viewModel::anexar,
      aoRemover = viewModel::remover,
      limiteLarguraPx = limiteLarguraPx,
      limiteAlturaPx = limiteAlturaPx,
      modifier = modifier,
  )
}

/**
 * Quanto a miniatura pode andar dentro do container, em pixels.
 *
 * A margem entra duas vezes porque ela existe nas duas pontas do percurso: a miniatura começa
 * afastada da borda de origem e precisa parar afastada da borda oposta.
 *
 * Função à parte, e não uma conta inline no gesto, porque é a única regra testável do arraste: o
 * `coerceAtLeast` cobre o container menor que a própria miniatura, em que a folga é zero e a
 * alternativa seria um intervalo invertido — `coerceIn` lança quando o mínimo passa do máximo.
 */
internal fun folgaDeArraste(limitePx: Float, ladoPx: Float, margemPx: Float): Float =
    (limitePx - ladoPx - 2 * margemPx).coerceAtLeast(0f)

/**
 * Proporção 3:4, a mesma do frame que o stream entrega (480x640 medidos em bancada).
 *
 * O quadrado anterior cortava a imagem: a [PreviaEspelho] ocupa a largura toda e deriva a altura
 * da proporção do frame, então 160x160 pedia 213dp de altura dentro de uma caixa de 160dp.
 */
private val LARGURA = 200.dp
private val ALTURA = 266.dp

/** Afastamento da borda, para a miniatura não encostar no limite da tela. */
private val MARGEM = 16.dp
