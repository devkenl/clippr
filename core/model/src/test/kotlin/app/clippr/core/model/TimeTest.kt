package app.clippr.core.model

import app.clippr.core.model.time.FrameRate
import app.clippr.core.model.time.Timecode
import org.junit.Test
import kotlin.test.assertEquals

class TimeTest {

    @Test
    fun frameStartAndIndexAreInverseForIntegerAndNtscRates() {
        for (rate in listOf(FrameRate.FPS_24, FrameRate.FPS_30, FrameRate.FPS_60, FrameRate.NTSC_29_97, FrameRate.NTSC_23_976)) {
            for (i in 0L..5000L) {
                assertEquals(i, rate.frameIndexAt(rate.frameStartUs(i)), "rate=$rate frame=$i")
                // One microsecond before a frame start belongs to the previous frame.
                if (i > 0) assertEquals(i - 1, rate.frameIndexAt(rate.frameStartUs(i) - 1), "rate=$rate frame=$i")
            }
        }
    }

    @Test
    fun ntscFrameBoundariesAreExact() {
        val r = FrameRate.NTSC_29_97
        // 30000 frames at 29.97 fps last exactly 1001 seconds.
        assertEquals(1001 * SEC, r.frameStartUs(30_000))
    }

    @Test
    fun roundToFrameChoosesNearestBoundary() {
        val r = FrameRate.FPS_30 // frames at 0, 33334, 66667, 100000 ...
        assertEquals(0, r.roundToFrame(10_000))
        assertEquals(33_334, r.roundToFrame(20_000))
        assertEquals(100_000, r.roundToFrame(100_000))
    }

    @Test
    fun timecodeFormatting() {
        assertEquals("00:00:01:15", Timecode.format(1_500_000, FrameRate.FPS_30))
        assertEquals("01:01:01:00", Timecode.format((3661) * SEC, FrameRate.FPS_25))
        assertEquals("0:07.4", Timecode.formatShort(7_450_000))
        assertEquals("1:02:03.0", Timecode.formatShort(3723 * SEC))
        assertEquals("12.4s", Timecode.formatDuration(12_400_000))
        assertEquals("3m 05s", Timecode.formatDuration(185 * SEC))
    }

    @Test
    fun aspectPresetsProduceEvenDimensions() {
        assertEquals(1920 to 1080, AspectRatioPreset.LANDSCAPE_16_9.dimensionsForShortSide(1080))
        assertEquals(1080 to 1920, AspectRatioPreset.PORTRAIT_9_16.dimensionsForShortSide(1080))
        assertEquals(1080 to 1350, AspectRatioPreset.PORTRAIT_4_5.dimensionsForShortSide(1080))
        assertEquals(2520 to 1080, AspectRatioPreset.CINEMA_21_9.dimensionsForShortSide(1080))
        assertEquals(1280 to 720, AspectRatioPreset.LANDSCAPE_16_9.dimensionsForShortSide(720))
        assertEquals(AspectRatioPreset.PORTRAIT_9_16, ProjectSettings(1080, 1920).preset)
    }
}
