package com.agvtronic.pickvoice.ui.operation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agvtronic.pickvoice.audio.output.EstadoSaidaAudio

/**
 * A tela do separador: uma superfície só para as três validações do WMS.
 *
 * Informativa por definição — não existe botão de avanço do fluxo principal. Quem avança é a
 * voz, a câmera ou o ciclo de vida da sessão; a tela apenas mostra em que ponto o fluxo está
 * (spec — "Readback de quantidade").
 *
 * A prévia da câmera não é composta aqui: ela é a miniatura flutuante hospedada pela
 * `MainActivity`, acima de qualquer superfície, para que arrastá-la e dispensá-la valha para a
 * tela inteira e não se perca a cada troca de etapa.
 *
 * @param aoAbrirDebug entrada identificada como desenvolvimento; alternar de superfície não
 *   publica evento, não cria sessão e não reinicia áudio.
 */
@Composable
fun OperationScreen(
    viewModel: OperationViewModel,
    aoAbrirDebug: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  Column(
      modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
  ) {
    Cabecalho(uiState)
    Spacer(Modifier.height(12.dp))
    CartaoDaEtapa(uiState, viewModel::registrarOcorrencia)
    Spacer(Modifier.height(12.dp))
    Rodape(uiState, aoAbrirDebug)
  }
}

@Composable
private fun Cabecalho(uiState: OperationUiState) {
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp)) {
      Text(uiState.ordem ?: "Nenhuma ordem em separação", style = MaterialTheme.typography.titleMedium)
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(uiState.progresso ?: "—", style = MaterialTheme.typography.bodyMedium)
        Text(uiState.situacao, style = MaterialTheme.typography.bodyMedium)
      }
      // O passo atual fecha a tríade do cabeçalho: onde estou na ordem (progresso), como está a
      // sessão (situação) e o que estou fazendo agora (design.md - Decisão 2). Em destaque
      // porque é a única das três que muda a cada transição.
      Spacer(Modifier.height(4.dp))
      Text(
          uiState.nomeEtapa,
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.primary,
      )
    }
  }
}

@Composable
private fun CartaoDaEtapa(uiState: OperationUiState, aoRegistrarOcorrencia: () -> Unit) {
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp)) {
      when (uiState.etapa) {
        EtapaOperacao.ENDERECO -> ConteudoEndereco(uiState)
        EtapaOperacao.PRODUTO -> ConteudoProduto(uiState)
        EtapaOperacao.QUANTIDADE -> ConteudoQuantidade(uiState)
        EtapaOperacao.MENSAGEM -> ConteudoMensagem(uiState)
      }
      Spacer(Modifier.height(12.dp))
      Text(uiState.instrucao, style = MaterialTheme.typography.titleMedium)
      uiState.dicaDeVoz?.let {
        Spacer(Modifier.height(4.dp))
        Text(
            it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
      }
      uiState.ultimaConfirmacao?.let {
        Spacer(Modifier.height(4.dp))
        Text(
            it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
      }
      // A única ação de fluxo da tela, e só na ocorrência: ver `registrarOcorrencia` no
      // ViewModel. Fora de `TratandoExcecao` a tela continua sem botão de avanço.
      if (uiState.podeRegistrarOcorrencia) {
        Spacer(Modifier.height(12.dp))
        Button(onClick = aoRegistrarOcorrencia, modifier = Modifier.fillMaxWidth()) {
          Text("Registrar ocorrência e seguir")
        }
      }
    }
  }
}

@Composable
private fun ConteudoEndereco(uiState: OperationUiState) {
  Text("Endereço", style = MaterialTheme.typography.labelLarge)
  Text(uiState.endereco ?: "—", style = MaterialTheme.typography.headlineMedium)
  uiState.produto?.let {
    Spacer(Modifier.height(8.dp))
    Text(it, style = MaterialTheme.typography.bodyMedium)
  }
}

@Composable
private fun ConteudoProduto(uiState: OperationUiState) {
  Text("Produto", style = MaterialTheme.typography.labelLarge)
  uiState.produto?.let { Text(it, style = MaterialTheme.typography.titleLarge) }
  uiState.endereco?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

  uiState.statusLeitura?.let {
    Spacer(Modifier.height(8.dp))
    Text(it, style = MaterialTheme.typography.bodyMedium)
  }
  if (uiState.orientacaoPendente) {
    Text(
        "Aponte para o código do produto",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
    )
  }
}

@Composable
private fun ConteudoQuantidade(uiState: OperationUiState) {
  Text("Quantidade", style = MaterialTheme.typography.labelLarge)
  uiState.produto?.let { Text(it, style = MaterialTheme.typography.titleLarge) }
  uiState.quantidadeEsperada?.let {
    Spacer(Modifier.height(8.dp))
    Text("Esperado: $it", style = MaterialTheme.typography.headlineMedium)
  }
  uiState.quantidadeInformada?.let {
    Text("Entendido: $it", style = MaterialTheme.typography.headlineSmall)
  }
  uiState.compartimento?.let {
    Spacer(Modifier.height(4.dp))
    Text("Compartimento $it", style = MaterialTheme.typography.bodyMedium)
  }
}

@Composable
private fun ConteudoMensagem(uiState: OperationUiState) {
  Text(uiState.mensagem ?: "—", style = MaterialTheme.typography.headlineSmall)
}

@Composable
private fun Rodape(uiState: OperationUiState, aoAbrirDebug: () -> Unit) {
  Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(estadoDeVoz(uiState), style = MaterialTheme.typography.bodyMedium)
    TextButton(onClick = aoAbrirDebug) { Text("Desenvolvimento") }
  }
}

private fun estadoDeVoz(uiState: OperationUiState): String =
    when {
      uiState.estadoFala == EstadoSaidaAudio.INDISPONIVEL -> "Voz indisponível"
      uiState.estadoFala != EstadoSaidaAudio.PRONTA -> "Preparando a voz"
      uiState.aguardandoVoz -> "Aguardando comando de voz"
      else -> "Voz ativa"
    }
