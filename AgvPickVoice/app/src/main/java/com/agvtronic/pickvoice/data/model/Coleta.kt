package com.agvtronic.pickvoice.data.model

import java.time.Instant

/** O registro de que uma linha foi efetivamente coletada — doc §11.2. */
data class Coleta(
    val quantidade: Int,
    /** Lote efetivamente coletado — mesmo vocabulário do WMS que `Linha.partida`. */
    val partida: String,
    val serie: String,
    val timestamp: Instant,
    val metodoValidacao: MetodoValidacao,
    val confianca: Float,
    /**
     * Se o operador confirmou explicitamente o readback antes do registro.
     *
     * Invariante do doc §3.4.2: nada é registrado sem readback confirmado quando o valor
     * diverge do esperado.
     */
    val readbackConfirmado: Boolean,
)

/**
 * Por qual caminho a coleta foi validada — o campo mais importante do modelo (doc §11.3).
 *
 * É a resposta para "como vocês provam a rastreabilidade": o sistema não registra só que o
 * item foi conferido, mas por qual caminho e com que confiança. Um item validado por
 * DataMatrix e um validado por OCR com fuzzy match não têm o mesmo peso probatório, e o
 * sistema não finge que têm — em auditoria ANVISA isso é a diferença entre evidência e
 * afirmação.
 */
enum class MetodoValidacao {
  /** ML Kit nos frames do stream (cascata §6.3, passo 1). */
  DATAMATRIX_STREAM,

  /** ML Kit no frame de `capturePhoto()` ou suas variantes (passos 2–3). */
  DATAMATRIX_FOTO,

  /** zxing-cpp sobre as variantes de pré-processamento (passo 4). */
  CODE128,

  /** Text Recognition v2 com fuzzy match contra o lote esperado (passo 5). */
  OCR_FUZZY,

  /** Verificação por VLM — único passo que usa rede (passo 6, §6.4). */
  VLM_ASSISTIDO,

  /** Check digit do produto por voz — fallback final da cascata (passo 7, §7.2). */
  CHECK_DIGIT_VOZ,

  /** Registro manual pelo painel do celular. */
  MANUAL,
}
