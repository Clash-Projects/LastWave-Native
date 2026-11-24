/*
 * Copyright (C) 2026 LastWave Authors (https://github.com/Clash-Projects/LastWave-Native)
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This file is part of LastWave.
 *
 * LastWave is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * LastWave is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LastWave. If not, see <https://www.gnu.org/licenses/>.
 */

// JNI surface for com.lastwave.app.playback.InternalDirectStream.

#include "DirectOutputStream.h"

#include <jni.h>

#include <cstdint>
#include <new>

namespace {

using lastwave::audio::DirectOutputStream;
using lastwave::audio::DirectProbe;

DirectOutputStream* fromHandle(jlong handle) noexcept {
    return reinterpret_cast<DirectOutputStream*>(static_cast<std::uintptr_t>(handle));
}

jintArray toIntArray(JNIEnv* env, const jint* values, jsize count) {
    jintArray array = env->NewIntArray(count);
    if (array != nullptr) env->SetIntArrayRegion(array, 0, count, values);
    return array;
}

}  // namespace

extern "C" JNIEXPORT jintArray JNICALL
Java_com_lastwave_app_playback_InternalDirectStream_nativeProbe(
    JNIEnv* env,
    jclass,
    jint channelCount) {
    const DirectProbe probe = DirectOutputStream::probe(channelCount);
    const jint values[] = {
        probe.status,
        probe.exclusive ? 1 : 0,
        probe.mmap ? 1 : 0,
        probe.sampleRate,
        probe.channelCount,
        probe.encoding,
        probe.deviceId,
        probe.framesPerBurst,
        probe.bufferCapacity,
    };
    return toIntArray(env, values, static_cast<jsize>(sizeof(values) / sizeof(values[0])));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_lastwave_app_playback_InternalDirectStream_nativeCreate(JNIEnv*, jclass) {
    return static_cast<jlong>(reinterpret_cast<std::uintptr_t>(new (std::nothrow) DirectOutputStream()));
}

extern "C" JNIEXPORT void JNICALL
Java_com_lastwave_app_playback_InternalDirectStream_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    delete fromHandle(handle);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_lastwave_app_playback_InternalDirectStream_nativeOpen(
    JNIEnv*,
    jclass,
    jlong handle,
    jint sampleRate,
    jint channelCount,
    jint encoding) {
    auto* stream = fromHandle(handle);
    if (stream == nullptr) return lastwave::audio::kOpenBadArgs;
    return stream->open(sampleRate, channelCount, encoding);
}

extern "C" JNIEXPORT void JNICALL
Java_com_lastwave_app_playback_InternalDirectStream_nativeClose(JNIEnv*, jclass, jlong handle) {
    if (auto* stream = fromHandle(handle)) stream->close();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_lastwave_app_playback_InternalDirectStream_nativeStart(JNIEnv*, jclass, jlong handle) {
    auto* stream = fromHandle(handle);
    return stream != nullptr && stream->start() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_lastwave_app_playback_InternalDirectStream_nativePause(JNIEnv*, jclass, jlong handle) {
    auto* stream = fromHandle(handle);
    return stream != nullptr && stream->pause() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_lastwave_app_playback_InternalDirectStream_nativeFlush(JNIEnv*, jclass, jlong handle) {
    if (auto* stream = fromHandle(handle)) stream->flush();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_lastwave_app_playback_InternalDirectStream_nativeWrite(
    JNIEnv* env,
    jclass,
    jlong handle,
    jobject directBuffer,
    jint offsetBytes,
    jint frames,
    jint inputEncoding,
    jint inputChannels) {
    auto* stream = fromHandle(handle);
    if (stream == nullptr || directBuffer == nullptr || offsetBytes < 0) return 0;
    auto* base = static_cast<std::uint8_t*>(env->GetDirectBufferAddress(directBuffer));
    if (base == nullptr) return 0;
    return stream->write(base + offsetBytes, frames, inputEncoding, inputChannels);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_lastwave_app_playback_InternalDirectStream_nativeCounters(JNIEnv* env, jclass, jlong handle) {
    auto* stream = fromHandle(handle);
    const jlong values[] = {
        stream != nullptr ? stream->consumedFrames() : 0,
        stream != nullptr ? stream->bufferedFrames() : 0,
        stream != nullptr ? stream->underruns() : 0,
        stream != nullptr && stream->isAlive() ? 1 : 0,
        stream != nullptr && stream->isStarted() ? 1 : 0,
    };
    jlongArray array = env->NewLongArray(5);
    if (array != nullptr) env->SetLongArrayRegion(array, 0, 5, values);
    return array;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_lastwave_app_playback_InternalDirectStream_nativeInfo(JNIEnv* env, jclass, jlong handle) {
    auto* stream = fromHandle(handle);
    const jint values[] = {
        stream != nullptr ? stream->sampleRate() : 0,
        stream != nullptr ? stream->channelCount() : 0,
        stream != nullptr ? stream->encoding() : -1,
        stream != nullptr ? stream->deviceId() : 0,
        stream != nullptr ? stream->deviceBufferFrames() : 0,
        stream != nullptr && stream->mmapUsed() ? 1 : 0,
    };
    return toIntArray(env, values, static_cast<jsize>(sizeof(values) / sizeof(values[0])));
}
