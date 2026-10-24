package app.clippr.feature.timeline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Horizontal mapping between timeline time and screen pixels.
 *
 * Scroll position is stored as the time at the left edge ([leftUs]), so zooming keeps the
 * content under the anchor point (pinch centroid or mouse pointer) fixed.
 */
@Stable
class TimelineViewport(pxPerSecond: Float, leftUs: Double) {

    /** Zoom level in pixels per second of timeline. */
    var pxPerSecond by mutableFloatStateOf(pxPerSecond.coerceIn(MIN_PX_PER_SECOND, MAX_PX_PER_SECOND))
        private set

    /** Timeline time shown at the left edge of the clip area. */
    var leftUs by mutableDoubleStateOf(leftUs)
        private set

    /** Width of the clip area in pixels, reported by the timeline when laid out. */
    var widthPx by mutableFloatStateOf(0f)
        internal set

    fun timeToX(timeUs: Long): Float = ((timeUs - leftUs) * pxPerSecond / 1_000_000.0).toFloat()

    fun xToTime(x: Float): Long = (leftUs + x * 1_000_000.0 / pxPerSecond).roundToLong()

    fun pxToDurationUs(px: Float): Long = (px * 1_000_000.0 / pxPerSecond).roundToLong()

    fun durationToPx(durationUs: Long): Float = (durationUs * pxPerSecond / 1_000_000.0).toFloat()

    /** Scrolls by [dx] pixels, clamped to `[0, maxUs]`. */
    fun scrollBy(dx: Float, maxUs: Long) {
        leftUs = (leftUs + dx * 1_000_000.0 / pxPerSecond).coerceIn(0.0, max(0L, maxUs).toDouble())
    }

    fun scrollTo(timeUs: Double, maxUs: Long) {
        leftUs = timeUs.coerceIn(0.0, max(0L, maxUs).toDouble())
    }

    /** Multiplies the zoom by [factor], keeping the time under [anchorX] in place. */
    fun zoomBy(factor: Float, anchorX: Float, maxUs: Long) {
        val anchorTime = leftUs + anchorX * 1_000_000.0 / pxPerSecond
        pxPerSecond = (pxPerSecond * factor).coerceIn(MIN_PX_PER_SECOND, MAX_PX_PER_SECOND)
        leftUs = (anchorTime - anchorX * 1_000_000.0 / pxPerSecond).coerceIn(0.0, max(0L, maxUs).toDouble())
    }

    /** Fits [durationUs] into the visible width. */
    fun fit(durationUs: Long) {
        if (widthPx <= 0f) return
        val d = max(durationUs, 1_000_000L)
        pxPerSecond = (widthPx * 0.92f / (d / 1_000_000f)).coerceIn(MIN_PX_PER_SECOND, MAX_PX_PER_SECOND)
        leftUs = 0.0
    }

    /** Scrolls so that [timeUs] is visible, paging when it leaves the right side. */
    fun follow(timeUs: Long, maxUs: Long) {
        if (widthPx <= 0f) return
        val x = timeToX(timeUs)
        if (x < 0f || x > widthPx * 0.9f) {
            scrollTo(timeUs - pxToDurationUs(widthPx * 0.1f).toDouble(), maxUs)
        }
    }

    companion object {
        const val MIN_PX_PER_SECOND = 1.5f
        const val MAX_PX_PER_SECOND = 2400f

        val Saver: Saver<TimelineViewport, Any> = listSaver(
            save = { listOf(it.pxPerSecond.toDouble(), it.leftUs) },
            restore = { TimelineViewport(it[0].toFloat(), it[1]) },
        )
    }
}

@Composable
fun rememberTimelineViewport(initialPxPerSecond: Float = 80f): TimelineViewport =
    rememberSaveable(saver = TimelineViewport.Saver) { TimelineViewport(initialPxPerSecond, 0.0) }

/** Picks ruler tick spacing so labelled ticks are at least [minLabelSpacingPx] apart. */
internal object RulerScale {
    private val majorSteps = longArrayOf(
        100_000, 200_000, 500_000, 1_000_000, 2_000_000, 5_000_000, 10_000_000, 15_000_000,
        30_000_000, 60_000_000, 120_000_000, 300_000_000, 600_000_000, 1_800_000_000, 3_600_000_000,
    )

    /** Returns (major step, minor subdivisions). */
    fun steps(pxPerSecond: Float, minLabelSpacingPx: Float): Pair<Long, Int> {
        val step = majorSteps.firstOrNull { it / 1_000_000f * pxPerSecond >= minLabelSpacingPx } ?: majorSteps.last()
        val minor = when (step) {
            200_000L, 2_000_000L, 120_000_000L -> 4
            15_000_000L -> 3
            else -> 5
        }
        return step to minor
    }

    fun label(timeUs: Long, stepUs: Long): String {
        val totalSec = timeUs / 1_000_000L
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        val base = if (h > 0) "%d:%02d:%02d".format(java.util.Locale.ROOT, h, m, s) else "%d:%02d".format(java.util.Locale.ROOT, m, s)
        return if (stepUs < 1_000_000L) {
            val tenths = (timeUs % 1_000_000L) / 100_000L
            if (tenths == 0L) base else "$base.$tenths"
        } else {
            base
        }
    }
}
