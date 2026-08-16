package com.agvtronic.pickvoice.ui.mirror

import com.agvtronic.pickvoice.vision.DiagnosticoVisao

/** Estado pronto para apresentação, sem objetos de câmera, codec ou buffers. */
data class MirrorUiState(val diagnostico: DiagnosticoVisao) {
  val dimensoes: String
    get() {
      val largura = diagnostico.larguraEfetiva
      val altura = diagnostico.alturaEfetiva
      return if (largura != null && altura != null) "${largura}×$altura" else "aguardando"
    }

  val ultimaTentativa: String
    get() {
      val tentativa = diagnostico.ultimaTentativa ?: return "nenhuma"
      val resultado = tentativa.codigo ?: "sem leitura"
      return "$resultado (${tentativa.duracaoMs} ms)"
    }

  val captura: String
    get() =
        "${diagnostico.estadoCaptura} · tentativa ${diagnostico.tentativasCaptura}" +
            if (diagnostico.quadrosEstaveis > 0) " · estável ${diagnostico.quadrosEstaveis}"
            else ""
}
