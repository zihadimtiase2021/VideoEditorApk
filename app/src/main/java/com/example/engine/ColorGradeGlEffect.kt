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
import com.example.model.ColorGradeConfig

/**
 * Hardware-accelerated OpenGL ES 2.0 Color Grading & Vignette GlEffect shared identically
 * by BOTH live ExoPlayer video preview (via dynamic [configProvider]) and Media3 Transformer export.
 */
@OptIn(UnstableApi::class)
class ColorGradeGlEffect(
    private val configProvider: () -> ColorGradeConfig,
    private val alwaysActive: Boolean = false
) : GlEffect {

    constructor(config: ColorGradeConfig) : this({ config }, alwaysActive = false)

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return ColorGradeGlShaderProgram(useHdr = useHdr, configProvider = configProvider)
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean {
        return if (alwaysActive) false else configProvider().isDefault()
    }
}

@OptIn(UnstableApi::class)
private class ColorGradeGlShaderProgram(
    useHdr: Boolean,
    private val configProvider: () -> ColorGradeConfig
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {

    private val glProgram: GlProgram
    private var outputSize = Size(1, 1)

    init {
        try {
            glProgram = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        } catch (e: Exception) {
            throw VideoFrameProcessingException("Failed to compile color grade shader", e)
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
            val config = configProvider()
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            glProgram.setFloatUniform("uBrightness", config.brightness.coerceIn(-0.4f, 0.4f))
            glProgram.setFloatUniform("uContrast", config.contrast.coerceIn(0.5f, 1.6f))
            glProgram.setFloatUniform("uSaturation", config.saturation.coerceIn(0f, 2.0f))
            glProgram.setFloatUniform("uWarmth", config.warmth.coerceIn(-0.5f, 0.5f))
            glProgram.setFloatUniform("uVignette", config.vignette.coerceIn(0f, 0.85f))
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (e: Exception) {
            throw VideoFrameProcessingException("Error drawing color graded frame", e)
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

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexSampler;
            uniform float uBrightness;
            uniform float uContrast;
            uniform float uSaturation;
            uniform float uWarmth;
            uniform float uVignette;
            varying vec2 vTexSamplingCoord;

            void main() {
                vec4 src = texture2D(uTexSampler, vTexSamplingCoord);
                vec3 rgb = src.rgb;

                // Saturation
                float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
                rgb = mix(vec3(luma), rgb, uSaturation);

                // Warmth / Temperature tint
                vec3 gain = vec3(
                    clamp(1.0 + uWarmth * 0.22, 0.7, 1.3),
                    clamp(1.0 + uWarmth * 0.04, 0.85, 1.15),
                    clamp(1.0 - uWarmth * 0.24, 0.7, 1.3)
                );
                rgb *= gain;

                // Contrast & Brightness
                rgb = (rgb - 0.5) * uContrast + 0.5 + uBrightness;

                // Smooth Radial Vignette
                if (uVignette > 0.005) {
                    vec2 centered = vTexSamplingCoord - vec2(0.5);
                    float dist = length(centered) * 1.4142;
                    float vig = smoothstep(0.35, 1.15, dist) * uVignette;
                    rgb *= (1.0 - vig);
                }

                gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), src.a);
            }
        """
    }
}
