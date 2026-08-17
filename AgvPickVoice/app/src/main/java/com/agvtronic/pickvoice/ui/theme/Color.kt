package com.agvtronic.pickvoice.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Paleta própria do AGV Pick Voice, inspirada nos princípios de acessibilidade publicados sobre
 * o íon Itaú — não uma cópia de ativos de marca do Itaú (design.md - Decisão 9 de
 * `add-operator-feedback-improvements`): dois tons de verde como cor principal, acento em
 * verde-limão e cinza, laranja restrito a destaques pontuais.
 *
 * Todo par texto/fundo aqui foi conferido contra o mínimo de contraste AA da WCAG 2.1 (4,5:1) —
 * ver [ContrasteTest] em `test/`. Os nomes seguem os *roles* do Material 3 (`ColorScheme`) para
 * que o tema em [Theme.kt] seja só um `lightColorScheme(...)` direto, sem mapeamento adicional.
 */

// Verde — cor principal, dois tons (base + container, o "duotom" do princípio original).
val Verde = Color(0xFF146C3F)
val VerdeContainer = Color(0xFFC8F5D6)
val OnVerdeContainer = Color(0xFF0B3A21)

// Verde-limão — acento secundário.
val VerdeLimao = Color(0xFF3D5C1B)
val VerdeLimaoContainer = Color(0xFFE3F5B8)
val OnVerdeLimaoContainer = Color(0xFF25390F)

// Laranja — destaque pontual, nunca superfície dominante (spec accessible-visual-identity).
val Laranja = Color(0xFF8A4400)
val LaranjaContainer = Color(0xFFFFDCB8)
val OnLaranjaContainer = Color(0xFF5C2D00)

// Neutros — fundo, superfície e texto.
val Fundo = Color(0xFFF5FAF6)
val Superficie = Color(0xFFFFFFFF)
val OnFundo = Color(0xFF12231A)
val OnSuperficieVariante = Color(0xFF42504A)
val Contorno = Color(0xFF5F6D66)

// Erro — vermelho, deliberadamente distinto do laranja de destaque para não confundir "erro"
// com "atenção pontual".
val Erro = Color(0xFFB3261E)
