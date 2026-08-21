package com.agvtronic.pickvoice.ui.debugsettings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.agvtronic.pickvoice.debug.ConfiguracoesDeDebug
import com.agvtronic.pickvoice.debug.MotorAsrSelecionado
import com.agvtronic.pickvoice.debug.RepositorioConfiguracoesDeDebug
import com.agvtronic.pickvoice.vision.QualidadeStream

@Composable
fun ConfiguracoesDebugScreen(
    repositorio: RepositorioConfiguracoesDeDebug,
    aoVoltar: () -> Unit,
    modifier: Modifier = Modifier,
) {
  var configuracoes by remember { mutableStateOf(repositorio.carregar()) }
  var salvo by remember { mutableStateOf(false) }

  Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
    TextButton(onClick = aoVoltar) { Text("Voltar ao painel") }
    Text("Configuração de bancada", style = MaterialTheme.typography.headlineSmall)
    Text(
        "As alterações são aplicadas no próximo início do app. Feche e abra o aplicativo após salvar.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(12.dp))

    Cartao("Dispositivo") {
      Alternador("Usar óculos simulados (MockDeviceKit)", configuracoes.usarOculosSimulado) {
        configuracoes = configuracoes.copy(usarOculosSimulado = it)
        salvo = false
      }
      Alternador("Capturar voz pelo microfone do óculos (HFP)", configuracoes.usarMicrofoneDoOculos) {
        configuracoes = configuracoes.copy(usarMicrofoneDoOculos = it)
        salvo = false
      }
    }
    Cartao("Reconhecimento de voz") {
      MotorAsrSelecionado.entries.forEach { motor ->
        Opcao(motor.rotulo, configuracoes.motorAsr == motor) {
          configuracoes = configuracoes.copy(motorAsr = motor)
          salvo = false
        }
      }
    }
    Cartao("Câmera e leitura") {
      Text("Qualidade do stream", style = MaterialTheme.typography.labelMedium)
      QualidadeStream.entries.forEach { qualidade ->
        Opcao("${qualidade.name.lowercase().replaceFirstChar { it.uppercase() }} (${qualidade.largura}×${qualidade.altura})", configuracoes.qualidadeVideo == qualidade) {
          configuracoes = configuracoes.copy(qualidadeVideo = qualidade)
          salvo = false
        }
      }
      Text("Taxa de quadros", style = MaterialTheme.typography.labelMedium)
      RepositorioConfiguracoesDeDebug.FPS_VALIDOS.forEach { fps ->
        Opcao("$fps fps", configuracoes.fps == fps) {
          configuracoes = configuracoes.copy(fps = fps)
          salvo = false
        }
      }
      Text("Rotação do código", style = MaterialTheme.typography.labelMedium)
      RepositorioConfiguracoesDeDebug.ROTACOES_VALIDAS.forEach { rotacao ->
        Opcao("$rotacao°", configuracoes.rotacaoGraus == rotacao) {
          configuracoes = configuracoes.copy(rotacaoGraus = rotacao)
          salvo = false
        }
      }
      Alternador("Usar captura de foto para validar orientação", configuracoes.capturaPorFotoAtiva) {
        configuracoes = configuracoes.copy(capturaPorFotoAtiva = it)
        salvo = false
      }
    }
    Button(
        onClick = {
          repositorio.salvar(configuracoes)
          salvo = true
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Salvar configuração") }
    if (salvo) {
      Text("Salvo. Reinicie o app para aplicar.", color = MaterialTheme.colorScheme.primary)
    }
  }
}

@Composable private fun Cartao(titulo: String, conteudo: @Composable () -> Unit) {
  Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
    Column(Modifier.padding(16.dp)) { Text(titulo, style = MaterialTheme.typography.titleMedium); conteudo() }
  }
}

@Composable private fun Alternador(rotulo: String, marcado: Boolean, aoMudar: (Boolean) -> Unit) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(rotulo, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    Switch(checked = marcado, onCheckedChange = aoMudar)
  }
}

@Composable private fun Opcao(rotulo: String, marcada: Boolean, aoSelecionar: () -> Unit) {
  Row(
      Modifier.fillMaxWidth().selectable(
          selected = marcada,
          role = Role.RadioButton,
          onClick = aoSelecionar,
      ),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioButton(selected = marcada, onClick = null)
    Text(rotulo, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
  }
}
