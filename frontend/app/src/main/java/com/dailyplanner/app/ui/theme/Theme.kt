package com.dailyplanner.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// App chrome follows the "Routine Planner" palette from Figma.
val Cream = Color(0xFFFFFDF5)
val Amber = Color(0xFFDD8D00)
val Peach = Color(0xFFFFDB9B)
val PeachSoft = Color(0xFFFFF1D6)
val Ink = Color(0xFF1F1F1F)
val InkSoft = Color(0xFF6B6259)
val Line = Color(0xFFEBDDC4)

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun fredoka(weight: Int) = Font(
    path = "fonts/fredoka_var.ttf",
    assetManager = AppAssets.manager,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.width(100f)),
)

object AppAssets { lateinit var manager: android.content.res.AssetManager }

val Fredoka by lazy { FontFamily(fredoka(400), fredoka(500), fredoka(600), fredoka(700)) }

@Composable
fun DailyPlannerTheme(content: @Composable () -> Unit) {
    val f = Fredoka
    val base = Typography()
    fun TextStyle.fr() = copy(fontFamily = f)
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Amber,
            onPrimary = Color.White,
            primaryContainer = Peach,
            onPrimaryContainer = Ink,
            secondary = Color(0xFF8A6A3A),
            secondaryContainer = PeachSoft,
            onSecondaryContainer = Ink,
            background = Cream,
            onBackground = Ink,
            surface = Cream,
            onSurface = Ink,
            surfaceVariant = PeachSoft,
            onSurfaceVariant = InkSoft,
            surfaceContainer = Color(0xFFFFF8E8),
            surfaceContainerHigh = Color(0xFFFFF4DE),
            surfaceContainerLow = Color(0xFFFFFBEF),
            outline = Color(0xFFD9C3A0),
            outlineVariant = Line,
        ),
        typography = Typography(
            displaySmall = base.displaySmall.fr(),
            headlineMedium = base.headlineMedium.fr().copy(fontWeight = FontWeight(600)),
            headlineSmall = base.headlineSmall.fr().copy(fontWeight = FontWeight(600)),
            titleLarge = base.titleLarge.fr().copy(fontWeight = FontWeight(600)),
            titleMedium = base.titleMedium.fr().copy(fontWeight = FontWeight(500)),
            titleSmall = base.titleSmall.fr().copy(fontWeight = FontWeight(500)),
            bodyLarge = base.bodyLarge.fr(),
            bodyMedium = base.bodyMedium.fr(),
            bodySmall = base.bodySmall.fr(),
            labelLarge = base.labelLarge.fr().copy(fontWeight = FontWeight(500)),
            labelMedium = base.labelMedium.fr().copy(fontWeight = FontWeight(500)),
            labelSmall = base.labelSmall.fr().copy(fontSize = 11.sp),
        ),
        content = content,
    )
}
