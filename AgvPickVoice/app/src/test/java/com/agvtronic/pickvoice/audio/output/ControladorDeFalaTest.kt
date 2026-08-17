package com.agvtronic.pickvoice.audio.output

import com.agvtronic.pickvoice.domain.statemachine.ItemEmAndamento
import com.agvtronic.pickvoice.domain.statemachine.MotivoExcecao
import com.agvtronic.pickvoice.domain.statemachine.PickingActor
import com.agvtronic.pickvoice.domain.statemachine.PickingEvent
import com.agvtronic.pickvoice.domain.statemachine.PickingState
import com.agvtronic.pickvoice.vision.DiagnosticoVisao
import com.agvtronic.pickvoice.vision.QualidadeStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ControladorDeFalaTest {
  private val item = ItemEmAndamento("408176", 0, "R04-P12-N03-A05", 2)

  @Test
  fun `orientacao fala uma vez por ciclo de escaneamento`() = runTest {
    val actor = PickingActor(this, PickingState.EscaneandoProduto(item))
    val visao = MutableStateFlow(diagnostico())
    val saida = SaidaFake()
    val controlador = ControladorDeFala(actor, visao, saida, this)

    controlador.iniciar()
    advanceUntilIdle()
    visao.value = diagnostico(orientacaoPendente = true)
    advanceUntilIdle()
    visao.value = diagnostico(orientacaoPendente = false)
    visao.value = diagnostico(orientacaoPendente = true)
    advanceUntilIdle()

    assertEquals(1, saida.mensagens.count { it.chave == "orientar-codigo-produto" })

    actor.send(PickingEvent.ComandoParar)
    advanceUntilIdle()
    actor.send(PickingEvent.SessaoRetomada)
    advanceUntilIdle()
    visao.value = diagnostico(orientacaoPendente = false)
    visao.value = diagnostico(orientacaoPendente = true)
    advanceUntilIdle()

    assertEquals(2, saida.mensagens.count { it.chave == "orientar-codigo-produto" })
    controlador.parar()
    actor.close()
    advanceUntilIdle()
  }

  @Test
  fun `alerta critico para rotina antes de falar`() = runTest {
    val actor = PickingActor(this, PickingState.NavegandoParaEndereco(item))
    val saida = SaidaFake()
    val controlador =
        ControladorDeFala(actor, MutableStateFlow(diagnostico()), saida, this)

    controlador.iniciar()
    advanceUntilIdle()
    val paradasAntes = saida.paradas
    actor.send(PickingEvent.ExcecaoSolicitada(MotivoExcecao.AVARIA))
    advanceUntilIdle()

    assertTrue(saida.paradas > paradasAntes)
    assertEquals(PrioridadeFala.CRITICA, saida.mensagens.last().prioridade)
    controlador.parar()
    actor.close()
    advanceUntilIdle()
  }

  @Test
  fun `correcao de readback fala novamente a quantidade esperada da linha`() = runTest {
    val actor = PickingActor(this, PickingState.ConfirmandoQuantidade(item, quantidadeEsperada = 12))
    val saida = SaidaFake()
    val controlador =
        ControladorDeFala(actor, MutableStateFlow(diagnostico()), saida, this)

    controlador.iniciar()
    advanceUntilIdle()
    actor.send(PickingEvent.QuantidadeInformada(12))
    advanceUntilIdle()
    actor.send(PickingEvent.ReadbackCorrecaoSolicitada)
    advanceUntilIdle()

    assertEquals(
        listOf("Colete 12 unidades", "Confirma 12?", "Colete 12 unidades"),
        saida.mensagens.map { it.texto },
    )
    controlador.parar()
    actor.close()
    advanceUntilIdle()
  }

  @Test
  fun `retorno ao primeiro plano nao duplica mensagem do mesmo estado`() = runTest {
    val actor = PickingActor(this, PickingState.EscaneandoProduto(item))
    val saida = SaidaFake()
    val controlador =
        ControladorDeFala(actor, MutableStateFlow(diagnostico()), saida, this)

    controlador.iniciar()
    advanceUntilIdle()
    controlador.parar()
    controlador.iniciar()
    advanceUntilIdle()

    assertEquals(1, saida.mensagens.count { it.chave == "escanear-produto" })
    assertEquals(2, saida.inicios)
    assertEquals(1, saida.fechamentos)
    controlador.parar()
    actor.close()
    advanceUntilIdle()
  }

  private fun diagnostico(orientacaoPendente: Boolean = false) =
      DiagnosticoVisao(
          qualidade = QualidadeStream.MEDIA,
          fpsConfigurado = 7,
          fatorRecorte = 0.6f,
          orientacaoPendente = orientacaoPendente,
      )

  private class SaidaFake : SaidaDeAudio {
    private val _diagnostico = MutableStateFlow(DiagnosticoSaidaAudio())
    override val diagnostico: StateFlow<DiagnosticoSaidaAudio> = _diagnostico.asStateFlow()
    override val falando: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    val mensagens = mutableListOf<MensagemFalavel>()
    var inicios = 0
    var paradas = 0
    var fechamentos = 0

    override fun iniciar() {
      inicios++
      _diagnostico.value = DiagnosticoSaidaAudio(EstadoSaidaAudio.PRONTA)
    }

    override fun falar(mensagem: MensagemFalavel) {
      mensagens += mensagem
      _diagnostico.value =
          _diagnostico.value.copy(ultimaChaveMensagem = mensagem.chave)
    }

    override fun parar() {
      paradas++
    }

    override fun fechar() {
      fechamentos++
      _diagnostico.value = DiagnosticoSaidaAudio(EstadoSaidaAudio.PARADA)
    }
  }
}
