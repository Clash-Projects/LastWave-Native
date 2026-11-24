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

#pragma once

#include "LockFreeRingBuffer.h"

#include <oboe/Oboe.h>

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>

namespace lastwave::audio {

// PCM encodings shared with Kotlin (InternalDirectStream). Keep in sync.
enum DirectEncoding : std::int32_t {
    kDirectI16 = 0,
    kDirectI24Packed = 1,
    kDirectI32 = 2,
    kDirectFloat = 3,
};

// Result of opening an AAudio EXCLUSIVE stream with no rate/format request:
// the configuration the device grants an exclusive (MMAP) client on the
// current route. status < 0 is an oboe::Result error code.
struct DirectProbe {
    std::int32_t status = 0;
    bool exclusive = false;
    bool mmap = false;
    std::int32_t sampleRate = 0;
    std::int32_t channelCount = 0;
    std::int32_t encoding = -1;
    std::int32_t deviceId = 0;
    std::int32_t framesPerBurst = 0;
    std::int32_t bufferCapacity = 0;
};

// Open failure reasons returned to Kotlin (negative, distinct from oboe codes).
enum DirectOpenError : std::int32_t {
    kOpenOk = 0,
    kOpenFailed = -1,          // openStream() returned an error
    kOpenNotAAudio = -2,       // OpenSL ES fallback, no exclusive path
    kOpenShared = -3,          // device downgraded EXCLUSIVE to SHARED (mixer)
    kOpenRateMismatch = -4,    // device substituted another sample rate
    kOpenFormatMismatch = -5,  // device substituted another sample format
    kOpenChannelMismatch = -6, // device substituted another channel count
    kOpenBadArgs = -7,
    kOpenStartFailed = -8,
};

/**
 * Exclusive (MMAP) AAudio output for the phone's own outputs (speaker, wired).
 *
 * The app writes PCM straight into the HAL's memory-mapped buffer: AudioFlinger's
 * mixer, its resampler and its software volume never touch the samples. The
 * stream is always opened in the device's exact configuration with every Oboe
 * conversion disabled, so what getSharingMode()/getSampleRate()/getFormat()
 * report is the real wire format. Any format change is done here in [write]
 * and declared to Kotlin; rate changes are done upstream by the app (soxr).
 *
 * Producer: the ExoPlayer playback thread ([write]). Consumer: the AAudio
 * real-time callback. A lock-free byte ring sits between them.
 */
class DirectOutputStream final : public oboe::AudioStreamDataCallback,
                                 public oboe::AudioStreamErrorCallback {
public:
    DirectOutputStream() = default;
    ~DirectOutputStream() override;

    DirectOutputStream(const DirectOutputStream&) = delete;
    DirectOutputStream& operator=(const DirectOutputStream&) = delete;

    static DirectProbe probe(std::int32_t channelCount) noexcept;

    std::int32_t open(std::int32_t sampleRate,
                      std::int32_t channelCount,
                      std::int32_t encoding) noexcept;
    void close() noexcept;

    bool start() noexcept;
    bool pause() noexcept;
    void flush() noexcept;

    // Converts [frames] of interleaved input (inputEncoding, inputChannels) into
    // the stream's wire format and queues as many whole frames as fit.
    // Returns the number of INPUT frames consumed (0 when full or flushing).
    std::int32_t write(const std::uint8_t* data,
                       std::int32_t frames,
                       std::int32_t inputEncoding,
                       std::int32_t inputChannels) noexcept;

    [[nodiscard]] std::int64_t consumedFrames() const noexcept {
        return consumedFrames_.load(std::memory_order_acquire);
    }
    [[nodiscard]] std::int64_t bufferedFrames() const noexcept;
    [[nodiscard]] std::int64_t underruns() const noexcept {
        return underruns_.load(std::memory_order_acquire);
    }
    [[nodiscard]] bool isAlive() const noexcept { return alive_.load(std::memory_order_acquire); }
    [[nodiscard]] bool isStarted() const noexcept { return started_.load(std::memory_order_acquire); }
    [[nodiscard]] std::int32_t sampleRate() const noexcept { return sampleRate_; }
    [[nodiscard]] std::int32_t channelCount() const noexcept { return channelCount_; }
    [[nodiscard]] std::int32_t encoding() const noexcept { return encoding_; }
    [[nodiscard]] std::int32_t deviceId() const noexcept { return deviceId_; }
    [[nodiscard]] std::int32_t deviceBufferFrames() const noexcept { return deviceBufferFrames_; }
    [[nodiscard]] bool mmapUsed() const noexcept { return mmapUsed_; }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream,
                                          void* audioData,
                                          std::int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    void closeLocked() noexcept;

    std::mutex lock_;
    std::shared_ptr<oboe::AudioStream> stream_;
    std::unique_ptr<LockFreeRingBuffer<std::uint8_t>> ring_;
    std::int32_t sampleRate_ = 0;
    std::int32_t channelCount_ = 0;
    std::int32_t encoding_ = -1;
    std::int32_t bytesPerFrame_ = 0;
    std::int32_t deviceId_ = 0;
    std::int32_t deviceBufferFrames_ = 0;
    bool mmapUsed_ = false;

    std::atomic<bool> alive_{false};
    std::atomic<bool> started_{false};
    std::atomic<bool> flushRequested_{false};
    std::atomic<bool> hasDeliveredAudio_{false};
    std::atomic<std::int64_t> consumedFrames_{0};
    std::atomic<std::int64_t> underruns_{0};
};

}  // namespace lastwave::audio
