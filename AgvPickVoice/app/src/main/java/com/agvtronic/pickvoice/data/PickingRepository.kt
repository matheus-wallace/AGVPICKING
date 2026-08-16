package com.agvtronic.pickvoice.data

import com.agvtronic.pickvoice.data.model.Coleta
import com.agvtronic.pickvoice.data.model.Conferencia
import com.agvtronic.pickvoice.data.model.Excecao
import com.agvtronic.pickvoice.data.model.Operador
import com.agvtronic.pickvoice.data.model.Ordem
import com.agvtronic.pickvoice.data.model.ResumoOrdem

/**
 * Acesso a ordem, produto, lote, endereço e usuário — doc §11.1.
 *
 * A assinatura é **idêntica à que uma implementação HTTP teria**, e é por isso que todos os
 * métodos são `suspend` mesmo quando a implementação atual é um mapa em memória: trocar
 * `MockPickingRepository` por um `HttpPickingRepository` quando a integração real com o WMS
 * acontecer precisa ser uma linha no `AppContainer`, não uma refatoração dos chamadores.
 *
 * Esta é a única camada mockada do sistema (doc §1.2). Sensor e decodificação nunca são —
 * mockar resultado de decodificação invalidaria o experimento inteiro.
 */
interface PickingRepository {

  /** O separador da sessão. Sem login/token no protótipo (doc §12). */
  suspend fun operadorAtual(): Operador

  /** Cabeçalhos das ordens disponíveis para separação. */
  suspend fun ordensDisponiveis(): List<ResumoOrdem>

  /**
   * A ordem completa com suas linhas.
   *
   * @throws NoSuchElementException se [id] não existe.
   */
  suspend fun ordem(id: String): Ordem

  /**
   * Registra a coleta de uma linha.
   *
   * @param linha índice da linha em [Ordem.linhas].
   */
  suspend fun registrarColeta(ordemId: String, linha: Int, coleta: Coleta)

  /** Registra uma ocorrência estruturada a partir do estado `TratandoExcecao`. */
  suspend fun registrarExcecao(ordemId: String, excecao: Excecao)

  /** Fecha a ordem e devolve o resultado da conferência. */
  suspend fun fecharConferencia(ordemId: String): Conferencia
}
