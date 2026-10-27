package com.lastwave.app.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Custom BaseAudioProcessor for each ExoPlayer deck (Deck A / Deck B).
 * Controls that deck's High-Pass filter cutoff (20 Hz..600 Hz), Low-Shelf (<180 Hz bass gain),
 * and outgoing Echo/Reverb tail via the native C++ DSP filter instance.
 */
class DeckDspAudioProcessor(
    val deckTag: String,
    private val nativeAutoMixEngine: dagger.Lazy<NativeAutoMixEngine>,
) : BaseAudioProcessor() {

    @Volatile var hpfCutoffHz: Float = if (deckTag == "deckA") 20.0f else 200.0f
    @Volatile var bassGainDb: Float = 0.0f
    @Volatile var echoReverbTail: Float = 0.0f
    @Volatile var isDeckA: Boolean = (deckTag == "deckA")

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding !in listOf(
                C.ENCODING_PCM_16BIT,
                C.ENCODING_PCM_24BIT,
                C.ENCODING_PCM_32BIT,
                C.ENCODING_PCM_FLOAT,
            ) || inputAudioFormat.channelCount !in 1..2
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val count = inputBuffer.remaining()
        val out = replaceOutputBuffer(count).order(ByteOrder.nativeOrder())
        out.put(inputBuffer)
        out.flip()

        val engine = runCatching { nativeAutoMixEngine.get() }.getOrNull()
        if (engine != null && engine.isAvailable) {
            val enc = when (outputAudioFormat.encoding) {
                C.ENCODING_PCM_16BIT -> 0
                C.ENCODING_PCM_24BIT -> 1
                C.ENCODING_PCM_32BIT -> 2
                C.ENCODING_PCM_FLOAT -> 3
                else -> -1
            }
            if (enc >= 0) {
                engine.processDeckBuffer(
                    out,
                    enc,
                    outputAudioFormat.sampleRate,
                    isDeckA = isDeckA,
                )
            }
        }
    }
}
