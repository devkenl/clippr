package app.clippr.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.clippr.core.ui.theme.palette
import kotlin.math.sin
import kotlin.random.Random

/** Short lines shown on the banner, one per day. Written for Clippr. */
private val Taglines = listOf(
    "Every frame is a choice.",
    "Cut what doesn't serve the story.",
    "Make something you'd want to watch.",
    "Good edits are felt, not seen.",
    "Start rough. Finish sharp.",
    "The story is in the cut.",
    "Less, but better.",
)

/**
 * The Home welcome banner. The artwork (dusk sky, stars and layered mountain ridges) is drawn
 * procedurally in Clippr's palette, so it is original and costs nothing in APK size.
 */
@Composable
internal fun HeroBanner(returning: Boolean, compact: Boolean, modifier: Modifier = Modifier) {
    val c = palette
    val tagline = remember { Taglines[((System.currentTimeMillis() / 86_400_000L) % Taglines.size).toInt()] }
    Box(
        modifier
            .fillMaxWidth()
            .height(if (compact) 168.dp else 210.dp)
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, c.hairline, RoundedCornerShape(20.dp)),
    ) {
        Canvas(Modifier.fillMaxSize()) { drawDuskMountains() }
        Column(Modifier.align(Alignment.CenterStart).padding(start = if (compact) 20.dp else 40.dp, end = 20.dp).widthIn(max = 560.dp)) {
            Text(
                buildAnnotatedString {
                    append(if (returning) "Welcome " else "Welcome to ")
                    withStyle(SpanStyle(color = c.accent)) { append(if (returning) "back" else "Clippr") }
                },
                fontSize = if (compact) 30.sp else 44.sp,
                fontWeight = FontWeight.Bold,
                color = c.textPrimary,
                lineHeight = if (compact) 34.sp else 50.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (returning) "Pick up where you left off, or start something new." else "Create, edit and export videos. Everything stays on this device.",
                style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                color = c.textPrimary.copy(alpha = 0.85f),
            )
        }
        if (!compact) {
            Column(Modifier.align(Alignment.BottomEnd).padding(end = 28.dp, bottom = 24.dp), horizontalAlignment = Alignment.End) {
                Text("“$tagline”", style = MaterialTheme.typography.bodyLarge, color = c.textPrimary.copy(alpha = 0.9f), textAlign = TextAlign.End)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.width(28.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(c.textPrimary.copy(alpha = 0.6f)))
            }
        }
    }
}

private fun DrawScope.drawDuskMountains() {
    val w = size.width
    val h = size.height
    // Sky: deep violet at the top to a warm glow at the horizon.
    drawRect(
        Brush.verticalGradient(
            0f to Color(0xFF14102A),
            0.45f to Color(0xFF3B2667),
            0.75f to Color(0xFF8E5A9E),
            1f to Color(0xFFD99A8C),
        ),
    )
    // Stars, fixed seed so they never jump around.
    val rnd = Random(7)
    repeat(70) {
        val x = rnd.nextFloat() * w
        val y = rnd.nextFloat() * h * 0.5f
        val r = 0.6f + rnd.nextFloat() * 1.3f
        drawCircle(Color.White.copy(alpha = 0.25f + rnd.nextFloat() * 0.5f), r, Offset(x, y))
    }
    // Soft glow behind the peaks.
    drawCircle(
        Brush.radialGradient(listOf(Color(0x66F5B38A), Color.Transparent), center = Offset(w * 0.62f, h * 0.78f), radius = w * 0.45f),
        radius = w * 0.45f,
        center = Offset(w * 0.62f, h * 0.78f),
    )
    ridge(seed = 3, base = 0.52f, amplitude = 0.26f, color = Color(0xFF5A3C86), roughness = 6)
    ridge(seed = 11, base = 0.64f, amplitude = 0.22f, color = Color(0xFF3A2860), roughness = 8)
    ridge(seed = 19, base = 0.78f, amplitude = 0.16f, color = Color(0xFF221840), roughness = 10)
    ridge(seed = 23, base = 0.9f, amplitude = 0.08f, color = Color(0xFF130E26), roughness = 14)
    // Darken the left side so the welcome text stays readable.
    drawRect(Brush.horizontalGradient(0f to Color(0xCC0E0B1C), 0.55f to Color(0x330E0B1C), 1f to Color.Transparent))
}

/** One jagged mountain silhouette from a few summed sine waves plus jitter. */
private fun DrawScope.ridge(seed: Int, base: Float, amplitude: Float, color: Color, roughness: Int) {
    val w = size.width
    val h = size.height
    val rnd = Random(seed)
    val phases = List(4) { rnd.nextFloat() * 6.28f }
    val steps = 120
    val path = Path().apply {
        moveTo(0f, h)
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val wave = sin(t * 3.1f + phases[0]) * 0.5f + sin(t * 7.3f + phases[1]) * 0.28f +
                sin(t * 13.7f + phases[2]) * 0.14f + sin(t * roughness * 4.1f + phases[3]) * 0.08f
            val jitter = (rnd.nextFloat() - 0.5f) * 0.02f
            lineTo(t * w, h * (base - amplitude * (0.5f + 0.5f * wave) + jitter))
        }
        lineTo(w, h)
        close()
    }
    drawPath(path, color)
}
