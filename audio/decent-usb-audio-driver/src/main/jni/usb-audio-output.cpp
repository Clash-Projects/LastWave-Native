/**
 * @file usb-audio-output.cpp
 * @brief Direct USB audio output via Linux usbdevfs isochronous transfers.
 *
 * Pipeline: pre-allocated ring buffer of USB_AUDIO_NUM_URBS (= 80) URBs.
 * No malloc/free during streaming — completely avoids ARM MTE pointer tag
 * issues on Samsung devices.
 *
 * ISO URBs with ISO_ASAP complete in FIFO order, so we use a simple ring
 * with submit/reap indices. No need to identify which URB was reaped.
 */

#include "usb-audio-output.h"

#include <jni.h>
#include <android/log.h>
#include <cerrno>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <new>
#include <unistd.h>
#include <time.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>

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
        if (s > 32767.0f) s = 32767.0f;
        if (s < -32768.0f) s = -32768.0f;
        out[i] = (int16_t)s;
    }
}
static void convertFloatToInt24(const float *src, uint8_t *dst, int n) {
    for (int i = 0; i < n; i++) {
        float s = clampf(src[i]) * 8388608.0f;
        if (s > 8388607.0f) s = 8388607.0f;
        if (s < -8388608.0f) s = -8388608.0f;
        int32_t v = (int32_t)s;
        dst[i*3] = v & 0xFF; dst[i*3+1] = (v>>8) & 0xFF; dst[i*3+2] = (v>>16) & 0xFF;
    }
}
static void convertFloatToInt32(const float *src, uint8_t *dst, int n) {
    auto *out = reinterpret_cast<int32_t *>(dst);
    for (int i = 0; i < n; i++) {
        // Use double: float32 can't represent 2147483648.0 exactly (needs 31 bits,
        // float32 has 24-bit mantissa). Double has 53-bit mantissa — sufficient.
        double s = (double)clampf(src[i]) * 2147483648.0;
        if (s > 2147483647.0) s = 2147483647.0;
        if (s < -2147483648.0) s = -2147483648.0;
        out[i] = (int32_t)s;
    }
}

// ── Ring buffer management ──────────────────────────────────────────

/**
 * Allocate all URB slots in the ring buffer.
 * Called once at stream creation. All memory stays alive until destroy.
 */
static bool allocRing(UsbAudioContext *ctx) {
    size_t urbStructSize = sizeof(struct usbdevfs_urb) +
                           USB_AUDIO_PACKETS_PER_URB * sizeof(struct usbdevfs_iso_packet_desc);
    for (int i = 0; i < USB_AUDIO_NUM_URBS; i++) {
        ctx->ring[i].urb = (struct usbdevfs_urb *)calloc(1, urbStructSize);
        ctx->ring[i].buffer = (uint8_t *)malloc(USB_AUDIO_URB_BUFFER_SIZE);
        ctx->ring[i].dataLength = 0;
        if (!ctx->ring[i].urb || !ctx->ring[i].buffer) {
            LOGE("allocRing: OOM at slot %d", i);
            // Free what we allocated
            for (int j = 0; j <= i; j++) {
                free(ctx->ring[j].urb);
                free(ctx->ring[j].buffer);
            }
            return false;
        }
    }
    ctx->ringAllocated = true;
    return true;
}

/**
 * Free all URB slots. Called once at stream destruction.
 */
static void freeRing(UsbAudioContext *ctx) {
    if (!ctx->ringAllocated) return;
    for (int i = 0; i < USB_AUDIO_NUM_URBS; i++) {
        free(ctx->ring[i].urb);
        free(ctx->ring[i].buffer);
        ctx->ring[i].urb = nullptr;
        ctx->ring[i].buffer = nullptr;
    }
    ctx->ringAllocated = false;
}

// ── USB helpers ─────────────────────────────────────────────────────

static double readFeedback(int fd, int ep) {
    uint8_t fb[4] = {};
    size_t sz = sizeof(struct usbdevfs_urb) + sizeof(struct usbdevfs_iso_packet_desc);
    auto *u = (struct usbdevfs_urb *)calloc(1, sz);
    if (!u) return 0;
    u->type = USBDEVFS_URB_TYPE_ISO;
    u->flags = USBDEVFS_URB_ISO_ASAP;
    u->endpoint = (unsigned char)ep;
    u->buffer = fb;
    u->buffer_length = 4;
    u->number_of_packets = 1;
    u->iso_frame_desc[0].length = 4;
    if (ioctl(fd, USBDEVFS_SUBMITURB, u) < 0) { free(u); return 0; }
    struct usbdevfs_urb *c = nullptr;
    usleep(2000);
    if (ioctl(fd, USBDEVFS_REAPURBNDELAY, &c) < 0) {
        ioctl(fd, USBDEVFS_DISCARDURB, u);
        ioctl(fd, USBDEVFS_REAPURBNDELAY, &c);
        free(u); return 0;
    }
    double r = 0;
    if (u->iso_frame_desc[0].actual_length >= 4) {
        uint32_t raw = fb[0] | (fb[1]<<8) | (fb[2]<<16) | (fb[3]<<24);
        r = raw / 65536.0;
    }
    free(u);
    return r;
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
    if (ctx->endpointFeedback <= 0 || !ctx->feedbackUrb || ctx->feedbackInFlight)
        return false;

    struct usbdevfs_urb *u = ctx->feedbackUrb;
    size_t sz = sizeof(struct usbdevfs_urb) + sizeof(struct usbdevfs_iso_packet_desc);
    memset(u, 0, sz);
    u->type = USBDEVFS_URB_TYPE_ISO;
    u->flags = USBDEVFS_URB_ISO_ASAP;
    u->endpoint = (unsigned char)ctx->endpointFeedback;
    u->buffer = ctx->feedbackBuffer;
    u->buffer_length = 4;
    u->number_of_packets = 1;
    u->iso_frame_desc[0].length = 4;

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

    if (ctx->feedbackUrb->iso_frame_desc[0].actual_length >= 4) {
        uint8_t *fb = ctx->feedbackBuffer;
        uint32_t raw = fb[0] | (fb[1] << 8) | (fb[2] << 16) | (fb[3] << 24);
        double newFpmf = raw / 65536.0;

        // Sanity check: feedback should be within ±1% of nominal
        double nominal = ctx->sampleRate / 8000.0;
        if (newFpmf > nominal * 0.99 && newFpmf < nominal * 1.01) {
            ctx->calibratedFpmf = newFpmf;
            // Log only every 10000th feedback — logging in the audio path is expensive
            if (g_feedbackCount % 10000 == 0) {
                LOGI("Feedback #%lld: fpmf=%.4f (%.1f Hz)",
                     (long long)g_feedbackCount, newFpmf, newFpmf * 8000.0);
            }
        }
    }

    // Resubmit for continuous tracking
    submitFeedbackUrb(ctx);
}

// ── ISO URB submission / reap ───────────────────────────────────────

/**
 * Submit the URB at ring[submitIdx] with the given packet layout.
 * @param numPackets  Actual number of packets (no zero-length padding)
 * Returns 0 on success, -1 on error.
 */
static int submitRingUrb(UsbAudioContext *ctx, const int *pktSizes, int numPackets, int totalBytes) {
    UrbSlot *slot = &ctx->ring[ctx->submitIdx];
    struct usbdevfs_urb *u = slot->urb;

    // Clear the full URB struct including iso_frame_desc (stale actual_length/status)
    size_t clearSize = sizeof(struct usbdevfs_urb) +
                       numPackets * sizeof(struct usbdevfs_iso_packet_desc);
    memset(u, 0, clearSize);

    u->type = USBDEVFS_URB_TYPE_ISO;
    u->flags = USBDEVFS_URB_ISO_ASAP;
    u->endpoint = (unsigned char)ctx->endpointOut;
    u->buffer = slot->buffer;
    u->buffer_length = totalBytes;
    u->number_of_packets = numPackets;
    for (int i = 0; i < numPackets; i++)
        u->iso_frame_desc[i].length = (unsigned)pktSizes[i];

    slot->dataLength = totalBytes;

    int ret = ioctl(ctx->fd, USBDEVFS_SUBMITURB, u);
    if (ret < 0) {
        LOGE("SUBMITURB FAILED slot=%d ep=0x%02x bytes=%d pkts=%d errno=%d (%s)",
             ctx->submitIdx, ctx->endpointOut, totalBytes, numPackets, errno, strerror(errno));
        return -1;
    }

    ctx->submitIdx = (ctx->submitIdx + 1) % USB_AUDIO_NUM_URBS;
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
            ctx->reapIdx = (ctx->reapIdx + 1) % USB_AUDIO_NUM_URBS;
            ctx->urbsInFlight--;
            return 0;
        }
        if (ret < 0 && errno != EAGAIN) {
            LOGE("reapOldestUrb: error errno=%d (%s)", errno, strerror(errno));
            return -1;
        }
        usleep(125);  // 125µs = 1 USB microframe
    }
    return -2;  // timeout
}

/**
 * Drain ALL in-flight URBs. Blocks until every URB is reaped or timeout.
 * @return Number of URBs successfully drained
 */
static int drainAllUrbs(UsbAudioContext *ctx) {
    int drained = 0;
    int initialCount = ctx->urbsInFlight;
    LOGI("drainAllUrbs: draining %d URBs (feedback=%s)...",
         initialCount, ctx->feedbackInFlight ? "in-flight" : "idle");

    // Discard the feedback URB first so it doesn't interfere with draining
    if (ctx->feedbackInFlight && ctx->feedbackUrb) {
        ioctl(ctx->fd, USBDEVFS_DISCARDURB, ctx->feedbackUrb);
        struct usbdevfs_urb *c = nullptr;
        for (int i = 0; i < 50; i++) {
            if (ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &c) == 0 && c == ctx->feedbackUrb) {
                break;
            }
            usleep(100);
        }
        ctx->feedbackInFlight = false;
    }

    // Phase 1: try to reap naturally (URBs that completed normally)
    while (ctx->urbsInFlight > 0) {
        int result = reapOldestUrb(ctx, 500);
        if (result == 0) {
            drained++;
        } else {
            break;
        }
    }

    if (ctx->urbsInFlight > 0) {
        LOGW("drainAllUrbs: %d/%d reaped naturally, discarding remaining %d",
             drained, initialCount, ctx->urbsInFlight);

        // Phase 2: DISCARD all remaining URBs first
        int toDiscard = ctx->urbsInFlight;
        for (int i = 0; i < toDiscard; i++) {
            int slotIdx = (ctx->reapIdx + i) % USB_AUDIO_NUM_URBS;
            ioctl(ctx->fd, USBDEVFS_DISCARDURB, ctx->ring[slotIdx].urb);
        }

        // Phase 3: Reap ALL pending completions from the event ring.
        // CRITICAL: Don't match 1:1 with discards — REAPURBNDELAY returns
        // completions from ANY endpoint in ANY order. We must drain the
        // entire completion queue to prevent event ring accumulation that
        // would corrupt future streams.
        int reaped = 0;
        for (int attempt = 0; attempt < 500 && reaped < toDiscard; attempt++) {
            struct usbdevfs_urb *c = nullptr;
            int ret = ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &c);
            if (ret == 0 && c != nullptr) {
                reaped++;
            } else if (ret < 0 && errno != EAGAIN) {
                LOGE("drainAllUrbs: reap error errno=%d", errno);
                break;
            } else {
                usleep(1000);  // 1ms
            }
        }
        LOGI("drainAllUrbs: discarded %d, reaped %d completions", toDiscard, reaped);
        drained += reaped;

        // Phase 4: Flush any remaining stale completions (from previous
        // sessions' leaked feedback URBs, etc.)
        for (int i = 0; i < 10; i++) {
            struct usbdevfs_urb *c = nullptr;
            if (ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &c) == 0 && c != nullptr) {
                LOGW("drainAllUrbs: flushed stale completion %p", c);
                drained++;
            } else {
                break;
            }
        }
    }

    // Reset ring to clean state
    ctx->urbsInFlight = 0;
    ctx->submitIdx = 0;
    ctx->reapIdx = 0;

    LOGI("drainAllUrbs: drained %d/%d, ring reset", drained, initialCount);
    return drained;
}

// ── JNI entry points ────────────────────────────────────────────────

// Forward declaration (defined after integer padding functions, non-static for native-audio-engine)
void submitPcmToUrbs(UsbAudioContext *ctx, const uint8_t *pcmData, int totalBytes);

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioCreate(
        JNIEnv *, jobject, jint fd, jint ifId, jint epOut, jint epFb,
        jint rate, jint ch, jint bits, jint maxPkt) {
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
    ctx->running.store(false);
    ctx->transferBuffer = nullptr;
    ctx->transferBufferCapacity = 0;
    ctx->framesWritten = 0;
    ctx->interfaceClaimed = true;
    ctx->submitIdx = 0;
    ctx->reapIdx = 0;
    ctx->urbsInFlight = 0;
    ctx->ringAllocated = false;
    ctx->frameAccumulator = 0.0;
    ctx->calibratedFpmf = rate / 8000.0;
    ctx->residualBytes = 0;
    memset(ctx->residualBuffer, 0, sizeof(ctx->residualBuffer));
    memset(ctx->ring, 0, sizeof(ctx->ring));
    ctx->feedbackUrb = nullptr;
    ctx->feedbackInFlight = false;
    memset(ctx->feedbackBuffer, 0, sizeof(ctx->feedbackBuffer));

    if (!allocRing(ctx)) {
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
    if (!ctx) return JNI_FALSE;
    ctx->running.store(true);
    ctx->framesWritten = 0;
    ctx->submitIdx = 0;
    ctx->reapIdx = 0;
    ctx->urbsInFlight = 0;
    ctx->frameAccumulator = 0.0;
    ctx->residualBytes = 0;

    // Initial calibration from the DAC's async feedback endpoint.
    // Pipeline is empty here, so REAPURBNDELAY can only return the feedback URB.
    double nominalFpmf = ctx->sampleRate / 8000.0;
    ctx->calibratedFpmf = nominalFpmf;

    if (ctx->endpointFeedback > 0) {
        double fb = readFeedback(ctx->fd, ctx->endpointFeedback);
        if (fb > 0) {
            ctx->calibratedFpmf = fb;
            LOGI("Start: initial feedback=%.4f fpmf (%.1f Hz), nominal=%.4f (%.1f Hz)",
                 fb, fb * 8000.0, nominalFpmf, nominalFpmf * 8000.0);
        } else {
            LOGW("Start: feedback not responding, using nominal %.4f fpmf", nominalFpmf);
        }

        // Start continuous feedback: submit a feedback URB that will be
        // automatically recycled in the reap loop during streaming.
        // The host controller schedules the feedback endpoint once per
        // microframe (~1 ms effective interval).
        submitFeedbackUrb(ctx);
    }

    LOGI("Start: rate=%d ch=%d bits=%d ring=%d slots×%dpkt fpmf=%.4f feedback=%s",
         ctx->sampleRate, ctx->channelCount, ctx->bitDepth,
         USB_AUDIO_NUM_URBS, USB_AUDIO_PACKETS_PER_URB,
         ctx->calibratedFpmf,
         ctx->feedbackInFlight ? "continuous" : "one-shot");
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
    // Log every call that took > 10ms, or every 100th call
    if (writeUs > 10000 || writeCallCount % 100 == 0) {
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
Java_com_decent_usbaudio_UsbAudioStream_nativeFlush(
        JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx) return;
    ctx->frameAccumulator = 0.0;
    ctx->residualBytes = 0;
    ctx->framesWritten = 0;
    LOGI("Flush: frameAccumulator, residual, and framesWritten reset");
}

JNIEXPORT jint JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeDrainUrbs(
        JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx) return 0;
    ctx->running.store(false);
    return drainAllUrbs(ctx);
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioDestroy(
        JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (!ctx) return;
    ctx->running.store(false);

    if (ctx->urbsInFlight > 0) {
        drainAllUrbs(ctx);
    }

    freeRing(ctx);
    free(ctx->feedbackUrb);
    ctx->feedbackUrb = nullptr;
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
Java_com_decent_usbaudio_UsbAudioStream_nativeUsbReset(
        JNIEnv *, jclass, jint fd) {
    LOGI("USBDEVFS_RESET fd=%d", fd);
    int ret = ioctl(fd, USBDEVFS_RESET, 0);
    if (ret < 0) { LOGE("RESET FAILED errno=%d", errno); return ret; }
    LOGI("RESET OK, claiming interfaces...");
    struct usbdevfs_ioctl cmd = {}; cmd.ifno = 0; cmd.ioctl_code = USBDEVFS_DISCONNECT;
    ioctl(fd, USBDEVFS_IOCTL, &cmd);
    int i0 = 0; ioctl(fd, USBDEVFS_CLAIMINTERFACE, &i0);
    cmd.ifno = 1; ioctl(fd, USBDEVFS_IOCTL, &cmd);
    int i1 = 1; ioctl(fd, USBDEVFS_CLAIMINTERFACE, &i1);
    struct usbdevfs_setinterface si = {}; si.interface = 1; si.altsetting = 0;
    ioctl(fd, USBDEVFS_SETINTERFACE, &si);
    return 0;
}

} // extern "C" — pause for non-JNI functions used by native-audio-engine

// ── Integer padding (lossless, zero float) ──────────────────────────

// 16-bit → 32-bit: shift left 16
void padInt16ToInt32(const uint8_t *src, uint8_t *dst, int numSamples) {
    auto *out = reinterpret_cast<int32_t *>(dst);
    auto *in16 = reinterpret_cast<const int16_t *>(src);
    for (int i = 0; i < numSamples; i++) out[i] = (int32_t)in16[i] << 16;
}

// int32 (24-bit sign-extended from libFLAC) → 32-bit: shift left 8
void shiftInt32From24(const uint8_t *src, uint8_t *dst, int numSamples) {
    auto *out = reinterpret_cast<int32_t *>(dst);
    auto *in32 = reinterpret_cast<const int32_t *>(src);
    for (int i = 0; i < numSamples; i++) out[i] = in32[i] << 8;
}

// 24-bit packed (3 bytes/sample) → 32-bit: read 3 bytes, sign-extend, shift left 8
void padInt24ToInt32(const uint8_t *src, uint8_t *dst, int numSamples) {
    auto *out = reinterpret_cast<int32_t *>(dst);
    for (int i = 0; i < numSamples; i++) {
        int32_t s = src[i*3] | (src[i*3+1] << 8) | (src[i*3+2] << 16);
        if (s & 0x800000) s |= 0xFF000000;  // sign-extend from 24 to 32 bits
        out[i] = s << 8;  // shift to fill 32-bit range
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
        if (mergedBuf) {
            memcpy(mergedBuf, ctx->residualBuffer, ctx->residualBytes);
            memcpy(mergedBuf + ctx->residualBytes, pcmData, totalBytes);
            data = mergedBuf;
        }
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

        for (int p = 0; p < USB_AUDIO_PACKETS_PER_URB; p++) {
            int remaining = dataLen - offset - urbBytes;
            if (remaining <= 0) break;

            ctx->frameAccumulator += fpmf;
            int frames = (int)ctx->frameAccumulator;
            ctx->frameAccumulator -= frames;
            int b = frames * ctx->bytesPerFrame;

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
        if (numPackets <= 0 || urbBytes <= 0) break;

        // Never submit short URBs (< full packet count). Short URBs create
        // empty ISO microframes in the xHCI schedule — the DAC receives
        // silence for those microframes → audible click/pop.
        // Instead, save leftover data for the next write() call.
        if (numPackets < USB_AUDIO_PACKETS_PER_URB) {
            int leftover = dataLen - offset;
            if (leftover > 0 && leftover < (int)sizeof(ctx->residualBuffer)) {
                memcpy(ctx->residualBuffer, data + offset, leftover);
                ctx->residualBytes = leftover;
            }
            break;
        }

        if (ctx->urbsInFlight >= USB_AUDIO_NUM_URBS) {
            int result = reapOldestUrb(ctx, 200);
            if (result == -2) {
                LOGE("submitPcmToUrbs: reap timeout, inflight=%d", ctx->urbsInFlight);
                drainAllUrbs(ctx);
                ctx->running.store(false);
                free(mergedBuf);
                return;
            } else if (result < 0) {
                ctx->running.store(false);
                free(mergedBuf);
                return;
            }
        }

        UrbSlot *slot = &ctx->ring[ctx->submitIdx];
        memcpy(slot->buffer, data + offset, urbBytes);

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
    if (leftover > 0 && leftover < (int)sizeof(ctx->residualBuffer) && ctx->residualBytes == 0) {
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

    // Bit-depth matching: pad input to DAC's bit depth (lossless integer ops)
    if (inputBitDepth == ctx->bitDepth) {
        // Same bit depth: zero-copy
        memcpy(ctx->transferBuffer, rawData, inputBytes);
    } else if (inputBitDepth == 16 && ctx->bitDepth == 32) {
        padInt16ToInt32((uint8_t *)rawData, ctx->transferBuffer, totalSamples);
    } else if (inputBitDepth == 24 && ctx->bitDepth == 32) {
        // 24-bit packed (3 bytes/sample) → 32-bit: sign-extend + shift left 8
        padInt24ToInt32((uint8_t *)rawData, ctx->transferBuffer, totalSamples);
    } else if (inputBitDepth == 32 && ctx->bitDepth == 32) {
        // libFLAC 24-bit → PCM_32BIT (sign-extended): shift left 8 to fill 32-bit range
        shiftInt32From24((uint8_t *)rawData, ctx->transferBuffer, totalSamples);
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
