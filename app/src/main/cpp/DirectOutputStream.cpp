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

#include "DirectOutputStream.h"

#include <oboe/OboeExtensions.h>

#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>

namespace lastwave::audio {
namespace {

constexpr const char* kTag = "DirectOutputStream";
constexpr std::int64_t kStateTimeoutNanos = 500LL * 1000LL * 1000LL;
// Producer-side headroom: ExoPlayer feeds every ~10 ms, the RT callback drains
// every burst. 750 ms of wire-format audio absorbs network/decoder hiccups.
constexpr std::int64_t kRingMillis = 750;

std::int32_t bytesPerSample(std::int32_t encoding) noexcept {
    switch (encoding) {
        case kDirectI16: return 2;
        case kDirectI24Packed: return 3;
        case kDirectI32: return 4;
        case kDirectFloat: return 4;
        default: return 0;
    }
}

bool toOboeFormat(std::int32_t encoding, oboe::AudioFormat& out) noexcept {
    switch (encoding) {
        case kDirectI16: out = oboe::AudioFormat::I16; return true;
        case kDirectI24Packed: out = oboe::AudioFormat::I24; return true;
        case kDirectI32: out = oboe::AudioFormat::I32; return true;
        case kDirectFloat: out = oboe::AudioFormat::Float; return true;
        default: return false;
    }
}

std::int32_t fromOboeFormat(oboe::AudioFormat format) noexcept {
    switch (format) {
        case oboe::AudioFormat::I16: return kDirectI16;
        case oboe::AudioFormat::I24: return kDirectI24Packed;
        case oboe::AudioFormat::I32: return kDirectI32;
        case oboe::AudioFormat::Float: return kDirectFloat;
        default: return -1;
    }
}

void configureBuilder(oboe::AudioStreamBuilder& builder, std::int32_t channelCount) {
    // EXCLUSIVE + LowLatency is the only public route to an MMAP stream that
    // AudioFlinger does not mix. Every Oboe conversion stays OFF: if the device
    // cannot honour the request exactly, the open must fail (or be detected as
    // a substitution below) instead of silently converting behind our back.
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setUsage(oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Music)
        ->setChannelCount(channelCount)
        ->setFormatConversionAllowed(false)
        ->setChannelConversionAllowed(false)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::None)
        ->setSpatializationBehavior(oboe::SpatializationBehavior::Never);
}

// Integer input is carried as a left-justified 32-bit value so every
// widening (16->24, 16->32, 24->32, int->float) is exact.
inline std::int32_t readInt(const std::uint8_t* p, std::int32_t encoding) noexcept {
    switch (encoding) {
        case kDirectI16: {
            std::int16_t v;
            std::memcpy(&v, p, sizeof(v));
            return static_cast<std::int32_t>(static_cast<std::uint32_t>(static_cast<std::int32_t>(v)) << 16);
        }
        case kDirectI24Packed: {
            const auto u = static_cast<std::uint32_t>(p[0]) << 8 |
                static_cast<std::uint32_t>(p[1]) << 16 |
                static_cast<std::uint32_t>(p[2]) << 24;
            return static_cast<std::int32_t>(u);
        }
        case kDirectI32: {
            std::int32_t v;
            std::memcpy(&v, p, sizeof(v));
            return v;
        }
        default:
            return 0;
    }
}

inline float readFloat(const std::uint8_t* p) noexcept {
    float v;
    std::memcpy(&v, p, sizeof(v));
    return v;
}

template <typename T>
inline T clampRound(double value, double lo, double hi) noexcept {
    return static_cast<T>(std::lround(std::clamp(value, lo, hi)));
}

void writeSample(std::uint8_t* out,
                 std::int32_t outEncoding,
                 const std::uint8_t* in,
                 std::int32_t inEncoding) noexcept {
    const bool inFloat = inEncoding == kDirectFloat;
    switch (outEncoding) {
        case kDirectFloat: {
            float v = inFloat
                ? readFloat(in)
                : static_cast<float>(static_cast<double>(readInt(in, inEncoding)) / 2147483648.0);
            std::memcpy(out, &v, sizeof(v));
            break;
        }
        case kDirectI32: {
            std::int32_t v = inFloat
                ? static_cast<std::int32_t>(std::clamp(
                    static_cast<double>(readFloat(in)) * 2147483648.0, -2147483648.0, 2147483647.0))
                : readInt(in, inEncoding);
            std::memcpy(out, &v, sizeof(v));
            break;
        }
        case kDirectI24Packed: {
            const std::int32_t v = inFloat
                ? clampRound<std::int32_t>(static_cast<double>(readFloat(in)) * 8388608.0, -8388608.0, 8388607.0)
                : (readInt(in, inEncoding) >> 8);
            const auto u = static_cast<std::uint32_t>(v);
            out[0] = static_cast<std::uint8_t>(u & 0xFFu);
            out[1] = static_cast<std::uint8_t>((u >> 8) & 0xFFu);
            out[2] = static_cast<std::uint8_t>((u >> 16) & 0xFFu);
            break;
        }
        case kDirectI16: {
            const std::int16_t v = inFloat
                ? clampRound<std::int16_t>(static_cast<double>(readFloat(in)) * 32768.0, -32768.0, 32767.0)
                : static_cast<std::int16_t>(readInt(in, inEncoding) >> 16);
            std::memcpy(out, &v, sizeof(v));
            break;
        }
        default:
            break;
    }
}

}  // namespace

DirectOutputStream::~DirectOutputStream() {
    close();
}

DirectProbe DirectOutputStream::probe(std::int32_t channelCount) noexcept {
    DirectProbe probe;
    try {
        oboe::AudioStreamBuilder builder;
        configureBuilder(builder, std::clamp(channelCount, 1, 2));
        std::shared_ptr<oboe::AudioStream> stream;
        const auto result = builder.openStream(stream);
        if (result != oboe::Result::OK || stream == nullptr) {
            probe.status = static_cast<std::int32_t>(result);
            return probe;
        }
        probe.exclusive = stream->usesAAudio() &&
            stream->getSharingMode() == oboe::SharingMode::Exclusive;
        probe.mmap = oboe::OboeExtensions::isMMapUsed(stream.get());
        probe.sampleRate = stream->getSampleRate();
        probe.channelCount = stream->getChannelCount();
        probe.encoding = fromOboeFormat(stream->getFormat());
        probe.deviceId = stream->getDeviceId();
        probe.framesPerBurst = stream->getFramesPerBurst();
        probe.bufferCapacity = stream->getBufferCapacityInFrames();
        stream->close();
        __android_log_print(ANDROID_LOG_INFO, kTag,
            "probe exclusive=%d mmap=%d rate=%d ch=%d enc=%d device=%d burst=%d cap=%d",
            probe.exclusive ? 1 : 0, probe.mmap ? 1 : 0, probe.sampleRate,
            probe.channelCount, probe.encoding, probe.deviceId,
            probe.framesPerBurst, probe.bufferCapacity);
    } catch (...) {
        probe.status = static_cast<std::int32_t>(oboe::Result::ErrorInternal);
    }
    return probe;
}

std::int32_t DirectOutputStream::open(std::int32_t sampleRate,
                                      std::int32_t channelCount,
                                      std::int32_t encoding) noexcept {
    std::lock_guard<std::mutex> guard(lock_);
    closeLocked();
    oboe::AudioFormat format;
    if (sampleRate <= 0 || sampleRate > 768000 || channelCount < 1 || channelCount > 2 ||
        !toOboeFormat(encoding, format)) {
        return kOpenBadArgs;
    }
    try {
        oboe::AudioStreamBuilder builder;
        configureBuilder(builder, channelCount);
        builder.setSampleRate(sampleRate)
            ->setFormat(format)
            ->setDataCallback(this)
            ->setErrorCallback(this);
        std::shared_ptr<oboe::AudioStream> opened;
        const auto result = builder.openStream(opened);
        if (result != oboe::Result::OK || opened == nullptr) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "open %d Hz enc=%d failed: %s",
                sampleRate, encoding, oboe::convertToText(result));
            return kOpenFailed;
        }
        std::int32_t verdict = kOpenOk;
        if (!opened->usesAAudio()) {
            verdict = kOpenNotAAudio;
        } else if (opened->getSharingMode() != oboe::SharingMode::Exclusive) {
            verdict = kOpenShared;
        } else if (opened->getSampleRate() != sampleRate) {
            verdict = kOpenRateMismatch;
        } else if (opened->getFormat() != format) {
            verdict = kOpenFormatMismatch;
        } else if (opened->getChannelCount() != channelCount) {
            verdict = kOpenChannelMismatch;
        }
        if (verdict != kOpenOk) {
            __android_log_print(ANDROID_LOG_WARN, kTag,
                "open %d Hz enc=%d rejected (%d): got sharing=%d rate=%d fmt=%d ch=%d",
                sampleRate, encoding, verdict,
                static_cast<int>(opened->getSharingMode()), opened->getSampleRate(),
                static_cast<int>(opened->getFormat()), opened->getChannelCount());
            opened->close();
            return verdict;
        }

        // Deepest device buffer the endpoint allows: this is a music player,
        // robustness against scheduling jitter beats round-trip latency.
        const auto capacity = opened->getBufferCapacityInFrames();
        if (capacity > 0) {
            (void) opened->setBufferSizeInFrames(capacity);
        }
        stream_ = opened;
        sampleRate_ = sampleRate;
        channelCount_ = channelCount;
        encoding_ = encoding;
        bytesPerFrame_ = bytesPerSample(encoding) * channelCount;
        deviceId_ = opened->getDeviceId();
        deviceBufferFrames_ = std::max(opened->getBufferSizeInFrames(), 0);
        mmapUsed_ = oboe::OboeExtensions::isMMapUsed(opened.get());
        const auto ringBytes = static_cast<std::size_t>(
            static_cast<std::int64_t>(sampleRate) * kRingMillis / 1000 * bytesPerFrame_);
        ring_ = std::make_unique<LockFreeRingBuffer<std::uint8_t>>(ringBytes);
        consumedFrames_.store(0, std::memory_order_release);
        underruns_.store(0, std::memory_order_release);
        flushRequested_.store(false, std::memory_order_release);
        hasDeliveredAudio_.store(false, std::memory_order_release);
        started_.store(false, std::memory_order_release);
        alive_.store(true, std::memory_order_release);
        __android_log_print(ANDROID_LOG_INFO, kTag,
            "opened EXCLUSIVE %d Hz enc=%d ch=%d device=%d mmap=%d buffer=%d frames",
            sampleRate, encoding, channelCount, deviceId_, mmapUsed_ ? 1 : 0, deviceBufferFrames_);
        return kOpenOk;
    } catch (...) {
        closeLocked();
        return kOpenFailed;
    }
}

void DirectOutputStream::close() noexcept {
    std::lock_guard<std::mutex> guard(lock_);
    closeLocked();
}

void DirectOutputStream::closeLocked() noexcept {
    alive_.store(false, std::memory_order_release);
    started_.store(false, std::memory_order_release);
    if (stream_ != nullptr) {
        // AAudio close() stops the stream and waits for any running callback.
        (void) stream_->close();
        stream_.reset();
    }
    ring_.reset();
    sampleRate_ = 0;
    channelCount_ = 0;
    encoding_ = -1;
    bytesPerFrame_ = 0;
    deviceId_ = 0;
    deviceBufferFrames_ = 0;
    mmapUsed_ = false;
}

bool DirectOutputStream::start() noexcept {
    std::lock_guard<std::mutex> guard(lock_);
    if (stream_ == nullptr || !alive_.load(std::memory_order_acquire)) return false;
    if (started_.load(std::memory_order_acquire)) return true;
    const auto result = stream_->requestStart();
    if (result != oboe::Result::OK) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "start failed: %s", oboe::convertToText(result));
        return false;
    }
    started_.store(true, std::memory_order_release);
    return true;
}

bool DirectOutputStream::pause() noexcept {
    std::lock_guard<std::mutex> guard(lock_);
    if (stream_ == nullptr) return false;
    if (!started_.load(std::memory_order_acquire)) return true;
    // Blocking pause: once it returns, no further callback runs, so the
    // producer may touch the consumer side of the ring safely.
    const auto result = stream_->pause(kStateTimeoutNanos);
    started_.store(false, std::memory_order_release);
    hasDeliveredAudio_.store(false, std::memory_order_release);
    return result == oboe::Result::OK;
}

void DirectOutputStream::flush() noexcept {
    std::lock_guard<std::mutex> guard(lock_);
    if (ring_ == nullptr) return;
    hasDeliveredAudio_.store(false, std::memory_order_release);
    // started_ is only ever true while stream_ is open (see start()/closeLocked()).
    if (started_.load(std::memory_order_acquire)) {
        // The RT callback owns the read index: let it drop the backlog on its
        // next pass. [write] refuses input until then so no post-seek audio is
        // discarded with it.
        flushRequested_.store(true, std::memory_order_release);
        return;
    }
    ring_->clear();
    flushRequested_.store(false, std::memory_order_release);
    if (stream_ != nullptr) {
        (void) stream_->requestFlush();
    }
}

std::int32_t DirectOutputStream::write(const std::uint8_t* data,
                                       std::int32_t frames,
                                       std::int32_t inputEncoding,
                                       std::int32_t inputChannels) noexcept {
    if (data == nullptr || frames <= 0) return 0;
    if (!alive_.load(std::memory_order_acquire)) return 0;
    if (flushRequested_.load(std::memory_order_acquire)) return 0;
    auto* ring = ring_.get();
    if (ring == nullptr || bytesPerFrame_ <= 0) return 0;
    const auto inBytesPerSample = bytesPerSample(inputEncoding);
    if (inBytesPerSample == 0 || inputChannels < 1 || inputChannels > channelCount_) return 0;
    if (inputChannels != channelCount_ && !(inputChannels == 1 && channelCount_ == 2)) return 0;

    const auto writableFrames = static_cast<std::int32_t>(
        ring->availableToWrite() / static_cast<std::size_t>(bytesPerFrame_));
    const auto count = std::min(frames, writableFrames);
    if (count <= 0) return 0;

    if (inputEncoding == encoding_ && inputChannels == channelCount_) {
        const auto bytes = static_cast<std::size_t>(count) * static_cast<std::size_t>(bytesPerFrame_);
        ring->write(data, bytes);
        return count;
    }

    // Wire-format conversion (only the path Kotlin declared to the user).
    thread_local std::vector<std::uint8_t> scratch;
    const auto outBytes = static_cast<std::size_t>(count) * static_cast<std::size_t>(bytesPerFrame_);
    if (scratch.size() < outBytes) scratch.resize(outBytes);
    const auto outSample = bytesPerSample(encoding_);
    const auto inFrameBytes = inBytesPerSample * inputChannels;
    for (std::int32_t frame = 0; frame < count; ++frame) {
        const auto* inFrame = data + static_cast<std::size_t>(frame) * static_cast<std::size_t>(inFrameBytes);
        auto* outFrame = scratch.data() + static_cast<std::size_t>(frame) * static_cast<std::size_t>(bytesPerFrame_);
        for (std::int32_t ch = 0; ch < channelCount_; ++ch) {
            const auto inCh = inputChannels == 1 ? 0 : ch;
            writeSample(outFrame + ch * outSample, encoding_,
                        inFrame + inCh * inBytesPerSample, inputEncoding);
        }
    }
    ring->write(scratch.data(), outBytes);
    return count;
}

std::int64_t DirectOutputStream::bufferedFrames() const noexcept {
    auto* ring = ring_.get();
    if (ring == nullptr || bytesPerFrame_ <= 0) return 0;
    return static_cast<std::int64_t>(ring->availableToRead() / static_cast<std::size_t>(bytesPerFrame_));
}

oboe::DataCallbackResult DirectOutputStream::onAudioReady(oboe::AudioStream*,
                                                          void* audioData,
                                                          std::int32_t numFrames) {
    auto* out = static_cast<std::uint8_t*>(audioData);
    auto* ring = ring_.get();
    const auto bpf = bytesPerFrame_;
    if (out == nullptr || numFrames <= 0 || bpf <= 0) return oboe::DataCallbackResult::Continue;
    const auto wanted = static_cast<std::size_t>(numFrames) * static_cast<std::size_t>(bpf);
    if (ring == nullptr) {
        std::memset(out, 0, wanted);
        return oboe::DataCallbackResult::Continue;
    }
    if (flushRequested_.load(std::memory_order_acquire)) {
        ring->clear();
        flushRequested_.store(false, std::memory_order_release);
    }
    const auto got = ring->read(out, wanted);
    if (got < wanted) {
        // Silence keeps the exclusive stream clocked. Count each transition
        // from "playing audio" to "starved" once (end of track included).
        std::memset(out + got, 0, wanted - got);
        if (hasDeliveredAudio_.exchange(false, std::memory_order_acq_rel)) {
            underruns_.fetch_add(1, std::memory_order_acq_rel);
        }
    } else {
        hasDeliveredAudio_.store(true, std::memory_order_release);
    }
    consumedFrames_.fetch_add(static_cast<std::int64_t>(got / static_cast<std::size_t>(bpf)),
                              std::memory_order_acq_rel);
    return oboe::DataCallbackResult::Continue;
}

void DirectOutputStream::onErrorAfterClose(oboe::AudioStream*, oboe::Result error) {
    // Typically ErrorDisconnected: the route changed (headphones, Bluetooth,
    // USB). Kotlin observes isAlive()==false and falls back to the mixer.
    __android_log_print(ANDROID_LOG_WARN, kTag, "stream closed by error: %s", oboe::convertToText(error));
    alive_.store(false, std::memory_order_release);
    started_.store(false, std::memory_order_release);
}

}  // namespace lastwave::audio
