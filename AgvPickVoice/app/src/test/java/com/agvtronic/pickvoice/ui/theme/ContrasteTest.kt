package com.agvtronic.pickvoice.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 8.3: confere o contraste de toda combinação texto/fundo efetivamente usada em
 * `OperationScreen`/`MirrorScreen`/`MiniaturaDeCamera` contra o mínimo AA da WCAG 2.1 (4,5:1) —
 * spec `accessible-visual-identity`.
 *
 * Não cobre o botão de dispensar da miniatura de câmera: ele fica sobre o vídeo da prévia, não
 * sobre uma cor de fundo fixa, e a fórmula de contraste de duas cores não se aplica a esse caso.
 */
class ContrasteTest {

  @Test
  fun `texto padrao sobre superficie e fundo cumpre o minimo AA`() {
    assertAA(OnFundo, Superficie) // corpo de texto em Card (surface)
    assertAA(OnFundo, Fundo) // corpo de texto direto sobre o fundo do app
  }

  @Test
  fun `destaques de cor sobre superficie cumprem o minimo AA`() {
    assertAA(Verde, Superficie) // "última confirmação"/"aponte para o código" (colorScheme.primary)
    assertAA(VerdeLimao, Superficie) // dica de comando de voz (colorScheme.secondary)
    assertAA(Erro, Superficie) // "Visão: <detalhe>" (colorScheme.error)
  }

  @Test
  fun `pares onX-XContainer do esquema cumprem o minimo AA`() {
    assertAA(OnVerdeContainer, VerdeContainer)
    assertAA(OnVerdeLimaoContainer, VerdeLimaoContainer)
    assertAA(OnLaranjaContainer, LaranjaContainer)
  }

  private fun assertAA(texto: androidx.compose.ui.graphics.Color, fundo: androidx.compose.ui.graphics.Color) {
    val taxa = taxaDeContraste(texto, fundo)
    assertTrue("contraste $taxa abaixo do mínimo AA ($CONTRASTE_MINIMO_AA)", taxa >= CONTRASTE_MINIMO_AA)
  }
}
