package com.agvtronic.pickvoice.vision

import android.view.Surface

/**
 * Saída visual efêmera do mesmo HEVC usado pela análise.
 *
 * O frame decodificado vai do `MediaCodec` direto para a [Surface]. Esta classe não cria
 * `Bitmap`, não expõe pixels e não é dona da superfície — quem a libera é o `SurfaceView`.
 */
class RenderizadorHevc(
    surface: Surface,
    aoFormato: (largura: Int, altura: Int) -> Unit,
    aoErro: (String) -> Unit,
) {
  private val decodificador =
      DecodificadorHevc(surface = surface, aoFormato = aoFormato, aoErro = aoErro)

  fun iniciar(largura: Int, altura: Int) {
    decodificador.iniciar(largura, altura)
  }

  fun enfileirar(dados: ByteArray, apresentacaoUs: Long) {
    decodificador.enfileirar(dados, apresentacaoUs)
  }

  fun parar() {
    decodificador.parar()
  }
}
