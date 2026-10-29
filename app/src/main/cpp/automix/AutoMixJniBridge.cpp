#include <jni.h>
#include <android/log.h>
#include <memory>
#include <mutex>
#include "LinkwitzRileyCrossover.h"
#include "TransientPreservingStretcher.h"
#include "LufsContourNormalizer.h"

#define LOG_TAG "AutoMixJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
    std::mutex gMutex;
    std::unique_ptr<automix::SpectralDeckCrossover> gCrossover;
    std::unique_ptr<automix::TransientPreservingStretcher> gStretcherAlpha;
    std::unique_ptr<automix::TransientPreservingStretcher> gStretcherBeta;
    std::unique_ptr<automix::LufsContourNormalizer> gLufsNormalizer;
    float gSampleRate = 44100.0f;
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_lastwave_app_playback_automix_engine_SpectralCrossoverAudioProcessor_nativeInit(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jfloat sampleRate
) {
    std::lock_guard<std::mutex> lock(gMutex);
    gSampleRate = sampleRate > 0.0f ? sampleRate : 44100.0f;

    gCrossover = std::make_unique<automix::SpectralDeckCrossover>();
    gCrossover->init(gSampleRate);

    gStretcherAlpha = std::make_unique<automix::TransientPreservingStretcher>();
    gStretcherAlpha->init(gSampleRate, 2);

    gStretcherBeta = std::make_unique<automix::TransientPreservingStretcher>();
    gStretcherBeta->init(gSampleRate, 2);

    gLufsNormalizer = std::make_unique<automix::LufsContourNormalizer>();
    gLufsNormalizer->init(gSampleRate);

    LOGI("AutoMix DSP initialized at %.1f Hz", gSampleRate);
}

JNIEXPORT void JNICALL
Java_com_lastwave_app_playback_automix_engine_SpectralCrossoverAudioProcessor_nativeReset(
    JNIEnv* /* env */,
    jobject /* thiz */
) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (gCrossover) gCrossover->reset();
    if (gStretcherAlpha) gStretcherAlpha->reset();
    if (gStretcherBeta) gStretcherBeta->reset();
    if (gLufsNormalizer) gLufsNormalizer->reset();
}

JNIEXPORT void JNICALL
Java_com_lastwave_app_playback_automix_engine_SpectralCrossoverAudioProcessor_nativeSetTempoRatio(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jfloat ratioAlpha,
    jfloat ratioBeta
) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (gStretcherAlpha) gStretcherAlpha->setTempoRatio(ratioAlpha);
    if (gStretcherBeta) gStretcherBeta->setTempoRatio(ratioBeta);
}

JNIEXPORT void JNICALL
Java_com_lastwave_app_playback_automix_engine_SpectralCrossoverAudioProcessor_nativeProcessStereo(
    JNIEnv* env,
    jobject /* thiz */,
    jobject alphaBuf,
    jobject betaBuf,
    jobject outBuf,
    jint numFrames,
    jfloat progress,
    jboolean applySpectralSweeps
) {
    if (numFrames <= 0) return;

    const float* inA = alphaBuf ? static_cast<const float*>(env->GetDirectBufferAddress(alphaBuf)) : nullptr;
    const float* inB = betaBuf ? static_cast<const float*>(env->GetDirectBufferAddress(betaBuf)) : nullptr;
    float* out = outBuf ? static_cast<float*>(env->GetDirectBufferAddress(outBuf)) : nullptr;

    if (!out) return;

    std::lock_guard<std::mutex> lock(gMutex);
    if (!gCrossover) {
        gCrossover = std::make_unique<automix::SpectralDeckCrossover>();
        gCrossover->init(gSampleRate);
    }

    gCrossover->processStereo(inA, inB, out, numFrames, progress, applySpectralSweeps != JNI_FALSE);
}

JNIEXPORT jfloat JNICALL
Java_com_lastwave_app_playback_automix_engine_SpectralCrossoverAudioProcessor_nativeEstimateLufs(
    JNIEnv* env,
    jobject /* thiz */,
    jobject pcmBuf,
    jint numFrames
) {
    if (!pcmBuf || numFrames <= 0) return -70.0f;
    const float* pcm = static_cast<const float*>(env->GetDirectBufferAddress(pcmBuf));
    if (!pcm) return -70.0f;

    std::lock_guard<std::mutex> lock(gMutex);
    if (!gLufsNormalizer) {
        gLufsNormalizer = std::make_unique<automix::LufsContourNormalizer>();
        gLufsNormalizer->init(gSampleRate);
    }

    return gLufsNormalizer->estimateMomentaryLufs(pcm, numFrames);
}

} // extern "C"
