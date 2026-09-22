#include "usb_audio.h"
#include "usb_pack.h"
#include "core/dsp/round.h"
#include "core/dsp/wire_scale.h"

#ifdef _WIN32
#define NOMINMAX
#define _CRT_SECURE_NO_WARNINGS
#include <windows.h>
#include <avrt.h>
#pragma comment(lib, "avrt.lib")
#include <cstdio>
#include <mutex>
#include <cstdarg>

static std::mutex g_logMutex;
static inline void file_log(const char* level, const char* fmt, ...) {
    std::lock_guard<std::mutex> lock(g_logMutex);
    FILE* f = fopen("audio_engine.log", "a");
    if (!f) return;
    fprintf(f, "[%s] ", level);
    va_list args;
    va_start(args, fmt);
    vfprintf(f, fmt, args);
    va_end(args);
    fprintf(f, "\n");
    fclose(f);
}

#define LOGI(...) file_log("INFO", __VA_ARGS__)
#define LOGE(...) file_log("ERR", __VA_ARGS__)
// LOGD is compiled out unless USB_AUDIO_VERBOSE, exactly as on POSIX below.
// It used to call file_log unconditionally — a global mutex plus
// fopen/fprintf/fclose — and handleTransferComplete() logs on EVERY
// isochronous completion (~250/s at the default 64x32 queue, far more at high
// rates). Doing filesystem IO on the thread whose timing decides whether audio
// crackles is the one thing this driver must never do.
#ifdef USB_AUDIO_VERBOSE
#define LOGD(...) file_log("DEBUG", __VA_ARGS__)
#else
#define LOGD(...) do { } while (0)
#endif
static inline int mlock(const void* addr, size_t len) {
    return VirtualLock((LPVOID)addr, len) ? 0 : -1;
}
static inline int munlock(const void* addr, size_t len) {
    return VirtualUnlock((LPVOID)addr, len) ? 0 : -1;
}
static inline int setpriority(int, int, int) { return -1; }
static inline void usleep(unsigned us) { Sleep(us < 1000 ? 1 : us / 1000); }
#define PRIO_PROCESS 0
#elif defined(__ANDROID__)
#include <android/log.h>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <sched.h>
#include <pthread.h>
#define LOG_TAG "UsbAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
// Per-descriptor / per-format enumeration logs use LOGD so they're suppressed
// by default. Bump the logcat tag filter to `UsbAudio:D` when debugging
// descriptor parsing or format selection.
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#else
// Generic POSIX (desktop Linux, or any non-Android unix)
#include <cstdio>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <sched.h>
#include <pthread.h>
#define LOGI(...) do { fprintf(stderr, "[UsbAudio INFO] " __VA_ARGS__); fputc('\n', stderr); } while (0)
#define LOGE(...) do { fprintf(stderr, "[UsbAudio ERR] "  __VA_ARGS__); fputc('\n', stderr); } while (0)
#ifdef USB_AUDIO_VERBOSE
#define LOGD(...) do { fprintf(stderr, "[UsbAudio DEBUG] " __VA_ARGS__); fputc('\n', stderr); } while (0)
#else
#define LOGD(...) do { } while (0)
#endif
#endif

#include "libusb/libusb/libusb.h"
#include <ctime>
#include <cstring>

// Portable millisecond timestamp (wraps every ~49 days, fine for intervals)
static inline uint32_t millis_now() {
#ifdef _WIN32
    return (uint32_t)GetTickCount();
#else
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<uint32_t>(ts.tv_sec * 1000ULL + ts.tv_nsec / 1000000ULL);
#endif
}
#include <cmath>
#include <algorithm>
#include <unordered_map>
#include <thread>
#include <cerrno>

// Diagnostic: when true, the engine does not submit the feedback IN transfer
// and ignores the feedback callback's stored values. submitTransfer() then
// always uses nominalFpp (the host's idea of the rate), so packet sizes are
// the fixed fractional pattern (e.g. 5/6 alternating for 44.1k high-speed).
// A2+A3 confirmed feedback is NOT the cause of the Windows popcorn; restored
// to false so feedback is active.
static const bool DISABLE_FEEDBACK = false;

// Diagnostic A4: when true, submitTransfer() replaces the per-packet ring
// read with all-zeros. A4 confirmed the data path is the popcorn source
// (silence is clean), so this is restored to false; left in place so the
// test can be re-run quickly if needed.
static const bool SEND_SILENCE = false;

// Diagnostic A8: when true, UsbAudioDriver::writeFloat32() overrides every
// incoming float sample with a constant DC value (0.3) before the clamp/
// scale/shift/byte-pack steps. Everything downstream of the per-sample
// value runs identically to the music path.
//
// Purpose: split the data-path bug location between conversion vs upstream.
//   DC output is clean → conversion correct; bug is in player_window.cpp
//                       PCM callback (gain, EQ-bypass wiring, resampling).
//   DC output has clicks → bug is inside writeFloat32 itself (signed shift
//                         UB, ring wraparound, byte packing).
static const bool WRITE_CONST_DATA = false;

// Diagnostic A9: when true, submitTransfer() bypasses the ring read entirely
// and synthesizes a 220 Hz sine directly into each packet using a persistent
// phase counter that never resets across packets/transfers. This removes the
// ring buffer, writeFloat32, and the decoder/producer thread from the data
// path while keeping iso scheduling, packet sizing, feedback and MMCSS
// identical.
//
//   Clean continuous tone → iso delivery is faithful; bug is in the ring /
//                           producer-consumer path.
//   Tone with clicks/warble → iso packet delivery itself drops/repeats/
//                             mis-times packets (structural WinUSB).
static const bool GEN_SINE_IN_SUBMIT = false;

UsbAudioDriver::UsbAudioDriver() = default;

UsbAudioDriver::~UsbAudioDriver() {
    close();
    delete ringBuffer;
}

bool UsbAudioDriver::open(int fd) {
    if (opened) close();

    LOGI("Opening USB device fd=%d", fd);

    libusb_set_option(NULL, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
    int rc = libusb_init(&ctx);
    if (rc < 0) {
        LOGE("libusb_init failed: %s", libusb_error_name(rc));
        return false;
    }

    rc = libusb_wrap_sys_device(ctx, (intptr_t)fd, &handle);
    if (rc < 0) {
        LOGE("libusb_wrap_sys_device failed: %s", libusb_error_name(rc));
        libusb_exit(ctx);
        ctx = nullptr;
        return false;
    }

    usbSpeed = libusb_get_device_speed(libusb_get_device(handle));
    LOGI("USB device speed: %s",
        usbSpeed == LIBUSB_SPEED_LOW ? "Low" :
        usbSpeed == LIBUSB_SPEED_FULL ? "Full" :
        usbSpeed == LIBUSB_SPEED_HIGH ? "High" :
        usbSpeed == LIBUSB_SPEED_SUPER ? "Super" : "Unknown");

    rc = libusb_set_auto_detach_kernel_driver(handle, 1);
    if (rc < 0) {
        LOGI("auto_detach_kernel_driver not supported: %s (non-fatal)", libusb_error_name(rc));
    }

    opened = true;
    capFirstActivationAfterOpen.store(true);
    LOGI("USB device opened via fd %d", fd);
    return true;
}

bool UsbAudioDriver::open(uint16_t vid, uint16_t pid) {
    if (opened) close();

    LOGI("Opening USB device VID=%04X PID=%04X", vid, pid);

    int rc = libusb_init(&ctx);
    if (rc < 0) {
        LOGE("libusb_init failed: %s", libusb_error_name(rc));
        return false;
    }

    handle = libusb_open_device_with_vid_pid(ctx, vid, pid);
    if (!handle) {
        // libusb collapses "no such device" and "device is right there but I
        // could not open it" into the same NULL. Those need completely
        // different fixes — plug the DAC in, versus grant access to it — so
        // find out which one it was before saying anything about it.
        libusb_device** list = nullptr;
        const ssize_t   cnt  = libusb_get_device_list(ctx, &list);
        bool present  = false;
        int  openErr  = 0;
        for (ssize_t i = 0; i < cnt; i++) {
            libusb_device_descriptor desc{};
            if (libusb_get_device_descriptor(list[i], &desc) < 0) continue;
            if (desc.idVendor != vid || desc.idProduct != pid) continue;
            present = true;
            libusb_device_handle* probe = nullptr;
            openErr = libusb_open(list[i], &probe);
            if (openErr == 0 && probe) libusb_close(probe);  // raced; transient
            break;
        }
        if (cnt >= 0) libusb_free_device_list(list, 1);

        if (!present) {
            LOGE("Device VID=%04X PID=%04X is not connected", vid, pid);
        } else if (openErr == LIBUSB_ERROR_ACCESS) {
#ifdef _WIN32
            LOGE("Device VID=%04X PID=%04X found but access was denied — bind "
                 "interface 0 to libusbK with Zadig", vid, pid);
#else
            LOGE("Device VID=%04X PID=%04X found but access was denied — your "
                 "user needs read/write on its /dev/bus/usb node. Install a udev "
                 "rule for this VID/PID (TAG+=\"uaccess\", or MODE/GROUP), then "
                 "replug the device", vid, pid);
#endif
        } else {
            LOGE("Device VID=%04X PID=%04X found but could not be opened: %s",
                 vid, pid, libusb_error_name(openErr));
        }
        libusb_exit(ctx);
        ctx = nullptr;
        return false;
    }

    usbSpeed = libusb_get_device_speed(libusb_get_device(handle));
    LOGI("USB device speed: %s",
        usbSpeed == LIBUSB_SPEED_LOW ? "Low" :
        usbSpeed == LIBUSB_SPEED_FULL ? "Full" :
        usbSpeed == LIBUSB_SPEED_HIGH ? "High" :
        usbSpeed == LIBUSB_SPEED_SUPER ? "Super" : "Unknown");

    opened = true;
    capFirstActivationAfterOpen.store(true);
    LOGI("USB device opened: VID=%04X PID=%04X", vid, pid);
    return true;
}

std::vector<UsbAudioDeviceInfo> UsbAudioDriver::enumerateUsbAudioDevices() {
    std::vector<UsbAudioDeviceInfo> result;

    libusb_context* enumCtx = nullptr;
    if (libusb_init(&enumCtx) < 0) return result;

    libusb_device** devList = nullptr;
    ssize_t cnt = libusb_get_device_list(enumCtx, &devList);
    if (cnt < 0) {
        libusb_exit(enumCtx);
        return result;
    }

    for (ssize_t i = 0; i < cnt; i++) {
        libusb_device* dev = devList[i];
        struct libusb_device_descriptor desc;
        if (libusb_get_device_descriptor(dev, &desc) < 0) continue;

        struct libusb_config_descriptor* config = nullptr;
        if (libusb_get_active_config_descriptor(dev, &config) < 0) {
            if (libusb_get_config_descriptor(dev, 0, &config) < 0) continue;
        }

        bool hasAudioOut = false;
        for (int iface = 0; iface < config->bNumInterfaces && !hasAudioOut; iface++) {
            const struct libusb_interface& intf = config->interface[iface];
            for (int alt = 0; alt < intf.num_altsetting && !hasAudioOut; alt++) {
                const struct libusb_interface_descriptor& d = intf.altsetting[alt];
                if (d.bInterfaceClass == 1 && d.bInterfaceSubClass == 2) {
                    for (int ep = 0; ep < d.bNumEndpoints; ep++) {
                        if ((d.endpoint[ep].bEndpointAddress & 0x80) == 0 &&
                            (d.endpoint[ep].bmAttributes & 0x03) == 0x01) {
                            hasAudioOut = true;
                            break;
                        }
                    }
                }
            }
        }
        libusb_free_config_descriptor(config);

        if (!hasAudioOut) continue;

        UsbAudioDeviceInfo info;
        info.vid = desc.idVendor;
        info.pid = desc.idProduct;

        char nameBuf[256] = {};
        libusb_device_handle* h = nullptr;
        if (libusb_open(dev, &h) == 0) {
            if (desc.iProduct > 0)
                libusb_get_string_descriptor_ascii(h, desc.iProduct,
                    (unsigned char*)nameBuf, sizeof(nameBuf));
            libusb_close(h);
        }

        if (nameBuf[0]) {
            info.name = nameBuf;
        } else {
            char fallback[32];
            snprintf(fallback, sizeof(fallback), "USB Audio %04X:%04X", info.vid, info.pid);
            info.name = fallback;
        }

        result.push_back(std::move(info));
    }

    libusb_free_device_list(devList, 1);
    libusb_exit(enumCtx);
    return result;
}

bool UsbAudioDriver::parseDescriptors() {
    if (!handle) return false;

    formats.clear();
    uacVersion = 1;
    clockSourceId = -1;

    libusb_device* dev = libusb_get_device(handle);
    struct libusb_config_descriptor* config = nullptr;

    int rc = libusb_get_active_config_descriptor(dev, &config);
    if (rc < 0) {
        LOGE("get_active_config_descriptor failed: %s", libusb_error_name(rc));
        return false;
    }

    // Pass 1: Find Audio Control interface, detect UAC version, enumerate clock
    // sources, and build a terminal -> clock-source ID map. Devices that are both
    // a DAC and an ADC have two clock sources (one per direction) -- without the
    // terminal-link chain we can't tell which clock to query for which alt-setting.
    // We also collect Feature Units here so we can pick the playback-chain FU
    // for hardware volume control.
    std::vector<int> clockSourceIds;
    std::unordered_map<int, int> terminalToClock;
    // Terminal IDs whose wTerminalType == 0x0101 (USB streaming) coming IN
    // to the audio function -- i.e., the playback-path entry points.
    std::unordered_map<int, bool> isUsbStreamingIT;
    // Terminal IDs that represent physical audio entering the function
    // (Microphone 0x0201..0x0205, External 0x0601..0x0603, etc.) -- capture sources.
    std::unordered_map<int, bool> isCaptureSourceIT;
    // Output Terminals in the 0x01xx (USB function) category -- i.e. the host-side
    // sinks that stream captured audio back out via USB. Capture AudioStreaming
    // interfaces set bTerminalLink to one of these, NOT to the physical IT.
    std::unordered_map<int, bool> isUsbStreamingOT;
    struct FuCandidate { int unitId; int sourceId; bool hasVolume; bool hasMute; };
    std::vector<FuCandidate> featureUnits;
    volumeFuUnitId = -1;
    hasFuMute = false;
    for (int i = 0; i < config->bNumInterfaces; i++) {
        const struct libusb_interface& iface = config->interface[i];
        for (int a = 0; a < iface.num_altsetting; a++) {
            const struct libusb_interface_descriptor& alt = iface.altsetting[a];
            // Audio Control interface: class=1, subclass=1
            if (alt.bInterfaceClass != 1 || alt.bInterfaceSubClass != 1) continue;

            acInterfaceNum = alt.bInterfaceNumber;
            const uint8_t* extra = alt.extra;
            int extraLen = alt.extra_length;
            int pos = 0;

            while (pos + 4 < extraLen) {
                int descLen = extra[pos];
                int descType = extra[pos + 1];
                if (descLen < 3) break;

                if (descType == USB_DT_CS_INTERFACE) {
                    int subType = extra[pos + 2];
                    if (subType == UAC_HEADER && descLen >= 5) {
                        int bcdADC = extra[pos + 3] | (extra[pos + 4] << 8);
                        if (bcdADC >= UAC_VERSION_2) {
                            uacVersion = 2;
                        }
                        LOGI("UAC version: %d (bcdADC=0x%04x) on interface %d",
                             uacVersion, bcdADC, acInterfaceNum);
                    }
                    // UAC2 Clock Source descriptor
                    if (uacVersion == 2 && subType == UAC_CLOCK_SOURCE && descLen >= 5) {
                        int csId = extra[pos + 3];
                        if (clockSourceId < 0) clockSourceId = csId;  // first wins for legacy paths
                        if (std::find(clockSourceIds.begin(), clockSourceIds.end(), csId)
                                == clockSourceIds.end()) {
                            clockSourceIds.push_back(csId);
                        }
                        LOGD("UAC2 Clock Source ID: %d", csId);
                    }
                    // UAC2 Input Terminal (data flowing INTO audio function): bCSourceID at offset 7
                    if (uacVersion == 2 && subType == UAC_INPUT_TERMINAL && descLen >= 8) {
                        int termId = extra[pos + 3];
                        int termType = extra[pos + 4] | (extra[pos + 5] << 8);
                        int csId = extra[pos + 7];
                        terminalToClock[termId] = csId;
                        // 0x0101 = USB streaming -> playback entry point.
                        // Anything outside the 0x01xx (USB function) category is a
                        // physical capture source: Microphone (0x02xx), External
                        // (0x06xx), etc. Track those so we can identify capture
                        // alt-settings in pass 2.
                        if (termType == 0x0101) {
                            isUsbStreamingIT[termId] = true;
                        } else if ((termType & 0xFF00) != 0x0100) {
                            isCaptureSourceIT[termId] = true;
                        }
                        LOGD("UAC2 Input Terminal %d (type 0x%04x) -> Clock %d", termId, termType, csId);
                    }
                    // UAC1 Input Terminal: layout [3]=bTerminalID [4..5]=wTerminalType
                    // [6]=bAssocTerminal [7]=bNrChannels. No clock-source link
                    // (UAC1 sample rate lives on the AS endpoint), so skip
                    // terminalToClock here.
                    if (uacVersion < 2 && subType == UAC_INPUT_TERMINAL && descLen >= 6) {
                        int termId = extra[pos + 3];
                        int termType = extra[pos + 4] | (extra[pos + 5] << 8);
                        if (termType == 0x0101) {
                            isUsbStreamingIT[termId] = true;
                        } else if ((termType & 0xFF00) != 0x0100) {
                            isCaptureSourceIT[termId] = true;
                        }
                        LOGD("UAC1 Input Terminal %d (type 0x%04x)", termId, termType);
                    }
                    // UAC2 Output Terminal (data flowing OUT of audio function): bCSourceID at offset 8
                    if (uacVersion == 2 && subType == UAC_OUTPUT_TERMINAL && descLen >= 9) {
                        int termId = extra[pos + 3];
                        int termType = extra[pos + 4] | (extra[pos + 5] << 8);
                        int csId = extra[pos + 8];
                        terminalToClock[termId] = csId;
                        // OTs in the 0x01xx category stream data back to the host
                        // (USB Streaming = 0x0101, etc.). A capture AS interface's
                        // bTerminalLink points to one of these.
                        if ((termType & 0xFF00) == 0x0100) {
                            isUsbStreamingOT[termId] = true;
                        }
                        LOGD("UAC2 Output Terminal %d (type 0x%04x) -> Clock %d",
                             termId, termType, csId);
                    }
                    // UAC1 Output Terminal: layout [3]=bTerminalID [4..5]=wTerminalType
                    // [6]=bAssocTerminal [7]=bSourceID [8]=iTerminal. Capture AS
                    // interfaces set bTerminalLink to one of these (the host-side
                    // streaming sink, terminal type 0x0101).
                    if (uacVersion < 2 && subType == UAC_OUTPUT_TERMINAL && descLen >= 6) {
                        int termId = extra[pos + 3];
                        int termType = extra[pos + 4] | (extra[pos + 5] << 8);
                        if ((termType & 0xFF00) == 0x0100) {
                            isUsbStreamingOT[termId] = true;
                        }
                        LOGD("UAC1 Output Terminal %d (type 0x%04x)", termId, termType);
                    }
                    // UAC2 Feature Unit: layout [3]=bUnitID [4]=bSourceID
                    // [5..]=bmaControls[N+1] (4 bytes per channel, master first).
                    // Master controls live at offset 5..8 little-endian.
                    if (uacVersion == 2 && subType == UAC_FEATURE_UNIT && descLen >= 11) {
                        FuCandidate fu{};
                        fu.unitId = extra[pos + 3];
                        fu.sourceId = extra[pos + 4];
                        uint32_t masterCtrls = (uint32_t)extra[pos + 5]
                                             | ((uint32_t)extra[pos + 6] << 8)
                                             | ((uint32_t)extra[pos + 7] << 16)
                                             | ((uint32_t)extra[pos + 8] << 24);
                        // bits[1:0]=mute, bits[3:2]=volume. !=0 means present.
                        fu.hasMute = ((masterCtrls >> 0) & 0x3) != 0;
                        fu.hasVolume = ((masterCtrls >> 2) & 0x3) != 0;
                        featureUnits.push_back(fu);
                        LOGD("UAC2 Feature Unit %d <- src %d ctrls=0x%08x vol=%d mute=%d",
                             fu.unitId, fu.sourceId, masterCtrls, fu.hasVolume, fu.hasMute);
                    }
                    // UAC1 Feature Unit: layout [3]=bUnitID [4]=bSourceID
                    // [5]=bControlSize [6..]=bmaControls[N+1] (bControlSize bytes each).
                    // Master is the first entry. bit 0=mute, bit 1=volume.
                    if (uacVersion < 2 && subType == UAC_FEATURE_UNIT && descLen >= 7) {
                        FuCandidate fu{};
                        fu.unitId = extra[pos + 3];
                        fu.sourceId = extra[pos + 4];
                        int ctrlSize = extra[pos + 5];
                        if (ctrlSize >= 1 && descLen >= 6 + ctrlSize) {
                            uint8_t masterCtrls = extra[pos + 6];
                            fu.hasMute = (masterCtrls & 0x01) != 0;
                            fu.hasVolume = (masterCtrls & 0x02) != 0;
                        }
                        featureUnits.push_back(fu);
                        LOGD("UAC1 Feature Unit %d <- src %d vol=%d mute=%d",
                             fu.unitId, fu.sourceId, fu.hasVolume, fu.hasMute);
                    }
                }
                pos += descLen;
            }
        }
    }

    // Pass 1.5: UAC2 only -- query every clock source we found. Each can have
    // a different rate range (e.g., DAC clock 44.1k..384k, ADC clock 44.1k..96k).
    // Cache results per clock so pass 2 can pick the right list per alt-setting.
    std::unordered_map<int, std::vector<int>> clockRates;
    if (uacVersion >= 2) {
        for (int csId : clockSourceIds) {
            clockRates[csId] = queryUac2SampleRates(csId);
        }
    }

    // Pick the best Feature Unit for hardware volume. Prefer FUs whose source
    // chain is rooted at a USB-streaming Input Terminal (the playback path).
    // Fall back to any FU with volume on master. DAC+ADC devices have an FU
    // per direction; this prefers the playback one.
    for (auto& fu : featureUnits) {
        if (!fu.hasVolume) continue;
        if (isUsbStreamingIT.count(fu.sourceId)) {
            volumeFuUnitId = fu.unitId;
            hasFuMute = fu.hasMute;
            break;
        }
    }
    if (volumeFuUnitId < 0) {
        // No FU directly fed by a USB-streaming IT. Could be chained through
        // a Selector/Mixer unit -- take the first FU with volume on master.
        for (auto& fu : featureUnits) {
            if (fu.hasVolume) {
                volumeFuUnitId = fu.unitId;
                hasFuMute = fu.hasMute;
                break;
            }
        }
    }
    if (volumeFuUnitId >= 0) {
        LOGI("Selected Feature Unit %d for hardware volume (mute=%d)",
             volumeFuUnitId, hasFuMute);
        queryHwVolumeRange();
    } else {
        LOGI("No usable Feature Unit with FU_VOLUME found -- software gain only");
    }

    // Pass 2: Find Audio Streaming interfaces
    for (int i = 0; i < config->bNumInterfaces; i++) {
        const struct libusb_interface& iface = config->interface[i];

        for (int a = 0; a < iface.num_altsetting; a++) {
            const struct libusb_interface_descriptor& alt = iface.altsetting[a];

            if (alt.bInterfaceClass != 1 || alt.bInterfaceSubClass != 2) continue;
            if (alt.bNumEndpoints == 0) continue;

            // Walk endpoints. We classify direction here but don't decide which
            // is data vs. feedback yet -- that depends on whether the AS feeds
            // a playback (USB-streaming) IT or a capture-source IT, which we
            // learn from AS_GENERAL.bTerminalLink parsed below.
            int outEp = -1, inEp = -1;
            int rawMaxPacketOut = 0, rawMaxPacketIn = 0;

            for (int e = 0; e < alt.bNumEndpoints; e++) {
                const struct libusb_endpoint_descriptor& ep = alt.endpoint[e];
                if ((ep.bmAttributes & 0x03) != LIBUSB_TRANSFER_TYPE_ISOCHRONOUS) continue;

                if ((ep.bEndpointAddress & LIBUSB_ENDPOINT_DIR_MASK) == LIBUSB_ENDPOINT_OUT) {
                    outEp = ep.bEndpointAddress;
                    rawMaxPacketOut = ep.wMaxPacketSize;
                } else {
                    inEp = ep.bEndpointAddress;
                    rawMaxPacketIn = ep.wMaxPacketSize;
                }
            }
            if (outEp < 0 && inEp < 0) continue;

            // Parse class-specific AS descriptors for format info
            int channels = 2;
            int bitDepth = 16;
            int subslotSize = 0;  // 0 = not parsed, will default to bitDepth/8 later
            uint32_t bmFormats = 0;  // UAC2 AS_GENERAL bmFormats (bit 31 = RAW_DATA / DSD)
            int bTerminalLink = 0;   // UAC2 AS_GENERAL.bTerminalLink -> terminalToClock lookup
            std::vector<int> rates;

            const uint8_t* extra = alt.extra;
            int extraLen = alt.extra_length;
            int pos = 0;

            while (pos + 2 < extraLen) {
                int descLen = extra[pos];
                int descType = extra[pos + 1];
                if (descLen < 2) break;

                if (descType == USB_DT_CS_INTERFACE && pos + 3 < extraLen) {
                    int subType = extra[pos + 2];

                    if (uacVersion >= 2) {
                        // UAC2 AS_GENERAL: bNrChannels at offset 10
                        // Layout: [0]=bLength [1]=bDescriptorType [2]=bDescriptorSubtype
                        //         [3]=bTerminalLink [4]=bmControls [5]=bFormatType
                        //         [6..9]=bmFormats [10]=bNrChannels
                        if (subType == UAC_AS_GENERAL && descLen >= 16) {
                            bTerminalLink = extra[pos + 3];
                            channels = extra[pos + 10];
                            bmFormats = (uint32_t)extra[pos + 6]
                                      | ((uint32_t)extra[pos + 7] << 8)
                                      | ((uint32_t)extra[pos + 8] << 16)
                                      | ((uint32_t)extra[pos + 9] << 24);
                        }
                        // UAC2 FORMAT_TYPE: bSubslotSize at offset 4, bBitResolution at offset 5
                        if (subType == UAC_FORMAT_TYPE && descLen >= 6) {
                            subslotSize = extra[pos + 4];
                            bitDepth = extra[pos + 5];
                        }
                    } else {
                        // UAC1 AS_GENERAL: layout [3]=bTerminalLink [4]=bDelay
                        // [5..6]=wFormatTag. Capture-direction classification
                        // below uses bTerminalLink to look up the linked OT/IT.
                        if (subType == UAC_AS_GENERAL && descLen >= 4) {
                            bTerminalLink = extra[pos + 3];
                        }
                        // UAC1 FORMAT_TYPE descriptor
                        // Layout: [3]=bFormatType [4]=bNrChannels [5]=bSubFrameSize
                        //         [6]=bBitResolution [7]=bSamFreqType ...
                        if (subType == UAC_FORMAT_TYPE && descLen >= 8) {
                            if (extra[pos + 3] == UAC_FORMAT_TYPE_I) {
                                channels = extra[pos + 4];
                                subslotSize = extra[pos + 5];
                                bitDepth = extra[pos + 6];
                                int numRates = extra[pos + 7];
                                if (numRates == 0 && descLen >= 14) {
                                    int minRate = extra[pos + 8] | (extra[pos + 9] << 8) | (extra[pos + 10] << 16);
                                    int maxRate = extra[pos + 11] | (extra[pos + 12] << 8) | (extra[pos + 13] << 16);
                                    int commonRates[] = {44100, 48000, 88200, 96000,
                                                         176400, 192000, 352800, 384000};
                                    for (int r : commonRates) {
                                        if (r >= minRate && r <= maxRate) rates.push_back(r);
                                    }
                                } else {
                                    for (int r = 0; r < numRates && pos + 8 + r * 3 + 2 < extraLen; r++) {
                                        int off = pos + 8 + r * 3;
                                        int rate = extra[off] | (extra[off + 1] << 8) | (extra[off + 2] << 16);
                                        rates.push_back(rate);
                                    }
                                }
                            }
                        }
                    }
                }
                pos += descLen;
            }

            // Resolve which clock source serves this alt-setting.
            // AS_GENERAL.bTerminalLink -> terminalToClock map. Fall back to the
            // first clock found (legacy single-clock devices).
            int resolvedClockId = -1;
            if (uacVersion >= 2 && bTerminalLink > 0) {
                auto it = terminalToClock.find(bTerminalLink);
                if (it != terminalToClock.end()) resolvedClockId = it->second;
            }
            if (resolvedClockId < 0) resolvedClockId = clockSourceId;

            // Direction: capture iff bTerminalLink points to a physical source IT
            // (Microphone/Line-in/etc.). On capture alt-settings the IN EP carries
            // data and there is no async feedback EP (capture is source-clocked).
            // Without a positive capture hint we keep legacy behavior: iso OUT is
            // data, iso IN is feedback.
            // A capture AS interface's bTerminalLink points to the host-side
            // streaming OT (e.g. type 0x0101). Some non-standard firmware points
            // it straight at the physical capture IT (Mic 0x02xx etc.) -- accept
            // that too for robustness.
            bool isCaptureAs = false;
            if (bTerminalLink > 0
                    && (isUsbStreamingOT.count(bTerminalLink)
                            || isCaptureSourceIT.count(bTerminalLink))) {
                isCaptureAs = true;
            }

            int dataEp, rawMaxPacket, fbEp;
            if (isCaptureAs) {
                if (inEp < 0) continue;            // capture AS without IN EP is malformed
                dataEp = inEp;
                rawMaxPacket = rawMaxPacketIn;
                fbEp = -1;
            } else {
                if (outEp < 0) continue;           // playback AS without OUT EP -- skip
                dataEp = outEp;
                rawMaxPacket = rawMaxPacketOut;
                fbEp = inEp;
                if (fbEp >= 0) {
                    LOGI("  Feedback EP 0x%02x on iface %d alt %d",
                         fbEp, alt.bInterfaceNumber, alt.bAlternateSetting);
                }
            }

            if (rates.empty()) {
                // Prefer rates for this alt's own clock source. On DAC+ADC devices
                // this keeps the ADC's lower ceiling from leaking into playback.
                if (resolvedClockId >= 0) {
                    auto it = clockRates.find(resolvedClockId);
                    if (it != clockRates.end() && !it->second.empty()) {
                        rates = it->second;
                    }
                }
                if (rates.empty()) {
                    // Conservative fallback. High rates (705.6k/768k) ONLY enter
                    // via a successful UAC2 GET RANGE -- otherwise we'd be
                    // claiming capabilities the DAC may not have, and start()
                    // could ship isochronous data at an un-acked rate (noise).
                    rates = {44100, 48000, 88200, 96000, 176400, 192000,
                             352800, 384000};
                }
            }

            // Fall back if subslot wasn't parsed (older/malformed descriptors)
            int effectiveSubslot = (subslotSize > 0) ? subslotSize : ((bitDepth + 7) / 8);

            for (int rate : rates) {
                UsbAudioFormat fmt{};
                fmt.interfaceNum = alt.bInterfaceNumber;
                fmt.altSetting = alt.bAlternateSetting;
                fmt.endpointAddr = dataEp;
                fmt.maxPacketSize = rawMaxPacket;
                fmt.sampleRate = rate;
                fmt.channels = channels;
                fmt.bitDepth = bitDepth;
                fmt.subslotSize = effectiveSubslot;
                fmt.bmFormats = bmFormats;
                fmt.isDsd = (bmFormats & 0x80000000u) != 0;
                fmt.isCapture = isCaptureAs;
                fmt.feedbackEpAddr = fbEp;
                fmt.clockSourceId = resolvedClockId;
                formats.push_back(fmt);
            }
        }
    }

    libusb_free_config_descriptor(config);

    // Duplicate-alt heuristic: some DACs expose two alt-settings with identical
    // (rate, channels, bitDepth) but don't set the RAW_DATA bit in bmFormats.
    // When that happens, assume the higher-numbered alt is the DSD one. Only
    // applies within a single direction -- capture alts never produce DSD.
    for (auto& f : formats) {
        if (f.isDsd || f.isCapture) continue;
        bool dsdAlreadyInGroup = false;
        UsbAudioFormat* higher = nullptr;
        for (auto& g : formats) {
            if (&g == &f) continue;
            if (g.isCapture) continue;
            if (g.sampleRate != f.sampleRate) continue;
            if (g.channels != f.channels) continue;
            if (g.bitDepth != f.bitDepth) continue;
            if (g.isDsd) { dsdAlreadyInGroup = true; break; }
            if (g.altSetting > f.altSetting && (!higher || g.altSetting > higher->altSetting)) {
                higher = &g;
            }
        }
        if (dsdAlreadyInGroup || !higher) continue;
        if (!higher->isDsd) {
            higher->isDsd = true;
            LOGI("heuristic: marked alt=%d (rate=%d ch=%d bits=%d) as DSD candidate",
                 higher->altSetting, higher->sampleRate, higher->channels, higher->bitDepth);
        }
    }

    LOGI("Parsed %zu format(s), UAC%d", formats.size(), uacVersion);
    // f is read only inside LOGD, which compiles to a no-op unless
    // USB_AUDIO_VERBOSE is set — see the LOGD macro definitions above.
    for ([[maybe_unused]] auto& f : formats) {
        LOGD("  iface=%d alt=%d ep=0x%02x rate=%d ch=%d bits=%d subslot=%d bmFormats=0x%08x dsd=%d cap=%d clk=%d maxpkt=0x%04x fb=0x%02x",
             f.interfaceNum, f.altSetting, f.endpointAddr,
             f.sampleRate, f.channels, f.bitDepth, f.subslotSize,
             f.bmFormats, f.isDsd ? 1 : 0, f.isCapture ? 1 : 0, f.clockSourceId,
             f.maxPacketSize, f.feedbackEpAddr);
    }

    return !formats.empty();
}

std::vector<int> UsbAudioDriver::getSupportedRates() {
    // Historical name; returns output-side rates only. The merged `formats`
    // vector now also contains capture alt-settings (added later), so we
    // filter to keep this API's playback semantics intact for legacy callers.
    std::vector<int> rates;
    for (auto& f : formats) {
        if (f.isCapture) continue;
        if (std::find(rates.begin(), rates.end(), f.sampleRate) == rates.end()) {
            rates.push_back(f.sampleRate);
        }
    }
    std::sort(rates.begin(), rates.end());
    return rates;
}

bool UsbAudioDriver::setInterfaceAltSetting(int interface_num, int alt_setting) {
    int rc = libusb_set_interface_alt_setting(handle, interface_num, alt_setting);
    if (rc < 0) {
        LOGE("set_interface_alt_setting(%d, %d) failed: %s",
             interface_num, alt_setting, libusb_error_name(rc));
        return false;
    }
    return true;
}

bool UsbAudioDriver::setSampleRate(int endpoint, int rate) {
    // UAC1: 3-byte SET_CUR to endpoint
    uint8_t data[3];
    data[0] = static_cast<uint8_t>(rate & 0xFF);
    data[1] = static_cast<uint8_t>((rate >> 8) & 0xFF);
    data[2] = static_cast<uint8_t>((rate >> 16) & 0xFF);

    int rc = libusb_control_transfer(handle,
        LIBUSB_ENDPOINT_OUT | LIBUSB_REQUEST_TYPE_CLASS | LIBUSB_RECIPIENT_ENDPOINT,
        UAC_SET_CUR,
        0x0100, // SAMPLING_FREQ_CONTROL
        static_cast<uint16_t>(endpoint),
        data, 3, 1000);

    if (rc < 0) {
        LOGE("UAC1 setSampleRate(%d) failed: %s", rate, libusb_error_name(rc));
        return true; // non-fatal, some devices auto-detect
    }

    LOGI("UAC1: set sample rate %d Hz on EP 0x%02x", rate, endpoint);
    return true;
}

bool UsbAudioDriver::setSampleRateUAC2(int clockId, int rate) {
    // UAC2: 4-byte SET_CUR to clock source entity on AC interface
    uint8_t data[4];
    data[0] = static_cast<uint8_t>(rate & 0xFF);
    data[1] = static_cast<uint8_t>((rate >> 8) & 0xFF);
    data[2] = static_cast<uint8_t>((rate >> 16) & 0xFF);
    data[3] = static_cast<uint8_t>((rate >> 24) & 0xFF);

    // wValue = CS << 8 | CN, CS = SAM_FREQ_CONTROL (0x01), CN = 0
    // wIndex = clock source ID << 8 | interface number
    uint16_t wIndex = (uint16_t)((clockId << 8) | acInterfaceNum);

    int rc = libusb_control_transfer(handle,
        LIBUSB_ENDPOINT_OUT | LIBUSB_REQUEST_TYPE_CLASS | LIBUSB_RECIPIENT_INTERFACE,
        UAC_SET_CUR,
        (UAC2_CS_CONTROL_SAM_FREQ << 8),
        wIndex,
        data, 4, 1000);

    if (rc < 0) {
        LOGE("UAC2 setSampleRate(%d) clockId=%d failed: %s",
             rate, clockId, libusb_error_name(rc));
        return false;
    }

    LOGI("UAC2: set sample rate %d Hz on clock source %d", rate, clockId);

    // Read back SAM_FREQ_CONTROL — diagnostic for first-record empty-WAV bug.
    // Some ADCs silently no-op SET_CUR when the requested rate matches their
    // current internal state, and an iso IN endpoint configured against an
    // un-armed ADC delivers COMPLETED-but-empty packets. If the readback rate
    // here differs from `rate`, the SET_CUR didn't take effect.
    uint8_t readBuf[4] = {0};
    int rcRead = libusb_control_transfer(handle,
        LIBUSB_ENDPOINT_IN | LIBUSB_REQUEST_TYPE_CLASS | LIBUSB_RECIPIENT_INTERFACE,
        UAC_GET_CUR,
        (UAC2_CS_CONTROL_SAM_FREQ << 8),
        wIndex,
        readBuf, 4, 1000);
    if (rcRead >= 4) {
        uint32_t reportedRate = (uint32_t)readBuf[0]
                | ((uint32_t)readBuf[1] << 8)
                | ((uint32_t)readBuf[2] << 16)
                | ((uint32_t)readBuf[3] << 24);
        if ((int)reportedRate == rate) {
            LOGI("UAC2: GET_CUR sample_rate=%u Hz (matches requested)", reportedRate);
        } else {
            LOGE("UAC2: GET_CUR sample_rate=%u Hz but requested %d Hz — SET_CUR may have been ignored",
                 reportedRate, rate);
        }
    } else {
        LOGD("UAC2: GET_CUR sample_rate readback not supported (rc=%d)", rcRead);
    }

    // Verify clock is valid
    uint8_t validBuf[1] = {0};
    rc = libusb_control_transfer(handle,
        LIBUSB_ENDPOINT_IN | LIBUSB_REQUEST_TYPE_CLASS | LIBUSB_RECIPIENT_INTERFACE,
        UAC_GET_CUR,
        (UAC2_CS_CONTROL_CLOCK_VALID << 8),
        wIndex,
        validBuf, 1, 1000);

    if (rc >= 1) {
        if (validBuf[0]) {
            LOGI("UAC2: clock valid after rate change");
        } else {
            LOGE("UAC2: clock reports INVALID after rate change");
        }
    } else {
        LOGD("UAC2: clock valid query not supported (rc=%d), continuing", rc);
    }

    return true;
}

bool UsbAudioDriver::queryHwVolumeRange() {
    if (!handle || volumeFuUnitId < 0) return false;

    // UAC2 GET RANGE on FU_VOLUME_CONTROL, master channel.
    // Response: wNumSubRanges (2 bytes) + N * (wMIN, wMAX, wRES) each 2 bytes Q8.8 dB.
    const uint8_t bmRequest = LIBUSB_ENDPOINT_IN | LIBUSB_REQUEST_TYPE_CLASS
                            | LIBUSB_RECIPIENT_INTERFACE;
    const uint8_t bRequest = UAC2_RANGE;
    const uint16_t wValue = (UAC_FU_VOLUME_CONTROL << 8) | 0;  // master channel
    const uint16_t wIndex = (uint16_t)((volumeFuUnitId << 8) | acInterfaceNum);

    bool tempClaim = false;
    if (acInterfaceNum >= 0 && !acInterfaceClaimed) {
        if (libusb_claim_interface(handle, acInterfaceNum) == 0) tempClaim = true;
    }

    if (uacVersion >= 2) {
        uint8_t header[2] = {0, 0};
        int rc = libusb_control_transfer(handle, bmRequest, bRequest, wValue, wIndex,
                                         header, sizeof(header), 1000);
        if (rc < 2) {
            LOGI("FU volume GET_RANGE header read failed (rc=%d), using defaults", rc);
            if (tempClaim) libusb_release_interface(handle, acInterfaceNum);
            return false;
        }
        uint16_t n = (uint16_t)header[0] | ((uint16_t)header[1] << 8);
        if (n == 0) {
            if (tempClaim) libusb_release_interface(handle, acInterfaceNum);
            return false;
        }
        int totalLen = 2 + (int)n * 6;
        std::vector<uint8_t> buf(totalLen, 0);
        rc = libusb_control_transfer(handle, bmRequest, bRequest, wValue, wIndex,
                                     buf.data(), (uint16_t)totalLen, 1000);
        if (rc < totalLen) {
            LOGI("FU volume GET_RANGE full read short (rc=%d expected=%d)", rc, totalLen);
            if (tempClaim) libusb_release_interface(handle, acInterfaceNum);
            return false;
        }
        // Take the widest range across all sub-ranges (most DACs report one).
        int16_t minDb = 32767, maxDb = -32768;
        uint16_t res = 256;
        for (int i = 0; i < n; i++) {
            int off = 2 + i * 6;
            int16_t mn = (int16_t)(buf[off]     | ((uint16_t)buf[off + 1] << 8));
            int16_t mx = (int16_t)(buf[off + 2] | ((uint16_t)buf[off + 3] << 8));
            uint16_t rs = (uint16_t)(buf[off + 4] | ((uint16_t)buf[off + 5] << 8));
            if (mn < minDb) minDb = mn;
            if (mx > maxDb) maxDb = mx;
            if (rs > 0) res = rs;
        }
        volumeMinDbQ8 = minDb;
        volumeMaxDbQ8 = maxDb;
        volumeResDbQ8 = (int16_t)res;
        LOGI("FU volume range: min=%.2f dB max=%.2f dB res=%.2f dB",
             volumeMinDbQ8 / 256.0f, volumeMaxDbQ8 / 256.0f, volumeResDbQ8 / 256.0f);
    }
    // UAC1: would require three separate GET_MIN/MAX/RES queries. Keep defaults
    // for UAC1 (-32 dB .. 0 dB, 1 dB step); good enough for SET_CUR to work.

    if (tempClaim) libusb_release_interface(handle, acInterfaceNum);
    return true;
}

bool UsbAudioDriver::setHwVolumeDbQ8(int valueDbQ8) {
    if (!handle || volumeFuUnitId < 0) return false;
    // Clamp to declared range.
    if (valueDbQ8 < volumeMinDbQ8) valueDbQ8 = volumeMinDbQ8;
    if (valueDbQ8 > volumeMaxDbQ8) valueDbQ8 = volumeMaxDbQ8;

    int16_t v = (int16_t)valueDbQ8;
    uint8_t data[2] = { (uint8_t)(v & 0xFF), (uint8_t)((v >> 8) & 0xFF) };

    const uint16_t wValue = (UAC_FU_VOLUME_CONTROL << 8) | 0;
    const uint16_t wIndex = (uint16_t)((volumeFuUnitId << 8) | acInterfaceNum);

    int rc = libusb_control_transfer(handle,
        LIBUSB_ENDPOINT_OUT | LIBUSB_REQUEST_TYPE_CLASS | LIBUSB_RECIPIENT_INTERFACE,
        UAC_SET_CUR, wValue, wIndex, data, sizeof(data), 1000);
    if (rc < 0) {
        LOGE("setHwVolumeDbQ8(%d) failed: %s", valueDbQ8, libusb_error_name(rc));
        return false;
    }
    return true;
}

bool UsbAudioDriver::setHwMute(bool muted) {
    if (!handle || volumeFuUnitId < 0 || !hasFuMute) return false;
    uint8_t data[1] = { (uint8_t)(muted ? 1 : 0) };

    const uint16_t wValue = (UAC_FU_MUTE_CONTROL << 8) | 0;
    const uint16_t wIndex = (uint16_t)((volumeFuUnitId << 8) | acInterfaceNum);

    int rc = libusb_control_transfer(handle,
        LIBUSB_ENDPOINT_OUT | LIBUSB_REQUEST_TYPE_CLASS | LIBUSB_RECIPIENT_INTERFACE,
        UAC_SET_CUR, wValue, wIndex, data, sizeof(data), 1000);
    if (rc < 0) {
        LOGE("setHwMute(%d) failed: %s", muted, libusb_error_name(rc));
        return false;
    }
    return true;
}

std::vector<int> UsbAudioDriver::queryUac2SampleRates(int clockId) {
    // UAC2 GET RANGE on CS_SAM_FREQ_CONTROL of the clock source unit.
    // Response: wNumSubRanges (2 bytes) followed by N * (dMIN, dMAX, dRES) of 4
    // bytes each (12 bytes per sub-range). All values little-endian.
    std::vector<int> result;
    if (!handle) return result;

    const uint8_t bmRequest = LIBUSB_ENDPOINT_IN
                            | LIBUSB_REQUEST_TYPE_CLASS
                            | LIBUSB_RECIPIENT_INTERFACE;
    const uint8_t bRequest = 0x02;  // RANGE
    const uint16_t wValue = (UAC2_CS_CONTROL_SAM_FREQ << 8);
    const uint16_t wIndex = (uint16_t)((clockId << 8) | acInterfaceNum);

    // Some Android USB stacks require the AC interface to be claimed before
    // accepting class-specific control transfers to it. Claim here, release
    // after; start() will re-claim later.
    bool tempClaim = false;
    if (acInterfaceNum >= 0 && !acInterfaceClaimed) {
        if (libusb_claim_interface(handle, acInterfaceNum) == 0) {
            tempClaim = true;
        }
    }

    // First read: 2-byte header to learn wNumSubRanges.
    uint8_t header[2] = {0, 0};
    int rc = libusb_control_transfer(handle, bmRequest, bRequest, wValue, wIndex,
                                     header, sizeof(header), 1000);
    if (rc < 2) {
        LOGI("UAC2 GET RANGE header read failed (rc=%d), falling back to hardcoded rates",
             rc);
        if (tempClaim) libusb_release_interface(handle, acInterfaceNum);
        return result;
    }
    uint16_t numSubRanges = (uint16_t)header[0] | ((uint16_t)header[1] << 8);
    if (numSubRanges == 0) {
        LOGI("UAC2 GET RANGE: zero sub-ranges reported");
        if (tempClaim) libusb_release_interface(handle, acInterfaceNum);
        return result;
    }

    int totalLen = 2 + (int)numSubRanges * 12;
    std::vector<uint8_t> buf(totalLen, 0);
    rc = libusb_control_transfer(handle, bmRequest, bRequest, wValue, wIndex,
                                 buf.data(), (uint16_t)totalLen, 1000);
    if (rc < totalLen) {
        LOGI("UAC2 GET RANGE full read short (rc=%d expected=%d)", rc, totalLen);
        if (tempClaim) libusb_release_interface(handle, acInterfaceNum);
        return result;
    }

    LOGD("UAC2 Clock %d: %d sub-range(s)", clockId, (int)numSubRanges);
    const int commonRates[] = {44100, 48000, 88200, 96000, 176400, 192000,
                               352800, 384000, 705600, 768000};
    for (int i = 0; i < numSubRanges; i++) {
        int off = 2 + i * 12;
        uint32_t dMin = (uint32_t)buf[off]
                      | ((uint32_t)buf[off + 1] << 8)
                      | ((uint32_t)buf[off + 2] << 16)
                      | ((uint32_t)buf[off + 3] << 24);
        uint32_t dMax = (uint32_t)buf[off + 4]
                      | ((uint32_t)buf[off + 5] << 8)
                      | ((uint32_t)buf[off + 6] << 16)
                      | ((uint32_t)buf[off + 7] << 24);
        // Read only inside LOGD, a no-op unless USB_AUDIO_VERBOSE is set.
        [[maybe_unused]] uint32_t dRes = (uint32_t)buf[off + 8]
                      | ((uint32_t)buf[off + 9] << 8)
                      | ((uint32_t)buf[off + 10] << 16)
                      | ((uint32_t)buf[off + 11] << 24);
        LOGD("  sub-range %d: min=%u max=%u res=%u", i, dMin, dMax, dRes);

        if (dMin == dMax) {
            // Singleton sub-range: a single discrete fixed rate.
            result.push_back((int)dMin);
        } else {
            // Range (continuous when dRes==0, stepped otherwise). In both
            // cases enumerate the standard audiophile rates that fall inside
            // [dMin, dMax]. We don't filter by dRes alignment because real
            // DACs step at 1 Hz or implement the standard rates directly.
            for (int r : commonRates) {
                if ((uint32_t)r >= dMin && (uint32_t)r <= dMax) {
                    result.push_back(r);
                }
            }
        }
    }

    if (tempClaim) libusb_release_interface(handle, acInterfaceNum);

    std::sort(result.begin(), result.end());
    result.erase(std::unique(result.begin(), result.end()), result.end());
    return result;
}

bool UsbAudioDriver::configure(int sampleRate, int channels, int bitDepth, bool preferDsd) {
    if (!opened) {
        LOGE("configure() called but not opened");
        return false;
    }

    // Stop-before-reconfigure guard
    if (streaming.load()) {
        LOGI("configure() called while streaming -- stopping first");
        stop();
    }

    LOGI("configure requested: rate=%d ch=%d bits=%d preferDsd=%d",
         sampleRate, channels, bitDepth, preferDsd ? 1 : 0);

    // Pass 1: exact match including DSD preference. When preferDsd is true we
    // only accept a DSD-flagged alt; when false we only accept a non-DSD alt.
    // Capture alts (ADC IN) are never candidates for the playback configure().
    UsbAudioFormat* best = nullptr;
    for (auto& f : formats) {
        if (f.isCapture) continue;
        if (f.sampleRate == sampleRate && f.channels == channels && f.bitDepth == bitDepth
                && f.isDsd == preferDsd) {
            best = &f;
            break;
        }
    }
    // Pass 2: exact match on (rate, channels, bits) ignoring DSD flag.
    if (!best) {
        for (auto& f : formats) {
            if (f.isCapture) continue;
            if (f.sampleRate == sampleRate && f.channels == channels && f.bitDepth == bitDepth) {
                best = &f;
                break;
            }
        }
    }
    // Relax: match rate, prefer highest bit depth
    if (!best) {
        for (auto& f : formats) {
            if (f.isCapture) continue;
            if (f.sampleRate == sampleRate) {
                if (!best || f.bitDepth > best->bitDepth) {
                    best = &f;
                }
            }
        }
    }
    if (!best) {
        LOGE("No matching format for rate=%d ch=%d bits=%d", sampleRate, channels, bitDepth);
        return false;
    }
    LOGI("Selected alt=%d (isDsd=%d) for rate=%d ch=%d bits=%d",
         best->altSetting, best->isDsd ? 1 : 0,
         best->sampleRate, best->channels, best->bitDepth);

    activeFormat = *best;
    configuredRate = sampleRate;
    configuredChannels = best->channels;
    configuredBitDepth = best->bitDepth;
    configuredSubslotSize = (best->subslotSize > 0)
            ? best->subslotSize : ((best->bitDepth + 7) / 8);
    configured = true;

    LOGI("Configured: rate=%d ch=%d bits=%d subslot=%d iface=%d alt=%d ep=0x%02x UAC%d",
         configuredRate, configuredChannels, configuredBitDepth, configuredSubslotSize,
         activeFormat.interfaceNum, activeFormat.altSetting,
         activeFormat.endpointAddr, uacVersion);

    // Arm a ~30 ms startup fade-in (in channel-samples) so the first audio
    // ramps from silence instead of popping. Re-armed on every configure()
    // (i.e. every cold start); seamless gapless track swaps don't reconfigure,
    // so they won't re-trigger a fade mid-stream.
    fadeSampleTarget_ = (int64_t)(configuredRate * 30 / 1000) * configuredChannels;
    fadeSampleCounter_ = 0;

    // Allocate ring buffer now so callers can pre-fill before start().
    int bytesPerFrame = configuredSubslotSize * configuredChannels;
    int ringSize = (int)((int64_t)configuredRate * bytesPerFrame * playbackRingMs / 1000);
    if (ringSize < 131072) ringSize = 131072;
    delete ringBuffer;
    ringBuffer = new RingBuffer(ringSize);
    LOGI("Ring buffer allocated in configure(): %d bytes (%.0f ms)",
         ringSize, ringSize * 1000.0 / (configuredRate * bytesPerFrame));

    return true;
}

// --- Transfer callbacks ---

void UsbAudioDriver::transferCallback(struct libusb_transfer* transfer) {
    auto* ctx = static_cast<XferCtx*>(transfer->user_data);
    ctx->drv->handleTransferComplete(transfer, ctx->index);
}

void UsbAudioDriver::handleTransferComplete(struct libusb_transfer* transfer, int index) {
    // This transfer just left the in-flight pool. Track the running minimum so
    // the log can reveal whether the queue ever drains toward empty.
    //
    // Verbose-only: the millis_now() below is a clock_gettime on every
    // completion, and the whole block is a diagnostic for a bug that has since
    // been characterised (see the queue-geometry note in usb_audio.h). The
    // decrement itself is kept unconditionally because transfersInFlight is
    // read elsewhere.
    transfersInFlight.fetch_sub(1, std::memory_order_relaxed);
#ifdef USB_AUDIO_VERBOSE
    {
        int now_inflight = transfersInFlight.load(std::memory_order_relaxed);
        if (now_inflight < minInFlight) minInFlight = now_inflight;
        uint32_t t = millis_now();
        if ((t - lastInFlightLogMs) >= 1000) {
            LOGI("iso queue: min in-flight over last interval = %d / %d",
                 minInFlight, numTransfers);
            minInFlight = transfersInFlight.load(std::memory_order_relaxed);
            lastInFlightLogMs = t;
        }
    }
#endif

    if (!streaming.load()) {
        activeTransfers--;
        return;
    }

    if (transfer->status != LIBUSB_TRANSFER_COMPLETED &&
        transfer->status != LIBUSB_TRANSFER_TIMED_OUT) {
        uint32_t now = millis_now();
        if ((now - lastTransferErrLogMs) >= 1000) {
            LOGE("Transfer status=%d (%s) active=%d ring=%zu",
                 transfer->status,
                 transfer->status == LIBUSB_TRANSFER_ERROR ? "ERROR" :
                 transfer->status == LIBUSB_TRANSFER_STALL ? "STALL" :
                 transfer->status == LIBUSB_TRANSFER_NO_DEVICE ? "NO_DEVICE" :
                 transfer->status == LIBUSB_TRANSFER_OVERFLOW ? "OVERFLOW" :
                 transfer->status == LIBUSB_TRANSFER_CANCELLED ? "CANCELLED" : "UNKNOWN",
                 activeTransfers.load(),
                 ringBuffer ? ringBuffer->getAvailable() : 0);
            lastTransferErrLogMs = now;
        }
        int remaining = --activeTransfers;
        if (remaining <= 0) {
            LOGE("All isochronous transfers failed -- stopping streaming");
            streaming.store(false);
        }
        return;
    }

    // The index arrives with the callback (see XferCtx in usb_audio.h) rather
    // than being recovered by scanning the transfers[] array for a pointer
    // match, which was O(numTransfers) — up to 64 comparisons — on every
    // completion.
    if (index < 0 || index >= numTransfers || transfers[index] != transfer) {
        int remaining = --activeTransfers;
        if (remaining <= 0) {
            LOGE("All transfers lost -- stopping streaming");
            streaming.store(false);
        }
        return;
    }

    // This transfer's real audio has now reached the DAC: move it from
    // "in flight" to "played" before the slot is refilled by submitTransfer.
    framesPlayed_.fetch_add((uint64_t)transferRealFrames_[index], std::memory_order_relaxed);

    submitTransfer(index);
}

void UsbAudioDriver::submitTransfer(int index) {
    if (!streaming.load()) return;

    libusb_transfer* xfr = transfers[index];
    int bytesPerFrame = configuredSubslotSize * configuredChannels;
    uint8_t* buf = transferBuffers[index];

    // Determine frames per packet, using feedback if available
    bool isHighSpeed = (usbSpeed >= LIBUSB_SPEED_HIGH);
    double nominalFpp = isHighSpeed ?
        (configuredRate / 8000.0) : (configuredRate / 1000.0);

    uint32_t fb = currentFeedback.load(std::memory_order_relaxed);
    double fpp;
    if (fb > 0 && !DISABLE_FEEDBACK) {
        // Feedback is in 16.16 format (UAC1 10.14 was converted to 16.16 on receipt)
        fpp = fb / 65536.0;

        // Safety clamp: DACs should only deviate slightly from nominal rate.
        // Real DAC clock drift is < 100 ppm (~0.01%); anything bigger than a
        // couple of percent is noise from WinUSB scheduling jitter on the
        // feedback EP. Wider clamps let that noise modulate packet sizes and
        // produce continuous low-level crackle.
        double minFpp = nominalFpp * 0.98;
        double maxFpp = nominalFpp * 1.02;
        if (fpp < minFpp) fpp = minFpp;
        if (fpp > maxFpp) fpp = maxFpp;
    } else {
        fpp = nominalFpp;
    }

    // Parse effective max packet size from raw wMaxPacketSize
    int basePktSize = activeFormat.maxPacketSize & 0x7FF;
    int mult = ((activeFormat.maxPacketSize >> 11) & 0x03) + 1;
    int effectiveMaxPkt = basePktSize * mult;

    int totalFilled = 0;
    bool underrun = false;
    int realBytes = 0;  // bytes sourced from the ring (real audio, not silence)

    // Fill each packet individually with feedback-adjusted frame count
    for (int p = 0; p < packetsPerTransfer; p++) {
        feedbackAccumulator += fpp;
        int frames = (int)feedbackAccumulator;
        feedbackAccumulator -= frames;

        int packetBytes = frames * bytesPerFrame;
        if (packetBytes > effectiveMaxPkt) {
            packetBytes = effectiveMaxPkt;
        }

        int offset = totalFilled;
        int got = 0;
        if (GEN_SINE_IN_SUBMIT) {
            // A9 diagnostic: synthesize a 220 Hz sine with persistent phase,
            // no ring read. Same clamp/scale/shift/pack as writeFloat32.
            static double s_phase = 0.0;
            const double twoPiFOverFs = 2.0 * 3.14159265358979323846 * 220.0
                                      / (double)configuredRate;
            int subslotBytes = configuredSubslotSize;
            int padBits = subslotBytes * 8 - configuredBitDepth;
            if (padBits < 0) padBits = 0;
            int pktFrames = (bytesPerFrame > 0) ? packetBytes / bytesPerFrame : 0;
            int o = offset;
            for (int f = 0; f < pktFrames; f++) {
                double sd = 0.2 * sin(s_phase);
                s_phase += twoPiFOverFs;
                if (s_phase > 2.0 * 3.14159265358979323846)
                    s_phase -= 2.0 * 3.14159265358979323846;
                int32_t v;
                switch (configuredBitDepth) {
                    case 16: v = (int32_t)(sd * 32767.0);      break;
                    case 24: v = (int32_t)(sd * 8388607.0);    break;
                    case 32: v = (int32_t)(sd * 2147483647.0); break;
                    default: v = (int32_t)(sd * 32767.0);      break;
                }
                int32_t wire = (int32_t)((uint32_t)v << padBits);
                for (int ch = 0; ch < configuredChannels; ch++) {
                    for (int b = 0; b < subslotBytes; b++) {
                        buf[o++] = static_cast<uint8_t>((wire >> (b * 8)) & 0xFF);
                    }
                }
            }
            got = packetBytes;
        } else if (SEND_SILENCE || isPaused.load(std::memory_order_relaxed)) {
            // A4 diagnostic or paused: payload is zeros, no ring read at all.
            memset(buf + offset, 0, packetBytes);
            got = packetBytes;
        } else if (ringBuffer) {
            got = (int)ringBuffer->read(buf + offset, packetBytes);
            if (got < 0) got = 0; // Prevent negative offsets and heap corruption!
            realBytes += got;
        }
        if (got < packetBytes) {
            memset(buf + offset + got, 0, packetBytes - got);
            if (got < packetBytes && packetBytes > 0) underrun = true;
        }

        xfr->iso_packet_desc[p].length = packetBytes;
        totalFilled += packetBytes;
    }

    if (underrun) {
        int count = playbackUnderruns.fetch_add(1, std::memory_order_relaxed) + 1;
        uint32_t now = millis_now();
        if (count <= 5 || (now - lastUnderrunLogMs) >= 1000) {
            uint32_t fb = currentFeedback.load(std::memory_order_relaxed);
            LOGE("playback underrun #%d (ring=%zu bytes, fpp=%.4f)",
                 count, ringBuffer ? ringBuffer->getAvailable() : 0,
                 fb / 65536.0);
            lastUnderrunLogMs = now;
        }
    }

    xfr->length = totalFilled;

    // Credit this transfer's real audio to the drain accounting. Stored so the
    // completion callback can move it from "in flight" to "played".
    int realFrames = (bytesPerFrame > 0) ? (realBytes / bytesPerFrame) : 0;
    transferRealFrames_[index] = realFrames;
    framesSubmitted_.fetch_add((uint64_t)realFrames, std::memory_order_relaxed);

    int rc = libusb_submit_transfer(xfr);
    if (rc < 0) {
        LOGE("resubmit_transfer failed: %s", libusb_error_name(rc));
        int remaining = --activeTransfers;
        if (remaining <= 0) {
            LOGE("All transfers lost on resubmit -- stopping streaming");
            streaming.store(false);
        }
    } else {
        transfersInFlight.fetch_add(1, std::memory_order_relaxed);
    }
}

// --- Feedback endpoint ---

void UsbAudioDriver::feedbackCallback(struct libusb_transfer* transfer) {
    auto* driver = static_cast<UsbAudioDriver*>(transfer->user_data);
    driver->handleFeedbackComplete(transfer);
}

void UsbAudioDriver::handleFeedbackComplete(struct libusb_transfer* transfer) {
    if (!streaming.load()) return;

    if (transfer->status == LIBUSB_TRANSFER_COMPLETED) {
        int actualLen = transfer->iso_packet_desc[0].actual_length;
        if (actualLen >= 3) {
            uint32_t fb;
            if (actualLen >= 4 || uacVersion == 2) {
                // UAC2: 16.16 fixed point, already in desired format
                fb = feedbackBuffer[0] | ((uint32_t)feedbackBuffer[1] << 8) |
                     ((uint32_t)feedbackBuffer[2] << 16);
                if (actualLen >= 4) {
                    fb |= ((uint32_t)feedbackBuffer[3] << 24);
                }
            } else {
                // UAC1: 10.14 fixed point -- convert to 16.16 by shifting left 2
                fb = feedbackBuffer[0] | ((uint32_t)feedbackBuffer[1] << 8) |
                     ((uint32_t)feedbackBuffer[2] << 16);
                fb <<= 2;
            }

            uint32_t prev = currentFeedback.load(std::memory_order_relaxed);
            currentFeedback.store(fb, std::memory_order_relaxed);

            if (prev == 0) {
                double rate = fb / 65536.0;
                LOGI("First feedback value: %.4f frames/packet (raw=0x%08x)", rate, fb);
                lastStableFeedback = fb;
            } else if (lastStableFeedback != 0) {
                // Track large feedback excursions: throttled to once per second so
                // a spike near a stutter is visible without flooding the log.
                uint32_t stable = lastStableFeedback;
                uint32_t diff = (fb > stable) ? (fb - stable) : (stable - fb);
                // 2% of typical feedback magnitude is ~stable / 50.
                if (diff > stable / 50) {
                    uint32_t now = millis_now();
                    if ((now - lastFeedbackLogMs) >= 1000) {
                        LOGI("feedback jump: %.4f -> %.4f fpp (delta=%.4f)",
                             stable / 65536.0, fb / 65536.0,
                             (double)diff / 65536.0);
                        lastFeedbackLogMs = now;
                    }
                }
                // Slow-track the stable value: EMA with alpha ~ 1/16 so a spike
                // doesn't immediately become the new baseline.
                lastStableFeedback = (uint32_t)((uint64_t)stable * 15 / 16 + (uint64_t)fb / 16);
            }
        }
    } else if (transfer->status == LIBUSB_TRANSFER_NO_DEVICE) {
        LOGE("Feedback: device disconnected");
        return; // don't resubmit
    }

    // Resubmit
    submitFeedbackTransfer();
}

void UsbAudioDriver::submitFeedbackTransfer() {
    if (!feedbackTransfer || !streaming.load()) return;

    int rc = libusb_submit_transfer(feedbackTransfer);
    if (rc < 0 && rc != LIBUSB_ERROR_NO_DEVICE) {
        LOGE("Feedback submit failed: %s", libusb_error_name(rc));
    }
}

// --- Start / Stop ---

bool UsbAudioDriver::start() {
    if (!configured || !handle) {
        LOGE("start() called but configured=%d handle=%p", configured, handle);
        return false;
    }

    // Parse effective max packet size
    int basePktSize = activeFormat.maxPacketSize & 0x7FF;
    int mult = ((activeFormat.maxPacketSize >> 11) & 0x03) + 1;
    int effectiveMaxPkt = basePktSize * mult;

    bool isHighSpeed = (usbSpeed >= LIBUSB_SPEED_HIGH);
    int bytesPerFrame = configuredSubslotSize * configuredChannels;

    // Correct frames-per-packet based on USB speed
    int nominalFpp;
    if (isHighSpeed) {
        nominalFpp = configuredRate / 8000; // 125us microframes
    } else {
        nominalFpp = configuredRate / 1000; // 1ms frames
    }
    int bytesPerPacket = nominalFpp * bytesPerFrame;
    if (bytesPerPacket > effectiveMaxPkt) {
        LOGI("bytesPerPacket %d capped to effectiveMaxPkt %d", bytesPerPacket, effectiveMaxPkt);
        bytesPerPacket = effectiveMaxPkt;
    }

    LOGI("Starting: iface=%d alt=%d ep=0x%02x rate=%d ch=%d bits=%d subslot=%d "
         "maxpkt=0x%04x(eff=%d) speed=%s fpp=%d bpp=%d UAC%d",
         activeFormat.interfaceNum, activeFormat.altSetting, activeFormat.endpointAddr,
         configuredRate, configuredChannels, configuredBitDepth, configuredSubslotSize,
         activeFormat.maxPacketSize, effectiveMaxPkt,
         isHighSpeed ? "High" : "Full",
         nominalFpp, bytesPerPacket, uacVersion);

    if (SEND_SILENCE) {
        LOGI("SEND_SILENCE diagnostic: outgoing audio replaced with zeros (no ring read)");
    }
    if (WRITE_CONST_DATA) {
        LOGI("WRITE_CONST_DATA diagnostic: writeFloat32 input replaced with constant 0.3 (DC)");
    }
    if (GEN_SINE_IN_SUBMIT) {
        LOGI("GEN_SINE_IN_SUBMIT diagnostic: ring bypassed, 220 Hz sine synthesized in submitTransfer");
    }

    // Ring buffer is allocated in configure() so callers can pre-fill before
    // start(). Fallback: allocate here if configure() was not called.
    int ringSize = (int)((int64_t)configuredRate * bytesPerFrame * playbackRingMs / 1000);
    if (ringSize < 131072) ringSize = 131072;
    if (!ringBuffer || (int)ringBuffer->getCapacity() != ringSize) {
        delete ringBuffer;
        ringBuffer = new RingBuffer(ringSize);
        LOGI("Ring buffer allocated in start(): %d bytes (%.0f ms)",
             ringSize, ringSize * 1000.0 / (configuredRate * bytesPerFrame));
    } else {
        LOGI("Ring buffer reused from configure(): %d bytes, %zu bytes pre-filled",
             ringSize, ringBuffer->getAvailable());
    }

    // Lock ring buffer pages to prevent page faults during streaming
    if (mlock(ringBuffer->getBuffer(), ringBuffer->getCapacity()) != 0) {
        LOGD("mlock ring buffer failed: %s (non-fatal)", strerror(errno));
    } else {
        LOGD("mlock ring buffer: %zu bytes locked", ringBuffer->getCapacity());
    }

    // Detach kernel driver from both Audio Control and Streaming interfaces
    // to prevent Android's snd-usb-audio from reclaiming the device
    int rc;
    if (acInterfaceNum >= 0) {
        rc = libusb_detach_kernel_driver(handle, acInterfaceNum);
        LOGI("detach_kernel_driver(AC iface %d): %s", acInterfaceNum,
             rc == 0 ? "OK" : libusb_error_name(rc));
    }
    rc = libusb_detach_kernel_driver(handle, activeFormat.interfaceNum);
    LOGI("detach_kernel_driver(AS iface %d): %s", activeFormat.interfaceNum,
         rc == 0 ? "OK" : libusb_error_name(rc));

    // Claim Audio Control interface to hold off kernel driver. Idempotent
    // because the AC interface is shared with capture; re-claiming while
    // libusb has IN transfers queued causes usbfs to issue an implicit
    // SET_INTERFACE that resets the device's endpoint state.
    if (acInterfaceNum >= 0 && !acInterfaceClaimed) {
        rc = libusb_claim_interface(handle, acInterfaceNum);
        if (rc < 0) {
            LOGI("claim_interface(AC %d) failed: %s (non-fatal)",
                 acInterfaceNum, libusb_error_name(rc));
        } else {
            acInterfaceClaimed = true;
            LOGI("Claimed AC interface %d", acInterfaceNum);
        }
    }

    // Claim streaming interface
    rc = libusb_claim_interface(handle, activeFormat.interfaceNum);
    if (rc < 0) {
        LOGE("claim_interface(%d) failed: %s", activeFormat.interfaceNum, libusb_error_name(rc));
        stop();
        return false;
    }
    interfaceClaimed = true;

    // Reset to zero-bandwidth, then set active alt setting
    libusb_set_interface_alt_setting(handle, activeFormat.interfaceNum, 0);
    if (!setInterfaceAltSetting(activeFormat.interfaceNum, activeFormat.altSetting)) {
        stop();
        return false;
    }

    // Set sample rate based on UAC version. Use the active format's clock
    // source so DAC+ADC devices don't accidentally set the capture clock when
    // we meant to set the playback clock.
    int csIdForStart = activeFormat.clockSourceId >= 0
            ? activeFormat.clockSourceId : clockSourceId;
    if (uacVersion >= 2 && csIdForStart >= 0) {
        if (!setSampleRateUAC2(csIdForStart, configuredRate)) {
            // DAC rejected the rate. Abort before shipping isochronous packets:
            // sending data at a rate the DAC isn't clocking for produces loud
            // noise on the analog output, which is worse than silence.
            LOGE("start(): aborting because the DAC rejected sample rate %d Hz",
                 configuredRate);
            stop();
            return false;
        }
    } else {
        setSampleRate(activeFormat.endpointAddr, configuredRate);
    }

    // Pre-allocate and pre-fault transfer buffers
    // Use effectiveMaxPkt for buffer sizing to handle feedback-adjusted packets
    int maxBufSize = effectiveMaxPkt * packetsPerTransfer;
    // Ensure buffer is at least big enough for nominal case
    int nominalBufSize = bytesPerPacket * packetsPerTransfer;
    if (maxBufSize < nominalBufSize) maxBufSize = nominalBufSize;

    transferBufSize = maxBufSize;

#ifdef _WIN32
    // Bump process working-set quota so VirtualLock can pin all 24 transfer
    // buffers + the ring buffer + libusb internals. Default min working-set
    // (~1.4 MB) is too small once the multi-MB ring is in play, which causes
    // VirtualLock on the tail transfers to fail with ERROR_WORKING_SET_QUOTA.
    // Pageable transfer buffers take page faults during iso scheduling,
    // missing microframe deadlines and producing audible popcorn.
    {
        SIZE_T needed = (SIZE_T)ringSize
                      + (SIZE_T)maxBufSize * numTransfers
                      + 16 * 1024 * 1024; // headroom for libusb / heap / stack
        SIZE_T minWs = needed < (SIZE_T)64 * 1024 * 1024
                     ? (SIZE_T)64 * 1024 * 1024 : needed;
        SIZE_T maxWs = minWs * 4;
        if (!SetProcessWorkingSetSizeEx(GetCurrentProcess(), minWs, maxWs,
                QUOTA_LIMITS_HARDWS_MIN_ENABLE | QUOTA_LIMITS_HARDWS_MAX_DISABLE)) {
            LOGI("SetProcessWorkingSetSizeEx(%zu, %zu) failed: %lu",
                 minWs, maxWs, GetLastError());
        } else {
            LOGI("Working set: min=%zu max=%zu (for VirtualLock budget)",
                 minWs, maxWs);
        }
    }
#endif

    for (int i = 0; i < numTransfers; i++) {
        transfers[i] = libusb_alloc_transfer(packetsPerTransfer);
        if (!transfers[i]) {
            LOGE("alloc_transfer failed");
            stop();
            return false;
        }
        transferBuffers[i] = new uint8_t[maxBufSize];
        memset(transferBuffers[i], 0, maxBufSize); // pre-fault pages

        // Lock transfer buffer pages
        if (mlock(transferBuffers[i], maxBufSize) != 0) {
#ifdef _WIN32
            LOGI("mlock transfer[%d] failed: GetLastError=%lu (non-fatal)",
                 i, GetLastError());
#else
            LOGI("mlock transfer[%d] failed: %s (non-fatal)", i, strerror(errno));
#endif
        }

        transferCtx[i].drv = this;
        transferCtx[i].index = i;
        libusb_fill_iso_transfer(transfers[i], handle,
            static_cast<unsigned char>(activeFormat.endpointAddr),
            transferBuffers[i], nominalBufSize,
            packetsPerTransfer,
            transferCallback, &transferCtx[i], 1000);

        libusb_set_iso_packet_lengths(transfers[i], bytesPerPacket);
    }

    // Initialize feedback state
    feedbackAccumulator = 0.0;
    currentFeedback.store(0, std::memory_order_relaxed);
    playbackUnderruns.store(0, std::memory_order_relaxed);

    // Pre-fill transfer buffers from ring so first USB packets carry real audio.
    // Track per-transfer real-vs-requested bytes so we can prove or rule out
    // "pre-buffer too thin" as the cause of start-of-track popcorn.
    {
        double fpp = isHighSpeed
            ? (configuredRate / 8000.0) : (configuredRate / 1000.0);
        int fullTransfers = 0;
        int totalReal = 0;
        int totalReq  = 0;
        // Fresh drain accounting for this stream: the pre-fill below is the
        // first batch of real frames handed to the iso queue.
        framesSubmitted_.store(0, std::memory_order_relaxed);
        framesPlayed_.store(0, std::memory_order_relaxed);
        for (int i = 0; i < numTransfers; i++) {
            libusb_transfer* xfr = transfers[i];
            uint8_t* buf = transferBuffers[i];
            int totalFilled = 0;
            int xfrReal = 0;
            int xfrReq  = 0;
            for (int p = 0; p < packetsPerTransfer; p++) {
                feedbackAccumulator += fpp;
                int frames = (int)feedbackAccumulator;
                feedbackAccumulator -= frames;
                int pktBytes = frames * bytesPerFrame;
                if (pktBytes > effectiveMaxPkt) pktBytes = effectiveMaxPkt;
                int got = ringBuffer
                    ? (int)ringBuffer->read(buf + totalFilled, pktBytes) : 0;
                if (got < pktBytes)
                    memset(buf + totalFilled + got, 0, pktBytes - got);
                xfr->iso_packet_desc[p].length = pktBytes;
                totalFilled += pktBytes;
                xfrReal += got;
                xfrReq  += pktBytes;
            }
            xfr->length = totalFilled;
            if (xfrReal == xfrReq) fullTransfers++;
            totalReal += xfrReal;
            totalReq  += xfrReq;
            transferRealFrames_[i] = (bytesPerFrame > 0) ? (xfrReal / bytesPerFrame) : 0;
            framesSubmitted_.fetch_add((uint64_t)transferRealFrames_[i],
                                       std::memory_order_relaxed);
        }
        int pct = (totalReq > 0) ? (int)((100LL * totalReal) / totalReq) : 0;
        LOGI("Pre-fill: %d/%d transfers full, avg fill %d%% (%d/%d bytes), ring=%zu bytes remaining",
             fullTransfers, numTransfers, pct, totalReal, totalReq,
             ringBuffer ? ringBuffer->getAvailable() : 0);
    }

    streaming.store(true);
    activeTransfers.store(numTransfers);
    transfersInFlight.store(0);
    minInFlight = numTransfers;
    lastInFlightLogMs = millis_now();

    // Submit all data transfers
    for (int i = 0; i < numTransfers; i++) {
        rc = libusb_submit_transfer(transfers[i]);
        if (rc < 0) {
            LOGE("submit_transfer[%d] failed: %s", i, libusb_error_name(rc));
            activeTransfers--;
        } else {
            transfersInFlight.fetch_add(1, std::memory_order_relaxed);
        }
    }

    if (activeTransfers.load() <= 0) {
        LOGE("All initial transfers failed to submit");
        streaming.store(false);
        stop();
        return false;
    }

    LOGI("%d/%d initial transfers submitted", activeTransfers.load(), numTransfers);

    // Set up feedback endpoint if present (async mode)
    if (DISABLE_FEEDBACK) {
        LOGI("Feedback EP DISABLED for diagnostic (fpp pinned to nominal=%.4f)",
             (isHighSpeed ? configuredRate / 8000.0 : configuredRate / 1000.0));
    } else if (activeFormat.feedbackEpAddr >= 0) {
        int fbPktSize = (uacVersion == 2) ? 4 : 3;
        feedbackTransfer = libusb_alloc_transfer(1); // 1 iso packet
        if (feedbackTransfer) {
            memset(feedbackBuffer, 0, sizeof(feedbackBuffer));
            libusb_fill_iso_transfer(feedbackTransfer, handle,
                static_cast<unsigned char>(activeFormat.feedbackEpAddr),
                feedbackBuffer, fbPktSize,
                1, // 1 iso packet
                feedbackCallback, this, 1000);
            libusb_set_iso_packet_lengths(feedbackTransfer, fbPktSize);

            rc = libusb_submit_transfer(feedbackTransfer);
            if (rc < 0) {
                LOGE("Feedback EP submit failed: %s", libusb_error_name(rc));
                libusb_free_transfer(feedbackTransfer);
                feedbackTransfer = nullptr;
            } else {
                LOGI("Feedback EP 0x%02x active (%d-byte packets)",
                     activeFormat.feedbackEpAddr, fbPktSize);
            }
        }
    }

    // Start event handling thread with elevated priority
    ensureEventThread();

    LOGI("USB audio streaming started (%d transfers, ring=%d bytes)", numTransfers, ringSize);
    return true;
}

// Event-thread refcount. The thread services libusb callbacks for BOTH the OUT
// pipeline and the capture pipeline; we share one thread per device so a single
// libusb context dispatches both directions. Each start() / startCapture() bumps
// the user count; each stop() / stopCapture() decrements it. The thread exits
// when the count hits zero.
void UsbAudioDriver::ensureEventThread() {
    int prev = eventThreadUsers.fetch_add(1, std::memory_order_acq_rel);
    if (prev > 0) {
        // Already running for another direction.
        return;
    }
    eventThread = std::thread([this]() {
#ifdef _WIN32
        // MMCSS "Pro Audio" registers this thread with the Windows Multimedia
        // Class Scheduler so it isn't preempted under load. Without this, the
        // libusb iso refill thread misses its deadline → first packets ship
        // partial silence → audible popcorn at start. Mirrors the decoder
        // thread's pattern (see src/decoder.cpp).
        DWORD taskIndex = 0;
        HANDLE hTask = AvSetMmThreadCharacteristicsW(L"Pro Audio", &taskIndex);
        if (!hTask)
            LOGI("AvSetMmThreadCharacteristics(Pro Audio) failed: %lu", GetLastError());
        else
            LOGI("Event thread: MMCSS Pro Audio");
        if (!SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_TIME_CRITICAL))
            LOGI("SetThreadPriority(TIME_CRITICAL) failed: %lu", GetLastError());
        else
            LOGI("Event thread: THREAD_PRIORITY_TIME_CRITICAL");
#else
        if (setpriority(PRIO_PROCESS, 0, -19) != 0)
            LOGI("setpriority(-19) failed: %s, using default", strerror(errno));
        else
            LOGI("Event thread: nice=-19 (urgent audio)");
#endif

        LOGI("Event thread started");
        while (eventThreadUsers.load(std::memory_order_acquire) > 0) {
            struct timeval tv = {0, 2000}; // 2ms timeout
            libusb_handle_events_timeout_completed(ctx, &tv, nullptr);
        }
        LOGI("Event thread exited");
#ifdef _WIN32
        if (hTask) AvRevertMmThreadCharacteristics(hTask);
#endif
    });
}

void UsbAudioDriver::releaseEventThread() {
    int prev = eventThreadUsers.fetch_sub(1, std::memory_order_acq_rel);
    if (prev != 1) {
        // Other direction still streaming -- thread stays up.
        return;
    }
    if (eventThread.joinable()) {
        eventThread.join();
    }
}

int UsbAudioDriver::write(const uint8_t* data, int length) {
    if (!ringBuffer) return -1;
    // Align write to wire-frame boundary to prevent sample misalignment.
    // Caller is responsible for producing data already padded to subslot width
    // (e.g., DoP packer pads 24-bit-in-32 with LSB padding when subslot != 3).
    int bytesPerFrame = configuredSubslotSize * configuredChannels;
    if (bytesPerFrame > 0) {
        size_t space = ringBuffer->getFreeSpace();
        int aligned = ((int)space / bytesPerFrame) * bytesPerFrame;
        if (aligned <= 0) return 0;
        length = std::min(length, aligned);
    }
    return (int)ringBuffer->write(data, length);
}

int UsbAudioDriver::writeFloat32(const float* __restrict data, int numSamples) {
    if (!ringBuffer) return -1;

    // subslotSize is the on-wire byte count per sample; bitDepth is the data range.
    // When subslot > bitDepth/8 (e.g., 24-bit-in-32-bit), unused bits are at the LSB
    // end per UAC2 spec, so the data value is left-aligned in the subslot.
    int subslotBytes = configuredSubslotSize;
    int padBits = subslotBytes * 8 - configuredBitDepth;
    if (padBits < 0) padBits = 0;

    const int CHUNK = 512;
    uint8_t convBuf[CHUNK * 4]; // max 4 bytes per sample
    int totalConsumed = 0;

    int bytesPerFrame = subslotBytes * configuredChannels;

    while (totalConsumed < numSamples) {
        size_t space = ringBuffer->getFreeSpace();
        int alignedBytes = bytesPerFrame > 0
            ? ((int)space / bytesPerFrame) * bytesPerFrame : (int)space;
        int maxSamples = alignedBytes / subslotBytes;
        if (maxSamples <= 0) break;
        int batch = std::min({CHUNK, numSamples - totalConsumed, maxSamples});

        int outBytes = 0;

        // Everything the old per-sample `switch (configuredBitDepth)` decided
        // is fixed for the whole call, so decide it once. Dither is applied at
        // 16-bit only: there the truncation error needs decorrelating into a
        // flat noise floor, while at 24/32-bit the quantization error already
        // sits below any DAC's noise floor and dither would only waste
        // headroom.
        const double quantScale = ae::wireScaleNative(configuredBitDepth);
        const bool ditherAndClamp16 =
            (configuredBitDepth != 24 && configuredBitDepth != 32);

        const float gain = softwareGain.load(std::memory_order_relaxed);
        for (int i = 0; i < batch; i++) {
            double sd = WRITE_CONST_DATA
                ? 0.3
                : std::max(-1.0, std::min(1.0, (double)(data[totalConsumed + i] * gain)));

            // Startup fade-in: linear ramp 0 -> 1 over the first fadeSampleTarget_
            // channel-samples written after configure().
            if (fadeSampleCounter_ < fadeSampleTarget_) {
                sd *= (double)fadeSampleCounter_ / (double)fadeSampleTarget_;
                fadeSampleCounter_++;
            }

            // Quantize with rounding (a bare cast truncates toward zero, which
            // adds correlated distortion). ae::roundHalfEven is lrint's exact
            // semantics as a single instruction — lrint itself is a libm call,
            // which this loop was making once per sample.
            int32_t v;
            if (ditherAndClamp16) {
                const double scaled = sd * quantScale + ditherRng_.nextTPDF();
                int32_t q = (int32_t)ae::roundHalfEven(scaled);
                if (q > 32767) q = 32767;
                else if (q < -32768) q = -32768;
                v = q;
            } else {
                v = (int32_t)ae::roundHalfEven(sd * quantScale);
            }
            const int32_t wire =
                (int32_t)((uint32_t)v << padBits);   // unsigned: no signed-shift UB
            ae::usbpack::storeLEDyn(convBuf + outBytes, wire, subslotBytes);
            outBytes += subslotBytes;
        }

        int written = (int)ringBuffer->write(convBuf, outBytes);
        int samplesWritten = written / subslotBytes;
        totalConsumed += samplesWritten;
        if (samplesWritten < batch) break;
    }

    return totalConsumed;
}

int UsbAudioDriver::writeInt16(const int16_t* data, int numSamples) {
    if (!ringBuffer) return -1;

    // Upscale 16-bit data into the DAC's wire format. The data is left-aligned
    // within the subslot (per UAC2: padding at LSB end). When the subslot is
    // narrower than the source (e.g. 8-bit DAC), shift right so the signal's
    // MSBs survive in the output and only the LSBs are truncated. (Previously
    // the code clamped to 0 here and emitted the LSB byte, which destroyed
    // the signal entirely.)
    const int subslotBytes = configuredSubslotSize;
    const int bytesPerFrame = subslotBytes * configuredChannels;

    const int CHUNK = 512;
    uint8_t convBuf[CHUNK * 4]; // max 4 bytes per sample
    int totalConsumed = 0;

    while (totalConsumed < numSamples) {
        size_t space = ringBuffer->getFreeSpace();
        int alignedBytes = bytesPerFrame > 0
            ? ((int)space / bytesPerFrame) * bytesPerFrame : (int)space;
        int maxSamples = alignedBytes / subslotBytes;
        if (maxSamples <= 0) break;
        int batch = std::min({CHUNK, numSamples - totalConsumed, maxSamples});

        const float gain = softwareGain.load(std::memory_order_relaxed);
        const int outBytes = ae::usbpack::packInt16Dyn(
            data + totalConsumed, batch, convBuf, subslotBytes, gain);

        int written = (int)ringBuffer->write(convBuf, outBytes);
        int samplesWritten = written / subslotBytes;
        totalConsumed += samplesWritten;
        if (samplesWritten < batch) break;
    }

    return totalConsumed;
}

int UsbAudioDriver::writeInt24Packed(const uint8_t* data, int numBytes) {
    // 24-bit packed PCM: 3 bytes little-endian signed per sample. Sample-align
    // to the next multiple of 3 from the caller; this method assumes whole samples.
    if (!ringBuffer) return -1;
    int numSamples = numBytes / 3;
    if (numSamples <= 0) return 0;

    // usbpack left-aligns the 24-bit value inside the DAC subslot when the
    // subslot is wide enough, and otherwise drops LSBs so the signal's MSBs
    // reach the DAC (e.g. 24-bit source -> 16-bit DAC). Clamping the shift to 0
    // would emit the LSB bytes and silently destroy the signal, producing the
    // "noise that scales with input level" symptom of LSB-only playback.
    const int subslotBytes = configuredSubslotSize;
    const int bytesPerFrame = subslotBytes * configuredChannels;
    const float gain = softwareGain.load(std::memory_order_relaxed);

    // Bit-perfect fast path, same reasoning as writeInt32: a 3-byte subslot at
    // unity gain reproduces the caller's packed-24 bytes exactly.
    if (subslotBytes == 3 && gain >= ae::usbpack::kUnityGain) {
        const size_t space = ringBuffer->getFreeSpace();
        int alignedBytes = bytesPerFrame > 0
            ? (int)(space / bytesPerFrame) * bytesPerFrame : (int)space;
        const int toWrite = std::min(numSamples * 3, alignedBytes);
        if (toWrite <= 0) return 0;
        const int written = (int)ringBuffer->write(data, (size_t)toWrite);
        return written / 3;
    }

    const int CHUNK = 512;
    uint8_t convBuf[CHUNK * 4];
    int totalConsumed = 0;

    while (totalConsumed < numSamples) {
        size_t space = ringBuffer->getFreeSpace();
        int alignedBytes = bytesPerFrame > 0
            ? ((int)space / bytesPerFrame) * bytesPerFrame : (int)space;
        int maxSamples = alignedBytes / subslotBytes;
        if (maxSamples <= 0) break;
        int batch = std::min({CHUNK, numSamples - totalConsumed, maxSamples});

        const int outBytes = ae::usbpack::packInt24Dyn(
            data + (size_t)totalConsumed * 3, batch, convBuf, subslotBytes, gain);

        int written = (int)ringBuffer->write(convBuf, outBytes);
        int samplesWritten = written / subslotBytes;
        totalConsumed += samplesWritten;
        if (samplesWritten < batch) break;
    }
    return totalConsumed;
}

int UsbAudioDriver::writeInt32(const int32_t* data, int numSamples) {
    if (!ringBuffer) return -1;
    if (numSamples <= 0) return 0;

    // 32-bit data is already full-range; no left-align padding needed for a
    // 32-bit DAC. For narrower DAC subslots (24-bit, 16-bit, ...) usbpack
    // truncates LSBs so the signal's MSBs survive in the output. Clamping to 0
    // would emit the LSB bytes of the source and lose the signal entirely.
    const int subslotBytes = configuredSubslotSize;
    const int bytesPerFrame = subslotBytes * configuredChannels;
    const float gain = softwareGain.load(std::memory_order_relaxed);

    // ── Bit-perfect fast path ────────────────────────────────────────────
    // A 4-byte subslot at unity gain means the wire format IS the caller's
    // buffer: shift is zero, gain is identity, and the bytes are already
    // little-endian int32. Staging that through a 2 KB scratch buffer one
    // shift-and-mask at a time was pure ceremony. dsp_null_test asserts the
    // byte-for-byte equivalence that licenses this.
    if (subslotBytes == 4 && gain >= ae::usbpack::kUnityGain) {
        const size_t space = ringBuffer->getFreeSpace();
        int alignedBytes = bytesPerFrame > 0
            ? (int)(space / bytesPerFrame) * bytesPerFrame : (int)space;
        const int toWrite = std::min(numSamples * 4, alignedBytes);
        if (toWrite <= 0) return 0;
        const int written = (int)ringBuffer->write(
            reinterpret_cast<const uint8_t*>(data), (size_t)toWrite);
        return written / 4;
    }

    const int CHUNK = 512;
    uint8_t convBuf[CHUNK * 4];
    int totalConsumed = 0;

    while (totalConsumed < numSamples) {
        size_t space = ringBuffer->getFreeSpace();
        int alignedBytes = bytesPerFrame > 0
            ? ((int)space / bytesPerFrame) * bytesPerFrame : (int)space;
        int maxSamples = alignedBytes / subslotBytes;
        if (maxSamples <= 0) break;
        int batch = std::min({CHUNK, numSamples - totalConsumed, maxSamples});

        const int outBytes = ae::usbpack::packInt32Dyn(
            data + totalConsumed, batch, convBuf, subslotBytes, gain);

        int written = (int)ringBuffer->write(convBuf, outBytes);
        int samplesWritten = written / subslotBytes;
        totalConsumed += samplesWritten;
        if (samplesWritten < batch) break;
    }
    return totalConsumed;
}

void UsbAudioDriver::flush() {
    if (ringBuffer) {
        ringBuffer->clear();
    }
    // Reset drain accounting: the ring is now empty and any in-flight transfers
    // are being discarded, so their real frames must not count as pending.
    framesSubmitted_.store(0, std::memory_order_relaxed);
    framesPlayed_.store(0, std::memory_order_relaxed);
    for (int i = 0; i < MAX_NUM_TRANSFERS; i++) transferRealFrames_[i] = 0;
}

int UsbAudioDriver::getPendingPlaybackMs() const {
    if (!streaming.load(std::memory_order_relaxed) || configuredRate <= 0) return 0;
    int bytesPerFrame = configuredSubslotSize * configuredChannels;
    if (bytesPerFrame <= 0) return 0;
    int64_t ringFrames = ringBuffer
        ? (int64_t)(ringBuffer->getAvailable() / bytesPerFrame) : 0;
    int64_t inFlight = (int64_t)framesSubmitted_.load(std::memory_order_relaxed)
                     - (int64_t)framesPlayed_.load(std::memory_order_relaxed);
    if (inFlight < 0) inFlight = 0;  // transient after flush() / counter reset
    int64_t pendingFrames = ringFrames + inFlight;
    return (int)(pendingFrames * 1000 / configuredRate);
}

void UsbAudioDriver::setPaused(bool paused) {
    isPaused.store(paused, std::memory_order_relaxed);
}

void UsbAudioDriver::setLatencyProfile(int profile) {
    if (streaming.load() || capStreaming.load()) {
        LOGE("setLatencyProfile(%d) ignored: stream active (playback=%d capture=%d)",
             profile, streaming.load() ? 1 : 0, capStreaming.load() ? 1 : 0);
        return;
    }
    if (profile == PROFILE_LOW_LATENCY) {
        numTransfers = 16;
        packetsPerTransfer = 8;
        playbackRingMs = 500;
        capNumTransfers = 8;
        capPacketsPerTransfer = 8;
        capRingMs = 200;
    } else {
        numTransfers = MAX_NUM_TRANSFERS;            // 64
        packetsPerTransfer = MAX_PACKETS_PER_TRANSFER; // 32
        playbackRingMs = 3000;
        capNumTransfers = MAX_CAP_NUM_TRANSFERS;     // 16
        capPacketsPerTransfer = MAX_CAP_PACKETS_PER_TRANSFER; // 16
        capRingMs = 1000;
    }
    LOGI("Latency profile %s: playback %dx%d ring=%dms, capture %dx%d ring=%dms",
         profile == PROFILE_LOW_LATENCY ? "LOW_LATENCY" : "STABLE",
         numTransfers, packetsPerTransfer, playbackRingMs,
         capNumTransfers, capPacketsPerTransfer, capRingMs);
}

void UsbAudioDriver::stop() {
    LOGI("stop() called: streaming=%d eventThreadUsers=%d activeTransfers=%d",
         streaming.load() ? 1 : 0, eventThreadUsers.load(), activeTransfers.load());
    if (!streaming.load() && eventThreadUsers.load() == 0) {
        // Already stopped -- avoid double-release of the event thread refcount.
        return;
    }
    bool wasStreaming = streaming.exchange(false);

    if (ctx) {
        // Process pending events to let transfers complete
        for (int retry = 0; retry < 50 && activeTransfers.load() > 0; retry++) {
            struct timeval tv = {0, 10000};
            libusb_handle_events_timeout_completed(ctx, &tv, nullptr);
        }

        // Cancel data transfers. Iterate the full array: unused slots are
        // null and the profile (and thus numTransfers) may have changed
        // since the session that allocated them.
        for (int i = 0; i < MAX_NUM_TRANSFERS; i++) {
            if (transfers[i]) {
                libusb_cancel_transfer(transfers[i]);
            }
        }

        // Cancel feedback transfer
        if (feedbackTransfer) {
            libusb_cancel_transfer(feedbackTransfer);
        }

        // Process cancellations
        for (int retry = 0; retry < 30 && activeTransfers.load() > 0; retry++) {
            struct timeval tv = {0, 5000};
            libusb_handle_events_timeout_completed(ctx, &tv, nullptr);
        }
    }

    // Unlock and free data transfers
    for (int i = 0; i < MAX_NUM_TRANSFERS; i++) {
        if (transfers[i]) {
            libusb_free_transfer(transfers[i]);
            transfers[i] = nullptr;
        }
        if (transferBuffers[i]) {
            if (transferBufSize > 0) {
                munlock(transferBuffers[i], transferBufSize);
            }
            delete[] transferBuffers[i];
            transferBuffers[i] = nullptr;
        }
    }
    transferBufSize = 0;

    // Free feedback transfer
    if (feedbackTransfer) {
        libusb_free_transfer(feedbackTransfer);
        feedbackTransfer = nullptr;
    }

    // Reset alt setting and release interfaces
    if (handle && interfaceClaimed) {
        libusb_set_interface_alt_setting(handle, activeFormat.interfaceNum, 0);
        libusb_release_interface(handle, activeFormat.interfaceNum);
        interfaceClaimed = false;
    }
    // AC interface is shared with the capture pipeline -- keep it claimed while
    // capture is still streaming. close() will release it as a last step.
    if (handle && acInterfaceClaimed && !capStreaming.load()) {
        libusb_release_interface(handle, acInterfaceNum);
        acInterfaceClaimed = false;
    }

    // Unlock and clear ring buffer
    if (ringBuffer) {
        munlock(ringBuffer->getBuffer(), ringBuffer->getCapacity());
        ringBuffer->clear();
    }

    activeTransfers.store(0);
    currentFeedback.store(0, std::memory_order_relaxed);
    feedbackAccumulator = 0.0;

    if (wasStreaming) {
        releaseEventThread();
    }

    LOGI("USB audio streaming stopped");
}

// =====================================================================
//  Capture pipeline (ADC -> host). Mirrors the OUT pipeline in reverse.
// =====================================================================

bool UsbAudioDriver::hasCaptureFormats() const {
    for (auto& f : formats) {
        if (f.isCapture) return true;
    }
    return false;
}

std::vector<int> UsbAudioDriver::getCaptureRates() const {
    std::vector<int> out;
    for (auto& f : formats) {
        if (!f.isCapture) continue;
        if (std::find(out.begin(), out.end(), f.sampleRate) == out.end()) {
            out.push_back(f.sampleRate);
        }
    }
    std::sort(out.begin(), out.end());
    return out;
}

std::vector<int> UsbAudioDriver::getCaptureBitDepths() const {
    std::vector<int> out;
    for (auto& f : formats) {
        if (!f.isCapture) continue;
        if (std::find(out.begin(), out.end(), f.bitDepth) == out.end()) {
            out.push_back(f.bitDepth);
        }
    }
    std::sort(out.begin(), out.end());
    return out;
}

std::vector<int> UsbAudioDriver::getCaptureChannelCounts() const {
    std::vector<int> out;
    for (auto& f : formats) {
        if (!f.isCapture) continue;
        if (std::find(out.begin(), out.end(), f.channels) == out.end()) {
            out.push_back(f.channels);
        }
    }
    std::sort(out.begin(), out.end());
    return out;
}

std::vector<int> UsbAudioDriver::getOutputRates() const {
    // Match getSupportedRates() semantics: filter only by direction. The
    // isDsd flag is set on UAC2 alt-settings whose bmFormats has bit 31
    // (RAW_DATA), which is widely misused by consumer dongles to flag
    // normal PCM alt-settings too. Filtering by isDsd here would drop
    // valid PCM output formats. If a DSD-only alt leaks through, configure()
    // will reject it cleanly.
    std::vector<int> out;
    for (auto& f : formats) {
        if (f.isCapture) continue;
        if (std::find(out.begin(), out.end(), f.sampleRate) == out.end()) {
            out.push_back(f.sampleRate);
        }
    }
    std::sort(out.begin(), out.end());
    return out;
}

std::vector<int> UsbAudioDriver::getOutputBitDepths() const {
    std::vector<int> out;
    for (auto& f : formats) {
        if (f.isCapture) continue;
        if (std::find(out.begin(), out.end(), f.bitDepth) == out.end()) {
            out.push_back(f.bitDepth);
        }
    }
    std::sort(out.begin(), out.end());
    return out;
}

std::vector<int> UsbAudioDriver::getOutputChannelCounts() const {
    std::vector<int> out;
    for (auto& f : formats) {
        if (f.isCapture) continue;
        if (std::find(out.begin(), out.end(), f.channels) == out.end()) {
            out.push_back(f.channels);
        }
    }
    std::sort(out.begin(), out.end());
    return out;
}

// Deduplicated (rate, channels, bits) tuples for one direction, flattened
// 3 ints per format. Like the per-axis getters, no isDsd filter — consumer
// dongles widely misuse the RAW_DATA bit on plain PCM alts, and configure()
// rejects a genuinely DSD-only alt cleanly anyway.
static std::vector<int> collectFormatTuples(
        const std::vector<UsbAudioFormat>& formats, bool capture) {
    std::vector<int> out;
    for (auto& f : formats) {
        if (f.isCapture != capture) continue;
        bool dup = false;
        for (size_t i = 0; i + 2 < out.size(); i += 3) {
            if (out[i] == f.sampleRate && out[i + 1] == f.channels
                    && out[i + 2] == f.bitDepth) {
                dup = true;
                break;
            }
        }
        if (!dup) {
            out.push_back(f.sampleRate);
            out.push_back(f.channels);
            out.push_back(f.bitDepth);
        }
    }
    return out;
}

std::vector<int> UsbAudioDriver::getCaptureFormatTuples() const {
    return collectFormatTuples(formats, true);
}

std::vector<int> UsbAudioDriver::getOutputFormatTuples() const {
    return collectFormatTuples(formats, false);
}

std::vector<int> UsbAudioDriver::getDsdFormatTuples() const {
    std::vector<int> out;
    for (auto& f : formats) {
        if (f.isCapture || !f.isDsd) continue;
        const int flagged = (f.bmFormats & 0x80000000u) != 0 ? 1 : 0;
        bool dup = false;
        for (size_t i = 0; i + 3 < out.size(); i += 4) {
            if (out[i] == f.sampleRate && out[i + 1] == f.channels
                    && out[i + 2] == f.subslotSize) {
                dup = true;
                break;
            }
        }
        if (!dup) {
            out.push_back(f.sampleRate);
            out.push_back(f.channels);
            // The SUBSLOT, not bBitResolution: for a RAW_DATA alt-setting the
            // resolution field describes the container carrying the DSD bit
            // stream, and reporting it as a sample depth is how DSD ends up
            // looking like a 32-bit PCM mode a caller may write PCM into.
            out.push_back(f.subslotSize);
            out.push_back(flagged);
        }
    }
    return out;
}

bool UsbAudioDriver::hasNativeDsd() const {
    for (auto& f : formats)
        if (!f.isCapture && f.isDsd) return true;
    return false;
}

bool UsbAudioDriver::configureCapture(int sampleRate, int channels, int bitDepth) {
    if (!opened) {
        LOGE("configureCapture() called but not opened");
        return false;
    }
    if (capStreaming.load()) {
        LOGI("configureCapture() called while capturing -- stopping first");
        stopCapture();
    }

    LOGD("configureCapture requested: rate=%d ch=%d bits=%d", sampleRate, channels, bitDepth);

    // Two-pass match on capture-only alts.
    UsbAudioFormat* best = nullptr;
    for (auto& f : formats) {
        if (!f.isCapture) continue;
        if (f.sampleRate == sampleRate && f.channels == channels && f.bitDepth == bitDepth) {
            best = &f;
            break;
        }
    }
    if (!best) {
        for (auto& f : formats) {
            if (!f.isCapture) continue;
            if (f.sampleRate == sampleRate && f.channels == channels) {
                if (!best || f.bitDepth > best->bitDepth) best = &f;
            }
        }
    }
    if (!best) {
        for (auto& f : formats) {
            if (!f.isCapture) continue;
            if (f.sampleRate == sampleRate) {
                if (!best || f.bitDepth > best->bitDepth) best = &f;
            }
        }
    }
    if (!best) {
        LOGE("No matching capture format for rate=%d ch=%d bits=%d",
             sampleRate, channels, bitDepth);
        return false;
    }

    // Shared-clock devices: if playback is running on the same clock, the rate
    // is already pinned -- refuse a mismatched capture rate rather than yank
    // the playback's clock out from under it.
    if (streaming.load() && best->clockSourceId >= 0
            && best->clockSourceId == activeFormat.clockSourceId
            && sampleRate != configuredRate) {
        LOGE("shared clock between DAC and ADC; capture must use playback rate %d Hz",
             configuredRate);
        return false;
    }

    capActiveFormat = *best;
    capRate = sampleRate;
    capChannels = best->channels;
    capBitDepth = best->bitDepth;
    capSubslotSize = (best->subslotSize > 0) ? best->subslotSize : ((best->bitDepth + 7) / 8);
    capConfigured = true;

    LOGD("Configured capture: rate=%d ch=%d bits=%d subslot=%d iface=%d alt=%d ep=0x%02x clk=%d",
         capRate, capChannels, capBitDepth, capSubslotSize,
         capActiveFormat.interfaceNum, capActiveFormat.altSetting,
         capActiveFormat.endpointAddr, capActiveFormat.clockSourceId);
    return true;
}

void UsbAudioDriver::captureCallback(struct libusb_transfer* transfer) {
    auto* ctx = static_cast<XferCtx*>(transfer->user_data);
    ctx->drv->handleCaptureComplete(transfer, ctx->index);
}

void UsbAudioDriver::handleCaptureComplete(struct libusb_transfer* transfer, int index) {
    if (!capStreaming.load()) {
        capActiveTransfers--;
        return;
    }

    // CANCELLED / NO_DEVICE are terminal: we either initiated the cancel
    // (stopCapture) or the device is physically gone. Retire the slot.
    if (transfer->status == LIBUSB_TRANSFER_CANCELLED ||
        transfer->status == LIBUSB_TRANSFER_NO_DEVICE) {
        int remaining = --capActiveTransfers;
        if (remaining <= 0) {
            capStreaming.store(false);
        }
        return;
    }

    // STALL needs a clear_halt before any further submits succeed.
    if (transfer->status == LIBUSB_TRANSFER_STALL && handle) {
        libusb_clear_halt(handle, transfer->endpoint);
    }

    // For COMPLETED / TIMED_OUT / ERROR / OVERFLOW / STALL: process whatever
    // iso packets did succeed individually. Even on an overall ERROR status
    // some packets may carry valid data, and on warmup the device often
    // produces partial transfers before settling. Always resubmit so the
    // pipeline keeps the device exercised — a fresh USB IN endpoint may
    // need many transfers to start delivering reliably.
    int packetsOk = 0;
    int packetsEmptyCompleted = 0;
    int packetsNotCompleted = 0;
    for (int p = 0; p < transfer->num_iso_packets; p++) {
        auto& pkt = transfer->iso_packet_desc[p];
        if (pkt.status != LIBUSB_TRANSFER_COMPLETED) {
            packetsNotCompleted++;
            continue;
        }
        int len = pkt.actual_length;
        if (len <= 0) {
            packetsEmptyCompleted++;
            continue;
        }
        uint8_t* src = transfer->buffer + (capEffectiveMaxPkt * p);
        if (capRingBuffer) capRingBuffer->write(src, len);
        packetsOk++;
    }

    // Diagnostic histogram for the empty-WAV bug. Tallied per transfer and
    // emitted once every CAP_DIAG_LOG_EVERY transfers so we can see the shape
    // of the stream — a healthy session is ~all-with-data, a stuck-ADC session
    // is ~all-empty-COMPLETED.
    capDiagPacketsWithData += packetsOk;
    capDiagPacketsCompletedEmpty += packetsEmptyCompleted;
    capDiagPacketsNonCompleted += packetsNotCompleted;
    if (++capDiagTransfersSinceLog >= CAP_DIAG_LOG_EVERY) {
        LOGI("Capture diag over %d transfers: with_data=%d empty_completed=%d not_completed=%d",
             capDiagTransfersSinceLog, capDiagPacketsWithData,
             capDiagPacketsCompletedEmpty, capDiagPacketsNonCompleted);
        capDiagTransfersSinceLog = 0;
        capDiagPacketsWithData = 0;
        capDiagPacketsCompletedEmpty = 0;
        capDiagPacketsNonCompleted = 0;
    }

    // First successful packet retires the "first activation after open" state
    // so subsequent startCapture() calls skip the warm-up SET_CUR.
    if (packetsOk > 0 && capFirstActivationAfterOpen.load()) {
        capFirstActivationAfterOpen.store(false);
    }

    // Circuit breaker: if a streak of resubmits produces zero successful
    // packets we eventually give up. At ~125us per microframe this gives
    // the device about 5 seconds of warmup leeway before we admit defeat.
    if (packetsOk > 0) {
        capConsecutiveErrors.store(0, std::memory_order_relaxed);
    } else if (transfer->status != LIBUSB_TRANSFER_COMPLETED) {
        int n = capConsecutiveErrors.fetch_add(1, std::memory_order_relaxed) + 1;
        if (n >= 5000) {
            LOGE("Capture: %d consecutive empty/error transfers — giving up", n);
            int remaining = --capActiveTransfers;
            if (remaining <= 0) capStreaming.store(false);
            return;
        }
    }

    // Index arrives with the callback (XferCtx), not via a scan of capTransfers.
    if (index < 0 || index >= capNumTransfers || capTransfers[index] != transfer) {
        int remaining = --capActiveTransfers;
        if (remaining <= 0) {
            LOGE("All capture transfers lost -- stopping");
            capStreaming.store(false);
        }
        return;
    }

    submitCaptureTransfer(index);
}

void UsbAudioDriver::submitCaptureTransfer(int index) {
    if (!capStreaming.load()) return;
    libusb_transfer* xfr = capTransfers[index];

    // Re-arm each iso packet to the full advertised size; iso IN packets are
    // device-paced and only the actual_length on completion tells us how much
    // arrived. No data fill -- the previous callback already drained it.
    for (int p = 0; p < capPacketsPerTransfer; p++) {
        xfr->iso_packet_desc[p].length = capEffectiveMaxPkt;
        xfr->iso_packet_desc[p].actual_length = 0;
    }
    xfr->length = capEffectiveMaxPkt * capPacketsPerTransfer;

    int rc = libusb_submit_transfer(xfr);
    if (rc < 0) {
        LOGE("capture resubmit_transfer failed: %s", libusb_error_name(rc));
        int remaining = --capActiveTransfers;
        if (remaining <= 0) {
            LOGE("All capture transfers lost on resubmit -- stopping");
            capStreaming.store(false);
        }
    }
}

bool UsbAudioDriver::startCapture() {
    if (!capConfigured || !handle) {
        LOGE("startCapture() called but configured=%d handle=%p", capConfigured, handle);
        return false;
    }
    if (capStreaming.load()) {
        LOGI("startCapture() already running");
        return true;
    }

    int basePktSize = capActiveFormat.maxPacketSize & 0x7FF;
    int mult = ((capActiveFormat.maxPacketSize >> 11) & 0x03) + 1;
    capEffectiveMaxPkt = basePktSize * mult;

    bool isHighSpeed = (usbSpeed >= LIBUSB_SPEED_HIGH);
    int bytesPerFrame = capSubslotSize * capChannels;
    int nominalFpp = isHighSpeed ? (capRate / 8000) : (capRate / 1000);
    int nominalBytesPerPacket = nominalFpp * bytesPerFrame;

    LOGI("Starting capture: iface=%d alt=%d ep=0x%02x rate=%d ch=%d bits=%d subslot=%d "
         "maxpkt=0x%04x(eff=%d) speed=%s fpp=%d bpp=%d UAC%d",
         capActiveFormat.interfaceNum, capActiveFormat.altSetting, capActiveFormat.endpointAddr,
         capRate, capChannels, capBitDepth, capSubslotSize,
         capActiveFormat.maxPacketSize, capEffectiveMaxPkt,
         isHighSpeed ? "High" : "Full", nominalFpp, nominalBytesPerPacket, uacVersion);

    // Capture ring buffer (in wire-format bytes), sized by the latency
    // profile: 1 s STABLE so a stalled Java reader can't drop samples,
    // 200 ms LOW_LATENCY.
    int ringSize = (int)((int64_t)capRate * bytesPerFrame * capRingMs / 1000);
    if (ringSize < 65536) ringSize = 65536;
    delete capRingBuffer;
    capRingBuffer = new RingBuffer(ringSize);
    if (mlock(capRingBuffer->getBuffer(), capRingBuffer->getCapacity()) != 0) {
        LOGD("mlock capture ring buffer failed: %s (non-fatal)", strerror(errno));
    }

    int rc;
    // Detach kernel driver from capture AS (and AC, if not already done by OUT).
    if (acInterfaceNum >= 0 && !acInterfaceClaimed) {
        rc = libusb_detach_kernel_driver(handle, acInterfaceNum);
        LOGI("capture detach_kernel_driver(AC iface %d): %s", acInterfaceNum,
             rc == 0 ? "OK" : libusb_error_name(rc));
    }
    rc = libusb_detach_kernel_driver(handle, capActiveFormat.interfaceNum);
    LOGI("capture detach_kernel_driver(AS iface %d): %s", capActiveFormat.interfaceNum,
         rc == 0 ? "OK" : libusb_error_name(rc));

    // Claim AC interface (shared with OUT). Idempotent: skip if already held.
    if (acInterfaceNum >= 0 && !acInterfaceClaimed) {
        rc = libusb_claim_interface(handle, acInterfaceNum);
        if (rc < 0) {
            LOGI("capture claim_interface(AC %d) failed: %s (non-fatal)",
                 acInterfaceNum, libusb_error_name(rc));
        } else {
            acInterfaceClaimed = true;
        }
    }

    rc = libusb_claim_interface(handle, capActiveFormat.interfaceNum);
    if (rc < 0) {
        LOGE("capture claim_interface(%d) failed: %s",
             capActiveFormat.interfaceNum, libusb_error_name(rc));
        stopCapture();
        return false;
    }
    capInterfaceClaimed = true;

    libusb_set_interface_alt_setting(handle, capActiveFormat.interfaceNum, 0);
    if (!setInterfaceAltSetting(capActiveFormat.interfaceNum, capActiveFormat.altSetting)) {
        stopCapture();
        return false;
    }

    // Sample rate. On UAC2 we drive the ADC's clock source; on shared-clock
    // devices the rate was already pinned by playback and configureCapture
    // would have rejected a mismatch.
    int capClockId = capActiveFormat.clockSourceId >= 0
            ? capActiveFormat.clockSourceId : clockSourceId;
    if (uacVersion >= 2 && capClockId >= 0) {
        bool sharedClockAlreadySet = streaming.load()
                && capClockId == activeFormat.clockSourceId;
        if (!sharedClockAlreadySet) {
            // Warm-up: some UAC2 ADCs silently ignore a SET_CUR when the
            // requested rate equals the rate they last latched (or their
            // power-on default on the very first activation) — the ADC never
            // arms and iso IN packets come back COMPLETED with
            // actual_length=0. Bracketing the real SET_CUR with one at a
            // different rate forces the controller to re-arm. We run this on
            // every startCapture(): stuck-ADC has been observed not only on
            // first activation but also on subsequent record sessions within
            // the same plug-in, so the gate was removed. Best-effort: if the
            // warm-up rate is rejected we still proceed with the real
            // SET_CUR.
            int warmupRate = 0;
            for (auto& f : formats) {
                if (!f.isCapture) continue;
                if (f.clockSourceId != capActiveFormat.clockSourceId) continue;
                if (f.sampleRate == capRate) continue;
                if (warmupRate == 0 || f.sampleRate < warmupRate) {
                    warmupRate = f.sampleRate;
                }
            }
            if (warmupRate > 0) {
                LOGI("Capture warm-up: SET_CUR %d Hz before real %d Hz (clk %d)",
                     warmupRate, capRate, capClockId);
                setSampleRateUAC2(capClockId, warmupRate);
                usleep(10000);
            } else {
                LOGI("Capture warm-up skipped: no alternative rate on clock %d",
                     capClockId);
            }
            if (!setSampleRateUAC2(capClockId, capRate)) {
                LOGE("startCapture(): ADC rejected sample rate %d Hz", capRate);
                stopCapture();
                return false;
            }
        }
    } else {
        // UAC1 capture: best-effort endpoint SET_CUR. Some devices ignore it.
        setSampleRate(capActiveFormat.endpointAddr, capRate);
    }

    // Allocate transfers. Buffer size uses effectiveMaxPkt because each iso IN
    // packet may arrive at the max size; we cannot assume the nominal fpp.
    capTransferBufSize = capEffectiveMaxPkt * capPacketsPerTransfer;
    for (int i = 0; i < capNumTransfers; i++) {
        capTransfers[i] = libusb_alloc_transfer(capPacketsPerTransfer);
        if (!capTransfers[i]) {
            LOGE("capture alloc_transfer failed");
            stopCapture();
            return false;
        }
        capTransferBuffers[i] = new uint8_t[capTransferBufSize];
        memset(capTransferBuffers[i], 0, capTransferBufSize);
        if (mlock(capTransferBuffers[i], capTransferBufSize) != 0) {
            LOGD("mlock capture transfer[%d] failed: %s (non-fatal)", i, strerror(errno));
        }

        capTransferCtx[i].drv = this;
        capTransferCtx[i].index = i;
        libusb_fill_iso_transfer(capTransfers[i], handle,
            static_cast<unsigned char>(capActiveFormat.endpointAddr),
            capTransferBuffers[i], capTransferBufSize,
            capPacketsPerTransfer,
            captureCallback, &capTransferCtx[i], 1000);
        libusb_set_iso_packet_lengths(capTransfers[i], capEffectiveMaxPkt);
    }

    capStreaming.store(true);
    capActiveTransfers.store(capNumTransfers);
    capConsecutiveErrors.store(0);
    capDiagTransfersSinceLog = 0;
    capDiagPacketsWithData = 0;
    capDiagPacketsCompletedEmpty = 0;
    capDiagPacketsNonCompleted = 0;

    for (int i = 0; i < capNumTransfers; i++) {
        rc = libusb_submit_transfer(capTransfers[i]);
        if (rc < 0) {
            LOGE("capture submit_transfer[%d] failed: %s", i, libusb_error_name(rc));
            capActiveTransfers--;
        }
    }
    if (capActiveTransfers.load() <= 0) {
        LOGE("All capture transfers failed to submit");
        capStreaming.store(false);
        stopCapture();
        return false;
    }
    LOGI("%d/%d capture transfers submitted", capActiveTransfers.load(), capNumTransfers);

    ensureEventThread();

    LOGI("USB audio capture started (%d transfers, ring=%d bytes)",
         capNumTransfers, ringSize);
    return true;
}

int UsbAudioDriver::readCapture(uint8_t* out, int maxBytes) {
    if (!capStreaming.load() || !capRingBuffer) return -1;
    int bytesPerFrame = capSubslotSize * capChannels;
    if (bytesPerFrame > 0) {
        int aligned = (maxBytes / bytesPerFrame) * bytesPerFrame;
        if (aligned <= 0) return 0;
        maxBytes = aligned;
    }
    return (int)capRingBuffer->read(out, maxBytes);
}

void UsbAudioDriver::stopCapture() {
    if (!capStreaming.load() && capActiveTransfers.load() == 0
            && !capInterfaceClaimed && capTransferBuffers[0] == nullptr) {
        // Already torn down -- avoid double-release of the event thread refcount.
        return;
    }
    bool wasStreaming = capStreaming.exchange(false);

    if (ctx) {
        for (int retry = 0; retry < 50 && capActiveTransfers.load() > 0; retry++) {
            struct timeval tv = {0, 10000};
            libusb_handle_events_timeout_completed(ctx, &tv, nullptr);
        }
        // Full array: profile may have changed since allocation; unused slots null.
        for (int i = 0; i < MAX_CAP_NUM_TRANSFERS; i++) {
            if (capTransfers[i]) libusb_cancel_transfer(capTransfers[i]);
        }
        for (int retry = 0; retry < 30 && capActiveTransfers.load() > 0; retry++) {
            struct timeval tv = {0, 5000};
            libusb_handle_events_timeout_completed(ctx, &tv, nullptr);
        }
    }

    for (int i = 0; i < MAX_CAP_NUM_TRANSFERS; i++) {
        if (capTransfers[i]) {
            libusb_free_transfer(capTransfers[i]);
            capTransfers[i] = nullptr;
        }
        if (capTransferBuffers[i]) {
            if (capTransferBufSize > 0) {
                munlock(capTransferBuffers[i], capTransferBufSize);
            }
            delete[] capTransferBuffers[i];
            capTransferBuffers[i] = nullptr;
        }
    }
    capTransferBufSize = 0;

    if (handle && capInterfaceClaimed) {
        libusb_set_interface_alt_setting(handle, capActiveFormat.interfaceNum, 0);
        libusb_release_interface(handle, capActiveFormat.interfaceNum);
        capInterfaceClaimed = false;
    }
    // AC interface is shared with playback -- keep it claimed while OUT runs.
    if (handle && acInterfaceClaimed && !streaming.load()) {
        libusb_release_interface(handle, acInterfaceNum);
        acInterfaceClaimed = false;
    }

    if (capRingBuffer) {
        munlock(capRingBuffer->getBuffer(), capRingBuffer->getCapacity());
        delete capRingBuffer;
        capRingBuffer = nullptr;
    }

    capActiveTransfers.store(0);

    if (wasStreaming) {
        releaseEventThread();
    }

    LOGI("USB audio capture stopped");
}

void UsbAudioDriver::close() {
    LOGI("close() called, streaming=%d capStreaming=%d opened=%d",
         streaming.load() ? 1 : 0, capStreaming.load() ? 1 : 0, opened);

    if (capStreaming.load()) {
        stopCapture();
    }
    if (streaming.load()) {
        stop();
    }

    // Safety net before libusb_close/libusb_exit: make sure the event thread is
    // actually stopped and joined. A mid-stream device DETACH can leave the
    // eventThreadUsers refcount leaked > 0 (stopCapture()'s "already torn down"
    // early-return skips releaseEventThread()), so the thread keeps spinning in
    // libusb_handle_events. If it's still alive at libusb_exit(), libusb aborts
    // destroying a still-held mutex ("pthread_mutex_destroy(mutex) == 0" assertion).
    // The event loop polls with a 2 ms timeout, so this join returns promptly.
    eventThreadUsers.store(0, std::memory_order_release);
    if (eventThread.joinable()) {
        eventThread.join();
    }

    // Release AC interface if still held
    if (handle && acInterfaceClaimed) {
        libusb_release_interface(handle, acInterfaceNum);
        acInterfaceClaimed = false;
    }

    if (handle) {
        // Process any remaining events before closing
        if (ctx) {
            struct timeval tv = {0, 50000}; // 50ms
            libusb_handle_events_timeout_completed(ctx, &tv, nullptr);
        }
        libusb_close(handle);
        handle = nullptr;
    }
    if (ctx) {
        libusb_exit(ctx);
        ctx = nullptr;
    }

    formats.clear();
    configured = false;
    opened = false;
    uacVersion = 1;
    clockSourceId = -1;
    configuredSubslotSize = 0;
    LOGI("USB audio device closed");
}

std::string UsbAudioDriver::getDeviceInfo() const {
    if (!configured) return "Not configured";

    char buf[256];
    snprintf(buf, sizeof(buf), "%dHz/%dbit/%dch UAC%d %s",
             configuredRate, configuredBitDepth, configuredChannels,
             uacVersion,
             usbSpeed >= LIBUSB_SPEED_HIGH ? "High-Speed" :
             usbSpeed == LIBUSB_SPEED_FULL ? "Full-Speed" : "");
    return std::string(buf);
}
