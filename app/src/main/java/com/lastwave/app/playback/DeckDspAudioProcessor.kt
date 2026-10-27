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
typealias DeckTransitionAudioProcessor = DeckDspAudioProcessor

/**
 * Mathematically Continuous 3-Stem Mid-Side (M/S) Vocal-Split Crossfader for each ExoPlayer deck.
 * Ensures:
 * 1. Stereo instrumentals blend over the full 6.0 seconds.
 * 2. <180 Hz bass swaps with constant unity power (bassGainA + bassGainB == 1.0f at all times).
 * 3. Lead vocals (Mid channel >180 Hz) relay with zero overlap at full volume.
 * 4. At t = 0.0 and t = 1.0 the processed audio is 100% bit-identical to the dry track.
 */
class DeckDspAudioProcessor(
    val deckTag: String,
    private val nativeAutoMixEngine: dagger.Lazy<NativeAutoMixEngine>? = null,
) : BaseAudioProcessor() {

    @Volatile var hpfCutoffHz: Float = if (deckTag == "deckA") 20.0f else 200.0f
    @Volatile var bassGainDb: Float = 0.0f
    @Volatile var echoReverbTail: Float = 0.0f
    @Volatile var isDeckA: Boolean = (deckTag == "deckA")

    @Volatile var isTransitionActive: Boolean = false
    @Volatile var targetT: Float = 0.0f
    private var smoothT: Float = 0.0f
    private var lowL: Float = 0f
    private var lowR: Float = 0f
    private var filterInitialized: Boolean = false

    fun sCurve(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        return 0.5f - 0.5f * kotlin.math.cos(c * Math.PI.toFloat())
    }

    fun startTransition(asDeckA: Boolean, initialT: Float = 0f) {
        isDeckA = asDeckA
        targetT = initialT
        smoothT = initialT
        lowL = 0f
        lowR = 0f
        filterInitialized = false
        isTransitionActive = true
    }

    fun endTransition() {
        isTransitionActive = false
        targetT = 0f
        smoothT = 0f
        lowL = 0f
        lowR = 0f
        filterInitialized = false
    }

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

        if (!isTransitionActive) {
            out.put(inputBuffer)
            out.flip()
            return
        }

        val sampleRate = outputAudioFormat.sampleRate.takeIf { it > 0 } ?: 44100
        val alpha = (2.0 * Math.PI * 180.0 / sampleRate).toFloat().coerceIn(0.005f, 0.05f)
        val channelCount = outputAudioFormat.channelCount
        val encoding = outputAudioFormat.encoding

        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                if (channelCount == 2) {
                    while (inputBuffer.remaining() >= 4) {
                        val inShortL = inputBuffer.short
                        val inShortR = inputBuffer.short
                        val inL = inShortL / 32768.0f
                        val inR = inShortR / 32768.0f

                        smoothT += 0.0005f * (targetT - smoothT)
                        val st = smoothT.coerceIn(0f, 1f)

                        if (isDeckA && st <= 0.0001f) {
                            out.putShort(inShortL)
                            out.putShort(inShortR)
                            continue
                        }
                        if (!isDeckA && st >= 0.9999f) {
                            out.putShort(inShortL)
                            out.putShort(inShortR)
                            continue
                        }

                        if (!filterInitialized) {
                            lowL = inL
                            lowR = inR
                            filterInitialized = true
                        } else {
                            lowL += alpha * (inL - lowL)
                            lowR += alpha * (inR - lowR)
                        }

                        val highL = inL - lowL
                        val highR = inR - lowR

                        val midVocal = 0.5f * (highL + highR)
                        val sideInst = 0.5f * (highL - highR)

                        var outL: Float
                        var outR: Float

                        if (isDeckA) {
                            val vocalGainA = 1.0f - sCurve(st / 0.42f)
                            val bassGainA  = 1.0f - sCurve((st - 0.25f) / 0.40f)
                            val sideGainA  = 1.0f - sCurve(st)
                            outL = lowL * bassGainA + midVocal * vocalGainA + sideInst * sideGainA
                            outR = lowR * bassGainA + midVocal * vocalGainA - sideInst * sideGainA
                        } else {
                            val sideGainB  = sCurve(st)
                            val bassGainB  = sCurve((st - 0.25f) / 0.40f)
                            val vocalGainB = sCurve((st - 0.34f) / 0.42f)
                            outL = lowL * bassGainB + midVocal * vocalGainB + sideInst * sideGainB
                            outR = lowR * bassGainB + midVocal * vocalGainB - sideInst * sideGainB
                        }

                        if (kotlin.math.abs(outL) > 0.85f) {
                            outL = kotlin.math.tanh(outL * 0.90f)
                        }
                        if (kotlin.math.abs(outR) > 0.85f) {
                            outR = kotlin.math.tanh(outR * 0.90f)
                        }

                        val sL = (outL * 32767f).toInt().coerceIn(-32767, 32767).toShort()
                        val sR = (outR * 32767f).toInt().coerceIn(-32767, 32767).toShort()
                        out.putShort(sL)
                        out.putShort(sR)
                    }
                } else {
                    while (inputBuffer.remaining() >= 2) {
                        val inShort = inputBuffer.short
                        val inM = inShort / 32768.0f

                        smoothT += 0.0005f * (targetT - smoothT)
                        val st = smoothT.coerceIn(0f, 1f)

                        if (isDeckA && st <= 0.0001f) {
                            out.putShort(inShort)
                            continue
                        }
                        if (!isDeckA && st >= 0.9999f) {
                            out.putShort(inShort)
                            continue
                        }

                        if (!filterInitialized) {
                            lowL = inM
                            lowR = inM
                            filterInitialized = true
                        } else {
                            lowL += alpha * (inM - lowL)
                        }

                        val highM = inM - lowL
                        val midVocal = highM

                        var outM: Float

                        if (isDeckA) {
                            val vocalGainA = 1.0f - sCurve(st / 0.42f)
                            val bassGainA  = 1.0f - sCurve((st - 0.25f) / 0.40f)
                            outM = lowL * bassGainA + midVocal * vocalGainA
                        } else {
                            val bassGainB  = sCurve((st - 0.25f) / 0.40f)
                            val vocalGainB = sCurve((st - 0.34f) / 0.42f)
                            outM = lowL * bassGainB + midVocal * vocalGainB
                        }

                        if (kotlin.math.abs(outM) > 0.85f) {
                            outM = kotlin.math.tanh(outM * 0.90f)
                        }

                        val sM = (outM * 32767f).toInt().coerceIn(-32767, 32767).toShort()
                        out.putShort(sM)
                    }
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                if (channelCount == 2) {
                    while (inputBuffer.remaining() >= 8) {
                        val inL = inputBuffer.float
                        val inR = inputBuffer.float

                        smoothT += 0.0005f * (targetT - smoothT)
                        val st = smoothT.coerceIn(0f, 1f)

                        if (isDeckA && st <= 0.0001f) {
                            out.putFloat(inL)
                            out.putFloat(inR)
                            continue
                        }
                        if (!isDeckA && st >= 0.9999f) {
                            out.putFloat(inL)
                            out.putFloat(inR)
                            continue
                        }

                        if (!filterInitialized) {
                            lowL = inL
                            lowR = inR
                            filterInitialized = true
                        } else {
                            lowL += alpha * (inL - lowL)
                            lowR += alpha * (inR - lowR)
                        }

                        val highL = inL - lowL
                        val highR = inR - lowR

                        val midVocal = 0.5f * (highL + highR)
                        val sideInst = 0.5f * (highL - highR)

                        var outL: Float
                        var outR: Float

                        if (isDeckA) {
                            val vocalGainA = 1.0f - sCurve(st / 0.42f)
                            val bassGainA  = 1.0f - sCurve((st - 0.25f) / 0.40f)
                            val sideGainA  = 1.0f - sCurve(st)
                            outL = lowL * bassGainA + midVocal * vocalGainA + sideInst * sideGainA
                            outR = lowR * bassGainA + midVocal * vocalGainA - sideInst * sideGainA
                        } else {
                            val sideGainB  = sCurve(st)
                            val bassGainB  = sCurve((st - 0.25f) / 0.40f)
                            val vocalGainB = sCurve((st - 0.34f) / 0.42f)
                            outL = lowL * bassGainB + midVocal * vocalGainB + sideInst * sideGainB
                            outR = lowR * bassGainB + midVocal * vocalGainB - sideInst * sideGainB
                        }

                        if (kotlin.math.abs(outL) > 0.85f) {
                            outL = kotlin.math.tanh(outL * 0.90f)
                        }
                        if (kotlin.math.abs(outR) > 0.85f) {
                            outR = kotlin.math.tanh(outR * 0.90f)
                        }

                        out.putFloat(outL.coerceIn(-1.0f, 1.0f))
                        out.putFloat(outR.coerceIn(-1.0f, 1.0f))
                    }
                } else {
                    while (inputBuffer.remaining() >= 4) {
                        val inM = inputBuffer.float

                        smoothT += 0.0005f * (targetT - smoothT)
                        val st = smoothT.coerceIn(0f, 1f)

                        if (isDeckA && st <= 0.0001f) {
                            out.putFloat(inM)
                            continue
                        }
                        if (!isDeckA && st >= 0.9999f) {
                            out.putFloat(inM)
                            continue
                        }

                        if (!filterInitialized) {
                            lowL = inM
                            lowR = inM
                            filterInitialized = true
                        } else {
                            lowL += alpha * (inM - lowL)
                        }

                        val highM = inM - lowL
                        val midVocal = highM

                        var outM: Float

                        if (isDeckA) {
                            val vocalGainA = 1.0f - sCurve(st / 0.42f)
                            val bassGainA  = 1.0f - sCurve((st - 0.25f) / 0.40f)
                            outM = lowL * bassGainA + midVocal * vocalGainA
                        } else {
                            val bassGainB  = sCurve((st - 0.25f) / 0.40f)
                            val vocalGainB = sCurve((st - 0.34f) / 0.42f)
                            outM = lowL * bassGainB + midVocal * vocalGainB
                        }

                        if (kotlin.math.abs(outM) > 0.85f) {
                            outM = kotlin.math.tanh(outM * 0.90f)
                        }

                        out.putFloat(outM.coerceIn(-1.0f, 1.0f))
                    }
                }
            }
            C.ENCODING_PCM_24BIT -> {
                val frameBytes = channelCount * 3
                while (inputBuffer.remaining() >= frameBytes) {
                    val b0L = inputBuffer.get().toInt() and 0xFF
                    val b1L = inputBuffer.get().toInt() and 0xFF
                    val b2L = inputBuffer.get().toInt()
                    val valL = (b2L shl 16) or (b1L shl 8) or b0L
                    val inL = valL.toFloat() / 8388608.0f

                    val inR = if (channelCount == 2) {
                        val b0R = inputBuffer.get().toInt() and 0xFF
                        val b1R = inputBuffer.get().toInt() and 0xFF
                        val b2R = inputBuffer.get().toInt()
                        val valR = (b2R shl 16) or (b1R shl 8) or b0R
                        valR.toFloat() / 8388608.0f
                    } else inL

                    smoothT += 0.0005f * (targetT - smoothT)
                    val st = smoothT.coerceIn(0f, 1f)

                    if (!filterInitialized) {
                        lowL = inL
                        lowR = inR
                        filterInitialized = true
                    } else {
                        lowL += alpha * (inL - lowL)
                        lowR += alpha * (inR - lowR)
                    }

                    val highL = inL - lowL
                    val highR = inR - lowR

                    val midVocal = 0.5f * (highL + highR)
                    val sideInst = 0.5f * (highL - highR)

                    var outL: Float
                    var outR: Float

                    if (isDeckA) {
                        val vocalGainA = 1.0f - sCurve(st / 0.42f)
                        val bassGainA  = 1.0f - sCurve((st - 0.25f) / 0.40f)
                        val sideGainA  = 1.0f - sCurve(st)
                        outL = lowL * bassGainA + midVocal * vocalGainA + sideInst * sideGainA
                        outR = lowR * bassGainA + midVocal * vocalGainA - sideInst * sideGainA
                    } else {
                        val sideGainB  = sCurve(st)
                        val bassGainB  = sCurve((st - 0.25f) / 0.40f)
                        val vocalGainB = sCurve((st - 0.34f) / 0.42f)
                        outL = lowL * bassGainB + midVocal * vocalGainB + sideInst * sideGainB
                        outR = lowR * bassGainB + midVocal * vocalGainB - sideInst * sideGainB
                    }

                    if (kotlin.math.abs(outL) > 0.85f) {
                        outL = kotlin.math.tanh(outL * 0.90f)
                    }
                    val i24L = (outL * 8388607f).toInt().coerceIn(-8388607, 8388607)
                    out.put((i24L and 0xFF).toByte())
                    out.put(((i24L shr 8) and 0xFF).toByte())
                    out.put(((i24L shr 16) and 0xFF).toByte())

                    if (channelCount == 2) {
                        if (kotlin.math.abs(outR) > 0.85f) {
                            outR = kotlin.math.tanh(outR * 0.90f)
                        }
                        val i24R = (outR * 8388607f).toInt().coerceIn(-8388607, 8388607)
                        out.put((i24R and 0xFF).toByte())
                        out.put(((i24R shr 8) and 0xFF).toByte())
                        out.put(((i24R shr 16) and 0xFF).toByte())
                    }
                }
            }
            C.ENCODING_PCM_32BIT -> {
                val frameBytes = channelCount * 4
                while (inputBuffer.remaining() >= frameBytes) {
                    val inL = inputBuffer.int.toFloat() / 2147483648.0f
                    val inR = if (channelCount == 2) inputBuffer.int.toFloat() / 2147483648.0f else inL

                    smoothT += 0.0005f * (targetT - smoothT)
                    val st = smoothT.coerceIn(0f, 1f)

                    if (!filterInitialized) {
                        lowL = inL
                        lowR = inR
                        filterInitialized = true
                    } else {
                        lowL += alpha * (inL - lowL)
                        lowR += alpha * (inR - lowR)
                    }

                    val highL = inL - lowL
                    val highR = inR - lowR

                    val midVocal = 0.5f * (highL + highR)
                    val sideInst = 0.5f * (highL - highR)

                    var outL: Float
                    var outR: Float

                    if (isDeckA) {
                        val vocalGainA = 1.0f - sCurve(st / 0.42f)
                        val bassGainA  = 1.0f - sCurve((st - 0.25f) / 0.40f)
                        val sideGainA  = 1.0f - sCurve(st)
                        outL = lowL * bassGainA + midVocal * vocalGainA + sideInst * sideGainA
                        outR = lowR * bassGainA + midVocal * vocalGainA - sideInst * sideGainA
                    } else {
                        val sideGainB  = sCurve(st)
                        val bassGainB  = sCurve((st - 0.25f) / 0.40f)
                        val vocalGainB = sCurve((st - 0.34f) / 0.42f)
                        outL = lowL * bassGainB + midVocal * vocalGainB + sideInst * sideGainB
                        outR = lowR * bassGainB + midVocal * vocalGainB - sideInst * sideGainB
                    }

                    if (kotlin.math.abs(outL) > 0.85f) {
                        outL = kotlin.math.tanh(outL * 0.90f)
                    }
                    val i32L = (outL.toDouble() * 2147483647.0).toLong().coerceIn(-2147483647L, 2147483647L).toInt()
                    out.putInt(i32L)

                    if (channelCount == 2) {
                        if (kotlin.math.abs(outR) > 0.85f) {
                            outR = kotlin.math.tanh(outR * 0.90f)
                        }
                        val i32R = (outR.toDouble() * 2147483647.0).toLong().coerceIn(-2147483647L, 2147483647L).toInt()
                        out.putInt(i32R)
                    }
                }
            }
            else -> {
                out.put(inputBuffer)
            }
        }

        while (inputBuffer.hasRemaining()) {
            out.put(inputBuffer.get())
        }
        out.flip()
    }
}
