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

  /**
   * `true` enquanto há fala do sistema enfileirada ou em reprodução.
   *
   * É o sinal que o reconhecimento de voz usa para não disputar a instrução com o TTS
   * (design.md - Decisão 6): enquanto está `true`, nenhum resultado de ASR é aceito, e a volta
   * para `false` reinicia a janela de endpoint. Fica separado do [diagnostico] porque muda a
   * cada elocução — misturá-lo ali faria a UI recompor a cada frase falada.
   */
  val falando: StateFlow<Boolean>

  fun iniciar()

  fun falar(mensagem: MensagemFalavel)

  fun parar()

  fun fechar()
}
