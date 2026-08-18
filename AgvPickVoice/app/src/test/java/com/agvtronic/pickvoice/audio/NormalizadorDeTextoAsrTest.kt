package com.agvtronic.pickvoice.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * O [NormalizadorDeTextoAsr] é função pura — roda na JVM, sem Android e sem motor de ASR.
 *
 * Os casos vêm do que o pipeline realmente precisa suportar: as formas que um decodificador de
 * vocabulário aberto devolve (pontuado, capitalizado) contra as constantes acentuadas e
 * minúsculas do [VocabularioDeVoz], que é com quem o [InterpretadorDeFala] compara por igualdade
 * exata.
 */
class NormalizadorDeTextoAsrTest {

  @Test
  fun `remove ponto final e capitalizacao de inicio de frase`() {
    // O caso que a bancada do Vosk registrou como típico do Whisper (design.md - Decisão 3).
    assertEquals(VocabularioDeVoz.PROXIMO, NormalizadorDeTextoAsr.normalizar("Próximo."))
  }

  @Test
  fun `remove virgula ao fim de uma quantidade por extenso`() {
    assertEquals("quarenta e sete", NormalizadorDeTextoAsr.normalizar("quarenta e sete,"))
  }

  @Test
  fun `preserva acento das palavras do vocabulario`() {
    // Tirar acento aqui quebraria justamente as palavras que precisam bater.
    assertEquals(VocabularioDeVoz.EMERGENCIA, NormalizadorDeTextoAsr.normalizar("Emergência!"))
    assertEquals(VocabularioDeVoz.DIVERGENCIA, NormalizadorDeTextoAsr.normalizar("Divergência?"))
  }

  @Test
  fun `pontuacao no meio da frase vira separador e nao cola as palavras`() {
    // Virar vazio produziria "quarentasete", que não bate com nada.
    assertEquals("quarenta sete", NormalizadorDeTextoAsr.normalizar("quarenta,sete"))
  }

  @Test
  fun `colapsa espacos repetidos e apara as pontas`() {
    assertEquals("oito dois", NormalizadorDeTextoAsr.normalizar("  oito   dois \n"))
  }

  @Test
  fun `preserva algarismos`() {
    // O Whisper pode escrever o número em algarismo; converter para extenso não é trabalho da
    // normalização, mas perder o algarismo no caminho seria pior ainda.
    assertEquals("47", NormalizadorDeTextoAsr.normalizar("47."))
  }

  @Test
  fun `texto ja limpo passa intacto`() {
    // Idempotência: é o que permite aplicar a normalização sem medo em qualquer motor.
    assertEquals(VocabularioDeVoz.PARAR, NormalizadorDeTextoAsr.normalizar(VocabularioDeVoz.PARAR))
  }

  @Test
  fun `normalizar duas vezes da o mesmo resultado`() {
    val umaVez = NormalizadorDeTextoAsr.normalizar("Próximo.")
    assertEquals(umaVez, NormalizadorDeTextoAsr.normalizar(umaVez))
  }

  @Test
  fun `texto vazio e texto so de pontuacao viram vazio`() {
    // Elocução sem fala não pode virar um texto que o InterpretadorDeFala tente interpretar.
    assertEquals("", NormalizadorDeTextoAsr.normalizar(""))
    assertEquals("", NormalizadorDeTextoAsr.normalizar("... ,!"))
  }

  @Test
  fun `token de fala desconhecida do Vosk perderia os colchetes`() {
    // Documenta por que o MotorVosk NÃO usa esta normalização: `[unk]` viraria `unk` e deixaria
    // de bater com VocabularioDeVoz.DESCONHECIDA, que o InterpretadorDeFala compara por
    // igualdade exata para descartar fala fora da gramática.
    assertEquals("unk", NormalizadorDeTextoAsr.normalizar(VocabularioDeVoz.DESCONHECIDA))
  }
}
