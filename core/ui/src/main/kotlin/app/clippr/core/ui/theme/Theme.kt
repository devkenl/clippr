package app.clippr.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Clippr colour tokens. Material roles are derived from these in [ClipprTheme]. */
@Immutable
data class ClipprColors(
    val background: Color = Color(0xFF101116),
    val surface: Color = Color(0xFF191B23),
    val panel: Color = Color(0xFF232630),
    val panelHigh: Color = Color(0xFF2C303C),
    val hairline: Color = Color(0xFF2A2D38),
    val outline: Color = Color(0xFF3A3E4B),
    val accent: Color = Color(0xFFA78BFA),
    val accentStrong: Color = Color(0xFF8B6CF6),
    val accentSoft: Color = Color(0x29A78BFA),
    val onAccent: Color = Color(0xFF14101F),
    val textPrimary: Color = Color(0xFFF5F5F7),
    val textSecondary: Color = Color(0xFFA0A3B1),
    val textTertiary: Color = Color(0xFF6C6F7D),
    val danger: Color = Color(0xFFF87171),
    val warning: Color = Color(0xFFFBBF24),
    val success: Color = Color(0xFF4ADE80),
    // Timeline clip families
    val videoClip: Color = Color(0xFF2B2745),
    val videoClipEdge: Color = Color(0xFF4B4378),
    val audioClip: Color = Color(0xFF18333A),
    val audioClipEdge: Color = Color(0xFF2B5C66),
    val waveform: Color = Color(0xFF5EEAD4),
    val textClip: Color = Color(0xFF3A3020),
    val textClipEdge: Color = Color(0xFF6B5524),
    val textClipLabel: Color = Color(0xFFFCD38D),
    val playhead: Color = Color(0xFFF5F5F7),
    val snapLine: Color = Color(0xFFFBBF24),
    val missingMedia: Color = Color(0xFF4A2228),
)

val LocalClipprColors = staticCompositionLocalOf { ClipprColors() }

/** Spacing scale (4dp grid). */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

object Radii {
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
}

private val Sans = FontFamily.SansSerif

private val ClipprTypography = Typography(
    displaySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.05.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 0.4.sp),
)

/** Tabular digits so timecodes do not jitter while playing. */
val TimecodeStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 13.sp,
    letterSpacing = 0.3.sp,
    fontFeatureSettings = "tnum",
)

private val ClipprShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(Radii.sm),
    medium = RoundedCornerShape(Radii.md),
    large = RoundedCornerShape(Radii.lg),
    extraLarge = RoundedCornerShape(Radii.xl),
)

@Composable
fun ClipprTheme(content: @Composable () -> Unit) {
    val c = ClipprColors()
    val scheme = darkColorScheme(
        primary = c.accent,
        onPrimary = c.onAccent,
        primaryContainer = c.accentSoft,
        onPrimaryContainer = c.textPrimary,
        secondary = c.accentStrong,
        onSecondary = c.textPrimary,
        background = c.background,
        onBackground = c.textPrimary,
        surface = c.surface,
        onSurface = c.textPrimary,
        surfaceVariant = c.panel,
        onSurfaceVariant = c.textSecondary,
        surfaceContainerLowest = c.background,
        surfaceContainerLow = c.surface,
        surfaceContainer = c.surface,
        surfaceContainerHigh = c.panel,
        surfaceContainerHighest = c.panelHigh,
        outline = c.outline,
        outlineVariant = c.hairline,
        error = c.danger,
        onError = c.background,
        scrim = Color(0xCC000000),
    )
    CompositionLocalProvider(LocalClipprColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = ClipprTypography, shapes = ClipprShapes, content = content)
    }
}

/** Shorthand for the current Clippr colour tokens. */
val palette: ClipprColors
    @Composable get() = LocalClipprColors.current
