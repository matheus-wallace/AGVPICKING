package com.agvtronic.pickvoice.audio.output

import com.agvtronic.pickvoice.domain.statemachine.PickingActor
import com.agvtronic.pickvoice.domain.statemachine.PickingState
import com.agvtronic.pickvoice.vision.DiagnosticoVisao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Observa domínio e visão e produz fala sem publicar eventos de volta ao ator. */
class ControladorDeFala(
    private val actor: PickingActor,
    private val diagnosticoVisao: StateFlow<DiagnosticoVisao>,
    private val saida: SaidaDeAudio,
    private val scope: CoroutineScope,
    private val projetor: ProjetorDeFalaPicking = ProjetorDeFalaPicking(),
) {
  val diagnostico: StateFlow<DiagnosticoSaidaAudio> = saida.diagnostico

  private var jobEstado: Job? = null
  private var jobOrientacao: Job? = null
  private var ultimoEstado: PickingState? = null
  private val chavesDaEntrada = mutableSetOf<String>()

  fun iniciar() {
    saida.iniciar()
    if (jobEstado != null) return

    jobEstado =
        scope.launch {
          // StateFlow já suprime valores iguais; aplicar distinctUntilChanged seria redundante.
          actor.state.collect { estado ->
            if (estado != ultimoEstado) {
              ultimoEstado = estado
              chavesDaEntrada.clear()
            }
            projetor.projetar(estado)?.let(::emitirUmaVez)
          }
        }
    jobOrientacao =
        scope.launch {
          combine(actor.state, diagnosticoVisao) { estado, diagnostico ->
                estado is PickingState.EscaneandoProduto && diagnostico.orientacaoPendente
              }
              .distinctUntilChanged()
              .collect { deveOrientar ->
                if (deveOrientar) {
                  emitirUmaVez(
                      MensagemFalavel(
                          chave = CHAVE_ORIENTACAO,
                          texto = "aponte para o código do produto",
                      )
                  )
                }
              }
        }
  }

  /** Para observação e libera o motor; as chaves ficam para não duplicar no retorno. */
  fun parar() {
    jobEstado?.cancel()
    jobOrientacao?.cancel()
    jobEstado = null
    jobOrientacao = null
    saida.parar()
    saida.fechar()
  }

  private fun emitirUmaVez(mensagem: MensagemFalavel) {
    if (!chavesDaEntrada.add(mensagem.chave)) return
    if (mensagem.prioridade == PrioridadeFala.CRITICA) saida.parar()
    saida.falar(mensagem)
  }

  private companion object {
    const val CHAVE_ORIENTACAO = "orientar-codigo-produto"
  }
}
