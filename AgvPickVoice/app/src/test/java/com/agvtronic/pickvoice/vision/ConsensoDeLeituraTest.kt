package com.agvtronic.pickvoice.vision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifica a regra que separa leitura boa de falso positivo.
 *
 * Não é teste de aritmética: é o teste do comportamento que a bancada mostrou ser necessário
 * (3 falsos positivos em 5 execuções, dois deles EAN-13 com dígito verificador válido). Se esta
 * regra afrouxar, o sistema volta a poder confirmar a caixa errada — e é a falha mais cara que
 * este projeto pode ter.
 */
class ConsensoDeLeituraTest {

  @Test
  fun `uma leitura isolada nao basta`() {
    val consenso = ConsensoDeLeitura(confirmacoes = 2)

    assertFalse(consenso.registrar(GTIN))
  }

  @Test
  fun `duas leituras iguais seguidas valem`() {
    val consenso = ConsensoDeLeitura(confirmacoes = 2)

    consenso.registrar(GTIN)

    assertTrue(consenso.registrar(GTIN))
  }

  @Test
  fun `valor diferente reinicia a contagem em vez de decrementar`() {
    // O caso exato da bancada: o código certo aparece, um falso positivo se intromete, e o
    // certo volta. A intromissão não pode deixar o falso positivo a um passo de publicar, nem
    // permitir que o certo publique sem se confirmar de novo.
    val consenso = ConsensoDeLeitura(confirmacoes = 2)

    consenso.registrar(GTIN)
    assertFalse("o falso positivo não pode herdar a contagem", consenso.registrar(FALSO_POSITIVO))
    assertFalse("o código certo recomeça do zero", consenso.registrar(GTIN))
    assertTrue(consenso.registrar(GTIN))
  }

  @Test
  fun `alternar entre dois valores nunca publica`() {
    val consenso = ConsensoDeLeitura(confirmacoes = 2)

    repeat(10) {
      assertFalse(consenso.registrar(GTIN))
      assertFalse(consenso.registrar(FALSO_POSITIVO))
    }
  }

  @Test
  fun `tres confirmacoes exigem tres frames`() {
    val consenso = ConsensoDeLeitura(confirmacoes = 3)

    assertFalse(consenso.registrar(GTIN))
    assertFalse(consenso.registrar(GTIN))
    assertTrue(consenso.registrar(GTIN))
  }

  @Test
  fun `uma confirmacao restaura o comportamento antigo`() {
    // É o valor que desliga a proteção — existe para a calibração do doc §10 poder medir os
    // dois lados do trade-off.
    val consenso = ConsensoDeLeitura(confirmacoes = 1)

    assertTrue(consenso.registrar(GTIN))
  }

  @Test
  fun `o codigo segue valendo enquanto continuar chegando`() {
    // O código fica no campo de visão por vários frames depois de confirmado. Quem publica uma
    // vez só é o controlador; aqui a resposta continua sendo `true`.
    val consenso = ConsensoDeLeitura(confirmacoes = 2)

    consenso.registrar(GTIN)
    assertTrue(consenso.registrar(GTIN))
    assertTrue(consenso.registrar(GTIN))
    assertTrue(consenso.registrar(GTIN))
  }

  @Test
  fun `reiniciar esquece o que foi lido`() {
    val consenso = ConsensoDeLeitura(confirmacoes = 2)

    consenso.registrar(GTIN)
    consenso.reiniciar()

    assertFalse("o escaneamento novo não herda a contagem do anterior", consenso.registrar(GTIN))
  }

  @Test(expected = IllegalArgumentException::class)
  fun `zero confirmacoes e erro de programacao`() {
    ConsensoDeLeitura(confirmacoes = 0)
  }

  private companion object {
    /** O EAN da caixa usada em bancada. */
    const val GTIN = "7896523202204"

    /** Um dos falsos positivos realmente observados — EAN-13 com dígito verificador válido. */
    const val FALSO_POSITIVO = "7793563202204"
  }
}
