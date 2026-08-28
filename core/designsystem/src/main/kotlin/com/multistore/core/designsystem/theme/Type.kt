package com.multistore.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * Typography.
 *
 * We stay on the system family: it already has the right shapes for the 5 interface languages
 * and for the Cyrillic that turns up in pdalife and an1 content, without shipping fonts in the
 * APK. The scale is Material 3's; only the weights are adjusted, because the default headings
 * read too light next to app icons.
 */
internal val MultiStoreTypography: Typography = Typography().let { base ->
    base.copy(
        headlineLarge = base.headlineLarge.weighted(FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.weighted(FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.weighted(FontWeight.SemiBold),
        titleLarge = base.titleLarge.weighted(FontWeight.SemiBold),
        titleMedium = base.titleMedium.weighted(FontWeight.Medium),
        titleSmall = base.titleSmall.weighted(FontWeight.Medium),
        labelLarge = base.labelLarge.weighted(FontWeight.Medium),
        labelMedium = base.labelMedium.weighted(FontWeight.Medium),
        labelSmall = base.labelSmall.weighted(FontWeight.Medium),
    )
}

private fun TextStyle.weighted(weight: FontWeight): TextStyle =
    copy(fontFamily = FontFamily.Default, fontWeight = weight)
