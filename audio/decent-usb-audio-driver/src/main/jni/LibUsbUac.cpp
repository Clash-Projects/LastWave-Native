/**
 * Bit-perfect USB Audio transport on libusb.
 *
 * libusb's Linux backend still submits usbdevfs isochronous URBs with
 * ISO_ASAP. The kernel spaces those packets by the endpoint bInterval
 * (2^(bInterval-1) microframes). This file does not assume every endpoint
 * is one packet per 125 µs. Packet size is an integer Q16.16 phase:
 *
 *   phase += freqm << dataInterval
 *   frames = phase >> 16
 *
 * freqm starts at the nominal samples-per-microframe. An asynchronous
 * feedback endpoint replaces it after the value is shifted into that
 * format, the same way ALSA does, and is held until the next sample.
 *
 * The renderer only fills a ring. A libusb event thread owns the transfer
 * pool. Seek cancels every in-flight transfer and waits until those
 * callbacks return before the ring is cleared.
 */

#include <libusb.h>

#include <android/log.h>
#include <jni.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <thread>
#include <vector>

#include <sys/time.h>
#include <unistd.h>

#define LOG_TAG "LibUsbUac"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kTransfers = 12;
constexpr int kRingBytes = 512 * 1024;

struct Engine;

struct Xfer {
    Engine *eng = nullptr;
    libusb_transfer *t = nullptr;
    std::vector<uint8_t> buf;
    bool inFlight = false;
    bool feedback = false;
    int countedFrames = 0;
};

struct Engine {
    libusb_context *ctx = nullptr;
    libusb_device_handle *dev = nullptr;
    int dupFd = -1;
    int epOut = 0;
    int epFb = 0;
    int rate = 48000;
    int channels = 2;
    int bits = 16;
    int bpf = 4;
    int maxPacket = 0;
    int bInterval = 1;
    int dataInterval = 0;
    int microframes = 1;
    int packetsPerXfer = 8;
    std::atomic<uint32_t> freqm{0};
    std::mutex sched;
    int freqShift = 0x7fffffff;
    uint32_t phase = 0;
    std::atomic<bool> running{false};
    std::atomic<bool> fatal{false};
    std::atomic<bool> paused{false};
    std::atomic<bool> stopEvents{false};
    std::atomic<int> active{0};
    std::atomic<int64_t> framesCompleted{0};
    std::atomic<int64_t> underruns{0};
    std::atomic<int64_t> transfersDone{0};
    std::atomic<int64_t> transferErrors{0};
    std::mutex mu;
    std::condition_variable cv;
    std::vector<uint8_t> ring;
    int ringR = 0;
    int ringW = 0;
    int ringFill = 0;
    bool flushing = false;
    Xfer out[kTransfers];
    Xfer fb;
    std::thread events;
    bool eventsStarted = false;
    int64_t packetLog = 0;
};

static uint32_t nominalFreqm(int rate) {
    if (rate <= 0) return 0;
    return (uint32_t)(((uint64_t)rate << 16) / 8000u);
}

static int serviceFrames(Engine *e) {
    uint32_t freq = e->freqm.load();
    uint64_t sum = (uint64_t)(e->phase & 0xffffu) + ((uint64_t)freq << e->dataInterval);
    int frames = (int)(sum >> 16);
    e->phase = (uint32_t)(sum & 0xffffu);
    if (frames < 1) frames = 1;
    int maxFrames = e->bpf > 0 ? e->maxPacket / e->bpf : 0;
    if (maxFrames > 0 && frames > maxFrames) {
        LOGE("packet %d frames (%d bytes) exceeds max %d (rate=%d bInterval=%d)",
             frames, frames * e->bpf, e->maxPacket, e->rate, e->bInterval);
        e->fatal.store(true);
        e->running.store(false);
        return 0;
    }
    return frames;
}

static bool popRing(Engine *e, uint8_t *dst, int n) {
    std::lock_guard<std::mutex> lock(e->mu);
    if (e->paused.load() || e->ringFill < n) return false;
    int cap = (int)e->ring.size();
    int first = n;
    if (e->ringR + first > cap) first = cap - e->ringR;
    memcpy(dst, e->ring.data() + e->ringR, (size_t)first);
    if (first < n) memcpy(dst + first, e->ring.data(), (size_t)(n - first));
    e->ringR = (e->ringR + n) % cap;
    e->ringFill -= n;
    e->cv.notify_all();
    return true;
}

static void pushRing(Engine *e, const uint8_t *src, int n) {
    std::unique_lock<std::mutex> lock(e->mu);
    int cap = (int)e->ring.size();
    int left = n;
    const uint8_t *p = src;
    while (left > 0 && e->running.load() && !e->fatal.load()) {
        if (e->ringFill >= cap) {
            e->cv.wait_for(lock, std::chrono::milliseconds(500));
            continue;
        }
        int space = cap - e->ringFill;
        int chunk = left < space ? left : space;
        int first = chunk;
        if (e->ringW + first > cap) first = cap - e->ringW;
        memcpy(e->ring.data() + e->ringW, p, (size_t)first);
        if (first < chunk) memcpy(e->ring.data(), p + first, (size_t)(chunk - first));
        e->ringW = (e->ringW + chunk) % cap;
        e->ringFill += chunk;
        p += chunk;
        left -= chunk;
    }
}

static void clearRing(Engine *e) {
    e->ringR = 0;
    e->ringW = 0;
    e->ringFill = 0;
}

static void onFeedback(libusb_transfer *t) {
    auto *x = static_cast<Xfer *>(t->user_data);
    Engine *e = x->eng;
    x->inFlight = false;
    e->active.fetch_sub(1);
    if (t->status == LIBUSB_TRANSFER_COMPLETED && t->num_iso_packets > 0 &&
        t->iso_packet_desc[0].actual_length >= 3) {
        int n = t->iso_packet_desc[0].actual_length;
        uint32_t raw = x->buf[0] | (x->buf[1] << 8) | (x->buf[2] << 16);
        if (n >= 4) raw |= (uint32_t)x->buf[3] << 24;
        if (n == 3) raw &= 0x00ffffff;
        else raw &= 0x0fffffff;
        uint32_t nominal = nominalFreqm(e->rate);
        if (raw != 0 && nominal != 0) {
            int shift = e->freqShift;
            if (shift == 0x7fffffff) {
                shift = 0;
                uint32_t f = raw;
                while (f < nominal - nominal / 4 && shift < 8) {
                    f <<= 1;
                    shift++;
                }
                while (f > nominal + nominal / 2 && shift > -8) {
                    f >>= 1;
                    shift--;
                }
                e->freqShift = shift;
                LOGI("feedback format shift=%d raw=0x%x nominal_q16=%u", shift, raw, nominal);
            }
            uint32_t f = raw;
            if (shift >= 0) f <<= shift;
            else f >>= -shift;
            if (f >= nominal - nominal / 8 && f <= nominal + nominal / 2) {
                e->freqm.store(f);
            } else {
                e->freqShift = 0x7fffffff;
            }
        }
    }
    bool again = e->running.load() && !e->fatal.load() && !e->flushing && e->epFb > 0;
    if (again) {
        t->status = LIBUSB_TRANSFER_COMPLETED;
        if (libusb_submit_transfer(t) == 0) {
            x->inFlight = true;
            e->active.fetch_add(1);
        }
    }
    e->cv.notify_all();
}

static void fillOut(Engine *e, Xfer *x) {
    std::lock_guard<std::mutex> schedule(e->sched);
    libusb_transfer *t = x->t;
    int offset = 0;
    x->countedFrames = 0;
    for (int i = 0; i < e->packetsPerXfer; i++) {
        int frames = serviceFrames(e);
        if (frames <= 0) {
            t->iso_packet_desc[i].length = 0;
            continue;
        }
        int bytes = frames * e->bpf;
        bool got = popRing(e, t->buffer + offset, bytes);
        if (!got) {
            memset(t->buffer + offset, 0, (size_t)bytes);
            e->underruns.fetch_add(1);
        }
        t->iso_packet_desc[i].length = (unsigned)bytes;
        offset += bytes;
        if (got) x->countedFrames += frames;
        if ((++e->packetLog % 4000) == 0) {
            LOGI("pkt frames=%d bytes=%d freqm=%u phase=%u ring=%d underrun=%lld done=%lld",
                 frames, bytes, e->freqm.load(), e->phase, e->ringFill,
                 (long long)e->underruns.load(), (long long)e->transfersDone.load());
        }
    }
    t->length = offset;
}

static void onOut(libusb_transfer *t);

static bool submitOut(Xfer *x) {
    Engine *e = x->eng;
    if (!e->running.load() || e->fatal.load() || e->flushing) return false;
    fillOut(e, x);
    if (e->flushing || x->t->length <= 0) return false;
    int rc = libusb_submit_transfer(x->t);
    if (rc != 0) {
        e->transferErrors.fetch_add(1);
        LOGW("submit iso rc=%d", rc);
        if (rc == LIBUSB_ERROR_NO_DEVICE || rc == LIBUSB_ERROR_PIPE) {
            e->fatal.store(true);
            e->running.store(false);
        }
        return false;
    }
    x->inFlight = true;
    e->active.fetch_add(1);
    return true;
}

static void onOut(libusb_transfer *t) {
    auto *x = static_cast<Xfer *>(t->user_data);
    Engine *e = x->eng;
    x->inFlight = false;
    e->active.fetch_sub(1);
    if (t->status == LIBUSB_TRANSFER_COMPLETED) {
        e->transfersDone.fetch_add(1);
        e->framesCompleted.fetch_add(x->countedFrames);
    } else if (t->status == LIBUSB_TRANSFER_CANCELLED || t->status == LIBUSB_TRANSFER_NO_DEVICE) {
        e->cv.notify_all();
        return;
    } else if (t->status == LIBUSB_TRANSFER_STALL) {
        e->transferErrors.fetch_add(1);
        libusb_clear_halt(e->dev, (unsigned char)e->epOut);
    } else if (t->status == LIBUSB_TRANSFER_ERROR || t->status == LIBUSB_TRANSFER_TIMED_OUT) {
        e->transferErrors.fetch_add(1);
        LOGW("iso status=%d", t->status);
    } else {
        e->transferErrors.fetch_add(1);
    }
    if (e->running.load() && !e->fatal.load() && !e->flushing) {
        submitOut(x);
    }
    e->cv.notify_all();
}

static void eventMain(Engine *e) {
    while (!e->stopEvents.load()) {
        timeval tv;
        tv.tv_sec = 0;
        tv.tv_usec = 50000;
        libusb_handle_events_timeout_completed(e->ctx, &tv, nullptr);
    }
}

static void ensureEvents(Engine *e) {
    if (e->eventsStarted) return;
    e->stopEvents.store(false);
    e->events = std::thread(eventMain, e);
    e->eventsStarted = true;
}

static void kick(Engine *e) {
    if (!e->running.load() || e->fatal.load() || e->flushing) return;
    ensureEvents(e);
    for (int i = 0; i < kTransfers; i++) {
        if (!e->out[i].inFlight) submitOut(&e->out[i]);
    }
    if (e->epFb > 0 && e->fb.t && !e->fb.inFlight) {
        int rc = libusb_submit_transfer(e->fb.t);
        if (rc == 0) {
            e->fb.inFlight = true;
            e->active.fetch_add(1);
        }
    }
}

static void cancelAll(Engine *e) {
    e->flushing = true;
    for (int pass = 0; pass < 4 && e->active.load() != 0; pass++) {
        for (int i = 0; i < kTransfers; i++) {
            if (e->out[i].inFlight && e->out[i].t) libusb_cancel_transfer(e->out[i].t);
        }
        if (e->fb.inFlight && e->fb.t) libusb_cancel_transfer(e->fb.t);
        std::unique_lock<std::mutex> lock(e->mu);
        e->cv.wait_for(lock, std::chrono::milliseconds(200), [&] { return e->active.load() == 0; });
    }
    int left = e->active.load();
    if (left != 0) LOGW("seek flush: %d transfers still active", left);
}

static void configureSchedule(Engine *e) {
    int interval = e->bInterval;
    if (interval < 1) interval = 1;
    if (interval > 16) interval = 16;
    e->bInterval = interval;
    e->dataInterval = interval - 1;
    e->microframes = 1 << e->dataInterval;
    int packs = 8 >> e->dataInterval;
    if (packs < 1) packs = 1;
    e->packetsPerXfer = packs;
    e->freqm.store(nominalFreqm(e->rate));
    e->freqShift = 0x7fffffff;
    e->phase = 0;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_decent_usbaudio_LibUsbAudioStream_nativeCreate(
        JNIEnv *, jobject, jint fd, jint epOut, jint epFb, jint rate, jint ch, jint bits,
        jint maxPkt, jint interval) {
    auto *e = new (std::nothrow) Engine();
    if (!e) return 0;
    e->epOut = epOut;
    e->epFb = epFb;
    e->rate = rate;
    e->channels = ch;
    e->bits = bits;
    e->bpf = (bits / 8) * (ch > 0 ? ch : 1);
    if (e->bpf < 1) e->bpf = 1;
    e->maxPacket = maxPkt;
    e->bInterval = interval;
    configureSchedule(e);
    e->ring.assign((size_t)kRingBytes, 0);
    int dupfd = dup(fd);
    if (dupfd < 0) {
        LOGE("dup fd failed");
        delete e;
        return 0;
    }
    e->dupFd = dupfd;
    libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
    if (libusb_init(&e->ctx) != 0) {
        LOGE("libusb_init failed");
        close(dupfd);
        delete e;
        return 0;
    }
    if (libusb_wrap_sys_device(e->ctx, (intptr_t)dupfd, &e->dev) != 0) {
        LOGE("libusb_wrap_sys_device failed");
        libusb_exit(e->ctx);
        delete e;
        return 0;
    }
    int cap = e->maxPacket * e->packetsPerXfer;
    if (cap < e->maxPacket) cap = e->maxPacket;
    for (int i = 0; i < kTransfers; i++) {
        e->out[i].eng = e;
        e->out[i].buf.assign((size_t)cap, 0);
        e->out[i].t = libusb_alloc_transfer(e->packetsPerXfer);
        if (!e->out[i].t) {
            LOGE("alloc transfer failed");
            e->fatal.store(true);
            break;
        }
        libusb_fill_iso_transfer(
                e->out[i].t, e->dev, (unsigned char)e->epOut, e->out[i].buf.data(), cap,
                e->packetsPerXfer, onOut, &e->out[i], 0);
    }
    if (e->epFb > 0) {
        e->fb.eng = e;
        e->fb.feedback = true;
        e->fb.buf.assign(16, 0);
        e->fb.t = libusb_alloc_transfer(1);
        if (e->fb.t) {
            libusb_fill_iso_transfer(
                    e->fb.t, e->dev, (unsigned char)e->epFb, e->fb.buf.data(), 4, 1, onFeedback,
                    &e->fb, 0);
            e->fb.t->iso_packet_desc[0].length = 4;
        }
    }
    const char *sync = e->epFb > 0 ? "asynchronous-feedback" : "nominal (no feedback endpoint)";
    LOGI("open rate=%d ch=%d bits=%d bpf=%d ep=0x%02x fb=0x%02x bInterval=%d microframes=%d "
         "maxPacket=%d pkts/xfer=%d sync=%s",
         e->rate, e->channels, e->bits, e->bpf, e->epOut, e->epFb, e->bInterval, e->microframes,
         e->maxPacket, e->packetsPerXfer, sync);
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT jboolean JNICALL
Java_com_decent_usbaudio_LibUsbAudioStream_nativeStart(JNIEnv *, jobject, jlong h) {
    auto *e = reinterpret_cast<Engine *>(h);
    if (!e || e->fatal.load()) return JNI_FALSE;
    e->flushing = false;
    e->paused.store(false);
    e->running.store(true);
    e->framesCompleted.store(0);
    configureSchedule(e);
    clearRing(e);
    ensureEvents(e);
    uint32_t freq = e->freqm.load();
    LOGI("start rate=%d bInterval=%d frames/service~%u", e->rate, e->bInterval,
         (unsigned)(((uint64_t)freq << e->dataInterval) >> 16));
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_LibUsbAudioStream_nativeWriteRaw(
        JNIEnv *env, jobject, jlong h, jbyteArray pcm, jint inputBits) {
    auto *e = reinterpret_cast<Engine *>(h);
    if (!e || !e->running.load() || e->fatal.load() || e->paused.load()) return;
    jsize inBytes = env->GetArrayLength(pcm);
    if (inBytes <= 0) return;
    int inBps = inputBits / 8;
    if (inBps <= 0) return;
    int samples = inBytes / inBps;
    int outBytes = samples * (e->bits / 8);
    std::vector<uint8_t> out((size_t)outBytes);
    jbyte *raw = env->GetByteArrayElements(pcm, nullptr);
    if (!raw) return;
    if (inputBits == e->bits) {
        memcpy(out.data(), raw, (size_t)inBytes);
    } else if (inputBits == 16 && e->bits == 32) {
        auto *s = reinterpret_cast<const int16_t *>(raw);
        auto *d = reinterpret_cast<int32_t *>(out.data());
        for (int i = 0; i < samples; i++) d[i] = (int32_t)s[i] << 16;
    } else if (inputBits == 16 && e->bits == 24) {
        auto *s = reinterpret_cast<const int16_t *>(raw);
        for (int i = 0; i < samples; i++) {
            int32_t v = (int32_t)s[i] << 8;
            out[(size_t)i * 3] = (uint8_t)(v & 0xff);
            out[(size_t)i * 3 + 1] = (uint8_t)((v >> 8) & 0xff);
            out[(size_t)i * 3 + 2] = (uint8_t)((v >> 16) & 0xff);
        }
    } else if (inputBits == 24 && e->bits == 32) {
        auto *d = reinterpret_cast<int32_t *>(out.data());
        auto *s = reinterpret_cast<const uint8_t *>(raw);
        for (int i = 0; i < samples; i++) {
            int32_t v = s[i * 3] | (s[i * 3 + 1] << 8) | (s[i * 3 + 2] << 16);
            if (v & 0x800000) v |= 0xff000000;
            d[i] = v << 8;
        }
    } else if (inputBits == 32 && e->bits == 24) {
        auto *s = reinterpret_cast<const int32_t *>(raw);
        for (int i = 0; i < samples; i++) {
            int32_t v = s[i];
            if (v < -0x800000 || v > 0x7fffff) v >>= 8;
            out[(size_t)i * 3] = (uint8_t)(v & 0xff);
            out[(size_t)i * 3 + 1] = (uint8_t)((v >> 8) & 0xff);
            out[(size_t)i * 3 + 2] = (uint8_t)((v >> 16) & 0xff);
        }
    } else if (inputBits == 32 && e->bits == 32) {
        auto *s = reinterpret_cast<const int32_t *>(raw);
        auto *d = reinterpret_cast<int32_t *>(out.data());
        for (int i = 0; i < samples; i++) d[i] = s[i] << 8;
    } else {
        LOGE("unsupported conversion %d -> %d", inputBits, e->bits);
        env->ReleaseByteArrayElements(pcm, raw, JNI_ABORT);
        return;
    }
    env->ReleaseByteArrayElements(pcm, raw, JNI_ABORT);
    pushRing(e, out.data(), outBytes);
    kick(e);
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_LibUsbAudioStream_nativeWriteFloat(
        JNIEnv *env, jobject, jlong h, jfloatArray pcm) {
    auto *e = reinterpret_cast<Engine *>(h);
    if (!e || !e->running.load() || e->fatal.load() || e->paused.load()) return;
    jsize n = env->GetArrayLength(pcm);
    if (n <= 0) return;
    jfloat *f = env->GetFloatArrayElements(pcm, nullptr);
    if (!f) return;
    int bps = e->bits / 8;
    std::vector<uint8_t> out((size_t)n * (size_t)bps);
    for (int i = 0; i < n; i++) {
        float s = f[i];
        if (s > 1.f) s = 1.f;
        if (s < -1.f) s = -1.f;
        if (e->bits == 16) {
            int16_t v = (int16_t)(s * 32767.f);
            memcpy(out.data() + (size_t)i * 2, &v, 2);
        } else if (e->bits == 24) {
            int32_t v = (int32_t)(s * 8388607.f);
            out[(size_t)i * 3] = (uint8_t)(v & 0xff);
            out[(size_t)i * 3 + 1] = (uint8_t)((v >> 8) & 0xff);
            out[(size_t)i * 3 + 2] = (uint8_t)((v >> 16) & 0xff);
        } else {
            int32_t v = (int32_t)(s * 2147483647.f);
            memcpy(out.data() + (size_t)i * 4, &v, 4);
        }
    }
    env->ReleaseFloatArrayElements(pcm, f, JNI_ABORT);
    pushRing(e, out.data(), (int)out.size());
    kick(e);
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_LibUsbAudioStream_nativeFlush(JNIEnv *, jobject, jlong h) {
    auto *e = reinterpret_cast<Engine *>(h);
    if (!e) return;
    int before = e->active.load();
    int ring = e->ringFill;
    LOGI("seek flush before transfers=%d ring=%d clock=%lld", before, ring,
         (long long)e->framesCompleted.load());
    e->paused.store(true);
    cancelAll(e);
    {
        std::lock_guard<std::mutex> lock(e->mu);
        clearRing(e);
    }
    e->phase = 0;
    e->flushing = false;
    e->paused.store(false);
    e->running.store(!e->fatal.load());
    LOGI("seek flush after transfers=%d clock=%lld", e->active.load(),
         (long long)e->framesCompleted.load());
    e->cv.notify_all();
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_LibUsbAudioStream_nativeSetPaused(JNIEnv *, jobject, jlong h, jboolean paused) {
    auto *e = reinterpret_cast<Engine *>(h);
    if (!e) return;
    e->paused.store(paused == JNI_TRUE);
    LOGI("paused=%d ring=%d", paused == JNI_TRUE ? 1 : 0, e->ringFill);
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_LibUsbAudioStream_nativeHalt(JNIEnv *, jobject, jlong h) {
    auto *e = reinterpret_cast<Engine *>(h);
    if (!e) return;
    e->running.store(false);
    cancelAll(e);
    LOGI("halt clock=%lld", (long long)e->framesCompleted.load());
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_LibUsbAudioStream_nativeStop(JNIEnv *, jobject, jlong h) {
    auto *e = reinterpret_cast<Engine *>(h);
    if (!e) return;
    e->running.store(false);
    cancelAll(e);
    e->stopEvents.store(true);
    if (e->eventsStarted && e->events.joinable()) e->events.join();
    e->eventsStarted = false;
    for (int i = 0; i < kTransfers; i++) {
        if (e->out[i].t) libusb_free_transfer(e->out[i].t);
        e->out[i].t = nullptr;
    }
    if (e->fb.t) libusb_free_transfer(e->fb.t);
    e->fb.t = nullptr;
    if (e->dev) libusb_close(e->dev);
    if (e->ctx) libusb_exit(e->ctx);
    e->dev = nullptr;
    e->ctx = nullptr;
    LOGI("stop clock=%lld underrun=%lld errors=%lld",
         (long long)e->framesCompleted.load(), (long long)e->underruns.load(),
         (long long)e->transferErrors.load());
    delete e;
}

JNIEXPORT jboolean JNICALL
Java_com_decent_usbaudio_LibUsbAudioStream_nativeIsRunning(JNIEnv *, jobject, jlong h) {
    auto *e = reinterpret_cast<Engine *>(h);
    return (e && e->running.load() && !e->fatal.load()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_decent_usbaudio_LibUsbAudioStream_nativeFrames(JNIEnv *, jobject, jlong h) {
    auto *e = reinterpret_cast<Engine *>(h);
    return e ? e->framesCompleted.load() : 0;
}

}  // extern "C"
