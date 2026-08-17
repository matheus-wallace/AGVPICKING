package com.agvtronic.pickvoice.ui.mirror

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A única regra do arraste que dá para verificar sem aparelho.
 *
 * O projeto não tem infraestrutura de teste de Compose (sem `androidTest`, sem
 * `compose-ui-test`), então posicionamento e gesto continuam sendo verificação manual de bancada
 * — o que sobra em JVM é a conta que decide até onde a miniatura pode andar.
 */
class MiniaturaDeCameraTest {

  @Test
  fun `folga e o que sobra do container depois da miniatura e das duas margens`() {
    assertEquals(824f, folgaDeArraste(limitePx = 1080f, ladoPx = 160f, margemPx = 48f), 0f)
    assertEquals(920f, folgaDeArraste(limitePx = 1080f, ladoPx = 160f, margemPx = 0f), 0f)
  }

  @Test
  fun `container menor que a miniatura nao gera folga negativa`() {
    // Um intervalo invertido faria o `coerceIn` do gesto lançar em vez de prender o arraste.
    assertEquals(0f, folgaDeArraste(limitePx = 100f, ladoPx = 160f, margemPx = 48f), 0f)
    assertEquals(0f, folgaDeArraste(limitePx = 0f, ladoPx = 160f, margemPx = 0f), 0f)
    // Container que só cabe a miniatura sem as margens também trava em zero.
    assertEquals(0f, folgaDeArraste(limitePx = 200f, ladoPx = 160f, margemPx = 48f), 0f)
  }
}
