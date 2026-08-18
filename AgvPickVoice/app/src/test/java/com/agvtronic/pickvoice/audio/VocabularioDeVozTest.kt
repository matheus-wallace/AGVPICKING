package com.agvtronic.pickvoice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * As leituras de número do vocabulário como funções puras.
 *
 * [VocabularioDeVoz.numero] já era exercido pelo [InterpretadorDeFalaTest] através do estado; o
 * que este arquivo cobre é a fronteira entre ela, [VocabularioDeVoz.numeroDigitoADigito] e
 * [VocabularioDeVoz.checkDigitExtenso] — quem aceita o quê, e por quê, sem depender do filtro de
 * intervalo do interpretador. As duas leituras (extenso e dígito a dígito) coexistem em
 * quantidade e em check digit desde a bancada de 18/08/2026: só dígito a dígito, por si só, caiu
 * nas duas pontas (add-voice-recognition-reliability - Decisões 1 e 7 e a reversão de 18/08).
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
    assertNull(VocabularioDeVoz.numeroDigitoADigito("cento e vinte e três"))
    // "meia" fica de fora da quantidade porque ali é ambígua com "meia dúzia".
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

  @Test
  fun `check digit e lido algarismo por algarismo, com o zero a esquerda preservado`() {
    // `digitos` devolve string justamente porque check digit é código: "07" nunca pode virar 7.
    // Continua valendo mesmo com o extenso restaurado — é a leitura de fallback do
    // interpretador quando `checkDigitExtenso` recusa.
    assertEquals("47", VocabularioDeVoz.digitos("quatro sete"))
    assertEquals("07", VocabularioDeVoz.digitos("zero sete"))
    assertEquals("82", VocabularioDeVoz.digitos("oito dois"))
    assertEquals("98", VocabularioDeVoz.digitos("nove oito"))
    // O modelo pode devolver o algarismo já escrito.
    assertEquals("47", VocabularioDeVoz.digitos("47"))
    // Palavra de dezena não é algarismo para `digitos` — quem entende isso é `checkDigitExtenso`.
    assertNull(VocabularioDeVoz.digitos("quarenta e sete"))
    assertNull(VocabularioDeVoz.digitos("dezessete"))
    assertNull(VocabularioDeVoz.digitos(""))
    assertNull(VocabularioDeVoz.digitos("[unk]"))
  }

  @Test
  fun `check digit por extenso restaurado na bancada de 18 08 2026`() {
    // Caiu na Decisão 1 (17/08) porque a gramática de 0-999 inteira confundia dígito a dígito
    // com centena, e voltou quando só dígito a dígito, por si só, também se provou instável
    // (18/08). `checkDigitExtenso` evita repetir o motivo original: restrito a 0..99.
    assertEquals("47", VocabularioDeVoz.checkDigitExtenso("quarenta e sete"))
    assertEquals("17", VocabularioDeVoz.checkDigitExtenso("dezessete"))
    assertEquals("10", VocabularioDeVoz.checkDigitExtenso("dez"))
    assertEquals("99", VocabularioDeVoz.checkDigitExtenso("noventa e nove"))
  }

  @Test
  fun `check digit por extenso exige primeira palavra de dezena e intervalo 0 a 99`() {
    // "oito dois" (82 dígito a dígito) não pode virar 8 + 2 = 10 por extenso: a primeira
    // palavra falada precisa já valer uma dezena.
    assertNull(VocabularioDeVoz.checkDigitExtenso("oito dois"))
    assertNull(VocabularioDeVoz.checkDigitExtenso("nove oito"))
    // Um algarismo isolado é ambíguo demais com fala cortada no meio de "quarenta e sete".
    assertNull(VocabularioDeVoz.checkDigitExtenso("sete"))
    // Centena não cabe num check digit de dois algarismos.
    assertNull(VocabularioDeVoz.checkDigitExtenso("cento e vinte"))
    assertNull(VocabularioDeVoz.checkDigitExtenso(""))
  }
}
