#include "AutoMixEngine.h"
#include "TransitionPlanner.h"

#include <jni.h>
#include <cstdint>
#include <new>
#include <vector>

namespace {

using lastwave::audio::AutoMixEngine;
using lastwave::audio::AutoMixState;
using lastwave::audio::TransitionPlan;
using lastwave::audio::TransitionPlanner;
using lastwave::audio::PhraseLengthMode;
using lastwave::audio::LyricTimeInterval;

AutoMixEngine* fromHandle(jlong handle) noexcept {
    return reinterpret_cast<AutoMixEngine*>(static_cast<std::uintptr_t>(handle));
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeCreate(
    JNIEnv*,
    jclass) {
    const auto ptr = reinterpret_cast<std::uintptr_t>(new (std::nothrow) AutoMixEngine());
    return static_cast<jlong>(ptr);
}

JNIEXPORT void JNICALL
Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeDestroy(
    JNIEnv*,
    jclass,
    jlong handle) {
    delete fromHandle(handle);
}

JNIEXPORT void JNICALL
Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeConfigure(
    JNIEnv*,
    jclass,
    jlong handle,
    jdouble sampleRate) {
    auto* engine = fromHandle(handle);
    if (engine != nullptr) {
        engine->configure(sampleRate);
    }
}

JNIEXPORT void JNICALL
Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeSetEnabled(
    JNIEnv*,
    jclass,
    jlong handle,
    jboolean enabled) {
    auto* engine = fromHandle(handle);
    if (engine != nullptr) {
        engine->setEnabled(enabled == JNI_TRUE);
    }
}

JNIEXPORT void JNICALL
Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeSetBarMode(
    JNIEnv*,
    jclass,
    jlong handle,
    jint bars) {
    auto* engine = fromHandle(handle);
    if (engine != nullptr) {
        engine->setBarMode(bars);
    }
}

JNIEXPORT void JNICALL
Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeSetPaused(
    JNIEnv*,
    jclass,
    jlong handle,
    jboolean paused) {
    auto* engine = fromHandle(handle);
    if (engine != nullptr) {
        engine->setPaused(paused == JNI_TRUE);
    }
}

JNIEXPORT void JNICALL
Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeSetDeckBVocalsActive(
    JNIEnv*,
    jclass,
    jlong handle,
    jboolean active) {
    auto* engine = fromHandle(handle);
    if (engine != nullptr) {
        engine->setDeckBVocalsActive(active == JNI_TRUE);
    }
}

JNIEXPORT void JNICALL
Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeSetDeckAVocalFree(
    JNIEnv*,
    jclass,
    jlong handle,
    jboolean free) {
    auto* engine = fromHandle(handle);
    if (engine != nullptr) {
        engine->setDeckAVocalFree(free == JNI_TRUE);
    }
}

JNIEXPORT void JNICALL
Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeTriggerFadeNow(
    JNIEnv*,
    jclass,
    jlong handle) {
    auto* engine = fromHandle(handle);
    if (engine != nullptr) {
        engine->triggerFadeNow();
    }
}

JNIEXPORT void JNICALL
Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeStartTransition(
    JNIEnv*,
    jclass,
    jlong handle,
    jint durationMs) {
    auto* engine = fromHandle(handle);
    if (engine != nullptr) {
        engine->startTransition(static_cast<int>(durationMs));
    }
}

JNIEXPORT void JNICALL
Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeSetTransitionProgress(
    JNIEnv*,
    jclass,
    jlong handle,
    jfloat progress) {
    auto* engine = fromHandle(handle);
    if (engine != nullptr) {
        engine->setTransitionProgress(static_cast<float>(progress));
    }
}

JNIEXPORT void JNICALL
Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeReset(
    JNIEnv*,
    jclass,
    jlong handle) {
    auto* engine = fromHandle(handle);
    if (engine != nullptr) {
        engine->reset();
    }
}

JNIEXPORT jint JNICALL
Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeWriteDeckBPcm(
    JNIEnv* env,
    jclass,
    jlong handle,
    jobject directBuffer,
    jint offsetBytes,
    jint frameCount) {
    auto* engine = fromHandle(handle);
    if (engine == nullptr || directBuffer == nullptr || frameCount <= 0 || offsetBytes < 0) {
        return 0;
    }

    auto* bufferAddress = static_cast<std::uint8_t*>(env->GetDirectBufferAddress(directBuffer));
    if (bufferAddress == nullptr) {
        return 0;
    }

    const auto* pcm = reinterpret_cast<const float*>(bufferAddress + offsetBytes);
    return static_cast<jint>(engine->writeDeckBPcm(pcm, static_cast<std::size_t>(frameCount)));
}

JNIEXPORT void JNICALL
Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeProcess(
    JNIEnv* env,
    jclass,
    jlong handle,
    jobject directBuffer,
    jint offsetBytes,
    jint frameCount) {
    auto* engine = fromHandle(handle);
    if (engine == nullptr || directBuffer == nullptr || frameCount <= 0 || offsetBytes < 0) {
        return;
    }

    auto* bufferAddress = static_cast<std::uint8_t*>(env->GetDirectBufferAddress(directBuffer));
    if (bufferAddress == nullptr) {
        return;
    }

    auto* pcm = reinterpret_cast<float*>(bufferAddress + offsetBytes);
    engine->process(pcm, static_cast<std::size_t>(frameCount));
}

JNIEXPORT jint JNICALL
Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeGetState(
    JNIEnv*,
    jclass,
    jlong handle) {
    auto* engine = fromHandle(handle);
    return (engine != nullptr) ? static_cast<jint>(engine->getState()) : 0;
}

JNIEXPORT jfloat JNICALL
Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeGetTransitionProgress(
    JNIEnv*,
    jclass,
    jlong handle) {
    auto* engine = fromHandle(handle);
    return (engine != nullptr) ? engine->getTransitionProgress() : 0.0F;
}

JNIEXPORT jlong JNICALL
Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeGetDeckBLeadingTrimFrames(
    JNIEnv*,
    jclass,
    jlong handle) {
    auto* engine = fromHandle(handle);
    return (engine != nullptr) ? static_cast<jlong>(engine->getDeckBLeadingTrimFrames()) : 0L;
}

JNIEXPORT jfloat JNICALL
Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeGetDeckBAvailableSeconds(
    JNIEnv*,
    jclass,
    jlong handle) {
    auto* engine = fromHandle(handle);
    return (engine != nullptr) ? engine->getDeckBAvailableSeconds() : 0.0F;
}

JNIEXPORT jlong JNICALL
Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeGetDeckBAvailableFrames(
    JNIEnv*,
    jclass,
    jlong handle) {
    auto* engine = fromHandle(handle);
    return (engine != nullptr) ? static_cast<jlong>(engine->getDeckBAvailableFrames()) : 0L;
}

JNIEXPORT jfloat JNICALL
Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeGetDeckABpm(
    JNIEnv*,
    jclass,
    jlong handle) {
    auto* engine = fromHandle(handle);
    return (engine != nullptr) ? engine->getDeckABpm() : 120.0F;
}

JNIEXPORT jfloat JNICALL
Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeGetDeckBBpm(
    JNIEnv*,
    jclass,
    jlong handle) {
    auto* engine = fromHandle(handle);
    return (engine != nullptr) ? engine->getDeckBBpm() : 120.0F;
}

JNIEXPORT jlong JNICALL
Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeGetDeckBFirstBeatMs(
    JNIEnv*,
    jclass,
    jlong handle) {
    auto* engine = fromHandle(handle);
    return (engine != nullptr) ? static_cast<jlong>(engine->getDeckBFirstBeatMs()) : 0L;
}

JNIEXPORT jlong JNICALL
Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeGetDeckBDropBeatMs(
    JNIEnv*,
    jclass,
    jlong handle) {
    auto* engine = fromHandle(handle);
    return (engine != nullptr) ? static_cast<jlong>(engine->getDeckBDropBeatMs()) : 0L;
}

JNIEXPORT jboolean JNICALL
Java_com_lastwave_app_playback_NativeAutoMixEngine_nativeProcessDeckBuffer(
    JNIEnv* env,
    jclass,
    jlong handle,
    jobject byteBuffer,
    jint offset,
    jint length,
    jint encoding,
    jint sampleRate,
    jboolean isDeckA) {
    auto* engine = fromHandle(handle);
    if (engine == nullptr || byteBuffer == nullptr || length <= 0) return JNI_FALSE;

    auto* directAddress = static_cast<std::uint8_t*>(env->GetDirectBufferAddress(byteBuffer));
    if (directAddress == nullptr) return JNI_FALSE;

    std::size_t bytesPerSample = 2;
    switch (encoding) {
        case 0: bytesPerSample = 2; break; // PCM_I16
        case 1: bytesPerSample = 3; break; // PCM_I24_PACKED
        case 2: bytesPerSample = 4; break; // PCM_I32
        case 3: bytesPerSample = 4; break; // PCM_FLOAT
        default: return JNI_FALSE;
    }
    const std::size_t bytesPerFrame = bytesPerSample * 2; // stereo
    if (bytesPerFrame == 0) return JNI_FALSE;
    const std::size_t frameCount = static_cast<std::size_t>(length) / bytesPerFrame;
    if (frameCount == 0) return JNI_FALSE;

    const bool ok = engine->processDeckBuffer(
        directAddress + offset,
        frameCount,
        encoding,
        sampleRate,
        isDeckA == JNI_TRUE);

    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jdoubleArray JNICALL
Java_com_lastwave_app_playback_NativeAutoMixEngine_nativePlanTransition(
    JNIEnv* env,
    jclass,
    jlong handle,
    jlong durationMsA,
    jlong durationMsB,
    jdouble sampleRate,
    jint phraseModeInt,
    jboolean isManualSkip,
    jlong currentDeckAPosMs,
    jlongArray lyricsAIntervals, // pairs of [startMs, endMs]
    jlongArray lyricsBIntervals
) {
    auto* engine = fromHandle(handle);

    std::vector<LyricTimeInterval> intervalsA;
    if (lyricsAIntervals != nullptr) {
        const jsize lenA = env->GetArrayLength(lyricsAIntervals);
        std::vector<jlong> bufA(lenA);
        env->GetLongArrayRegion(lyricsAIntervals, 0, lenA, bufA.data());
        for (jsize i = 0; i + 1 < lenA; i += 2) {
            intervalsA.push_back({static_cast<int64_t>(bufA[i]), static_cast<int64_t>(bufA[i + 1]), false});
        }
    }

    std::vector<LyricTimeInterval> intervalsB;
    if (lyricsBIntervals != nullptr) {
        const jsize lenB = env->GetArrayLength(lyricsBIntervals);
        std::vector<jlong> bufB(lenB);
        env->GetLongArrayRegion(lyricsBIntervals, 0, lenB, bufB.data());
        for (jsize i = 0; i + 1 < lenB; i += 2) {
            intervalsB.push_back({static_cast<int64_t>(bufB[i]), static_cast<int64_t>(bufB[i + 1]), false});
        }
    }

    const auto mode = static_cast<PhraseLengthMode>(phraseModeInt);
    TransitionPlan plan = TransitionPlanner::planTransition(
        static_cast<int64_t>(durationMsA),
        nullptr, 0, sampleRate, intervalsA,
        static_cast<int64_t>(durationMsB),
        nullptr, 0, sampleRate, intervalsB,
        mode,
        isManualSkip == JNI_TRUE,
        static_cast<int64_t>(currentDeckAPosMs)
    );

    if (engine != nullptr) {
        engine->setTransitionPlan(plan);
    }

    jdouble results[11] = {
        static_cast<double>(plan.outCueMs),
        static_cast<double>(plan.inCueMs),
        static_cast<double>(plan.durationMs),
        static_cast<double>(plan.bars),
        static_cast<double>(plan.bpmA),
        static_cast<double>(plan.bpmB),
        static_cast<double>(plan.bpmRatio),
        static_cast<double>(plan.gainOffsetDb),
        static_cast<double>(plan.textureFx),
        plan.isVocalSafeLoop ? 1.0 : 0.0,
        plan.isSkitSkipped ? 1.0 : 0.0
    };

    jdoubleArray outArr = env->NewDoubleArray(11);
    if (outArr != nullptr) {
        env->SetDoubleArrayRegion(outArr, 0, 11, results);
    }
    return outArr;
}

} // extern "C"
