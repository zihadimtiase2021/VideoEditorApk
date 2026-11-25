package com.example.engine

import android.content.Context
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
import com.example.model.MaskMode
import com.example.model.ResolvedMaskInstance

/**
 * Hardware-accelerated OpenGL ES 2.0 Media3 GlEffect shared identically by BOTH
 * live ExoPlayer video preview and Media3 Transformer MP4 export.
 * Supports up to 12 simultaneous per-face shaped & feathered privacy masks
 * (Rounded, Oval, Circle, Heart, Star) across Blur, Mosaic, Frosted Glass,
 * Glitch, Invert, Spotlight, and Solid Color modes.
 */
@OptIn(UnstableApi::class)
class PrivacyMaskGlEffect(
    private val activeMasksProvider: (outputTimeUs: Long) -> List<ResolvedMaskInstance>
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return PrivacyMaskGlShaderProgram(
            useHdr = useHdr,
            activeMasksProvider = activeMasksProvider
        )
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false
}

@OptIn(UnstableApi::class)
private class PrivacyMaskGlShaderProgram(
    useHdr: Boolean,
    private val activeMasksProvider: (outputTimeUs: Long) -> List<ResolvedMaskInstance>
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {

    private val glProgram: GlProgram
    private var outputSize = Size(1, 1)

    init {
        try {
            glProgram = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        } catch (e: Exception) {
            throw VideoFrameProcessingException("Failed to compile shaped privacy mask shader", e)
        }
        glProgram.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
        )
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        outputSize = Size(inputWidth, inputHeight)
        return outputSize
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
            glProgram.setFloatsUniform(
                "uResolution",
                floatArrayOf(
                    outputSize.width.toFloat().coerceAtLeast(1f),
                    outputSize.height.toFloat().coerceAtLeast(1f)
                )
            )
            glProgram.setFloatUniform("uTimeSec", (presentationTimeUs / 1_000_000f) % 600f)

            val masks = activeMasksProvider(presentationTimeUs)
                .filter { it.mode.isShaderEffect }
                .take(MAX_BOXES)

            val hasSpotlight = masks.any { it.mode == MaskMode.SPOTLIGHT }
            val spotlightBlur = masks.firstOrNull { it.mode == MaskMode.SPOTLIGHT }?.blurIntensity ?: 0.72f
            val spotlightOpacity = masks.firstOrNull { it.mode == MaskMode.SPOTLIGHT }?.opacity ?: 1.0f

            glProgram.setIntUniform("uBoxCount", masks.size)
            glProgram.setIntUniform("uHasSpotlight", if (hasSpotlight) 1 else 0)
            glProgram.setFloatUniform("uSpotlightBlur", spotlightBlur.coerceIn(0.15f, 1.0f))
            glProgram.setFloatUniform("uSpotlightOpacity", spotlightOpacity.coerceIn(0.25f, 1.0f))

            for (i in 0 until MAX_BOXES) {
                val mask = masks.getOrNull(i)
                if (mask != null) {
                    val r = mask.rect
                    glProgram.setFloatsUniform("uBox$i", floatArrayOf(r.left, r.top, r.right, r.bottom))
                    glProgram.setFloatsUniform(
                        "uMeta$i",
                        floatArrayOf(
                            mask.mode.shaderId.toFloat(),
                            mask.shape.shaderId.toFloat(),
                            mask.feather.coerceIn(0f, 1f),
                            mask.opacity.coerceIn(0.25f, 1f)
                        )
                    )
                    if (mask.mode == MaskMode.SOLID_COLOR) {
                        val argb = mask.solidColorArgb
                        val cr = ((argb shr 16) and 0xFF) / 255f
                        val cg = ((argb shr 8) and 0xFF) / 255f
                        val cb = (argb and 0xFF) / 255f
                        glProgram.setFloatsUniform("uParam$i", floatArrayOf(cr, cg, cb, 1f))
                    } else {
                        glProgram.setFloatsUniform(
                            "uParam$i",
                            floatArrayOf(
                                mask.blurIntensity.coerceIn(0.15f, 1f),
                                mask.pixelBlockSize.coerceIn(0.1f, 1f),
                                0f,
                                0f
                            )
                        )
                    }
                } else {
                    glProgram.setFloatsUniform("uBox$i", EMPTY_RECT)
                    glProgram.setFloatsUniform("uMeta$i", EMPTY_META)
                    glProgram.setFloatsUniform("uParam$i", EMPTY_PARAM)
                }
            }

            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (e: Exception) {
            throw VideoFrameProcessingException("Error drawing shaped privacy mask frame", e)
        }
    }

    override fun release() {
        super.release()
        try {
            glProgram.delete()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val MAX_BOXES = 12
        private val EMPTY_RECT = floatArrayOf(-1f, -1f, -1f, -1f)
        private val EMPTY_META = floatArrayOf(0f, 0f, 0f, 0f)
        private val EMPTY_PARAM = floatArrayOf(0.7f, 0.5f, 0f, 0f)

        private const val VERTEX_SHADER = """
            attribute vec4 aFramePosition;
            varying vec2 vTexSamplingCoord;
            void main() {
                gl_Position = aFramePosition;
                vTexSamplingCoord = vec2(
                    aFramePosition.x * 0.5 + 0.5,
                    aFramePosition.y * 0.5 + 0.5
                );
            }
        """

        // Top-down normalized coords are converted from OpenGL bottom-up UV via (uv.x, 1.0 - uv.y).
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexSampler;
            uniform vec2 uResolution;
            uniform float uTimeSec;
            uniform int uBoxCount;
            uniform int uHasSpotlight;
            uniform float uSpotlightBlur;
            uniform float uSpotlightOpacity;
            uniform vec4 uBox0; uniform vec4 uMeta0; uniform vec4 uParam0;
            uniform vec4 uBox1; uniform vec4 uMeta1; uniform vec4 uParam1;
            uniform vec4 uBox2; uniform vec4 uMeta2; uniform vec4 uParam2;
            uniform vec4 uBox3; uniform vec4 uMeta3; uniform vec4 uParam3;
            uniform vec4 uBox4; uniform vec4 uMeta4; uniform vec4 uParam4;
            uniform vec4 uBox5; uniform vec4 uMeta5; uniform vec4 uParam5;
            uniform vec4 uBox6; uniform vec4 uMeta6; uniform vec4 uParam6;
            uniform vec4 uBox7; uniform vec4 uMeta7; uniform vec4 uParam7;
            uniform vec4 uBox8; uniform vec4 uMeta8; uniform vec4 uParam8;
            uniform vec4 uBox9; uniform vec4 uMeta9; uniform vec4 uParam9;
            uniform vec4 uBox10; uniform vec4 uMeta10; uniform vec4 uParam10;
            uniform vec4 uBox11; uniform vec4 uMeta11; uniform vec4 uParam11;
            varying vec2 vTexSamplingCoord;

            // Signed distance in normalized [-1..1] box coordinates (negative inside, positive outside)
            float shapeSdf(vec2 tdUv, vec4 b, int shapeId) {
                if (b.z <= b.x || b.w <= b.y) return 10.0;
                vec2 center = (b.xy + b.zw) * 0.5;
                vec2 halfSize = max((b.zw - b.xy) * 0.5, vec2(0.005));
                vec2 p = (tdUv - center) / halfSize;

                if (shapeId == 1) {
                    // Oval
                    return length(p) - 1.0;
                } else if (shapeId == 2) {
                    // Inscribed True Circle (matches MaskRenderUtils & Compose Shape identically)
                    vec2 diffPx = (tdUv - center) * uResolution;
                    vec2 halfPx = halfSize * uResolution;
                    float rad = min(halfPx.x, halfPx.y);
                    return (length(diffPx) / max(rad, 1.0)) - 1.0;
                } else if (shapeId == 3) {
                    // Heart curve (top-down y inverted so heart points down)
                    vec2 h = vec2(p.x * 1.15, -p.y * 1.15 + 0.15);
                    float a = h.x * h.x + h.y * h.y - 1.0;
                    float val = a * a * a - h.x * h.x * h.y * h.y * h.y;
                    return val * 0.45;
                } else if (shapeId == 4) {
                    // 5-Point Star
                    vec2 sp = vec2(p.x, -p.y);
                    float r = length(sp);
                    float theta = atan(sp.y, sp.x) + 1.570796;
                    float wave = cos(5.0 * theta);
                    float starRad = mix(0.46, 0.98, wave * 0.5 + 0.5);
                    return r - starRad;
                }

                // Default 0: Rounded Rectangle
                vec2 d = abs(p) - vec2(0.76);
                return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0) - 0.24;
            }

            float computeAlpha(vec2 tdUv, vec4 b, vec4 meta) {
                int shapeId = int(meta.y + 0.5);
                float dist = shapeSdf(tdUv, b, shapeId);
                float featherEdge = max(0.015, meta.z * 0.42);
                float inside = 1.0 - smoothstep(-featherEdge, 0.02, dist);
                return inside * meta.w;
            }

            vec4 sampleGaussianBlur(vec2 uv, float intensity) {
                vec2 texel = (1.0 / uResolution) * (4.0 + intensity * 22.0);
                vec4 sum = texture2D(uTexSampler, uv) * 0.12;
                float weightSum = 0.12;
                for (int i = 0; i < 8; i++) {
                    float angle = float(i) * 0.785398;
                    vec2 off = vec2(cos(angle), sin(angle)) * texel * 0.55;
                    sum += texture2D(uTexSampler, clamp(uv + off, 0.001, 0.999)) * 0.06;
                    weightSum += 0.06;
                }
                for (int i = 0; i < 8; i++) {
                    float angle = float(i) * 0.785398 + 0.392699;
                    vec2 off = vec2(cos(angle), sin(angle)) * texel * 1.15;
                    sum += texture2D(uTexSampler, clamp(uv + off, 0.001, 0.999)) * 0.035;
                    weightSum += 0.035;
                }
                for (int i = 0; i < 8; i++) {
                    float angle = float(i) * 0.785398;
                    vec2 off = vec2(cos(angle), sin(angle)) * texel * 1.85;
                    sum += texture2D(uTexSampler, clamp(uv + off, 0.001, 0.999)) * 0.015;
                    weightSum += 0.015;
                }
                return sum / weightSum;
            }

            vec4 computeEffectColor(vec2 uv, int modeId, vec4 param) {
                if (modeId == 2) {
                    // Mosaic / Pixelate
                    float minDim = min(uResolution.x, uResolution.y);
                    float blockPx = max(6.0, minDim * (0.015 + param.y * 0.055));
                    vec2 blocks = uResolution / blockPx;
                    vec2 snappedUv = (floor(uv * blocks) + 0.5) / blocks;
                    return texture2D(uTexSampler, clamp(snappedUv, 0.001, 0.999));
                } else if (modeId == 3) {
                    // Frosted Glass (soft blur + micro-crystal noise + cool luminance veil)
                    float n = fract(sin(dot(floor(uv * uResolution * 0.5), vec2(12.9898, 78.233))) * 43758.5453);
                    vec2 jitter = (vec2(n, fract(n * 19.1)) - 0.5) * (6.0 / uResolution);
                    vec4 blurred = sampleGaussianBlur(clamp(uv + jitter, 0.001, 0.999), max(0.55, param.x));
                    vec3 frosted = mix(blurred.rgb, vec3(0.92, 0.95, 0.98), 0.24);
                    frosted += (n - 0.5) * 0.05;
                    return vec4(clamp(frosted, 0.0, 1.0), 1.0);
                } else if (modeId == 4) {
                    // Glitch (RGB channel split + horizontal scanline jitter)
                    float band = floor(uv.y * 42.0 + uTimeSec * 8.0);
                    float jitter = (fract(sin(band * 91.7) * 43758.5453) - 0.5) * 0.055 * (0.4 + param.x);
                    vec2 uvR = clamp(uv + vec2(jitter + 0.014, 0.0), 0.001, 0.999);
                    vec2 uvG = clamp(uv + vec2(jitter * 0.5, 0.0), 0.001, 0.999);
                    vec2 uvB = clamp(uv + vec2(jitter - 0.014, 0.0), 0.001, 0.999);
                    float r = texture2D(uTexSampler, uvR).r;
                    float g = texture2D(uTexSampler, uvG).g;
                    float b = texture2D(uTexSampler, uvB).b;
                    float scan = 0.88 + 0.12 * sin(uv.y * uResolution.y * 0.8);
                    return vec4(vec3(r, g, b) * scan, 1.0);
                } else if (modeId == 5) {
                    // Invert
                    vec4 baseC = texture2D(uTexSampler, uv);
                    return vec4(vec3(1.0) - baseC.rgb, baseC.a);
                } else if (modeId == 7) {
                    // Solid Color Fill (uses uParam.rgb)
                    return vec4(param.rgb, 1.0);
                }
                // Default 1: Gaussian Blur
                return sampleGaussianBlur(uv, param.x);
            }

            void evalBox(
                vec2 tdUv,
                vec4 b,
                vec4 meta,
                vec4 param,
                inout float bestAlpha,
                inout int bestMode,
                inout vec4 bestParam,
                inout float spotlightKeep
            ) {
                int m = int(meta.x + 0.5);
                if (m <= 0) return;
                float a = computeAlpha(tdUv, b, meta);
                if (m == 6) {
                    spotlightKeep = max(spotlightKeep, a);
                } else if (a > bestAlpha) {
                    bestAlpha = a;
                    bestMode = m;
                    bestParam = param;
                }
            }

            void main() {
                vec2 uv = vTexSamplingCoord;
                vec2 tdUv = vec2(uv.x, 1.0 - uv.y);
                vec4 orig = texture2D(uTexSampler, uv);

                if (uBoxCount <= 0) {
                    gl_FragColor = orig;
                    return;
                }

                float bestAlpha = 0.0;
                int bestMode = 0;
                vec4 bestParam = vec4(0.7, 0.5, 0.0, 0.0);
                float spotlightKeep = 0.0;

                if (uBoxCount > 0) evalBox(tdUv, uBox0, uMeta0, uParam0, bestAlpha, bestMode, bestParam, spotlightKeep);
                if (uBoxCount > 1) evalBox(tdUv, uBox1, uMeta1, uParam1, bestAlpha, bestMode, bestParam, spotlightKeep);
                if (uBoxCount > 2) evalBox(tdUv, uBox2, uMeta2, uParam2, bestAlpha, bestMode, bestParam, spotlightKeep);
                if (uBoxCount > 3) evalBox(tdUv, uBox3, uMeta3, uParam3, bestAlpha, bestMode, bestParam, spotlightKeep);
                if (uBoxCount > 4) evalBox(tdUv, uBox4, uMeta4, uParam4, bestAlpha, bestMode, bestParam, spotlightKeep);
                if (uBoxCount > 5) evalBox(tdUv, uBox5, uMeta5, uParam5, bestAlpha, bestMode, bestParam, spotlightKeep);
                if (uBoxCount > 6) evalBox(tdUv, uBox6, uMeta6, uParam6, bestAlpha, bestMode, bestParam, spotlightKeep);
                if (uBoxCount > 7) evalBox(tdUv, uBox7, uMeta7, uParam7, bestAlpha, bestMode, bestParam, spotlightKeep);
                if (uBoxCount > 8) evalBox(tdUv, uBox8, uMeta8, uParam8, bestAlpha, bestMode, bestParam, spotlightKeep);
                if (uBoxCount > 9) evalBox(tdUv, uBox9, uMeta9, uParam9, bestAlpha, bestMode, bestParam, spotlightKeep);
                if (uBoxCount > 10) evalBox(tdUv, uBox10, uMeta10, uParam10, bestAlpha, bestMode, bestParam, spotlightKeep);
                if (uBoxCount > 11) evalBox(tdUv, uBox11, uMeta11, uParam11, bestAlpha, bestMode, bestParam, spotlightKeep);

                vec4 outColor = orig;

                if (uHasSpotlight == 1) {
                    float bgBlurAlpha = (1.0 - spotlightKeep) * uSpotlightOpacity;
                    if (bgBlurAlpha > 0.005) {
                        vec4 bgBlurred = sampleGaussianBlur(uv, uSpotlightBlur);
                        bgBlurred.rgb *= 0.68;
                        outColor = mix(orig, bgBlurred, bgBlurAlpha);
                    }
                }

                if (bestAlpha > 0.005 && bestMode > 0) {
                    vec4 fxColor = computeEffectColor(uv, bestMode, bestParam);
                    outColor = mix(outColor, fxColor, clamp(bestAlpha, 0.0, 1.0));
                }

                gl_FragColor = outColor;
            }
        """
    }
}
