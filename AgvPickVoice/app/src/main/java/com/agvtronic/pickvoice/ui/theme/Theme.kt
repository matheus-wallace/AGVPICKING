package com.agvtronic.pickvoice.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val EsquemaDeCores =
    lightColorScheme(
        primary = Verde,
        onPrimary = Superficie,
        primaryContainer = VerdeContainer,
        onPrimaryContainer = OnVerdeContainer,
        secondary = VerdeLimao,
        onSecondary = Superficie,
        secondaryContainer = VerdeLimaoContainer,
        onSecondaryContainer = OnVerdeLimaoContainer,
        tertiary = Laranja,
        onTertiary = Superficie,
        tertiaryContainer = LaranjaContainer,
        onTertiaryContainer = OnLaranjaContainer,
        background = Fundo,
        onBackground = OnFundo,
        surface = Superficie,
        onSurface = OnFundo,
        onSurfaceVariant = OnSuperficieVariante,
        outline = Contorno,
        error = Erro,
        onError = Superficie,
    )

/**
 * O tema visual das telas operacional e de espelho (spec `accessible-visual-identity`).
 *
 * Um esquema só, sem alternância clara/escuro por enquanto: o app roda em galpão bem iluminado,
 * e o par texto/fundo já foi calibrado para o mínimo AA (4,5:1) nesse cenário — ver
 * [ContrasteTest] em `test/`. Adicionar um esquema escuro fica para quando houver um cenário de
 * uso que precise dele, não como generalização antecipada.
 */
@Composable
fun AgvPickVoiceTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = EsquemaDeCores, typography = Tipografia, content = content)
}
