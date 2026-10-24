package app.clippr.core.render

import android.content.Context
import android.graphics.Matrix
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.MatrixTransformation
import app.clippr.core.model.ChromaKey
import app.clippr.core.model.MaskShape
import app.clippr.core.model.StyleEffect
import app.clippr.core.model.TransitionMask
import app.clippr.core.model.TransitionMath
import app.clippr.core.model.render.TransitionWindow
import app.clippr.core.model.StyleEffectType
import kotlin.math.sin

/**
 * GPU implementations of Clippr's stylized effects ([StyleEffectType]).
 *
 * Every effect except Shake is a fragment shader run on the clip's frame after it has been
 * framed into the canvas. Distances are expressed in "reference pixels" (one pixel of a 1080p
 * frame, see `uUnit`), so an effect looks the same in a 720p preview and a 4K export. Animated
 * effects read the frame time in seconds from `uTime`, measured from the clip's start on the
 * timeline, so preview and export produce the same frames.
 */
@OptIn(UnstableApi::class)
internal object ShaderEffects {

    /** Media3 effects for [style], starting at [clipStartUs] on the composition timeline. */
    fun create(style: StyleEffect, clipStartUs: Long): androidx.media3.common.Effect? {
        if (style.amount <= 0f) return null
        return when (style.type) {
            StyleEffectType.SHAKE -> ShakeMatrix(style.amount, clipStartUs)
            else -> StyleShaderEffect(fragmentFor(style.type), style.amount, clipStartUs)
        }
    }

    /**
     * Hides part of a clip during a wipe-like transition (see [TransitionMath.mask]). The edge is
     * softened over a few reference pixels.
     */
    fun transitionMask(window: TransitionWindow, mask: TransitionMask): androidx.media3.common.Effect {
        val shape = when (mask.shape) {
            MaskShape.LEFT -> 0f
            MaskShape.RIGHT -> 1f
            MaskShape.UP -> 2f
            MaskShape.DOWN -> 3f
            MaskShape.CIRCLE -> 4f
        }
        return StyleShaderEffect(
            MASK_FRAGMENT,
            amount = 1f,
            clipStartUs = 0L,
            uniforms = mapOf("uShape" to floatArrayOf(shape), "uInvert" to floatArrayOf(if (mask.invert) 1f else 0f)),
            perFrame = { t -> mapOf("uProgress" to floatArrayOf(TransitionMath.progress(window.transition, window.startUs, window.endUs, t))) },
        )
    }

    /**
     * Green/blue-screen key. With [composited] the keyed areas become transparent for the layers
     * beneath; on a single layer they turn black instead (there is nothing underneath).
     */
    fun chromaKey(key: ChromaKey, composited: Boolean): androidx.media3.common.Effect {
        val r = ((key.colorArgb shr 16) and 0xFF) / 255f
        val g = ((key.colorArgb shr 8) and 0xFF) / 255f
        val b = (key.colorArgb and 0xFF) / 255f
        return StyleShaderEffect(
            CHROMA_FRAGMENT,
            amount = 1f,
            clipStartUs = 0L,
            uniforms = mapOf(
                "uKey" to floatArrayOf(r, g, b),
                "uSimilarity" to floatArrayOf(key.similarity),
                "uSmoothness" to floatArrayOf(key.smoothness),
                "uSpill" to floatArrayOf(key.spill),
                "uComposited" to floatArrayOf(if (composited) 1f else 0f),
            ),
        )
    }

    internal const val MASK_FRAGMENT: String = HEADER + """
uniform float uProgress;
uniform float uShape;
uniform float uInvert;
void main() {
  vec2 uv = vTexSamplingCoord;
  vec4 c = tex(uv);
  float p = uProgress;
  float soft = uUnit.y * 3.0;
  float r;
  if (uShape < 0.5) {
    r = smoothstep(1.0 - p - soft, 1.0 - p + soft, uv.x);
  } else if (uShape < 1.5) {
    r = 1.0 - smoothstep(p - soft, p + soft, uv.x);
  } else if (uShape < 2.5) {
    r = 1.0 - smoothstep(p - soft, p + soft, uv.y);
  } else if (uShape < 3.5) {
    r = smoothstep(1.0 - p - soft, 1.0 - p + soft, uv.y);
  } else {
    vec2 d = vec2((uv.x - 0.5) * uAspect, uv.y - 0.5);
    float maxR = sqrt(uAspect * uAspect * 0.25 + 0.25);
    r = 1.0 - smoothstep(p * maxR - soft, p * maxR + soft, length(d));
  }
  // At the very end of a reveal everything is shown, and at the start nothing.
  if (p >= 1.0) r = 1.0;
  if (p <= 0.0) r = 0.0;
  float a = mix(r, 1.0 - r, uInvert);
  gl_FragColor = vec4(c.rgb, c.a * a);
}"""

    internal const val CHROMA_FRAGMENT: String = HEADER + """
uniform vec3 uKey;
uniform float uSimilarity;
uniform float uSmoothness;
uniform float uSpill;
uniform float uComposited;
vec2 chroma(vec3 c) {
  float y = luma(c);
  return vec2((c.b - y) * 0.565, (c.r - y) * 0.713);
}
void main() {
  vec4 c = tex(vTexSamplingCoord);
  float d = distance(chroma(c.rgb), chroma(uKey));
  float sim = mix(0.03, 0.4, uSimilarity);
  float soft = mix(0.005, 0.2, uSmoothness);
  float a = smoothstep(sim, sim + soft, d);
  // Remove the key colour's tint from edges and reflections.
  float spill = (1.0 - smoothstep(sim, sim + soft + 0.15, d)) * uSpill;
  vec3 rgb = mix(c.rgb, vec3(luma(c.rgb)), spill);
  if (uComposited > 0.5) {
    gl_FragColor = vec4(rgb, c.a * a);
  } else {
    gl_FragColor = vec4(rgb * a, c.a);
  }
}"""

    private const val HEADER = """#version 100
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uTexSampler;
uniform float uAmount;
uniform float uTime;
uniform float uAspect;
uniform vec2 uUnit;
varying vec2 vTexSamplingCoord;
float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }
float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
vec4 tex(vec2 p) { return texture2D(uTexSampler, p); }
"""

    fun fragmentFor(type: StyleEffectType): String = HEADER + when (type) {
        StyleEffectType.SHARPEN -> """
void main() {
  vec2 uv = vTexSamplingCoord;
  vec2 o = uUnit * 1.3;
  vec4 c = tex(uv);
  vec3 blur = (tex(uv + vec2(o.x, 0.0)).rgb + tex(uv - vec2(o.x, 0.0)).rgb +
               tex(uv + vec2(0.0, o.y)).rgb + tex(uv - vec2(0.0, o.y)).rgb) * 0.25;
  gl_FragColor = vec4(clamp(c.rgb + (c.rgb - blur) * uAmount * 2.5, 0.0, 1.0), c.a);
}"""
        StyleEffectType.GLOW -> """
vec3 bright(vec2 p) {
  vec3 c = tex(p).rgb;
  return c * smoothstep(0.55, 0.95, luma(c));
}
void main() {
  vec2 uv = vTexSamplingCoord;
  vec4 c = tex(uv);
  // Three rings of 12 taps, rotated against each other, approximate a soft wide blur of the
  // bright parts without visible copies.
  vec3 acc = bright(uv) * 2.0;
  float total = 2.0;
  for (int r = 1; r <= 3; r++) {
    float radius = float(r) * 7.0;
    float w = 1.0 / float(r);
    for (int i = 0; i < 12; i++) {
      float a = float(i) * 0.5236 + float(r) * 0.26;
      acc += bright(uv + vec2(cos(a), sin(a)) * uUnit * radius) * w;
      total += w;
    }
  }
  vec3 glow = acc / total;
  vec3 col = c.rgb + glow * uAmount * 2.2;
  gl_FragColor = vec4(clamp(col, 0.0, 1.0), c.a);
}"""
        StyleEffectType.LIGHT_LEAK -> """
void main() {
  vec2 uv = vTexSamplingCoord;
  vec4 c = tex(uv);
  float t = uTime * 0.25;
  vec2 p = vec2(uv.x * uAspect, uv.y);
  float l1 = smoothstep(0.95, 0.0, distance(p, vec2((0.15 + 0.25 * sin(t)) * uAspect, 0.2 + 0.2 * cos(t * 1.3))));
  float l2 = smoothstep(0.85, 0.0, distance(p, vec2((0.9 + 0.1 * cos(t * 0.7)) * uAspect, 0.8 + 0.2 * sin(t * 0.9))));
  vec3 leak = vec3(1.0, 0.42, 0.14) * l1 + vec3(1.0, 0.74, 0.32) * l2 * 0.7;
  vec3 col = 1.0 - (1.0 - c.rgb) * (1.0 - clamp(leak * uAmount, 0.0, 1.0));
  gl_FragColor = vec4(col, c.a);
}"""
        StyleEffectType.ZOOM_BLUR -> """
void main() {
  vec2 uv = vTexSamplingCoord;
  vec2 dir = uv - vec2(0.5);
  float strength = uAmount * 0.18;
  vec4 acc = vec4(0.0);
  for (int i = 0; i < 16; i++) {
    acc += tex(vec2(0.5) + dir * (1.0 - strength * float(i) / 15.0));
  }
  gl_FragColor = acc / 16.0;
}"""
        StyleEffectType.CHROMATIC -> """
void main() {
  vec2 uv = vTexSamplingCoord;
  vec2 d = uv - vec2(0.5);
  vec2 off = d * uAmount * 0.03 + vec2(uUnit.x * 3.0 * uAmount, 0.0);
  vec4 c = tex(uv);
  gl_FragColor = vec4(tex(uv - off).r, c.g, tex(uv + off).b, c.a);
}"""
        StyleEffectType.FISHEYE -> """
void main() {
  vec2 p = vTexSamplingCoord * 2.0 - 1.0;
  p.x *= uAspect;
  float r2 = dot(p, p) / (uAspect * uAspect);
  float k = uAmount * 0.7;
  vec2 q = p * (1.0 - k * 0.5 + k * 0.5 * r2);
  q.x /= uAspect;
  vec2 uv = q * 0.5 + 0.5;
  float inside = step(0.0, uv.x) * step(uv.x, 1.0) * step(0.0, uv.y) * step(uv.y, 1.0);
  vec4 c = tex(clamp(uv, 0.0, 1.0));
  gl_FragColor = vec4(c.rgb * inside, c.a);
}"""
        StyleEffectType.MIRROR -> """
void main() {
  vec2 uv = vTexSamplingCoord;
  vec2 m = vec2(uv.x < 0.5 ? uv.x : 1.0 - uv.x, uv.y);
  gl_FragColor = mix(tex(uv), tex(m), uAmount);
}"""
        StyleEffectType.PIXELATE -> """
void main() {
  vec2 cell = uUnit * mix(2.0, 72.0, uAmount * uAmount);
  vec2 uv = (floor(vTexSamplingCoord / cell) + 0.5) * cell;
  gl_FragColor = tex(uv);
}"""
        StyleEffectType.POSTERIZE -> """
void main() {
  vec4 c = tex(vTexSamplingCoord);
  float levels = mix(12.0, 3.0, uAmount);
  gl_FragColor = vec4(floor(c.rgb * levels + 0.5) / levels, c.a);
}"""
        StyleEffectType.SKETCH -> """
float l(vec2 uv, float x, float y) { return luma(tex(uv + vec2(x, y) * uUnit * 1.2).rgb); }
void main() {
  vec2 uv = vTexSamplingCoord;
  vec4 c = tex(uv);
  float gx = -l(uv,-1.0,-1.0) - 2.0*l(uv,-1.0,0.0) - l(uv,-1.0,1.0) + l(uv,1.0,-1.0) + 2.0*l(uv,1.0,0.0) + l(uv,1.0,1.0);
  float gy = -l(uv,-1.0,-1.0) - 2.0*l(uv,0.0,-1.0) - l(uv,1.0,-1.0) + l(uv,-1.0,1.0) + 2.0*l(uv,0.0,1.0) + l(uv,1.0,1.0);
  float e = length(vec2(gx, gy));
  vec3 paper = vec3(0.97, 0.95, 0.91);
  vec3 sketch = paper * (1.0 - smoothstep(0.06, 0.45, e));
  gl_FragColor = vec4(mix(c.rgb, sketch, uAmount), c.a);
}"""
        StyleEffectType.VHS -> """
void main() {
  vec2 uv = vTexSamplingCoord;
  float t = uTime;
  float line = floor(uv.y / (uUnit.y * 2.0));
  float jitter = (hash(vec2(line, floor(t * 30.0))) - 0.5) * 0.004 * uAmount;
  float wave = sin(uv.y * 30.0 + t * 3.0) * 0.0025 * uAmount;
  vec2 p = vec2(uv.x + jitter + wave, uv.y);
  vec2 split = vec2(uUnit.x * 5.0 * uAmount, 0.0);
  vec3 col = vec3(tex(p + split).r, tex(p).g, tex(p - split).b);
  col *= 1.0 - 0.18 * uAmount * step(0.5, fract(uv.y / (uUnit.y * 3.0)));
  col += (hash(uv * 512.0 + fract(t * 7.0)) - 0.5) * 0.09 * uAmount;
  float band = smoothstep(0.0, 0.03, abs(fract(uv.y + t * 0.08) - 0.5));
  col += (1.0 - band) * 0.12 * uAmount;
  col = mix(col, vec3(luma(col)), 0.2 * uAmount);
  col = col * (1.0 - 0.08 * uAmount) + 0.04 * uAmount;
  gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}"""
        StyleEffectType.OLD_FILM -> """
void main() {
  vec2 uv = vTexSamplingCoord;
  float t = uTime;
  vec4 c = tex(uv);
  float frame = floor(t * 24.0);
  vec3 sepia = vec3(luma(c.rgb)) * vec3(1.07, 0.86, 0.62);
  float flicker = 1.0 + (hash(vec2(frame, 1.0)) - 0.5) * 0.14;
  float grain = (hash(uv * 731.0 + fract(frame * 0.013)) - 0.5) * 0.14;
  float vig = smoothstep(0.9, 0.3, distance(uv, vec2(0.5)));
  vec3 film = sepia * flicker * mix(0.55, 1.0, vig) + grain;
  float sx = hash(vec2(floor(t * 12.0), 7.0));
  float show = step(0.65, hash(vec2(floor(t * 12.0), 3.0)));
  float scratch = (1.0 - smoothstep(0.0, uUnit.x * 1.5, abs(uv.x - sx))) * show;
  film = mix(film, vec3(0.92), scratch * 0.7);
  gl_FragColor = vec4(clamp(mix(c.rgb, film, uAmount), 0.0, 1.0), c.a);
}"""
        StyleEffectType.GLITCH -> """
void main() {
  vec2 uv = vTexSamplingCoord;
  float t = floor(uTime * 12.0);
  float blockY = floor(uv.y * 18.0);
  float r = hash(vec2(blockY, t));
  float active = step(1.0 - 0.35 * uAmount, r);
  float burst = step(0.85, hash(vec2(t, 9.0)));
  float shift = (hash(vec2(t, blockY + 3.1)) - 0.5) * 0.12 * uAmount * active * (0.4 + burst * 1.2);
  vec2 p = vec2(fract(uv.x + shift), uv.y);
  vec2 split = vec2(0.01 * uAmount * (0.3 + burst), 0.0);
  vec4 c = tex(p);
  gl_FragColor = vec4(tex(p + split).r, c.g, tex(p - split).b, c.a);
}"""
        StyleEffectType.SHAKE -> error("Shake is a matrix effect")
    }
}

/** Runs one stylized fragment shader over each frame. */
@OptIn(UnstableApi::class)
internal class StyleShaderEffect(
    private val fragment: String,
    private val amount: Float,
    private val clipStartUs: Long,
    /** Extra constant uniforms (float or vec2/3/4). */
    private val uniforms: Map<String, FloatArray> = emptyMap(),
    /** Uniforms that change every frame, from the frame time. */
    private val perFrame: ((presentationTimeUs: Long) -> Map<String, FloatArray>)? = null,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        StyleShaderProgram(fragment, amount, clipStartUs, useHdr, uniforms, perFrame)
}

@OptIn(UnstableApi::class)
internal class StyleShaderProgram(
    fragment: String,
    amount: Float,
    private val clipStartUs: Long,
    useHdr: Boolean,
    uniforms: Map<String, FloatArray> = emptyMap(),
    private val perFrame: ((Long) -> Map<String, FloatArray>)? = null,
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ useHdr, /* texturePoolCapacity= */ 1) {

    private val program: GlProgram = try {
        GlProgram(VERTEX_SHADER, fragment)
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }

    init {
        program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        val identity = GlUtil.create4x4IdentityMatrix()
        program.setFloatsUniform("uTransformationMatrix", identity)
        program.setFloatsUniform("uTexTransformationMatrix", identity)
        program.setFloatsUniformIfPresent("uAmount", floatArrayOf(amount.coerceIn(0f, 1f)))
        uniforms.forEach { (name, value) -> program.setFloatsUniformIfPresent(name, value) }
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        val w = inputWidth.coerceAtLeast(1).toFloat()
        val h = inputHeight.coerceAtLeast(1).toFloat()
        // One reference pixel (a pixel of a 1080-line frame), in texture coordinates.
        program.setFloatsUniformIfPresent("uUnit", floatArrayOf(h / (REFERENCE_LINES * w), 1f / REFERENCE_LINES))
        program.setFloatsUniformIfPresent("uAspect", floatArrayOf(w / h))
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
            val seconds = ((presentationTimeUs - clipStartUs).coerceAtLeast(0) / 1_000_000.0).toFloat()
            program.setFloatsUniformIfPresent("uTime", floatArrayOf(seconds))
            perFrame?.invoke(presentationTimeUs)?.forEach { (name, value) -> program.setFloatsUniformIfPresent(name, value) }
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    private companion object {
        const val REFERENCE_LINES = 1080f
        const val VERTEX_SHADER = """#version 100
attribute vec4 aFramePosition;
uniform mat4 uTransformationMatrix;
uniform mat4 uTexTransformationMatrix;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = uTransformationMatrix * aFramePosition;
  vec4 texturePosition = vec4(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5, 0.0, 1.0);
  vTexSamplingCoord = (uTexTransformationMatrix * texturePosition).xy;
}"""
    }
}

/**
 * Handheld camera shake: a smooth pseudo-random wobble of position and angle, zoomed in slightly
 * so the frame edges stay out of view.
 */
@OptIn(UnstableApi::class)
internal class ShakeMatrix(private val amount: Float, private val clipStartUs: Long) : MatrixTransformation {
    private val matrix = Matrix()

    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val t = (presentationTimeUs - clipStartUs) / 1_000_000f
        val a = amount.coerceIn(0f, 1f)
        val dx = (sin(t * 17.3f) * 0.6f + sin(t * 31.7f + 1.3f) * 0.4f) * 0.025f * a
        val dy = (sin(t * 13.1f + 0.7f) * 0.6f + sin(t * 27.9f + 2.1f) * 0.4f) * 0.025f * a
        val rot = (sin(t * 9.7f + 0.4f) * 0.7f + sin(t * 23.3f) * 0.3f) * 1.2f * a
        val zoom = 1f + 0.06f * a
        matrix.reset()
        matrix.postScale(zoom, zoom)
        matrix.postRotate(rot)
        matrix.postTranslate(dx * 2f, dy * 2f)
        return matrix
    }
}
