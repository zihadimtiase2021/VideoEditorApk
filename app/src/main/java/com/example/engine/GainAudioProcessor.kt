package com.example.engine

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Lightweight Media3 PCM16 volume scaling AudioProcessor for mixing original clip audio
 * and background music tracks cleanly during export.
 */
@OptIn(UnstableApi::class)
class GainAudioProcessor(
    private val gain: Float
) : BaseAudioProcessor() {

    private val clampedGain = gain.coerceIn(0f, 1.5f)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return if (kotlin.math.abs(clampedGain - 1.0f) < 0.01f) {
            AudioProcessor.AudioFormat.NOT_SET
        } else {
            inputAudioFormat
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val outputBuffer = replaceOutputBuffer(remaining)
        while (inputBuffer.hasRemaining()) {
            val sample = inputBuffer.short.toInt()
            val scaled = (sample * clampedGain).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            outputBuffer.putShort(scaled.toShort())
        }
        outputBuffer.flip()
    }
}
