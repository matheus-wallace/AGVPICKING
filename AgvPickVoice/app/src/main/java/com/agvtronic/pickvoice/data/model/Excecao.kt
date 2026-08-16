package com.agvtronic.pickvoice.data.model

import com.agvtronic.pickvoice.domain.statemachine.MotivoExcecao
import java.time.Instant

/**
 * Ocorrência registrada no estado `TratandoExcecao`.
 *
 * Os campos estruturados espelham o JSON que o LLM produz a partir do relato livre
 * transcrito (doc §6.4): `{tipo, divergencia_lote, qtd_aproveitavel, requer_supervisor}`.
 * [relatoTranscrito] guarda a transcrição crua junto, porque em 8 kHz com gramática livre
 * ela é ruidosa e a estruturação pode ter errado — a fonte precisa sobreviver ao resumo.
 */
data class Excecao(
    /** Índice da linha afetada, ou `null` quando a exceção é da ordem inteira. */
    val linha: Int?,
    val tipo: MotivoExcecao,
    val relatoTranscrito: String,
    /** Lote encontrado, quando diverge do esperado. */
    val divergenciaLote: String? = null,
    /** Quantidade ainda aproveitável apesar da ocorrência. */
    val quantidadeAproveitavel: Int? = null,
    val requerSupervisor: Boolean = false,
    val timestamp: Instant,
)
