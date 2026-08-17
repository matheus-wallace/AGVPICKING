package com.agvtronic.pickvoice.vision

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Verifica a leitura dos ajustes de bancada.
 *
 * O que este teste protege não é a aritmética, é a promessa: **arquivo ausente ou torto não pode
 * mudar o comportamento de produção**. Na manhã de 18/09 o aparelho não vai ter arquivo nenhum, e
 * um `AjustesVisao` que caísse para zero de recorte ou zero formatos naquele momento desligaria a
 * visão sem ninguém entender por quê.
 */
class AjustesVisaoTest {

  @get:Rule val pasta = TemporaryFolder()

  @Test
  fun `arquivo ausente devolve os valores de producao`() {
    val inexistente = File(pasta.root, "nao-existe.properties")

    assertEquals(AjustesVisao(), AjustesVisao.carregarDe(inexistente))
  }

  @Test
  fun `uma chave sobrepoe apenas o proprio campo`() {
    val arquivo = arquivoCom("fatorRecorte=0.4")

    val ajustes = AjustesVisao.carregarDe(arquivo)

    assertEquals(0.4f, ajustes.fatorRecorte, 1e-6f)
    // Todo o resto continua no default.
    assertEquals(AjustesVisao().qualidade, ajustes.qualidade)
    assertEquals(AjustesVisao().fps, ajustes.fps)
    assertEquals(AjustesVisao().formatos, ajustes.formatos)
  }

  @Test
  fun `valor invalido mantem o default e avisa`() {
    val arquivo = arquivoCom("fps=sete\nqualidade=ULTRA\nrotacaoGraus=45")
    val avisos = mutableListOf<String>()

    val ajustes = AjustesVisao.carregarDe(arquivo) { avisos += it }

    assertEquals(AjustesVisao().fps, ajustes.fps)
    assertEquals(AjustesVisao().qualidade, ajustes.qualidade)
    assertEquals(AjustesVisao().rotacaoGraus, ajustes.rotacaoGraus)
    assertEquals(3, avisos.size)
  }

  @Test
  fun `fator de recorte fora do intervalo cai para o default`() {
    // Zero não reteria pixel nenhum e acima de 1 pediria pixels que o frame não tem — nos dois
    // casos o certo é ignorar o arquivo, não desligar a leitura.
    assertEquals(AjustesVisao().fatorRecorte, AjustesVisao.carregarDe(arquivoCom("fatorRecorte=0")).fatorRecorte, 1e-6f)
    assertEquals(AjustesVisao().fatorRecorte, AjustesVisao.carregarDe(arquivoCom("fatorRecorte=1.4")).fatorRecorte, 1e-6f)
  }

  @Test
  fun `lista de formatos e lida e normalizada`() {
    val ajustes = AjustesVisao.carregarDe(arquivoCom("formatos= code_128 , ean_13 "))

    assertEquals(listOf(FormatoCodigo.CODE_128, FormatoCodigo.EAN_13), ajustes.formatos)
  }

  @Test
  fun `lista sem nenhum formato valido mantem os padroes`() {
    val avisos = mutableListOf<String>()

    val ajustes = AjustesVisao.carregarDe(arquivoCom("formatos=pdf417")) { avisos += it }

    assertEquals(AjustesVisao.FORMATOS_PADRAO, ajustes.formatos)
    assertTrue(avisos.isNotEmpty())
  }

  @Test
  fun `qualidade aceita o nome do enum em qualquer caixa`() {
    assertEquals(QualidadeStream.ALTA, AjustesVisao.carregarDe(arquivoCom("qualidade=alta")).qualidade)
  }

  @Test
  fun `ajustes do fallback por foto podem ser calibrados por arquivo`() {
    val ajustes =
        AjustesVisao.carregarDe(
            arquivoCom(
                """
                capturaPorFotoAtiva=false
                limiarDetalhe=90.5
                limiarNitidez=140
                limiarEstabilidade=8.5
                quadrosEstaveisParaCaptura=4
                cooldownCapturaMs=2000
                timeoutOrientacaoMs=9000
                """.trimIndent()
            )
        )

    assertEquals(false, ajustes.capturaPorFotoAtiva)
    assertEquals(90.5f, ajustes.limiarDetalhe, 1e-6f)
    assertEquals(140f, ajustes.limiarNitidez, 1e-6f)
    assertEquals(8.5f, ajustes.limiarEstabilidade, 1e-6f)
    assertEquals(4, ajustes.quadrosEstaveisParaCaptura)
    assertEquals(2_000, ajustes.cooldownCapturaMs)
    assertEquals(9_000, ajustes.timeoutOrientacaoMs)
  }

  private fun arquivoCom(conteudo: String): File =
      pasta.newFile().apply { writeText(conteudo) }
}
