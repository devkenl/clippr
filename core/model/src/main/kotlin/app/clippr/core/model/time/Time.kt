package app.clippr.core.model.time

import java.util.Locale

/**
 * Clippr represents all editing time as integer microseconds ([Long]). Floating-point seconds are
 * never stored in the project model; they are only produced for display.
 */
const val US_PER_MS: Long = 1_000L
const val US_PER_SECOND: Long = 1_000_000L

fun Long.msToUs(): Long = this * US_PER_MS

fun Long.usToMs(): Long = this / US_PER_MS

fun Double.secondsToUs(): Long = Math.round(this * US_PER_SECOND)

fun Long.usToSeconds(): Double = this.toDouble() / US_PER_SECOND

/**
 * A frame rate expressed as an exact rational number, so that NTSC rates such as 29.97
 * (`30000/1001`) are represented without rounding error.
 *
 * Frame boundaries are defined so that [frameIndexAt] and [frameStartUs] are exact inverses:
 * `frameIndexAt(frameStartUs(i)) == i` for every non-negative `i`.
 */
data class FrameRate(val numerator: Int, val denominator: Int = 1) {
    init {
        require(numerator > 0) { "numerator must be positive: $numerator" }
        require(denominator > 0) { "denominator must be positive: $denominator" }
    }

    val fps: Double get() = numerator.toDouble() / denominator

    /** Nominal duration of a single frame, rounded up to the next microsecond. */
    val frameDurationUs: Long get() = ceilDiv(denominator.toLong() * US_PER_SECOND, numerator.toLong())

    /** Index of the frame that is displayed at [timeUs]. */
    fun frameIndexAt(timeUs: Long): Long {
        val t = timeUs.coerceAtLeast(0)
        return t * numerator / (denominator.toLong() * US_PER_SECOND)
    }

    /** First microsecond at which frame [index] is displayed. */
    fun frameStartUs(index: Long): Long {
        val i = index.coerceAtLeast(0)
        return ceilDiv(i * denominator * US_PER_SECOND, numerator.toLong())
    }

    /** Snaps [timeUs] to the start of the frame it falls in. */
    fun floorToFrame(timeUs: Long): Long = frameStartUs(frameIndexAt(timeUs))

    /** Snaps [timeUs] to the nearest frame boundary. */
    fun roundToFrame(timeUs: Long): Long {
        val floor = floorToFrame(timeUs)
        val next = frameStartUs(frameIndexAt(timeUs) + 1)
        return if (timeUs - floor <= next - timeUs) floor else next
    }

    /** Integer frames per second used for timecode display (e.g. 30 for 29.97). */
    val timecodeBase: Int get() = Math.round(fps).toInt().coerceAtLeast(1)

    override fun toString(): String =
        if (denominator == 1) "$numerator fps" else "%.3f fps".format(Locale.ROOT, fps)

    companion object {
        val FPS_24 = FrameRate(24)
        val FPS_25 = FrameRate(25)
        val FPS_30 = FrameRate(30)
        val FPS_50 = FrameRate(50)
        val FPS_60 = FrameRate(60)
        val NTSC_23_976 = FrameRate(24_000, 1_001)
        val NTSC_29_97 = FrameRate(30_000, 1_001)
        val NTSC_59_94 = FrameRate(60_000, 1_001)

        val COMMON: List<FrameRate> = listOf(FPS_24, FPS_25, FPS_30, FPS_50, FPS_60)

        private fun ceilDiv(a: Long, b: Long): Long = -Math.floorDiv(-a, b)
    }
}

/** Formatting helpers for displaying microsecond times. */
object Timecode {

    /** Non-drop-frame SMPTE-style timecode `HH:MM:SS:FF`. */
    fun format(timeUs: Long, frameRate: FrameRate): String {
        val t = timeUs.coerceAtLeast(0)
        val totalSeconds = t / US_PER_SECOND
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val frameInSecond =
            frameRate.frameIndexAt(t) - frameRate.frameIndexAt(totalSeconds * US_PER_SECOND)
        val frames = frameInSecond.coerceIn(0, (frameRate.timecodeBase - 1).toLong())
        return "%02d:%02d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds, frames)
    }

    /** Compact human-readable time, e.g. `0:07.4` or `1:02:03.0`. */
    fun formatShort(timeUs: Long): String {
        val t = timeUs.coerceAtLeast(0)
        val tenths = (t / 100_000L) % 10
        val totalSeconds = t / US_PER_SECOND
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d.%d".format(Locale.ROOT, hours, minutes, seconds, tenths)
        } else {
            "%d:%02d.%d".format(Locale.ROOT, minutes, seconds, tenths)
        }
    }

    /** Duration label such as `12.4s` or `3m 05s`. */
    fun formatDuration(durationUs: Long): String {
        val d = durationUs.coerceAtLeast(0)
        val totalSeconds = d / US_PER_SECOND
        return when {
            totalSeconds < 60 -> "%.1fs".format(Locale.ROOT, d.toDouble() / US_PER_SECOND)
            totalSeconds < 3600 -> "%dm %02ds".format(Locale.ROOT, totalSeconds / 60, totalSeconds % 60)
            else -> "%dh %02dm".format(Locale.ROOT, totalSeconds / 3600, (totalSeconds % 3600) / 60)
        }
    }
}
