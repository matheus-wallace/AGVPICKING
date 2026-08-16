package com.agvtronic.pickvoice.ui.mirror

import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agvtronic.pickvoice.vision.ControladorDeVisao
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class MirrorViewModel(private val controlador: ControladorDeVisao) : ViewModel() {

  val uiState: StateFlow<MirrorUiState> =
      controlador.diagnostico
          .map(::MirrorUiState)
          .stateIn(
              scope = viewModelScope,
              started = SharingStarted.WhileSubscribed(TIMEOUT_ASSINATURA_MS),
              initialValue = MirrorUiState(controlador.diagnostico.value),
          )

  fun anexar(surface: Surface) {
    controlador.anexarPreview(surface)
  }

  fun remover() {
    controlador.removerPreview()
  }

  override fun onCleared() {
    controlador.removerPreview()
  }

  private companion object {
    const val TIMEOUT_ASSINATURA_MS = 5_000L
  }
}
