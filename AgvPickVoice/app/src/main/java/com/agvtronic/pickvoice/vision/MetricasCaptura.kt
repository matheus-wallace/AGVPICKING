package com.agvtronic.pickvoice.vision

import kotlin.math.abs

/** Sinais pequenos usados para decidir a captura; não contém pixels. */
data class MetricasCaptura(
    val varianciaLaplaciano: Double,
    val diferencaTemporalMedia: Double?,
)

/**
 * Reduz a luminância da ROI e calcula nitidez/detalhe e estabilidade temporal.
 *
 * Só a miniatura anterior, de no máximo 32×32 bytes, permanece entre chamadas. O NV21 recebido
 * continua pertencendo ao leitor e não é guardado aqui.
 */
class AnalisadorMetricasCaptura(private val ladoMiniatura: Int = 32) {
  private var miniaturaAnterior: ByteArray? = null

  init {
    require(ladoMiniatura >= 3) { "ladoMiniatura precisa ser >= 3" }
  }

  fun analisar(recorte: RecorteNv21): MetricasCaptura {
    val largura = minOf(ladoMiniatura, recorte.largura)
    val altura = minOf(ladoMiniatura, recorte.altura)
    val atual = ByteArray(largura * altura)
    var destino = 0
    for (y in 0 until altura) {
      val origemY = y * recorte.altura / altura
      for (x in 0 until largura) {
        val origemX = x * recorte.largura / largura
        atual[destino++] = recorte.bytes[origemY * recorte.largura + origemX]
      }
    }

    val anterior = miniaturaAnterior
    val diferenca =
        if (anterior != null && anterior.size == atual.size) {
          atual.indices.sumOf { abs(atual[it].u8() - anterior[it].u8()) }.toDouble() / atual.size
        } else {
          null
        }
    miniaturaAnterior = atual

    var soma = 0.0
    var somaQuadrados = 0.0
    var quantidade = 0
    for (y in 1 until altura - 1) {
      for (x in 1 until largura - 1) {
        val centro = atual[y * largura + x].u8()
        val laplaciano =
            4 * centro -
                atual[(y - 1) * largura + x].u8() -
                atual[(y + 1) * largura + x].u8() -
                atual[y * largura + x - 1].u8() -
                atual[y * largura + x + 1].u8()
        soma += laplaciano
        somaQuadrados += laplaciano.toDouble() * laplaciano
        quantidade++
      }
    }
    val media = if (quantidade == 0) 0.0 else soma / quantidade
    val variancia =
        if (quantidade == 0) 0.0 else (somaQuadrados / quantidade - media * media).coerceAtLeast(0.0)
    return MetricasCaptura(variancia, diferenca)
  }

  fun reiniciar() {
    miniaturaAnterior = null
  }

  private fun Byte.u8(): Int = toInt() and 0xFF
}

/** Resultado de uma avaliação serial do gatilho. */
data class DecisaoCaptura(
    val capturar: Boolean,
    val orientarOperador: Boolean,
    val emCooldown: Boolean,
    val quadrosEstaveis: Int,
    val tentativas: Int,
)

/** Máquina pura que aplica qualidade, estabilidade e cooldown antes de disparar uma captura. */
class GatilhoDeCaptura(private val ajustes: AjustesVisao) {
  private var quadrosEstaveis = 0
  private var tentativas = 0
  private var ultimoFracassoMs: Long? = null
  private var inicioCicloMs: Long? = null
  private var orientacaoEmitida = false

  fun avaliar(metricas: MetricasCaptura, agoraMs: Long): DecisaoCaptura {
    if (inicioCicloMs == null) inicioCicloMs = agoraMs
    val orientar =
        !orientacaoEmitida &&
            agoraMs - checkNotNull(inicioCicloMs) >= ajustes.timeoutOrientacaoMs
    if (orientar) orientacaoEmitida = true

    val foraDoCooldown =
        ultimoFracassoMs?.let { agoraMs - it >= ajustes.cooldownCapturaMs } ?: true
    val elegivel =
        ajustes.capturaPorFotoAtiva &&
            foraDoCooldown &&
            metricas.varianciaLaplaciano >= ajustes.limiarDetalhe &&
            metricas.varianciaLaplaciano >= ajustes.limiarNitidez &&
            metricas.diferencaTemporalMedia != null &&
            metricas.diferencaTemporalMedia <= ajustes.limiarEstabilidade

    quadrosEstaveis = if (elegivel) quadrosEstaveis + 1 else 0
    val capturar = quadrosEstaveis >= ajustes.quadrosEstaveisParaCaptura
    if (capturar) {
      tentativas++
      quadrosEstaveis = 0
    }
    return DecisaoCaptura(capturar, orientar, !foraDoCooldown, quadrosEstaveis, tentativas)
  }

  /**
   * Registra uma tentativa de captura sem código encontrado: aplica o cooldown e reinicia a
   * sequência de estabilidade. Não há mais esgotamento — a câmera continua tentando até o
   * operador escanear o produto ou reportar exceção por voz (spec: janela de câmera acomoda o
   * tempo real do operador).
   */
  fun registrarFracasso(agoraMs: Long) {
    ultimoFracassoMs = agoraMs
    quadrosEstaveis = 0
  }

  fun reiniciar() {
    quadrosEstaveis = 0
    tentativas = 0
    ultimoFracassoMs = null
    inicioCicloMs = null
    orientacaoEmitida = false
  }
}
