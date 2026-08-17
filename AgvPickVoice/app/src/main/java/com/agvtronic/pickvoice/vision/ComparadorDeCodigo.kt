package com.agvtronic.pickvoice.vision

import com.agvtronic.pickvoice.data.PickingRepository
import com.agvtronic.pickvoice.data.model.Linha
import com.agvtronic.pickvoice.domain.statemachine.CODIGO_CHECK_DIGIT_PRODUTO
import com.agvtronic.pickvoice.domain.statemachine.ItemEmAndamento
import com.agvtronic.pickvoice.domain.statemachine.MotivoExcecao
import com.agvtronic.pickvoice.domain.statemachine.PickingActor
import com.agvtronic.pickvoice.domain.statemachine.PickingEvent
import com.agvtronic.pickvoice.domain.statemachine.PickingState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Quem fecha o elo entre o código lido e o dado que a ordem esperava — o produtor que faltava
 * para `ValidandoContraDados` sair sozinho do lugar.
 *
 * Fica em `vision/` mas não encosta em pixel nenhum: o `ControladorDeVisao` entrega o texto
 * decodificado e para por aí, porque misturar "ligar a câmera e decodificar" com "consultar o
 * repositório e decidir OK/divergente" acoplaria o componente de sensor ao [PickingRepository] —
 * a mesma separação que a voz já faz entre `ReconhecedorDeComando` e `ResolvedorDeIntencao`.
 *
 * Nunca escreve estado: observa [PickingActor.state] e devolve [PickingEvent], como todo produtor
 * do projeto. E nunca confirma às cegas — a comparação é literal contra o EAN cadastrado, e o que
 * não bate vira exceção em vez de seguir o fluxo.
 *
 * Sem thread dedicada, ao contrário do Vosk e do Silero: aqui não existe SDK não-thread-safe nem
 * estado mutável de hardware, só uma consulta suspensa. O `Dispatchers.Default` do [scope] basta.
 *
 * @param aoRegistrar hook de log estruturado. A classe é Kotlin puro para rodar na JVM em teste,
 *   então quem sabe escrever no logcat é quem a constrói. Nenhuma mensagem daqui carrega o valor
 *   esperado da linha — revelá-lo em log derrubaria a mesma garantia que o check digit protege.
 */
class ComparadorDeCodigo(
    private val actor: PickingActor,
    private val repository: PickingRepository,
    private val scope: CoroutineScope,
    private val aoRegistrar: (String) -> Unit = {},
) {

  private var job: Job? = null

  /** Idempotente, como todo `iniciar` do projeto. */
  fun iniciar() {
    if (job != null) return
    job =
        scope.launch {
          // `collectLatest` cancela a consulta em andamento se o estado mudar antes dela
          // terminar. É a primeira metade da guarda contra resultado obsoleto; a segunda está
          // em [comparar], porque o cancelamento só é observado numa suspensão — e entre o
          // retorno do repositório e o `send` não existe nenhuma.
          actor.state.collectLatest { estado ->
            if (estado is PickingState.ValidandoContraDados) comparar(estado)
          }
        }
  }

  fun parar() {
    job?.cancel()
    job = null
  }

  /**
   * A comparação de um estado de validação, do começo ao fim.
   *
   * `internal` para que o teste de JVM consiga exercitar a guarda de estado obsoleto sem
   * depender de uma corrida real entre o ator e o repositório.
   */
  internal suspend fun comparar(estado: PickingState.ValidandoContraDados) {
    val linha = linhaDoItem(estado.itemEmAndamento)
    if (linha == null) {
      // Não deveria acontecer — o item veio do próprio ator. Se acontecer, o fluxo trava
      // esperando o painel de dev, o que é melhor do que fingir uma validação que não houve.
      aoRegistrar(
          "VALIDACAO_AUTOMATICA resultado=linha_nao_encontrada " +
              "ordem=${estado.itemEmAndamento.ordemId} linha=${estado.itemEmAndamento.indiceLinha}"
      )
      return
    }

    // O sentinela do fallback de check digit do produto (doc §7.2) nunca é um EAN: o produto já
    // foi confirmado pelos dois últimos dígitos do lote falado, e compará-lo contra `linha.ean`
    // faria esse caminho — que hoje funciona — divergir sempre.
    val porCheckDigitDeVoz = estado.codigoLido == CODIGO_CHECK_DIGIT_PRODUTO
    val evento =
        if (porCheckDigitDeVoz || estado.codigoLido == linha.ean) {
          PickingEvent.ValidacaoOk(linha.quantidade)
        } else {
          PickingEvent.ValidacaoDivergente(MotivoExcecao.DIVERGENCIA)
        }

    // A consulta suspendeu, e o mundo pode ter mudado embaixo dela: um "parar", uma queda de
    // Bluetooth ou o botão do painel já podem ter tirado o ator daqui.
    if (actor.state.value != estado) {
      aoRegistrar("VALIDACAO_AUTOMATICA resultado=descartado_estado_obsoleto")
      return
    }

    aoRegistrar(
        "VALIDACAO_AUTOMATICA resultado=${if (evento is PickingEvent.ValidacaoOk) "ok" else "divergente"} " +
            "origem=${if (porCheckDigitDeVoz) "check_digit_voz" else "leitura"}"
    )
    actor.send(evento)
  }

  /**
   * A linha do item, ou `null` quando a ordem não existe mais.
   *
   * O repositório lança `NoSuchElementException` para ordem desconhecida; aqui isso vira ausência
   * de evento, mesmo padrão defensivo do `ResolvedorDeIntencao`.
   */
  private suspend fun linhaDoItem(item: ItemEmAndamento): Linha? =
      try {
        repository.ordem(item.ordemId).linhas.getOrNull(item.indiceLinha)
      } catch (cancelamento: CancellationException) {
        throw cancelamento
      } catch (semOrdem: NoSuchElementException) {
        null
      }
}
