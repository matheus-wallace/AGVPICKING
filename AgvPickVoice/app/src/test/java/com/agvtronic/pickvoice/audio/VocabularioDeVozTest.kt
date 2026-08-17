package com.agvtronic.pickvoice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * As duas leituras de número do vocabulário como funções puras.
 *
 * [VocabularioDeVoz.numero] já era exercido pelo [InterpretadorDeFalaTest] através do estado; o
 * que este arquivo cobre é a fronteira entre ela e [VocabularioDeVoz.numeroDigitoADigito] — quem
 * aceita o quê, e por quê, sem depender do filtro de intervalo do interpretador.
 */
class VocabularioDeVozTest {

  @Test
  fun `digito a digito remonta a sequencia de algarismos`() {
    assertEquals(12, VocabularioDeVoz.numeroDigitoADigito("um dois"))
    assertEquals(106, VocabularioDeVoz.numeroDigitoADigito("um zero seis"))
    assertEquals(999, VocabularioDeVoz.numeroDigitoADigito("nove nove nove"))
    assertEquals(5, VocabularioDeVoz.numeroDigitoADigito("cinco"))
  }

  @Test
  fun `digito a digito despe o zero a esquerda`() {
    // Ao contrário do check digit, aqui o resultado é quantidade: "05" e "5" são o mesmo número.
    assertEquals(5, VocabularioDeVoz.numeroDigitoADigito("zero cinco"))
    assertEquals(0, VocabularioDeVoz.numeroDigitoADigito("zero"))
  }

  @Test
  fun `digito a digito recusa sequencia vazia ou acima de tres algarismos`() {
    assertNull(VocabularioDeVoz.numeroDigitoADigito(""))
    assertNull(VocabularioDeVoz.numeroDigitoADigito("   "))
    assertNull(VocabularioDeVoz.numeroDigitoADigito("um dois três quatro"))
  }

  @Test
  fun `digito a digito recusa palavra que nao e algarismo`() {
    assertNull(VocabularioDeVoz.numeroDigitoADigito("vinte trinta"))
    assertNull(VocabularioDeVoz.numeroDigitoADigito("doze"))
    // "meia" fica de fora da quantidade pelo mesmo motivo que fica fora de QUANTIDADES.
    assertNull(VocabularioDeVoz.numeroDigitoADigito("meia"))
    assertNull(VocabularioDeVoz.numeroDigitoADigito("um meia"))
  }

  @Test
  fun `por extenso continua exigindo magnitudes decrescentes`() {
    assertEquals(123, VocabularioDeVoz.numero("cento e vinte e três"))
    assertEquals(5, VocabularioDeVoz.numero("cinco"))
    // Quem entende "um dois" é a leitura dígito a dígito, não esta.
    assertNull(VocabularioDeVoz.numero("um dois"))
  }

  @Test
  fun `meia continua valendo como seis na leitura de check digit`() {
    assertEquals("16", VocabularioDeVoz.digitos("um meia"))
  }
}
