package com.agvtronic.pickvoice.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * Hierarquia tipográfica do AGV Pick Voice: mesma fonte do sistema (Roboto via Compose default)
 * — não a família proprietária do íon Itaú, que é ativo de marca — com pesos mais fortes nos
 * títulos para leitura rápida em galpão, a distância e com luz variável.
 */
val Tipografia: Typography =
    Typography().let { padrao ->
        padrao.copy(
            titleLarge = padrao.titleLarge.reforcado(),
            titleMedium = padrao.titleMedium.reforcado(),
            headlineSmall = padrao.headlineSmall.reforcado(),
            headlineMedium = padrao.headlineMedium.reforcado(),
        )
    }

private fun TextStyle.reforcado(): TextStyle = copy(fontWeight = FontWeight.Bold)
