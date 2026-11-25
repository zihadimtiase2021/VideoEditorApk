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
import com.example.model.NormalizedRect
import kotlin.math.max
import kotlin.math.min

/**
 * Hardware-accelerated OpenGL ES 2.0 Media3 GlEffect that applies true Gaussian Blur or
 * Pixelate/Mosaic directly onto the video texture inside dynamic face & manual mask boxes.
 */
@OptIn(UnstableApi::class)
class PrivacyMaskGlEffect(
    private val mode: MaskMode,
    private val blurIntensity: Float,
    private val pixelBlockSize: Float,
    private val activeRectsProvider: (outputTimeUs: Long) -> List<NormalizedRect>
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return PrivacyMaskGlShaderProgram(
            useHdr = useHdr,
            mode = mode,
            blurIntensity = blurIntensity,
            pixelBlockSize = pixelBlockSize,
            activeRectsProvider = activeRectsProvider
        )
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean {
        return mode != MaskMode.GAUSSIAN_BLUR && mode != MaskMode.PIXELATE
    }
}

@OptIn(UnstableApi::class)
private class PrivacyMaskGlShaderProgram(
    useHdr: Boolean,
    private val mode: MaskMode,
    private val blurIntensity: Float,
    private val pixelBlockSize: Float,
    private val activeRectsProvider: (outputTimeUs: Long) -> List<NormalizedRect>
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {

    private val glProgram: GlProgram
    private var outputSize = Size(1, 1)

    init {
        try {
            glProgram = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        } catch (e: Exception) {
            throw VideoFrameProcessingException("Failed to compile privacy mask shader", e)
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
            glProgram.setIntUniform("uMode", if (mode == MaskMode.GAUSSIAN_BLUR) 1 else 2)
            glProgram.setFloatUniform("uBlurIntensity", blurIntensity.coerceIn(0.15f, 1.0f))
            glProgram.setFloatUniform("uPixelScale", pixelBlockSize.coerceIn(0.1f, 1.0f))
            glProgram.setFloatsUniform(
                "uResolution",
                floatArrayOf(
                    outputSize.width.toFloat().coerceAtLeast(1f),
                    outputSize.height.toFloat().coerceAtLeast(1f)
                )
            )

            val rects = activeRectsProvider(presentationTimeUs).take(MAX_BOXES)
            glProgram.setIntUniform("uBoxCount", rects.size)
            for (i in 0 until MAX_BOXES) {
                val rect = rects.getOrNull(i)
                val data = if (rect != null) {
                    floatArrayOf(rect.left, rect.top, rect.right, rect.bottom)
                } else {
                    EMPTY_RECT
                }
                glProgram.setFloatsUniform("uBox$i", data)
            }

            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (e: Exception) {
            throw VideoFrameProcessingException("Error drawing privacy mask frame", e)
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
            uniform int uMode;
            uniform float uBlurIntensity;
            uniform float uPixelScale;
            uniform vec2 uResolution;
            uniform int uBoxCount;
            uniform vec4 uBox0;
            uniform vec4 uBox1;
            uniform vec4 uBox2;
            uniform vec4 uBox3;
            uniform vec4 uBox4;
            uniform vec4 uBox5;
            uniform vec4 uBox6;
            uniform vec4 uBox7;
            uniform vec4 uBox8;
            uniform vec4 uBox9;
            uniform vec4 uBox10;
            uniform vec4 uBox11;
            varying vec2 vTexSamplingCoord;

            bool insideRoundedBox(vec2 p, vec4 b) {
                if (b.z <= b.x || b.w <= b.y) return false;
                if (p.x < b.x || p.x > b.z || p.y < b.y || p.y > b.w) return false;
                vec2 center = (b.xy + b.zw) * 0.5;
                vec2 halfSize = (b.zw - b.xy) * 0.5;
                float radius = min(halfSize.x, halfSize.y) * 0.25;
                vec2 q = abs(p - center) - (halfSize - vec2(radius));
                return length(max(q, 0.0)) <= radius;
            }

            bool isPointMasked(vec2 tdUv) {
                if (uBoxCount <= 0) return false;
                if (uBoxCount > 0 && insideRoundedBox(tdUv, uBox0)) return true;
                if (uBoxCount > 1 && insideRoundedBox(tdUv, uBox1)) return true;
                if (uBoxCount > 2 && insideRoundedBox(tdUv, uBox2)) return true;
                if (uBoxCount > 3 && insideRoundedBox(tdUv, uBox3)) return true;
                if (uBoxCount > 4 && insideRoundedBox(tdUv, uBox4)) return true;
                if (uBoxCount > 5 && insideRoundedBox(tdUv, uBox5)) return true;
                if (uBoxCount > 6 && insideRoundedBox(tdUv, uBox6)) return true;
                if (uBoxCount > 7 && insideRoundedBox(tdUv, uBox7)) return true;
                if (uBoxCount > 8 && insideRoundedBox(tdUv, uBox8)) return true;
                if (uBoxCount > 9 && insideRoundedBox(tdUv, uBox9)) return true;
                if (uBoxCount > 10 && insideRoundedBox(tdUv, uBox10)) return true;
                if (uBoxCount > 11 && insideRoundedBox(tdUv, uBox11)) return true;
                return false;
            }

            void main() {
                vec2 uv = vTexSamplingCoord;
                vec2 tdUv = vec2(uv.x, 1.0 - uv.y);

                if (!isPointMasked(tdUv)) {
                    gl_FragColor = texture2D(uTexSampler, uv);
                    return;
                }

                if (uMode == 2) {
                    // Pixelate / Mosaic block sampling
                    float minDim = min(uResolution.x, uResolution.y);
                    float blockPx = max(6.0, minDim * (0.015 + uPixelScale * 0.055));
                    vec2 blocks = uResolution / blockPx;
                    vec2 snappedUv = (floor(uv * blocks) + 0.5) / blocks;
                    gl_FragColor = texture2D(uTexSampler, clamp(snappedUv, 0.001, 0.999));
                    return;
                }

                // Multi-ring weighted Gaussian blur approximation
                vec2 texel = (1.0 / uResolution) * (4.0 + uBlurIntensity * 22.0);
                vec4 sum = texture2D(uTexSampler, uv) * 0.12;
                float weightSum = 0.12;

                // Inner ring (8 taps)
                for (int i = 0; i < 8; i++) {
                    float angle = float(i) * 0.785398;
                    vec2 off = vec2(cos(angle), sin(angle)) * texel * 0.55;
                    sum += texture2D(uTexSampler, clamp(uv + off, 0.001, 0.999)) * 0.06;
                    weightSum += 0.06;
                }

                // Mid ring (8 taps)
                for (int i = 0; i < 8; i++) {
                    float angle = float(i) * 0.785398 + 0.392699;
                    vec2 off = vec2(cos(angle), sin(angle)) * texel * 1.15;
                    sum += texture2D(uTexSampler, clamp(uv + off, 0.001, 0.999)) * 0.035;
                    weightSum += 0.035;
                }

                // Outer ring (8 taps)
                for (int i = 0; i < 8; i++) {
                    float angle = float(i) * 0.785398;
                    vec2 off = vec2(cos(angle), sin(angle)) * texel * 1.85;
                    sum += texture2D(uTexSampler, clamp(uv + off, 0.001, 0.999)) * 0.015;
                    weightSum += 0.015;
                }

                gl_FragColor = sum / weightSum;
            }
        """
    }
}
