package com.agvtronic.pickvoice.audio

import com.agvtronic.pickvoice.domain.statemachine.ItemEmAndamento
import com.agvtronic.pickvoice.domain.statemachine.PickingState
import java.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O [SintetizadorDeIntencaoRhino] é a única parte do `MotorPicovoiceRhino` que roda na JVM: não
 * depende de `Context`, de `AssetManager` nem de `libpv_rhino.so` (tarefa 5.1).
 *
 * O que estes testes protegem não é a síntese em si — ela é um `Map` e um `joinToString` —, é o
 * **contrato com o Picovoice Console**. O contexto `.rhn` é compilado à mão, fora do repositório,
 * e não há build que quebre quando ele e esta tabela divergirem: o sintoma seria um comando que o
 * motor entende e o app ignora, aparecendo só com o operador falando na bancada. Os testes de
 * ponta a ponta abaixo (intenção -> texto -> `PickingEvent`) são o que transforma essa divergência
 * numa falha de teste.
 */
class SintetizadorDeIntencaoRhinoTest {

  private val semSlots = emptyMap<String, String>()

  // -----------------------------------------------------------------------------------
  // O contrato de nomenclatura com o Console
  // -----------------------------------------------------------------------------------

  @Test
  fun `nome da intencao e a palavra do vocabulario sem acento`() {
    // A regra que o PROVENIENCIA.md de assets/contexto-picovoice publica para quem for autorar o
    // contexto. Sem este teste ela seria só uma frase num arquivo markdown.
    for ((intencao, palavra) in SintetizadorDeIntencaoRhino.INTENCOES) {
      assertEquals("intenção de \"$palavra\"", semAcento(palavra), intencao)
    }
  }

  @Test
  fun `todo comando transversal tem intencao correspondente`() {
    // Os transversais valem em todo estado operacional (doc §3.3): faltar um aqui é perder
    // "parar" ou "emergência" no fluxo inteiro, não num estado só.
    for (palavra in VocabularioDeVoz.TRANSVERSAIS) {
      assertTrue(
          "\"$palavra\" não tem intenção no contexto Rhino",
          SintetizadorDeIntencaoRhino.INTENCOES.containsValue(palavra),
      )
    }
  }

  @Test
  fun `todo comando de fluxo tem intencao correspondente`() {
    val comandosDeFluxo =
        listOf(
            VocabularioDeVoz.INICIAR,
            VocabularioDeVoz.CHEGUEI,
            VocabularioDeVoz.CONFIRMAR,
            VocabularioDeVoz.CORRIGIR,
            VocabularioDeVoz.ALOCADO,
            VocabularioDeVoz.PROXIMO,
            VocabularioDeVoz.CONCLUIR,
            VocabularioDeVoz.ENCERRAR,
            VocabularioDeVoz.RETOMAR,
        )

    for (palavra in comandosDeFluxo) {
      assertTrue(
          "\"$palavra\" não tem intenção no contexto Rhino",
          SintetizadorDeIntencaoRhino.INTENCOES.containsValue(palavra),
      )
    }
  }

  // -----------------------------------------------------------------------------------
  // Síntese
  // -----------------------------------------------------------------------------------

  @Test
  fun `intencao conhecida vira a palavra do vocabulario`() {
    assertEquals(
        VocabularioDeVoz.PARAR,
        SintetizadorDeIntencaoRhino.sintetizar(entendido = true, intencao = "parar", slots = semSlots),
    )
  }

  @Test
  fun `intencao sem acento vira a palavra acentuada`() {
    // O caminho que mais fácil quebraria em silêncio: o InterpretadorDeFala compara por igualdade
    // exata contra "próximo", então devolver "proximo" aqui não produziria evento nenhum.
    assertEquals(
        VocabularioDeVoz.PROXIMO,
        SintetizadorDeIntencaoRhino.sintetizar(
            entendido = true,
            intencao = "proximo",
            slots = semSlots,
        ),
    )
  }

  @Test
  fun `fala fora do contexto nao vira texto`() {
    // `isUnderstood = false` é o caso comum no galpão, não uma condição de erro. Texto vazio é a
    // convenção de "nada decodificado" que os outros dois motores já usam para silêncio.
    assertEquals(
        "",
        SintetizadorDeIntencaoRhino.sintetizar(
            entendido = false,
            intencao = null,
            slots = semSlots,
        ),
    )
  }

  @Test
  fun `intencao fora da tabela nao vira texto`() {
    // Uma expressão acrescentada no Console sem intenção correspondente aqui: falha visível (nada
    // publicado, uma linha no log), nunca um comando errado.
    assertEquals(
        "",
        SintetizadorDeIntencaoRhino.sintetizar(
            entendido = true,
            intencao = "comando_que_ninguem_declarou",
            slots = semSlots,
        ),
    )
  }

  @Test
  fun `slot vira o texto inteiro sem o nome da intencao`() {
    assertEquals(
        "47",
        SintetizadorDeIntencaoRhino.sintetizar(
            entendido = true,
            intencao = "check_digit",
            slots = mapOf("numero" to "47"),
        ),
    )
  }

  @Test
  fun `slot por extenso passa verbatim`() {
    assertEquals(
        "quarenta e sete",
        SintetizadorDeIntencaoRhino.sintetizar(
            entendido = true,
            intencao = "check_digit",
            slots = mapOf("numero" to "quarenta e sete"),
        ),
    )
  }

  @Test
  fun `texto do slot sai normalizado`() {
    // Limpeza de pontuação e caixa é responsabilidade do motor, nunca do InterpretadorDeFala
    // (add-sherpa-onnx-asr-engine - Decisão 3) — e o valor de um slot vem do YAML do Console, que
    // ninguém garante estar em minúsculas.
    assertEquals(
        "quarenta e sete",
        SintetizadorDeIntencaoRhino.sintetizar(
            entendido = true,
            intencao = "check_digit",
            slots = mapOf("numero" to "Quarenta e sete."),
        ),
    )
  }

  @Test
  fun `slots multiplos saem em ordem de nome`() {
    // `RhinoInference.getSlots()` é um Map sem ordem garantida. Com um slot só isso não muda nada;
    // o teste existe para que o dia em que houver dois não dependa da iteração de um HashMap.
    val texto =
        SintetizadorDeIntencaoRhino.sintetizar(
            entendido = true,
            intencao = "qualquer",
            slots = linkedMapOf("z_segundo" to "dois", "a_primeiro" to "um"),
        )

    assertEquals("um dois", texto)
  }

  // -----------------------------------------------------------------------------------
  // Ponta a ponta: a síntese tem que produzir texto que o InterpretadorDeFala aceite
  // -----------------------------------------------------------------------------------

  @Test
  fun `intencao transversal vira evento no interpretador`() {
    val texto =
        SintetizadorDeIntencaoRhino.sintetizar(entendido = true, intencao = "parar", slots = semSlots)

    assertNotNull(
        InterpretadorDeFala.interpretar(PickingState.OrdemCarregada(ORDEM, TOTAL_LINHAS), texto),
    )
  }

  @Test
  fun `check digit fundido vira intencao de check digit`() {
    val texto =
        SintetizadorDeIntencaoRhino.sintetizar(
            entendido = true,
            intencao = "check_digit",
            slots = mapOf("numero" to "47"),
        )

    assertEquals(
        IntencaoDeVoz.CheckDigitFalado("47"),
        InterpretadorDeFala.interpretar(PickingState.AguardandoCheckDigit(ITEM), texto),
    )
  }

  @Test
  fun `check digit por extenso vira a mesma intencao do fundido`() {
    // A propriedade que permite ao Console enumerar o slot de qualquer das duas formas: o app não
    // precisa saber qual delas veio.
    val texto =
        SintetizadorDeIntencaoRhino.sintetizar(
            entendido = true,
            intencao = "check_digit",
            slots = mapOf("numero" to "quarenta e sete"),
        )

    assertEquals(
        IntencaoDeVoz.CheckDigitFalado("47"),
        InterpretadorDeFala.interpretar(PickingState.AguardandoCheckDigit(ITEM), texto),
    )
  }

  @Test
  fun `fala fora do contexto nao vira intencao nenhuma`() {
    val texto =
        SintetizadorDeIntencaoRhino.sintetizar(entendido = false, intencao = null, slots = semSlots)

    assertEquals(null, InterpretadorDeFala.interpretar(PickingState.OrdemCarregada(ORDEM, TOTAL_LINHAS), texto))
  }

  /** `próximo` -> `proximo`: tira o diacrítico e mantém a letra base. */
  private fun semAcento(palavra: String): String =
      Normalizer.normalize(palavra, Normalizer.Form.NFD).replace(DIACRITICOS, "")

  private companion object {
    val DIACRITICOS = Regex("\\p{Mn}")

    const val ORDEM = "274K5010000-408176"

    const val TOTAL_LINHAS = 3

    val ITEM = ItemEmAndamento(ORDEM, 0, "R04-P12-N03-A05", 2)
  }
}
