package com.agvtronic.pickvoice.ui.mirror

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agvtronic.pickvoice.ui.devpanel.DevPanelScreen
import com.agvtronic.pickvoice.ui.devpanel.DevPanelViewModel

/**
 * Superfície de desenvolvimento: prévia espelho do §12 com o painel de eventos logo abaixo.
 *
 * Deixou de ser a tela principal da `Activity` — quem abre por padrão é a tela operacional —,
 * mas continua com todos os controles do painel, que a operação não replica.
 */
@Composable
fun MirrorScreen(
    viewModel: MirrorViewModel,
    devPanelViewModel: DevPanelViewModel,
    modifier: Modifier = Modifier,
    aoVoltarParaOperacao: (() -> Unit)? = null,
    aoAbrirConfiguracoes: (() -> Unit)? = null,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  DevPanelScreen(viewModel = devPanelViewModel, modifier = modifier) {
    if (aoVoltarParaOperacao != null) {
      TextButton(onClick = aoVoltarParaOperacao) { Text("Voltar à operação") }
    }
    if (aoAbrirConfiguracoes != null) {
      TextButton(onClick = aoAbrirConfiguracoes) { Text("Configuração de bancada") }
    }
    PreviewCard(uiState = uiState)
    Spacer(Modifier.height(12.dp))
  }
}

/**
 * Só o diagnóstico de visão: a imagem em si mora na miniatura flutuante hospedada pela
 * `MainActivity`, que sobrevive à troca de superfície. Prendê-la a este cartão significava
 * perder posição e dispensa toda vez que o operador saía do painel.
 */
@Composable
private fun PreviewCard(uiState: MirrorUiState) {
  val diagnostico = uiState.diagnostico

  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(12.dp)) {
      Text("Visão dos óculos", style = MaterialTheme.typography.titleMedium)
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
      Text("Captura: ${uiState.captura}", style = MaterialTheme.typography.bodySmall)
      diagnostico.ultimaMetricaCaptura?.let { metrica ->
        Text(
            "Nitidez: ${metrica.varianciaLaplaciano.toInt()} · movimento: " +
                (metrica.diferencaTemporalMedia?.let { "%.1f".format(it) } ?: "aguardando"),
            style = MaterialTheme.typography.bodySmall,
        )
      }
      if (diagnostico.orientacaoPendente) {
        Text(
            "Aponte para o código do produto",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall,
        )
      }
      diagnostico.detalheErro?.let {
        Text(
            "Visão: $it",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
      }
    }
  }
}
