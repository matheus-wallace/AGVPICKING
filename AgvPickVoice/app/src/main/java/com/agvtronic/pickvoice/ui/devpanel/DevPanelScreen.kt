package com.agvtronic.pickvoice.ui.devpanel

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Painel de desenvolvimento: mostra o estado corrente do ator e um botão por evento.
 *
 * Puramente declarativa — toda decisão (qual botão existe, se está aplicável, qual linha
 * exibir) já vem resolvida no [DevPanelUiState]. A tela não conhece o reducer nem o
 * repositório.
 */
@Composable
fun DevPanelScreen(
    viewModel: DevPanelViewModel,
    modifier: Modifier = Modifier,
    cabecalho: @Composable () -> Unit = {},
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  Column(
      modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
  ) {
    cabecalho()
    CartaoEstado(uiState)
    Spacer(Modifier.height(12.dp))
    CartaoAudio(uiState)
    Spacer(Modifier.height(12.dp))
    CartaoItem(uiState)
    Spacer(Modifier.height(16.dp))

    GrupoAcao.entries.forEach { grupo ->
      val acoes = uiState.acoes.filter { it.grupo == grupo }
      if (acoes.isNotEmpty()) {
        Text(grupo.titulo, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        acoes.forEach { acao ->
          Button(
              onClick = { viewModel.disparar(acao.evento) },
              enabled = acao.aplicavel,
              modifier = Modifier.fillMaxWidth(),
          ) {
            Text(acao.rotulo)
          }
        }
        Spacer(Modifier.height(16.dp))
      }
    }
  }
}

@Composable
private fun CartaoAudio(uiState: DevPanelUiState) {
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp)) {
      Text("Saída de áudio", style = MaterialTheme.typography.labelMedium)
      Text(uiState.diagnosticoAudio.estado.name)
      Text(
          "Última chave: ${uiState.diagnosticoAudio.ultimaChaveMensagem ?: "—"}",
          style = MaterialTheme.typography.bodySmall,
          fontFamily = FontFamily.Monospace,
      )
      uiState.diagnosticoAudio.categoriaErro?.let { erro ->
        Text(
            "Erro: ${erro.name}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
      }
    }
  }
}

@Composable
private fun CartaoEstado(uiState: DevPanelUiState) {
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp)) {
      Text("Estado", style = MaterialTheme.typography.labelMedium)
      Text(uiState.nomeEstado, style = MaterialTheme.typography.headlineSmall)
      Spacer(Modifier.height(8.dp))
      Text(
          uiState.detalheEstado,
          style = MaterialTheme.typography.bodySmall,
          fontFamily = FontFamily.Monospace,
      )
    }
  }
}

@Composable
private fun CartaoItem(uiState: DevPanelUiState) {
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp)) {
      when {
        uiState.carregando -> Text("Carregando ordem mockada…")
        else -> {
          val ordem = uiState.ordem
          Text("Ordem", style = MaterialTheme.typography.labelMedium)
          Text("${ordem?.praca} / pedido ${ordem?.pedido}")
          Spacer(Modifier.height(8.dp))

          val linha = uiState.linhaEmAndamento
          if (linha == null) {
            Text("Nenhum item em andamento", style = MaterialTheme.typography.bodyMedium)
          } else {
            Text("Item em andamento", style = MaterialTheme.typography.labelMedium)
            Text("${linha.produto} — ${linha.descricao}")
            Text("Endereço: ${linha.endereco.etiqueta}")
            // Só em build de debug (design.md - Decisão 7): quem confere o check digit falado
            // continua sendo o `ResolvedorDeIntencao`, sem atalho — isto é leitura de bancada,
            // não um caminho de confirmação. Em release, `checkDigitEsperado` já chega nulo.
            uiState.checkDigitEsperado?.let { checkDigit ->
              Text(
                  "Check digit esperado: $checkDigit",
                  style = MaterialTheme.typography.bodySmall,
                  fontFamily = FontFamily.Monospace,
              )
            }
            Text(
                "Código de barras: ${linha.endereco.codbarra}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "EAN ${linha.ean} · lote ${linha.partida} · ${linha.quantidade} un",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
          }
        }
      }
    }
  }
}
