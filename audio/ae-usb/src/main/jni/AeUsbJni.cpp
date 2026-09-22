/**
 * JNI around minervarr/audio_engine UsbAudioDriver (AGPL-3).
 * The fd passed in is duplicated; libusb closes the duplicate.
 */

#include "usb_audio.h"

#include <android/log.h>
#include <jni.h>
#include <unistd.h>

#include <cstdint>
#include <vector>

#define LOG_TAG "AeUsb"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_lastwave_aeusb_AeUsbEngine_nativeOpen(JNIEnv *, jobject, jint fd) {
    int dupfd = dup(fd);
    if (dupfd < 0) {
        LOGE("dup(%d) failed", fd);
        return 0;
    }
    auto *drv = new UsbAudioDriver();
    drv->setLatencyProfile(UsbAudioDriver::PROFILE_LOW_LATENCY);
    if (!drv->open(dupfd)) {
        LOGE("UsbAudioDriver::open failed");
        delete drv;
        return 0;
    }
    LOGI("opened fd=%d", fd);
    return reinterpret_cast<jlong>(drv);
}

JNIEXPORT jboolean JNICALL
Java_com_lastwave_aeusb_AeUsbEngine_nativeConfigure(
        JNIEnv *, jobject, jlong h, jint rate, jint channels, jint bits) {
    auto *drv = reinterpret_cast<UsbAudioDriver *>(h);
    if (!drv) return JNI_FALSE;
    bool ok = drv->configure(rate, channels, bits, false);
    LOGI("configure %d Hz %d ch %d bit -> %d", rate, channels, bits, ok ? 1 : 0);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_lastwave_aeusb_AeUsbEngine_nativeStart(JNIEnv *, jobject, jlong h) {
    auto *drv = reinterpret_cast<UsbAudioDriver *>(h);
    if (!drv) return JNI_FALSE;
    bool ok = drv->start();
    LOGI("start streaming=%d", ok && drv->isStreaming() ? 1 : 0);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_lastwave_aeusb_AeUsbEngine_nativeWrite16(JNIEnv *env, jobject, jlong h, jshortArray pcm) {
    auto *drv = reinterpret_cast<UsbAudioDriver *>(h);
    if (!drv || !pcm) return JNI_FALSE;
    jsize n = env->GetArrayLength(pcm);
    if (n <= 0) return JNI_TRUE;
    jshort *data = env->GetShortArrayElements(pcm, nullptr);
    if (!data) return JNI_FALSE;
    int off = 0;
    int spins = 0;
    bool ok = true;
    while (off < n) {
        if (!drv->isStreaming()) { ok = false; break; }
        int w = drv->writeInt16(reinterpret_cast<const int16_t *>(data + off), n - off);
        if (w < 0) { ok = false; break; }
        if (w == 0) {
            if (++spins > 4000) { ok = false; break; }
            usleep(1000);
            continue;
        }
        spins = 0;
        off += w;
    }
    env->ReleaseShortArrayElements(pcm, data, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_lastwave_aeusb_AeUsbEngine_nativeWrite24(JNIEnv *env, jobject, jlong h, jbyteArray pcm) {
    auto *drv = reinterpret_cast<UsbAudioDriver *>(h);
    if (!drv || !pcm) return JNI_FALSE;
    jsize n = env->GetArrayLength(pcm);
    if (n <= 0) return JNI_TRUE;
    jbyte *data = env->GetByteArrayElements(pcm, nullptr);
    if (!data) return JNI_FALSE;
    int off = 0;
    int spins = 0;
    bool ok = true;
    while (off + 2 < n) {
        if (!drv->isStreaming()) { ok = false; break; }
        int w = drv->writeInt24Packed(reinterpret_cast<const uint8_t *>(data + off), n - off);
        if (w < 0) { ok = false; break; }
        if (w == 0) {
            if (++spins > 4000) { ok = false; break; }
            usleep(1000);
            continue;
        }
        spins = 0;
        off += w * 3;
    }
    env->ReleaseByteArrayElements(pcm, data, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_lastwave_aeusb_AeUsbEngine_nativeWrite32(JNIEnv *env, jobject, jlong h, jintArray pcm) {
    auto *drv = reinterpret_cast<UsbAudioDriver *>(h);
    if (!drv || !pcm) return JNI_FALSE;
    jsize n = env->GetArrayLength(pcm);
    if (n <= 0) return JNI_TRUE;
    jint *data = env->GetIntArrayElements(pcm, nullptr);
    if (!data) return JNI_FALSE;
    int off = 0;
    int spins = 0;
    bool ok = true;
    while (off < n) {
        if (!drv->isStreaming()) { ok = false; break; }
        int w = drv->writeInt32(reinterpret_cast<const int32_t *>(data + off), n - off);
        if (w < 0) { ok = false; break; }
        if (w == 0) {
            if (++spins > 4000) { ok = false; break; }
            usleep(1000);
            continue;
        }
        spins = 0;
        off += w;
    }
    env->ReleaseIntArrayElements(pcm, data, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_lastwave_aeusb_AeUsbEngine_nativeWriteFloat(JNIEnv *env, jobject, jlong h, jfloatArray pcm) {
    auto *drv = reinterpret_cast<UsbAudioDriver *>(h);
    if (!drv || !pcm) return JNI_FALSE;
    jsize n = env->GetArrayLength(pcm);
    if (n <= 0) return JNI_TRUE;
    jfloat *data = env->GetFloatArrayElements(pcm, nullptr);
    if (!data) return JNI_FALSE;
    int off = 0;
    int spins = 0;
    bool ok = true;
    while (off < n) {
        if (!drv->isStreaming()) { ok = false; break; }
        int w = drv->writeFloat32(data + off, n - off);
        if (w < 0) { ok = false; break; }
        if (w == 0) {
            if (++spins > 4000) { ok = false; break; }
            usleep(1000);
            continue;
        }
        spins = 0;
        off += w;
    }
    env->ReleaseFloatArrayElements(pcm, data, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_lastwave_aeusb_AeUsbEngine_nativeSeek(JNIEnv *, jobject, jlong h) {
    auto *drv = reinterpret_cast<UsbAudioDriver *>(h);
    if (!drv) return;
    LOGI("seek: stop iso queue and restart");
    drv->flush();
    drv->stop();
    if (!drv->start()) LOGE("seek restart failed");
}

JNIEXPORT void JNICALL
Java_com_lastwave_aeusb_AeUsbEngine_nativeSetPaused(JNIEnv *, jobject, jlong h, jboolean paused) {
    auto *drv = reinterpret_cast<UsbAudioDriver *>(h);
    if (!drv) return;
    drv->setPaused(paused == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_lastwave_aeusb_AeUsbEngine_nativeClose(JNIEnv *, jobject, jlong h) {
    auto *drv = reinterpret_cast<UsbAudioDriver *>(h);
    if (!drv) return;
    drv->stop();
    drv->close();
    delete drv;
}

JNIEXPORT jboolean JNICALL
Java_com_lastwave_aeusb_AeUsbEngine_nativeIsStreaming(JNIEnv *, jobject, jlong h) {
    auto *drv = reinterpret_cast<UsbAudioDriver *>(h);
    return (drv && drv->isStreaming()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_lastwave_aeusb_AeUsbEngine_nativeHasHardwareVolume(JNIEnv *, jobject, jlong h) {
    auto *drv = reinterpret_cast<UsbAudioDriver *>(h);
    return (drv && drv->hasHardwareVolume()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_lastwave_aeusb_AeUsbEngine_nativeSetListeningGain(JNIEnv *, jobject, jlong h, jfloat gain) {
    auto *drv = reinterpret_cast<UsbAudioDriver *>(h);
    if (!drv) return JNI_FALSE;
    return drv->setListeningGain(gain) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_lastwave_aeusb_AeUsbEngine_nativeIsClockMatched(JNIEnv *, jobject, jlong h) {
    auto *drv = reinterpret_cast<UsbAudioDriver *>(h);
    return (drv && drv->isClockMatched()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_lastwave_aeusb_AeUsbEngine_nativeFramesPlayed(JNIEnv *, jobject, jlong h) {
    auto *drv = reinterpret_cast<UsbAudioDriver *>(h);
    return drv ? (jlong)drv->framesPlayed() : 0;
}

}  // extern "C"
