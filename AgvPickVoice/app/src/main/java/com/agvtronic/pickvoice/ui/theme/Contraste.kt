package com.agvtronic.pickvoice.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/** Mínimo AA da WCAG 2.1 para texto normal — spec `accessible-visual-identity`. */
const val CONTRASTE_MINIMO_AA = 4.5

/**
 * Taxa de contraste entre duas cores, pela fórmula da WCAG 2.1 (luminância relativa +
 * `(L1 + 0.05) / (L2 + 0.05)`, sempre com a mais clara no numerador).
 *
 * Kotlin puro, sem `toArgb()` nem nada que dependa do framework Android — só os componentes
 * `red`/`green`/`blue` já normalizados de [Color], por isso roda em teste de JVM comum (ver
 * `ContrasteTest`).
 */
fun taxaDeContraste(cor1: Color, cor2: Color): Double {
  val l1 = luminanciaRelativa(cor1)
  val l2 = luminanciaRelativa(cor2)
  val maisClara = maxOf(l1, l2)
  val maisEscura = minOf(l1, l2)
  return (maisClara + 0.05) / (maisEscura + 0.05)
}

private fun luminanciaRelativa(cor: Color): Double =
    0.2126 * canalLinear(cor.red) + 0.7152 * canalLinear(cor.green) + 0.0722 * canalLinear(cor.blue)

private fun canalLinear(componente: Float): Double {
  val c = componente.toDouble()
  return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
}
