package com.agvtronic.pickvoice.vision

/** Estado resumido do stream para a tela espelho, sem expor objetos do SDK à UI. */
enum class EstadoStreamVisao {
  DESLIGADO,
  INICIANDO,
  ATIVO,
  ERRO,
}

enum class EstadoCapturaFoto {
  OCIOSA,
  ELEGIVEL,
  CAPTURANDO,
  COOLDOWN,
  CONFIRMADA,
  ERRO,
}

/** Resultado de uma tentativa do ML Kit. Não contém imagem nem o recorte analisado. */
data class TentativaDeLeitura(
    val codigo: String?,
    val duracaoMs: Long,
)

/**
 * Telemetria observável da câmera.
 *
 * Deliberadamente só contém valores pequenos e imutáveis: nenhum frame, `Image`, `ByteBuffer`
 * ou `Surface` pode escapar do pipeline de visão por este estado.
 */
data class DiagnosticoVisao(
    val estadoStream: EstadoStreamVisao = EstadoStreamVisao.DESLIGADO,
    val qualidade: QualidadeStream,
    val fpsConfigurado: Int,
    val fatorRecorte: Float,
    val larguraEfetiva: Int? = null,
    val alturaEfetiva: Int? = null,
    val ultimaTentativa: TentativaDeLeitura? = null,
    val ultimoCodigoConfirmado: String? = null,
    val estadoCaptura: EstadoCapturaFoto = EstadoCapturaFoto.OCIOSA,
    val tentativasCaptura: Int = 0,
    val quadrosEstaveis: Int = 0,
    val ultimaMetricaCaptura: MetricasCaptura? = null,
    val orientacaoPendente: Boolean = false,
    val detalheErro: String? = null,
)
