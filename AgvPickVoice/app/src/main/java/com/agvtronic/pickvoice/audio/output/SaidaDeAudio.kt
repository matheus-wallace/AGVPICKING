package com.agvtronic.pickvoice.audio.output

import kotlinx.coroutines.flow.StateFlow

enum class EstadoSaidaAudio {
  PARADA,
  INICIALIZANDO,
  PRONTA,
  INDISPONIVEL,
}

enum class CategoriaErroSaidaAudio {
  FALHA_INICIALIZACAO,
  IDIOMA_INDISPONIVEL,
  FALHA_REPRODUCAO,
}

/** Diagnóstico deliberadamente sem o texto falado. */
data class DiagnosticoSaidaAudio(
    val estado: EstadoSaidaAudio = EstadoSaidaAudio.PARADA,
    val categoriaErro: CategoriaErroSaidaAudio? = null,
    val ultimaChaveMensagem: String? = null,
)

/** Porta substituível para TTS Android hoje e Piper/HFP no futuro. */
interface SaidaDeAudio {
  val diagnostico: StateFlow<DiagnosticoSaidaAudio>

  fun iniciar()

  fun falar(mensagem: MensagemFalavel)

  fun parar()

  fun fechar()
}
