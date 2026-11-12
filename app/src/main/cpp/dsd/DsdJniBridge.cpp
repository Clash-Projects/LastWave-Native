#include "DsdDecimator.h"

#include <jni.h>

#include <cstdint>
#include <cstring>
#include <new>
#include <vector>

namespace {

using lastwave::dsd::DsdDecimator;

DsdDecimator* fromHandle(jlong handle) noexcept {
    return reinterpret_cast<DsdDecimator*>(static_cast<std::uintptr_t>(handle));
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_lastwave_app_playback_dsd_DsdNativeDecimator_nativeCreate(
    JNIEnv*,
    jobject,
    jint channelCount,
    jint decimationFactor,
    jboolean lsbFirst) {
    auto* dec = new (std::nothrow) DsdDecimator(channelCount, decimationFactor, lsbFirst == JNI_TRUE);
    return static_cast<jlong>(reinterpret_cast<std::uintptr_t>(dec));
}

extern "C" JNIEXPORT void JNICALL
Java_com_lastwave_app_playback_dsd_DsdNativeDecimator_nativeDestroy(
    JNIEnv*,
    jobject,
    jlong handle) {
    delete fromHandle(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_lastwave_app_playback_dsd_DsdNativeDecimator_nativeReset(
    JNIEnv*,
    jobject,
    jlong handle) {
    if (auto* dec = fromHandle(handle); dec != nullptr) {
        dec->reset();
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_lastwave_app_playback_dsd_DsdNativeDecimator_nativeProcess(
    JNIEnv* env,
    jobject,
    jlong handle,
    jbyteArray inputBytes,
    jint inputOffset,
    jint inputLength,
    jboolean planar,
    jint blockSizePerChannel,
    jbyteArray outputPcmBytes) {
    auto* dec = fromHandle(handle);
    if (dec == nullptr || inputBytes == nullptr || outputPcmBytes == nullptr || inputLength <= 0) {
        return 0;
    }

    if (inputLength <= 0 || inputOffset < 0) return 0;

    const jint channels = dec->channelCount();
    if (channels <= 0) return 0;

    const jsize inCapBytes = env->GetArrayLength(inputBytes);
    const jsize outCapBytes = env->GetArrayLength(outputPcmBytes);
    if (static_cast<std::size_t>(inputOffset) + static_cast<std::size_t>(inputLength) >
        static_cast<std::size_t>(inCapBytes)) {
        return 0;
    }

    std::size_t frameBytes = 0;
    if (planar == JNI_TRUE) {
        const std::size_t expectedBlock = static_cast<std::size_t>(blockSizePerChannel) * static_cast<std::size_t>(channels);
        if (static_cast<std::size_t>(inputLength) >= expectedBlock && blockSizePerChannel > 0) {
            frameBytes = static_cast<std::size_t>(blockSizePerChannel);
        }
    } else {
        frameBytes = static_cast<std::size_t>(inputLength) / static_cast<std::size_t>(channels);
    }
    if (frameBytes == 0) return 0;

    const std::size_t maxOutFrames = dec->maxOutputFrames(frameBytes);
    const std::size_t neededFloats = maxOutFrames * static_cast<std::size_t>(channels);
    if (neededFloats * sizeof(float) > static_cast<std::size_t>(outCapBytes)) {
        return 0;
    }

    jboolean isInCopy = JNI_FALSE;
    auto* inPtr = static_cast<jbyte*>(env->GetPrimitiveArrayCritical(inputBytes, &isInCopy));
    if (inPtr == nullptr) return 0;

    const auto* raw = reinterpret_cast<const std::uint8_t*>(inPtr + inputOffset);
    std::vector<float> tmpOut(neededFloats);
    std::size_t producedFrames = 0;

    if (planar == JNI_TRUE) {
        std::vector<const std::uint8_t*> planes(static_cast<std::size_t>(channels));
        for (jint ch = 0; ch < channels; ++ch) {
            planes[static_cast<std::size_t>(ch)] = raw + static_cast<std::size_t>(ch) * static_cast<std::size_t>(blockSizePerChannel);
        }
        producedFrames = dec->processPlanar(planes.data(), frameBytes, tmpOut.data());
    } else {
        producedFrames = dec->processInterleaved(raw, frameBytes, tmpOut.data());
    }

    env->ReleasePrimitiveArrayCritical(inputBytes, inPtr, JNI_ABORT);

    if (producedFrames == 0) return 0;

    const std::size_t writtenBytes = producedFrames * static_cast<std::size_t>(channels) * sizeof(float);
    env->SetByteArrayRegion(
        outputPcmBytes,
        0,
        static_cast<jsize>(writtenBytes),
        reinterpret_cast<const jbyte*>(tmpOut.data())
    );
    return static_cast<jint>(writtenBytes);
}
