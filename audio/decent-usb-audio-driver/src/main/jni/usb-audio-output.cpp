/**
 * @file usb-audio-output.cpp
 * @brief Direct USB audio output via Linux usbdevfs isochronous transfers.
 *
 * Pipeline: a preallocated ring with an adjustable limit on in-flight URBs.
 *
 * ISO URBs with ISO_ASAP complete in FIFO order, so we use a simple ring
 * with submit/reap indices. No need to identify which URB was reaped.
 */

#include "usb-audio-output.h"

#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cerrno>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <new>
#include <unistd.h>
#include <time.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>
#include <linux/usb/ch9.h>

#ifndef USBDEVFS_URB_ISO_ASAP
#define USBDEVFS_URB_ISO_ASAP 0x02
#endif

#define TAG "UsbAudioOutput"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ── Float → PCM conversion ──────────────────────────────────────────

static inline float clampf(float v) { return v > 1.0f ? 1.0f : (v < -1.0f ? -1.0f : v); }

// Bit-perfect float→int conversion matching FFmpeg's libswresample normalization.
// FFmpeg normalizes: int / 2^N (e.g., int16 / 32768.0).
// Reconversion: float × 2^N gives exact round-trip for 16-bit and 24-bit because:
//   - 2^N is exactly representable in float32 (power of 2)
//   - float32 has 24-bit mantissa, covering int16 (16-bit) and int24 (24-bit) exactly
// Clamp after scaling to handle the asymmetry: -1.0 × 32768 = -32768 (valid min),
// but +1.0 × 32768 = 32768 (exceeds max 32767, needs clamping).

static void convertFloatToInt16(const float *src, uint8_t *dst, int n) {
    auto *out = reinterpret_cast<int16_t *>(dst);
    for (int i = 0; i < n; i++) {
        float s = clampf(src[i]) * 32768.0f;
        long v = std::lrintf(s);
        if (v > 32767L) v = 32767L;
        if (v < -32768L) v = -32768L;
        out[i] = static_cast<int16_t>(v);
    }
}
static void convertFloatToInt24(const float *src, uint8_t *dst, int n) {
    for (int i = 0; i < n; i++) {
        float s = clampf(src[i]) * 8388608.0f;
        long v = std::lrintf(s);
        if (v > 8388607L) v = 8388607L;
        if (v < -8388608L) v = -8388608L;
        dst[i*3] = v & 0xFF; dst[i*3+1] = (v>>8) & 0xFF; dst[i*3+2] = (v>>16) & 0xFF;
    }
}
static void convertFloatToInt32(const float *src, uint8_t *dst, int n) {
    auto *out = reinterpret_cast<int32_t *>(dst);
    for (int i = 0; i < n; i++) {
        // Use double: float32 can't represent 2147483648.0 exactly (needs 31 bits,
        // float32 has 24-bit mantissa). Double has 53-bit mantissa — sufficient.
        double s = (double)clampf(src[i]) * 2147483648.0;
        long long v = std::llround(s);
        if (v > 2147483647LL) v = 2147483647LL;
        if (v < -2147483648LL) v = -2147483648LL;
        out[i] = static_cast<int32_t>(v);
    }
}

// ── Ring buffer management ──────────────────────────────────────────

constexpr int kMaxBufferMs = 480;

static int packetPeriodMicroframes(const UsbAudioContext *ctx) {
    // Isochronous bInterval is an exponent in frames (full speed) or
    // microframes (high speed and faster).
    const int exponent = std::clamp(ctx->dataInterval, 1, 16) - 1;
    return (ctx->usbSpeed == USB_SPEED_FULL ? 8 : 1) << exponent;
}

static int bufferUrbCount(const UsbAudioContext *ctx, int bufferMs) {
    const int urbUs = packetPeriodMicroframes(ctx) * 125 * USB_AUDIO_PACKETS_PER_URB;
    const int requestedUs = std::clamp(bufferMs, 40, kMaxBufferMs) * 1000;
    return (requestedUs + urbUs - 1) / urbUs;
}

static bool allocRing(UsbAudioContext *ctx, int bufferMs) {
    ctx->ringCapacity = bufferUrbCount(ctx, kMaxBufferMs);
    ctx->targetUrbs = bufferUrbCount(ctx, bufferMs);
    const int maxPacketBytes = USB_AUDIO_URB_BUFFER_SIZE / USB_AUDIO_PACKETS_PER_URB;
    ctx->slotBytes = ctx->maxPacketSize > 0
            ? std::min(ctx->maxPacketSize, maxPacketBytes) * USB_AUDIO_PACKETS_PER_URB
            : USB_AUDIO_URB_BUFFER_SIZE;
    ctx->ring = static_cast<UrbSlot *>(calloc(ctx->ringCapacity, sizeof(UrbSlot)));
    ctx->ringPcm = static_cast<uint8_t *>(malloc(
            static_cast<size_t>(ctx->ringCapacity) * ctx->slotBytes));
    return ctx->ring && ctx->ringPcm;
}

static void freeRing(UsbAudioContext *ctx) {
    free(ctx->ringPcm);
    free(ctx->ring);
}

static void setBufferDuration(UsbAudioContext *ctx, int bufferMs) {
    ctx->targetUrbs = bufferUrbCount(ctx, bufferMs);
}

// ── Continuous feedback ─────────────────────────────────────────────

/**
 * Allocate and initialize the feedback URB (called once at creation).
 */
static bool allocFeedbackUrb(UsbAudioContext *ctx) {
    size_t sz = sizeof(struct usbdevfs_urb) + sizeof(struct usbdevfs_iso_packet_desc);
    ctx->feedbackUrb = (struct usbdevfs_urb *)calloc(1, sz);
    if (!ctx->feedbackUrb) return false;
    ctx->feedbackInFlight = false;
    memset(ctx->feedbackBuffer, 0, sizeof(ctx->feedbackBuffer));
    return true;
}

/**
 * Submit the feedback URB to read the DAC's async feedback endpoint.
 * The endpoint returns 4 bytes (Q16.16 format) reporting the DAC's
 * actual frames-per-microframe.
 */
static bool submitFeedbackUrb(UsbAudioContext *ctx) {
    if (!ctx->continuousFeedback || ctx->endpointFeedback <= 0 || !ctx->feedbackUrb ||
        ctx->feedbackInFlight)
        return false;

    struct usbdevfs_urb *u = ctx->feedbackUrb;
    size_t sz = sizeof(struct usbdevfs_urb) + sizeof(struct usbdevfs_iso_packet_desc);
    memset(u, 0, sz);
    u->type = USBDEVFS_URB_TYPE_ISO;
    u->flags = USBDEVFS_URB_ISO_ASAP;
    u->endpoint = (unsigned char)ctx->endpointFeedback;
    u->buffer = ctx->feedbackBuffer;
    u->buffer_length = ctx->feedbackPacketSize;
    u->number_of_packets = 1;
    u->iso_frame_desc[0].length = ctx->feedbackPacketSize;

    if (ioctl(ctx->fd, USBDEVFS_SUBMITURB, u) < 0) {
        LOGW("submitFeedbackUrb: failed errno=%d (%s)", errno, strerror(errno));
        return false;
    }
    ctx->feedbackInFlight = true;
    return true;
}

/**
 * Process a completed feedback URB: parse the DAC's clock rate and
 * update calibratedFpmf for real-time clock tracking.
 */
static int64_t g_feedbackCount = 0;

static void handleFeedbackCompletion(UsbAudioContext *ctx) {
    ctx->feedbackInFlight = false;
    g_feedbackCount++;

    if (ctx->feedbackUrb->iso_frame_desc[0].actual_length >=
            static_cast<unsigned>(ctx->feedbackPacketSize)) {
        uint8_t *fb = ctx->feedbackBuffer;
        uint32_t raw = fb[0] | (fb[1] << 8) | (fb[2] << 16);
        if (ctx->feedbackPacketSize == 4) raw |= static_cast<uint32_t>(fb[3]) << 24;
        double newFpmf = raw / (ctx->feedbackPacketSize == 3 ? 16384.0 : 65536.0);

        // Feedback describes its service interval; scale to the data
        // endpoint's interval before sizing packets.
        const double period = ctx->usbSpeed == USB_SPEED_FULL
                ? packetPeriodMicroframes(ctx) / 8.0
                : packetPeriodMicroframes(ctx);
        const double nominal = ctx->sampleRate * packetPeriodMicroframes(ctx) / 8000.0;
        newFpmf *= period;
        if (newFpmf > nominal * 0.99 && newFpmf < nominal * 1.01) {
            ctx->calibratedFpmf = newFpmf;
            // Log only every 10000th feedback — logging in the audio path is expensive
            if (g_feedbackCount % 10000 == 0) {
                LOGI("Feedback #%lld: fpmf=%.4f (%.1f Hz)",
                     (long long)g_feedbackCount, newFpmf, newFpmf * 8000.0);
            }
        }
    }

    // Resubmit for continuous tracking, but never while tearing the stream down.
    if (ctx->running.load()) submitFeedbackUrb(ctx);
}

// ── ISO URB submission / reap ───────────────────────────────────────

/**
 * Submit the URB at ring[submitIdx] with the given packet layout.
 * @param numPackets  Actual number of packets (no zero-length padding)
 * Returns 0 on success, -1 on error.
 */
static int submitRingUrb(UsbAudioContext *ctx, const int *pktSizes, int numPackets, int totalBytes) {
    UrbSlot *slot = &ctx->ring[ctx->submitIdx];
    struct usbdevfs_urb *u = slot->urb();

    // Clear the full URB struct including iso_frame_desc (stale actual_length/status)
    size_t clearSize = sizeof(struct usbdevfs_urb) +
                       numPackets * sizeof(struct usbdevfs_iso_packet_desc);
    memset(u, 0, clearSize);

    u->type = USBDEVFS_URB_TYPE_ISO;
    u->flags = USBDEVFS_URB_ISO_ASAP;
    u->endpoint = (unsigned char)ctx->endpointOut;
    u->buffer = ctx->ringPcm + ctx->submitIdx * ctx->slotBytes;
    u->buffer_length = totalBytes;
    u->number_of_packets = numPackets;
    for (int i = 0; i < numPackets; i++)
        u->iso_frame_desc[i].length = (unsigned)pktSizes[i];

    int ret = ioctl(ctx->fd, USBDEVFS_SUBMITURB, u);
    if (ret < 0) {
        LOGE("SUBMITURB FAILED slot=%d ep=0x%02x bytes=%d pkts=%d errno=%d (%s)",
             ctx->submitIdx, ctx->endpointOut, totalBytes, numPackets, errno, strerror(errno));
        return -1;
    }

    ctx->submitIdx = (ctx->submitIdx + 1) % ctx->ringCapacity;
    ctx->urbsInFlight++;
    return 0;
}

/**
 * Reap the oldest submitted URB (at ring[reapIdx]).
 * ISO URBs with ISO_ASAP complete in FIFO order, so we always reap
 * the oldest one.
 *
 * IMPORTANT: REAPURBNDELAY returns ANY completed URB — audio or feedback.
 * If we get a feedback URB, we process it (update fpmf, resubmit) and
 * retry. This implements continuous real-time clock calibration on the
 * same thread as audio URB recycling — no second thread, no extra
 * synchronization.
 *
 * @param timeoutMs  Maximum wait in milliseconds
 * @return 0 = success (audio URB reaped), -1 = error, -2 = timeout
 */
static int reapOldestUrb(UsbAudioContext *ctx, int timeoutMs) {
    struct usbdevfs_urb *c = nullptr;

    // Poll with 125µs interval matching USB high-speed microframe timing.
    int iterations = timeoutMs * 8;  // 8 iterations per ms (125µs each)
    for (int i = 0; i < iterations; i++) {
        int ret = ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &c);
        if (ret == 0 && c != nullptr) {
            // Check if this is a feedback URB (not an audio URB)
            if (c == ctx->feedbackUrb) {
                handleFeedbackCompletion(ctx);
                c = nullptr;  // retry — we need an audio URB
                continue;
            }
            // Audio URB reaped successfully
            ctx->reapIdx = (ctx->reapIdx + 1) % ctx->ringCapacity;
            ctx->urbsInFlight--;
            return 0;
        }
        if (ret < 0 && errno == EINTR) continue;
        if (ret < 0 && errno != EAGAIN) {
            LOGE("reapOldestUrb: error errno=%d (%s)", errno, strerror(errno));
            return -1;
        }
        usleep(125);  // 125µs = 1 USB microframe
    }
    return -2;  // timeout
}

// The writer has stopped. Keep URB memory alive until every completion has
// been reaped or the caller closes the USB connection.
static bool drainAllUrbs(UsbAudioContext *ctx) {
    if (ctx->feedbackInFlight) {
        ioctl(ctx->fd, USBDEVFS_DISCARDURB, ctx->feedbackUrb);
    }

    while (ctx->urbsInFlight > 0 && reapOldestUrb(ctx, 500) == 0) {}

    for (int i = 0; i < ctx->urbsInFlight; ++i) {
        const int slotIdx = (ctx->reapIdx + i) % ctx->ringCapacity;
        ioctl(ctx->fd, USBDEVFS_DISCARDURB, ctx->ring[slotIdx].urb());
    }

    int idleWaits = 0;
    while (ctx->urbsInFlight > 0 || ctx->feedbackInFlight) {
        struct usbdevfs_urb *completed = nullptr;
        if (ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &completed) == 0) {
            if (ctx->feedbackInFlight && completed == ctx->feedbackUrb) {
                ctx->feedbackInFlight = false;
                continue;
            }
            if (completed && ctx->urbsInFlight > 0) {
                --ctx->urbsInFlight;
                continue;
            }
        } else if (errno != EAGAIN && errno != EINTR) {
            return false;
        }
        if (++idleWaits == 500) return false;
        usleep(1000);
    }
    return true;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioCreate(
        JNIEnv *, jobject, jint fd, jint ifId, jint epOut, jint epFb,
        jint rate, jint ch, jint bits, jint maxPkt, jint dataInterval,
        jint feedbackPacketSize, jint feedbackInterval, jboolean continuousFeedback,
        jint bufferMs) {
    const int usbSpeed = ioctl(fd, USBDEVFS_GET_SPEED, 0);
    if (usbSpeed != USB_SPEED_FULL && usbSpeed != USB_SPEED_HIGH &&
        usbSpeed != USB_SPEED_SUPER && usbSpeed != USB_SPEED_SUPER_PLUS) {
        LOGE("Create: unsupported or unknown USB bus speed=%d; using Android audio output",
             usbSpeed);
        return 0;
    }
    LOGI("Create: fd=%d ep=0x%02x rate=%d ch=%d bits=%d maxPkt=%d",
         fd, epOut, rate, ch, bits, maxPkt);
    auto *ctx = new(std::nothrow) UsbAudioContext();
    if (!ctx) return 0;
    ctx->fd = fd;
    ctx->interfaceId = ifId;
    ctx->endpointOut = epOut;
    ctx->endpointFeedback = epFb;
    ctx->sampleRate = rate;
    ctx->channelCount = ch;
    ctx->bitDepth = bits;
    ctx->bytesPerSample = bits / 8;
    ctx->bytesPerFrame = (bits / 8) * ch;
    ctx->maxPacketSize = maxPkt;
    ctx->dataInterval = dataInterval > 0 ? dataInterval : 1;
    ctx->feedbackPacketSize = feedbackPacketSize == 3 || feedbackPacketSize == 4
            ? feedbackPacketSize : 4;
    ctx->feedbackInterval = feedbackInterval > 0 ? feedbackInterval : ctx->dataInterval;
    ctx->usbSpeed = usbSpeed;
    LOGI("Create descriptors: speed=%d dataInterval=%d feedbackPacket=%d "
         "feedbackInterval=%d maxPacket=%d", ctx->usbSpeed, ctx->dataInterval,
         ctx->feedbackPacketSize, ctx->feedbackInterval, ctx->maxPacketSize);
    ctx->running.store(false);
    ctx->transferBuffer = nullptr;
    ctx->transferBufferCapacity = 0;
    ctx->framesWritten = 0;
    ctx->interfaceClaimed = true;
    ctx->submitIdx = 0;
    ctx->reapIdx = 0;
    ctx->urbsInFlight = 0;
    ctx->frameAccumulator = 0.0;
    ctx->calibratedFpmf = rate * packetPeriodMicroframes(ctx) / 8000.0;
    ctx->residualBytes = 0;
    memset(ctx->residualBuffer, 0, sizeof(ctx->residualBuffer));
    ctx->feedbackUrb = nullptr;
    ctx->feedbackInFlight = false;
    ctx->continuousFeedback = continuousFeedback == JNI_TRUE && epFb > 0;
    memset(ctx->feedbackBuffer, 0, sizeof(ctx->feedbackBuffer));

    if (!allocRing(ctx, bufferMs)) {
        LOGE("USB ring allocation failed");
        freeRing(ctx);
        delete ctx;
        return 0;
    }

    if (!allocFeedbackUrb(ctx)) {
        freeRing(ctx);
        delete ctx;
        return 0;
    }

    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeSetBufferDuration(
        JNIEnv *, jobject, jlong h, jint bufferMs) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (ctx) setBufferDuration(ctx, bufferMs);
}

JNIEXPORT jboolean JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioSetAltSetting(
        JNIEnv *, jobject, jlong h, jint alt) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx) return JNI_FALSE;
    struct usbdevfs_setinterface si = {};
    si.interface = (unsigned)ctx->interfaceId;
    si.altsetting = (unsigned)alt;
    int r = ioctl(ctx->fd, USBDEVFS_SETINTERFACE, &si);
    LOGI("setAlt(%d,%d): ret=%d errno=%d", ctx->interfaceId, alt, r, errno);
    return r >= 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioSetSampleRate(
        JNIEnv *, jobject, jlong h, jint rate, jint csId) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx) return JNI_FALSE;
    ctx->sampleRate = rate;
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioStart(
        JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx || ctx->urbsInFlight > 0 || ctx->feedbackInFlight) return JNI_FALSE;
    ctx->running.store(true);
    ctx->framesWritten = 0;
    ctx->submitIdx = 0;
    ctx->reapIdx = 0;
    ctx->urbsInFlight = 0;
    ctx->frameAccumulator = 0.0;
    ctx->residualBytes = 0;

    ctx->calibratedFpmf = ctx->sampleRate * packetPeriodMicroframes(ctx) / 8000.0;

    // Starts at the nominal rate; each completion in the reap loop refines it.
    submitFeedbackUrb(ctx);

    LOGI("Start: rate=%d ch=%d bits=%d ring=%d slots×%dpkt fpmf=%.4f feedback=%s",
         ctx->sampleRate, ctx->channelCount, ctx->bitDepth,
         ctx->targetUrbs, USB_AUDIO_PACKETS_PER_URB,
         ctx->calibratedFpmf,
         ctx->feedbackInFlight ? "continuous" : "off");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioWrite(
        JNIEnv *env, jobject, jlong h, jfloatArray pcm) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx || !ctx->running.load()) return;

    jint totalSamples = env->GetArrayLength(pcm);
    if (totalSamples <= 0) return;
    int totalFrames = totalSamples / ctx->channelCount;
    int totalBytes = totalSamples * ctx->bytesPerSample;

    // Resize transfer buffer if needed
    if (!ctx->transferBuffer || ctx->transferBufferCapacity < totalBytes) {
        free(ctx->transferBuffer);
        ctx->transferBuffer = (uint8_t *)malloc(totalBytes);
        ctx->transferBufferCapacity = totalBytes;
    }

    struct timespec writeStart, writeEnd;
    clock_gettime(CLOCK_MONOTONIC, &writeStart);
    static long writeCallCount = 0;
    writeCallCount++;

    // Convert float PCM to target bit depth
    jfloat *f = env->GetFloatArrayElements(pcm, nullptr);
    if (!f) return;
    switch (ctx->bitDepth) {
        case 16: convertFloatToInt16(f, ctx->transferBuffer, totalSamples); break;
        case 24: convertFloatToInt24(f, ctx->transferBuffer, totalSamples); break;
        case 32: convertFloatToInt32(f, ctx->transferBuffer, totalSamples); break;
        default: env->ReleaseFloatArrayElements(pcm, f, JNI_ABORT); return;
    }
    env->ReleaseFloatArrayElements(pcm, f, JNI_ABORT);

    // Submit converted PCM to USB pipeline (shared with raw path)
    submitPcmToUrbs(ctx, ctx->transferBuffer, totalBytes);

    ctx->framesWritten += totalFrames;

    clock_gettime(CLOCK_MONOTONIC, &writeEnd);
    long writeUs = (writeEnd.tv_sec - writeStart.tv_sec) * 1000000L +
                   (writeEnd.tv_nsec - writeStart.tv_nsec) / 1000L;
    // A write normally blocks for about as long as the audio it carries
    // (the URB ring is full, so the DAC paces us). Logging every call over
    // 10ms therefore logged EVERY write (~43 lines/s at 44.1k), burning CPU
    // and flooding the diagnostics buffer. Only report real stalls (longer
    // than twice the block's own duration plus 20ms) or a periodic heartbeat.
    const long blockUs = ctx->sampleRate > 0
            ? static_cast<long>((int64_t)totalFrames * 1000000LL / ctx->sampleRate) : 0L;
    if (writeUs > blockUs * 2 + 20000L || writeCallCount % 2000 == 0) {
        LOGI("nativeWrite #%ld: %d samples, %ldus (%.1fms), inflight=%d",
             writeCallCount, totalSamples, writeUs, writeUs / 1000.0, ctx->urbsInFlight);
    }

    // Periodic logging (~once per second)
    if (ctx->framesWritten % ctx->sampleRate < (int64_t)totalFrames) {
        LOGI("Write: %lld frames (~%.0f sec) inflight=%d fpmf=%.4f (%.1f Hz)",
             (long long)ctx->framesWritten, (double)ctx->framesWritten/ctx->sampleRate,
             ctx->urbsInFlight, ctx->calibratedFpmf, ctx->calibratedFpmf * 8000.0);
    }
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioStop(
        JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx) return;
    ctx->running.store(false);
    LOGI("Stop: %lld frames, %d URBs in flight",
         (long long)ctx->framesWritten, ctx->urbsInFlight);
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativePump(
        JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx || !ctx->running.load()) return;
    // Reap completions while ExoPlayer is fetching the next bytes. If nobody
    // reaps during that gap, the ring stays full and the next write times out.
    for (int i = 0; i < 32 && (ctx->urbsInFlight > 0 || ctx->feedbackInFlight); i++) {
        struct usbdevfs_urb *c = nullptr;
        int ret = ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &c);
        if (ret == 0 && c != nullptr) {
            if (c == ctx->feedbackUrb) {
                handleFeedbackCompletion(ctx);
                continue;
            }
            ctx->reapIdx = (ctx->reapIdx + 1) % ctx->ringCapacity;
            if (ctx->urbsInFlight > 0) {
                ctx->urbsInFlight--;
            }
            continue;
        }
        break;
    }
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeFlush(
        JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx) return;
    ctx->frameAccumulator = 0.0;
    ctx->residualBytes = 0;
    memset(ctx->residualBuffer, 0, sizeof(ctx->residualBuffer));
    LOGI("Flush: frameAccumulator and residual reset (framesWritten=%lld)",
         (long long)ctx->framesWritten);
}

JNIEXPORT jboolean JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeDrainUrbs(
        JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx) return JNI_TRUE;
    ctx->running.store(false);
    return drainAllUrbs(ctx) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioDestroy(
        JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx) return;
    freeRing(ctx);
    free(ctx->feedbackUrb);
    free(ctx->transferBuffer);
    LOGI("Destroy: %lld frames total", (long long)ctx->framesWritten);
    delete ctx;
}

JNIEXPORT jboolean JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeIsRunning(
        JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx) return JNI_FALSE;
    return ctx->running.load() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeGetFramesWritten(
        JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx) return 0;
    return (jlong)ctx->framesWritten;
}

JNIEXPORT jint JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeUsbSpeed(
        JNIEnv *, jclass, jint fd) {
    return ioctl(fd, USBDEVFS_GET_SPEED, 0);
}

JNIEXPORT jint JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAttachKernelDriver(
        JNIEnv *, jclass, jint fd, jint interfaceNum) {
    LOGI("USBDEVFS_CONNECT fd=%d iface=%d", fd, interfaceNum);
    struct usbdevfs_ioctl ctrl;
    memset(&ctrl, 0, sizeof(ctrl));
    ctrl.ifno = interfaceNum;
    ctrl.ioctl_code = USBDEVFS_CONNECT;
    ctrl.data = nullptr;
    int ret = ioctl(fd, USBDEVFS_IOCTL, &ctrl);
    if (ret < 0 && errno != EBUSY) {
        LOGW("USBDEVFS_CONNECT failed iface=%d errno=%d", interfaceNum, errno);
        return ret;
    }
    LOGI("USBDEVFS_CONNECT OK iface=%d (ret=%d errno=%d)", interfaceNum, ret, errno);
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeUsbReset(
        JNIEnv *, jclass, jint fd) {
    LOGI("USBDEVFS_RESET fd=%d", fd);
    int ret = ioctl(fd, USBDEVFS_RESET, 0);
    if (ret < 0) { LOGE("RESET FAILED errno=%d", errno); return ret; }
    LOGI("RESET OK (descriptor-directed claiming handled in UsbAudioDevice.openDevice)");
    return 0;
}

} // extern "C" — pause for non-JNI functions used by native-audio-engine

// ── Integer padding (lossless, zero float) ──────────────────────────

// 16-bit → 24-bit packed: zero-fill LSB byte, copy 2 bytes
void padInt16ToInt24(const uint8_t *src, uint8_t *dst, int numSamples) {
    for (int i = 0; i < numSamples; i++) {
        dst[i * 3]     = 0;
        dst[i * 3 + 1] = src[i * 2];
        dst[i * 3 + 2] = src[i * 2 + 1];
    }
}

// 16-bit → 32-bit: shift left 16
void padInt16ToInt32(const uint8_t *src, uint8_t *dst, int numSamples) {
    auto *out = reinterpret_cast<int32_t *>(dst);
    auto *in16 = reinterpret_cast<const int16_t *>(src);
    for (int i = 0; i < numSamples; i++) {
        out[i] = static_cast<int32_t>(static_cast<uint32_t>(static_cast<int32_t>(in16[i])) << 16);
    }
}

// int32 (24-bit sign-extended from libFLAC) → 32-bit: shift left 8
void shiftInt32From24(const uint8_t *src, uint8_t *dst, int numSamples) {
    auto *out = reinterpret_cast<int32_t *>(dst);
    auto *in32 = reinterpret_cast<const int32_t *>(src);
    for (int i = 0; i < numSamples; i++) {
        out[i] = static_cast<int32_t>(static_cast<uint32_t>(in32[i]) << 8);
    }
}

// 24-bit packed (3 bytes/sample) → 32-bit: read 3 bytes, sign-extend, shift left 8
void padInt24ToInt32(const uint8_t *src, uint8_t *dst, int numSamples) {
    auto *out = reinterpret_cast<int32_t *>(dst);
    for (int i = 0; i < numSamples; i++) {
        uint32_t b0 = static_cast<uint32_t>(src[i * 3]);
        uint32_t b1 = static_cast<uint32_t>(src[i * 3 + 1]);
        uint32_t b2 = static_cast<uint32_t>(src[i * 3 + 2]);
        uint32_t u = (b2 << 16) | (b1 << 8) | b0;
        if (u & 0x800000) {
            u |= 0xFF000000;
        }
        out[i] = static_cast<int32_t>(u << 8);
    }
}

// 32-bit → 24-bit packed (3 bytes/sample)
void packInt32ToInt24(const uint8_t *src, uint8_t *dst, int numSamples) {
    // Take the top 3 of 4 bytes (standard 32→24 truncation, equivalent to >>8).
    // Do NOT sniff for "right-aligned 24-bit" and take the low 3 bytes: that
    // discards the MSB of a full-scale 32-bit sample and swings level wildly.
    for (int i = 0; i < numSamples; i++) {
        dst[i * 3]     = src[i * 4 + 1];
        dst[i * 3 + 1] = src[i * 4 + 2];
        dst[i * 3 + 2] = src[i * 4 + 3];
    }
}

// 24-bit packed → 16-bit (for 16-bit-only DACs)
void packInt24ToInt16(const uint8_t *src, uint8_t *dst, int numSamples) {
    for (int i = 0; i < numSamples; i++) {
        dst[i * 2]     = src[i * 3 + 1];
        dst[i * 2 + 1] = src[i * 3 + 2];
    }
}

// 32-bit → 16-bit (for 16-bit-only DACs)
void packInt32ToInt16(const uint8_t *src, uint8_t *dst, int numSamples) {
    for (int i = 0; i < numSamples; i++) {
        dst[i * 2]     = src[i * 4 + 2];
        dst[i * 2 + 1] = src[i * 4 + 3];
    }
}

// ── Shared URB submission logic ─────────────────────────────────────

/**
 * Submit PCM data (already in the target bit depth) to the USB pipeline.
 * Used by both nativeUsbAudioWrite (float path) and nativeUsbAudioWriteRaw.
 */
void submitPcmToUrbs(UsbAudioContext *ctx, const uint8_t *pcmData, int totalBytes) {
    // Prepend any residual bytes from the previous call to avoid
    // micro-discontinuities (pops) at buffer boundaries.
    const uint8_t *data = pcmData;
    int dataLen = totalBytes;
    uint8_t *mergedBuf = nullptr;

    if (ctx->residualBytes > 0 && totalBytes > 0) {
        dataLen = ctx->residualBytes + totalBytes;
        mergedBuf = (uint8_t *)malloc(dataLen);
        if (!mergedBuf) {
            LOGE("USB PCM buffer allocation failed");
            ctx->running.store(false);
            return;
        }
        memcpy(mergedBuf, ctx->residualBuffer, ctx->residualBytes);
        memcpy(mergedBuf + ctx->residualBytes, pcmData, totalBytes);
        data = mergedBuf;
        ctx->residualBytes = 0;
    }

    int offset = 0;
    // Use calibrated fpmf from DAC's async feedback endpoint (read at start).
    // This matches the DAC's actual hardware clock instead of the nominal rate.
    double fpmf = ctx->calibratedFpmf;

    while (offset < dataLen && ctx->running.load()) {
        int pktSizes[USB_AUDIO_PACKETS_PER_URB];
        int numPackets = 0;
        int urbBytes = 0;
        const double savedAccumulator = ctx->frameAccumulator;

        for (int p = 0; p < USB_AUDIO_PACKETS_PER_URB; p++) {
            int remaining = dataLen - offset - urbBytes;
            if (remaining <= 0) break;

            ctx->frameAccumulator += fpmf;
            int frames = (int)ctx->frameAccumulator;
            ctx->frameAccumulator -= frames;
            int b = frames * ctx->bytesPerFrame;

            if (ctx->maxPacketSize > 0 && b > ctx->maxPacketSize) {
                LOGE("Packet exceeds endpoint wMaxPacketSize: bytes=%d max=%d rate=%d interval=%d",
                     b, ctx->maxPacketSize, ctx->sampleRate, ctx->dataInterval);
                ctx->running.store(false);
                break;
            }

            if (urbBytes + b > ctx->slotBytes) {
                break;
            }

            if (b > remaining) {
                // Not enough data for a full packet — don't truncate.
                // Save remaining data for next call to avoid short ISO packets
                // that create silence microframes in the xHCI schedule.
                break;
            }

            pktSizes[p] = b;
            urbBytes += b;
            numPackets++;
        }
        if (numPackets <= 0 || urbBytes <= 0) {
            ctx->frameAccumulator = savedAccumulator;
            break;
        }

        // Never submit short URBs (< full packet count). Short URBs create
        // empty ISO microframes in the xHCI schedule — the DAC receives
        // silence for those microframes → audible click/pop.
        // Instead, roll back frameAccumulator and save leftover data for the next write() call.
        if (numPackets < USB_AUDIO_PACKETS_PER_URB) {
            ctx->frameAccumulator = savedAccumulator;
            int leftover = dataLen - offset;
            if (leftover > 0 && leftover <= (int)sizeof(ctx->residualBuffer)) {
                memcpy(ctx->residualBuffer, data + offset, leftover);
                ctx->residualBytes = leftover;
            }
            break;
        }

        while (ctx->running.load() && ctx->urbsInFlight >= ctx->targetUrbs) {
            int result = reapOldestUrb(ctx, 200);
            if (result == -2 && ctx->running.load()) {
                result = reapOldestUrb(ctx, 300);
            }
            if (result == -2) {
                LOGW("submitPcmToUrbs: reap timeout, inflight=%d", ctx->urbsInFlight);
            } else if (result < 0) {
                ctx->running.store(false);
            }
        }
        if (!ctx->running.load()) {
            free(mergedBuf);
            return;
        }

        uint8_t *slotPcm = ctx->ringPcm + ctx->submitIdx * ctx->slotBytes;
        memcpy(slotPcm, data + offset, urbBytes);

        if (submitRingUrb(ctx, pktSizes, numPackets, urbBytes) < 0) {
            LOGE("submitPcmToUrbs: submit failed, stopping stream");
            ctx->running.store(false);
            free(mergedBuf);
            return;
        }
        offset += urbBytes;
    }

    // Save any leftover bytes for the next call.
    // This catches sub-frame leftovers (the short-URB case is handled above).
    int leftover = dataLen - offset;
    if (leftover > 0 && leftover <= (int)sizeof(ctx->residualBuffer) && ctx->residualBytes == 0) {
        memcpy(ctx->residualBuffer, data + offset, leftover);
        ctx->residualBytes = leftover;
    }

    free(mergedBuf);
}

// ── Raw bytes write (no float, for libFLAC integer path) ────────────

extern "C" {  // resume JNI functions

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioWriteRaw(
        JNIEnv *env, jobject, jlong h, jbyteArray pcm, jint inputBitDepth) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx || !ctx->running.load()) return;

    jint inputBytes = env->GetArrayLength(pcm);
    if (inputBytes <= 0) return;

    int inputBps = inputBitDepth / 8;
    int totalSamples = inputBytes / inputBps;
    int totalFrames = totalSamples / ctx->channelCount;
    int outputBytes = totalSamples * ctx->bytesPerSample;

    // Resize transfer buffer if needed
    if (!ctx->transferBuffer || ctx->transferBufferCapacity < outputBytes) {
        free(ctx->transferBuffer);
        ctx->transferBuffer = (uint8_t *)malloc(outputBytes);
        ctx->transferBufferCapacity = outputBytes;
    }

    jbyte *rawData = env->GetByteArrayElements(pcm, nullptr);
    if (!rawData) return;

    // Bit-depth matching: pad/pack input to DAC's bit depth (lossless integer ops)
    if (inputBitDepth == 32 && ctx->bitDepth == 32) {
        // Pass through unchanged. Do NOT sniff for "right-aligned 24-bit"
        // samples and shift them up 8 bits — that raises level ~48 dB and
        // clips/distorts. The producer must deliver correctly scaled 32-bit.
        memcpy(ctx->transferBuffer, rawData, inputBytes);
    } else if (inputBitDepth == ctx->bitDepth) {
        // Same bit depth (16->16 or 24->24): zero-copy
        memcpy(ctx->transferBuffer, rawData, inputBytes);
    } else if (inputBitDepth == 16 && ctx->bitDepth == 24) {
        padInt16ToInt24((uint8_t *)rawData, ctx->transferBuffer, totalSamples);
    } else if (inputBitDepth == 16 && ctx->bitDepth == 32) {
        padInt16ToInt32((uint8_t *)rawData, ctx->transferBuffer, totalSamples);
    } else if (inputBitDepth == 24 && ctx->bitDepth == 32) {
        // 24-bit packed (3 bytes/sample) → 32-bit: sign-extend + shift left 8
        padInt24ToInt32((uint8_t *)rawData, ctx->transferBuffer, totalSamples);
    } else if (inputBitDepth == 32 && ctx->bitDepth == 24) {
        packInt32ToInt24((uint8_t *)rawData, ctx->transferBuffer, totalSamples);
    } else if (inputBitDepth == 24 && ctx->bitDepth == 16) {
        packInt24ToInt16((uint8_t *)rawData, ctx->transferBuffer, totalSamples);
    } else if (inputBitDepth == 32 && ctx->bitDepth == 16) {
        packInt32ToInt16((uint8_t *)rawData, ctx->transferBuffer, totalSamples);
    } else {
        LOGE("WriteRaw: unsupported bit-depth conversion %d → %d", inputBitDepth, ctx->bitDepth);
        env->ReleaseByteArrayElements(pcm, rawData, JNI_ABORT);
        return;
    }

    env->ReleaseByteArrayElements(pcm, rawData, JNI_ABORT);

    // Submit to USB pipeline
    submitPcmToUrbs(ctx, ctx->transferBuffer, outputBytes);

    ctx->framesWritten += totalFrames;
    if (ctx->framesWritten % ctx->sampleRate < (int64_t)totalFrames) {
        LOGI("WriteRaw: %lld frames (~%.0f sec) inflight=%d inputBits=%d fpmf=%.4f fb#%lld",
             (long long)ctx->framesWritten, (double)ctx->framesWritten/ctx->sampleRate,
             ctx->urbsInFlight, inputBitDepth, ctx->calibratedFpmf, (long long)g_feedbackCount);
    }
}

} // extern "C"
