package com.agvtronic.pickvoice.debug

import android.content.Context
import com.agvtronic.pickvoice.BuildConfig
import com.agvtronic.pickvoice.audio.AjustesAsr
import com.agvtronic.pickvoice.vision.AjustesVisao
import com.agvtronic.pickvoice.vision.QualidadeStream

/** Escolhas de bancada que precisam estar definidas antes de o processo criar DAT, câmera e ASR. */
data class ConfiguracoesDeDebug(
    val usarOculosSimulado: Boolean,
    val usarMicrofoneDoOculos: Boolean,
    val motorAsr: MotorAsrSelecionado,
    val qualidadeVideo: QualidadeStream,
    val fps: Int,
    val rotacaoGraus: Int,
    val capturaPorFotoAtiva: Boolean,
) {
  fun aplicarEm(ajustes: AjustesVisao): AjustesVisao =
      ajustes.copy(
          qualidade = qualidadeVideo,
          fps = fps,
          rotacaoGraus = rotacaoGraus,
          capturaPorFotoAtiva = capturaPorFotoAtiva,
      )
}

enum class MotorAsrSelecionado(val rotulo: String) {
  RHINO("Picovoice Rhino"),
  VOSK("Vosk"),
  SHERPA("Sherpa-onnx"),
}

/**
 * Preferências pequenas e deliberadamente persistidas. Salvar uma mudança pede reinício do app:
 * o MockDeviceKit, o codec e os motores de ASR não podem ser trocados com segurança em voo.
 */
class RepositorioConfiguracoesDeDebug(context: Context) {
  private val prefs = context.getSharedPreferences(NOME_ARQUIVO, Context.MODE_PRIVATE)

  fun carregar(): ConfiguracoesDeDebug =
      ConfiguracoesDeDebug(
          usarOculosSimulado = prefs.getBoolean(CHAVE_OCULOS_SIMULADO, BuildConfig.DEBUG),
          usarMicrofoneDoOculos = prefs.getBoolean(CHAVE_MICROFONE_OCULOS, false),
          motorAsr = enum(CHAVE_MOTOR_ASR, MotorAsrSelecionado.RHINO),
          qualidadeVideo = enum(CHAVE_QUALIDADE, QualidadeStream.MEDIA),
          fps = prefs.getInt(CHAVE_FPS, 7).takeIf { it in FPS_VALIDOS } ?: 7,
          rotacaoGraus = prefs.getInt(CHAVE_ROTACAO, 0).takeIf { it in ROTACOES_VALIDAS } ?: 0,
          capturaPorFotoAtiva = prefs.getBoolean(CHAVE_CAPTURA_FOTO, true),
      )

  fun salvar(configuracoes: ConfiguracoesDeDebug) {
    prefs.edit()
        .putBoolean(CHAVE_OCULOS_SIMULADO, configuracoes.usarOculosSimulado)
        .putBoolean(CHAVE_MICROFONE_OCULOS, configuracoes.usarMicrofoneDoOculos)
        .putString(CHAVE_MOTOR_ASR, configuracoes.motorAsr.name)
        .putString(CHAVE_QUALIDADE, configuracoes.qualidadeVideo.name)
        .putInt(CHAVE_FPS, configuracoes.fps)
        .putInt(CHAVE_ROTACAO, configuracoes.rotacaoGraus)
        .putBoolean(CHAVE_CAPTURA_FOTO, configuracoes.capturaPorFotoAtiva)
        .apply()
  }

  private inline fun <reified T : Enum<T>> enum(chave: String, padrao: T): T =
      prefs.getString(chave, null)?.let { nome -> enumValues<T>().firstOrNull { it.name == nome } }
          ?: padrao

  companion object {
    val FPS_VALIDOS = listOf(2, 7, 15, 24, 30)
    val ROTACOES_VALIDAS = listOf(0, 90, 180, 270)
    private const val NOME_ARQUIVO = "configuracoes-debug"
    private const val CHAVE_OCULOS_SIMULADO = "oculos_simulado"
    private const val CHAVE_MICROFONE_OCULOS = "microfone_oculos"
    private const val CHAVE_MOTOR_ASR = "motor_asr"
    private const val CHAVE_QUALIDADE = "qualidade_video"
    private const val CHAVE_FPS = "fps"
    private const val CHAVE_ROTACAO = "rotacao"
    private const val CHAVE_CAPTURA_FOTO = "captura_foto"
  }
}
