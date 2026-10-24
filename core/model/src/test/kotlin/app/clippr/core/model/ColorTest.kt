package app.clippr.core.model

import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ColorTest {

    private fun graded(grade: ColorGrade, r: Float, g: Float, b: Float, lut: CubeLut? = null): FloatArray =
        floatArrayOf(r, g, b).also { ColorMath.apply(grade, it, lut) }

    private fun near(expected: Float, actual: Float, eps: Float = 1e-3f) =
        assertTrue(abs(expected - actual) <= eps, "expected $expected but was $actual")

    @Test
    fun identityGradeBakesIdentityLut() {
        assertTrue(ColorGrade.NONE.isIdentity)
        val cube = ColorMath.bakeLut(ColorGrade.NONE, null, 17)
        for (r in 0 until 17) for (g in 0 until 17) for (b in 0 until 17) {
            val c = cube[r][g][b]
            val expect = { i: Int -> (i * 255f / 16f).toInt() }
            assertTrue(abs(((c shr 16) and 0xFF) - expect(r)) <= 1)
            assertTrue(abs(((c shr 8) and 0xFF) - expect(g)) <= 1)
            assertTrue(abs((c and 0xFF) - expect(b)) <= 1)
            assertEquals(0xFF, (c ushr 24))
        }
    }

    @Test
    fun adjustmentsMoveColoursInTheExpectedDirection() {
        near(0.4f, graded(ColorGrade(exposure = 1f), 0.2f, 0.2f, 0.2f)[0])
        val gray = graded(ColorGrade(saturation = -1f), 0.8f, 0.2f, 0.1f)
        near(gray[0], gray[1]); near(gray[1], gray[2])
        val warm = graded(ColorGrade(temperature = 1f), 0.5f, 0.5f, 0.5f)
        assertTrue(warm[0] > warm[2])
        val contrast = graded(ColorGrade(contrast = 0.5f), 0.7f, 0.7f, 0.7f)
        assertTrue(contrast[0] > 0.7f)
        assertTrue(graded(ColorGrade(shadows = 1f), 0.1f, 0.1f, 0.1f)[0] > 0.1f)
        assertTrue(graded(ColorGrade(highlights = -1f), 0.9f, 0.9f, 0.9f)[0] < 0.9f)
        val mono = graded(ColorGrade(filter = FilterPreset.MONO), 0.9f, 0.1f, 0.3f)
        near(mono[0], mono[2])
        val halfMono = graded(ColorGrade(filter = FilterPreset.MONO, filterStrength = 0.5f), 0.9f, 0.1f, 0.3f)
        assertTrue(halfMono[0] < 0.9f && halfMono[0] > mono[0])
        for (preset in FilterPreset.entries) {
            val c = graded(ColorGrade(filter = preset), 0.3f, 0.6f, 0.9f)
            assertTrue(c.all { it in 0f..1f }, "$preset out of range")
        }
        assertFalse(ColorGrade(filter = FilterPreset.SEPIA).isIdentity)
        assertTrue(ColorGrade(filter = FilterPreset.SEPIA, filterStrength = 0f).isIdentity)
    }

    @Test
    fun parsesAndSamplesCubeFiles() {
        // A 2x2x2 LUT that inverts colours; red varies fastest.
        val text = """
            TITLE "invert"
            # comment
            LUT_3D_SIZE 2
            LUT_3D_INPUT_RANGE 0 1
            1 1 1
            0 1 1
            1 0 1
            0 0 1
            1 1 0
            0 1 0
            1 0 0
            0 0 0
        """.trimIndent()
        val lut = CubeLut.parse(text)
        assertEquals(2, lut.size)
        val out = FloatArray(3)
        lut.sample(0.25f, 0.5f, 1f, out)
        near(0.75f, out[0]); near(0.5f, out[1]); near(0f, out[2])
        val withLut = graded(ColorGrade(lutId = "x"), 0.2f, 0.4f, 0.6f, lut)
        near(0.8f, withLut[0]); near(0.6f, withLut[1]); near(0.4f, withLut[2])
    }

    @Test
    fun rejectsBadCubeFiles() {
        assertFailsWith<IllegalArgumentException> { CubeLut.parse("0 0 0\n1 1 1") }
        assertFailsWith<IllegalArgumentException> { CubeLut.parse("LUT_3D_SIZE 2\n0 0 0") }
        assertFailsWith<IllegalArgumentException> { CubeLut.parse("LUT_1D_SIZE 4") }
    }
}
