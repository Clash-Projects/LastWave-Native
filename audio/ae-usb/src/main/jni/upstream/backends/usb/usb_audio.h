#ifndef USB_AUDIO_H
#define USB_AUDIO_H

#include <cstdint>
#include <cstring>
#include <vector>
#include <mutex>
#include <atomic>
#include <string>
#include <thread>

#include "core/dsp/audio_convert.h"
#include "core/buffer/ring_buffer.h"

// RingBuffer now lives in core/buffer/ring_buffer.h (shared with other RT
// backends). Keep the unqualified name working throughout this driver.
using ae::RingBuffer;

struct libusb_device_handle;
struct libusb_context;
struct libusb_transfer;

// USB Audio Class constants
#define UAC_VERSION_1           0x0100
#define UAC_VERSION_2           0x0200

// Descriptor types
#define USB_DT_CS_INTERFACE     0x24
#define USB_DT_CS_ENDPOINT      0x25

// Audio Class-Specific AC Interface Descriptor Subtypes
#define UAC_HEADER              0x01
#define UAC_INPUT_TERMINAL      0x02
#define UAC_OUTPUT_TERMINAL     0x03
#define UAC_MIXER_UNIT          0x04
#define UAC_SELECTOR_UNIT       0x05
#define UAC_FEATURE_UNIT        0x06
#define UAC_CLOCK_SOURCE        0x0a // UAC2

// Audio Class-Specific AS Interface Descriptor Subtypes
#define UAC_AS_GENERAL          0x01
#define UAC_FORMAT_TYPE         0x02

// Audio Class-Specific Endpoint Descriptor Subtypes
#define UAC_EP_GENERAL          0x01

// Audio Class-Specific Request Codes
#define UAC_SET_CUR             0x01
#define UAC_GET_CUR             0x81
#define UAC2_RANGE              0x02
#define UAC2_CS_CONTROL_SAM_FREQ 0x01
#define UAC2_CS_CONTROL_CLOCK_VALID 0x02

// Feature Unit control selectors (used in wValue high byte for GET/SET CUR)
#define UAC_FU_MUTE_CONTROL     0x01
#define UAC_FU_VOLUME_CONTROL   0x02

// Format type codes
#define UAC_FORMAT_TYPE_I       0x01

struct UsbAudioDeviceInfo {
    uint16_t vid;
    uint16_t pid;
    std::string name;
};

struct UsbAudioFormat {
    int interfaceNum;
    int altSetting;
    int endpointAddr;       // iso data EP (OUT when isCapture==false, IN when isCapture==true)
    int maxPacketSize;
    int sampleRate;
    int channels;
    int bitDepth;       // bBitResolution: number of significant bits per sample
    int subslotSize;    // bSubslotSize: bytes on the wire per sample (>= bitDepth/8)
    uint32_t bmFormats = 0;  // UAC2 AS_GENERAL bmFormats bitmap (bit 31 = RAW_DATA / DSD)
    bool isDsd = false;      // True when this alt-setting is the DSD variant
    bool isCapture = false;  // True when this alt-setting feeds data IN from the device (ADC/mic)
    int feedbackEpAddr = -1; // Iso IN feedback EP for playback alt-settings; -1 for capture
    int clockSourceId = -1;  // Resolved via AS_GENERAL.bTerminalLink -> Terminal.bCSourceID
};

class UsbAudioDriver {
public:
    UsbAudioDriver();
    ~UsbAudioDriver();

    static std::vector<UsbAudioDeviceInfo> enumerateUsbAudioDevices();

    bool open(int fd);
    bool open(uint16_t vid, uint16_t pid);  // Windows: open by VID/PID via libusb
    bool parseDescriptors();
    std::vector<int> getSupportedRates();
    bool configure(int sampleRate, int channels, int bitDepth, bool preferDsd = false);
    bool start();
    int write(const uint8_t* data, int length);
    int writeFloat32(const float* data, int numSamples);
    int writeInt16(const int16_t* data, int numSamples);
    int writeInt24Packed(const uint8_t* data, int numBytes);
    int writeInt32(const int32_t* data, int numSamples);
 void flush();
 void stop();
 void close();
 void setPaused(bool paused);
 bool isStreaming() const { return streaming.load(); }
 uint64_t framesPlayed() const { return framesPlayed_.load(); }

    int getConfiguredRate() const { return configuredRate; }
    int getConfiguredChannels() const { return configuredChannels; }
    int getConfiguredBitDepth() const { return configuredBitDepth; }
    int getConfiguredSubslotSize() const { return configuredSubslotSize; }
    size_t ringAvailable() const { return ringBuffer ? ringBuffer->getAvailable() : 0; }
    // Milliseconds of real (non-silence) audio still buffered between write()
    // and the DAC: ring-buffer occupancy plus the not-yet-played frames already
    // handed to the isochronous queue. Returns 0 when not streaming. Lets the
    // caller drain the queued tail before stop()/flush() so the last seconds of
    // a track aren't discarded at end-of-track.
    int getPendingPlaybackMs() const;
    int getUacVersion() const { return uacVersion; }
    std::string getDeviceInfo() const;

    // Volume control. Hardware path drives the UAC Feature Unit's volume/mute
    // controls; software path multiplies samples in writeFloat32/writeInt16.
    bool hasHardwareVolume() const { return volumeFuUnitId >= 0; }
    bool hasHardwareMute() const { return hasFuMute; }
    int getVolumeMinDbQ8() const { return volumeMinDbQ8; }
    int getVolumeMaxDbQ8() const { return volumeMaxDbQ8; }
    int getVolumeResDbQ8() const { return volumeResDbQ8; }
    bool setHwVolumeDbQ8(int valueDbQ8);
    bool setHwMute(bool muted);
    void setSoftwareGain(float gain) { softwareGain.store(gain, std::memory_order_relaxed); }

    // True when the integer write paths pass samples through untouched
    // (unity gain skips the float multiply/requantize entirely).
    bool isUnityGainBitPerfect() const {
        return softwareGain.load(std::memory_order_relaxed) >= 0.9999f;
    }

    // GET_CUR readback equals the rate start() programmed. A successful
    // SET_CUR transfer alone is not proof the DAC clock moved.
    bool isClockMatched() const {
        return clockConfirmed && configuredRate > 0 && clockReadbackHz == configuredRate;
    }

    // Map a linear listening gain onto the verified Feature Unit. PCM stays
    // at unity in the write path. Returns false when no writable FU exists.
    bool setListeningGain(float gain);

    // --- Capture (ADC -> host) ---
    bool configureCapture(int sampleRate, int channels, int bitDepth);
    bool startCapture();
    int  readCapture(uint8_t* out, int maxBytes);
    void stopCapture();
    int  getConfiguredCaptureRate() const        { return capRate; }
    int  getConfiguredCaptureChannels() const    { return capChannels; }
    int  getConfiguredCaptureBitDepth() const    { return capBitDepth; }
    int  getConfiguredCaptureSubslotSize() const { return capSubslotSize; }
    bool isCapturing() const                     { return capStreaming.load(); }
    bool hasCaptureFormats() const;
    std::vector<int> getCaptureRates() const;
    std::vector<int> getCaptureBitDepths() const;
    std::vector<int> getCaptureChannelCounts() const;

    // Output (DAC) capability introspection. Mirrors the capture-side getters
    // above. Filters the merged `formats` vector for non-capture, non-DSD
    // alt-settings so callers can constrain a UI to formats both directions
    // support (e.g., a recorder enabling live monitor playback).
    std::vector<int> getOutputRates() const;
    std::vector<int> getOutputBitDepths() const;
    std::vector<int> getOutputChannelCounts() const;

    // Full (rate, channels, bits) tuples, flattened 3 ints per format. The
    // per-axis getters above can't express coupled limits like "384 kHz only
    // at 16-bit"; a best-format picker needs the real tuples.
    std::vector<int> getCaptureFormatTuples() const;
    std::vector<int> getOutputFormatTuples() const;

    // The DSD (RAW_DATA) alt-settings, flattened as
    // (rate, channels, subslot, flaggedByDescriptor) QUADS. The fourth int is
    // 1 when the device itself set bmFormats bit 31 and 0 when this driver
    // GUESSED via the duplicate-alt heuristic below. A consumer must be able
    // to tell those apart: the first is the device's own claim, the second is
    // ours, and presenting them identically means either hedging over a fact
    // or asserting a guess. Separate from getOutputFormatTuples() because a DSD alt-setting
    // is NOT a PCM format that happens to be exotic: its bBitResolution is a
    // container width, not a sample depth, and feeding it PCM produces noise
    // at full scale. A caller must opt in, which is what configure()'s
    // preferDsd parameter is for.
    //
    // Empty means this device advertises no native DSD alt-setting. That is
    // not the same as "no DSD": DoP carries DSD inside an ordinary PCM stream
    // (see core/dsp/dsd/dsd_packager.h) and needs nothing from this list.
    std::vector<int> getDsdFormatTuples() const;
    // True if any alt-setting was parsed with the RAW_DATA bit set.
    bool hasNativeDsd() const;

    // Latency/stability profile: 0 = LOW_LATENCY, 1 = STABLE (default).
    // Sizes the iso transfer queues and ring buffers for both directions.
    // Takes effect at the next configure()/startCapture(); ignored (logged)
    // while either direction is streaming.
    static const int PROFILE_LOW_LATENCY = 0;
    static const int PROFILE_STABLE = 1;
    void setLatencyProfile(int profile);

private:
    bool setInterfaceAltSetting(int interface_num, int alt_setting);
    bool setSampleRate(int endpoint, int rate);
    bool setSampleRateUAC2(int clockId, int rate);
    bool verifyHardwareVolume();
    int readHwVolumeCur();
    void noteClockReadback(int reportedHz, int requestedHz);
    std::vector<int> queryUac2SampleRates(int clockId);
    bool queryHwVolumeRange();
    // Per-transfer callback context. libusb hands `user_data` back on every
    // completion, so carrying the queue slot in it turns the callback's
    // "which transfer was that?" step from a linear scan over the whole queue
    // (up to 64 pointer compares, on the isochronous event thread, for every
    // completion) into a field read. One instance per slot, owned by the
    // arrays below; they outlive every transfer that points at them.
    struct XferCtx {
        UsbAudioDriver* drv;
        int index;
    };

    void submitTransfer(int index);
    static void transferCallback(struct libusb_transfer* transfer);
    void handleTransferComplete(struct libusb_transfer* transfer, int index);

    static void feedbackCallback(struct libusb_transfer* transfer);
    void handleFeedbackComplete(struct libusb_transfer* transfer);
    void submitFeedbackTransfer();

    static void captureCallback(struct libusb_transfer* transfer);
    void handleCaptureComplete(struct libusb_transfer* transfer, int index);
    void submitCaptureTransfer(int index);

    void ensureEventThread();
    void releaseEventThread();

    libusb_context* ctx = nullptr;
    libusb_device_handle* handle = nullptr;

    std::vector<UsbAudioFormat> formats;
    UsbAudioFormat activeFormat{};

    int configuredRate = 0;
    int configuredChannels = 0;
    int configuredBitDepth = 0;
    int configuredSubslotSize = 0;
    int uacVersion = 1;
    int clockSourceId = -1;
    int acInterfaceNum = 0;
    int usbSpeed = 0;

    // Volume / Feature Unit state. -1 unitId == no hardware volume detected.
    // Q8.8 signed dB on the UAC2 wire (UAC1 SLR/LR widely used as Q8.8 too).
    int volumeFuUnitId = -1;
    bool hasFuMute = false;
    int16_t volumeMinDbQ8 = -8000;  // -32 dB default until queried
    int16_t volumeMaxDbQ8 = 0;
    int16_t volumeResDbQ8 = 256;    // 1 dB step default
    std::atomic<float> softwareGain{1.0f};
    bool clockConfirmed = false;
    int clockReadbackHz = -1;
    bool volumeVerified = false;

    // Isochronous transfer queue, sized by the latency profile (see
    // setLatencyProfile). MAX_* constants size the arrays; the runtime
    // members below select how much of each array a profile actually uses.
    //
    // STABLE (default) playback = 64 transfers x 32 packets. These exact
    // values are load-bearing on Windows: 32 packets/transfer = 4 ms per iso
    // transfer — with 1 ms transfers (8 packets) libusbK could not stitch
    // consecutive iso OUT transfers together gaplessly and produced a
    // continuous "popcorn" crackle at every transfer seam. The deep 64-deep
    // queue keeps resubmission jitter from draining it. (Android's kernel
    // URB scheduler tolerates much shallower queues.)
    // LOW_LATENCY = 16 x 8, validated on Android only.
    static const int MAX_NUM_TRANSFERS = 64;
    static const int MAX_PACKETS_PER_TRANSFER = 32;
    int numTransfers = 64;
    int packetsPerTransfer = 32;
    int playbackRingMs = 3000;
    struct libusb_transfer* transfers[MAX_NUM_TRANSFERS] = {};
    XferCtx transferCtx[MAX_NUM_TRANSFERS] = {};
    uint8_t* transferBuffers[MAX_NUM_TRANSFERS] = {};
    int transferBufSize = 0;
    std::atomic<int> activeTransfers{0};

    // End-of-track drain accounting. framesSubmitted_ counts real audio frames
    // handed to the iso queue (pre-fill + each resubmit); framesPlayed_ counts
    // those frames once their transfer completes. Their difference is the real
    // audio still in flight; added to ring occupancy it gives the true buffered
    // tail (see getPendingPlaybackMs). transferRealFrames_[i] remembers how many
    // real frames transfer i carried so completion can credit them. Written by
    // the producer (pre-fill / write) and the libusb event thread.
    std::atomic<uint64_t> framesSubmitted_{0};
    std::atomic<uint64_t> framesPlayed_{0};
    int transferRealFrames_[MAX_NUM_TRANSFERS] = {};

    // Diagnostic: number of transfers currently submitted (in flight). If this
    // periodically dips toward 0 the queue is draining (resubmission too slow);
    // if it stays high the click is a scheduling/seam issue, not a drain.
    std::atomic<int> transfersInFlight{0};
    int minInFlight = 1 << 30;
    uint32_t lastInFlightLogMs = 0;

    // Feedback transfer
    struct libusb_transfer* feedbackTransfer = nullptr;
    uint8_t feedbackBuffer[4] = {};
    std::atomic<uint32_t> currentFeedback{0};
    double feedbackAccumulator = 0.0;

    // Write buffer
    RingBuffer* ringBuffer = nullptr;

    // Startup fade-in: ramp the first ~30 ms of audio from silence to full so
    // playback doesn't begin with a hard 0->signal discontinuity (audible POP).
    // Counted in channel-samples; only touched by the producer (writeFloat32).
    int64_t fadeSampleCounter_ = 0;
    int64_t fadeSampleTarget_ = 0;

    // TPDF dither state for writeFloat32's 16-bit quantize. Persistent across
    // calls (a per-call generator repeats the same noise every buffer). Only
    // touched by the producer thread, like the fade counter above.
    DitherLCG ditherRng_;

    std::atomic<int> playbackUnderruns{0};

    // Diagnostic log throttles (ms wall-clock, 0 = never logged)
    uint32_t lastUnderrunLogMs = 0;
    uint32_t lastFeedbackLogMs = 0;
    uint32_t lastTransferErrLogMs = 0;
    uint32_t lastStableFeedback = 0;

    std::atomic<bool> isPaused{false};
    std::atomic<bool> streaming{false};
    std::thread eventThread;
    std::atomic<int> eventThreadUsers{0};
    bool opened = false;
    bool configured = false;
    bool interfaceClaimed = false;
    bool acInterfaceClaimed = false;

    // --- Capture-side mirror of OUT pipeline ---
    UsbAudioFormat capActiveFormat{};
    int capRate = 0;
    int capChannels = 0;
    int capBitDepth = 0;
    int capSubslotSize = 0;
    int capEffectiveMaxPkt = 0;

    // Capture transfer queue, profile-driven like the playback side. STABLE
    // (default) = 16 x 16 with a 1 s ring so a stalled Java reader can't drop
    // samples mid-take; LOW_LATENCY = 8 x 8 with a 200 ms ring (the original
    // tuning) for live-monitor use.
    static const int MAX_CAP_NUM_TRANSFERS = 16;
    static const int MAX_CAP_PACKETS_PER_TRANSFER = 16;
    int capNumTransfers = 16;
    int capPacketsPerTransfer = 16;
    int capRingMs = 1000;
    struct libusb_transfer* capTransfers[MAX_CAP_NUM_TRANSFERS] = {};
    XferCtx capTransferCtx[MAX_CAP_NUM_TRANSFERS] = {};
    uint8_t* capTransferBuffers[MAX_CAP_NUM_TRANSFERS] = {};
    int capTransferBufSize = 0;
    std::atomic<int> capActiveTransfers{0};

    RingBuffer* capRingBuffer = nullptr;

    std::atomic<bool> capStreaming{false};
    bool capInterfaceClaimed = false;
    bool capConfigured = false;
    // Consecutive transfer errors with zero successful packets in between.
    // Resets every time a packet arrives. Used as a circuit-breaker so a
    // genuinely dead endpoint can't loop on resubmits forever.
    std::atomic<int> capConsecutiveErrors{0};
    // Some UAC2 ADCs (observed on TTGK KM-HIFI-384KHZ) ignore the first
    // SET_CUR SAM_FREQ_CONTROL after enumeration when the requested rate
    // matches the device's power-on default — the ADC never arms and iso IN
    // transfers come back COMPLETED with actual_length=0. Bracketing the real
    // SET_CUR with a SET_CUR at a different rate forces the controller to
    // actually re-arm. This flag latches that "first activation per open"
    // condition. Cleared once the iso pipeline reports a successful packet.
    // Read from the recorder thread (startCapture), written from both the
    // recorder thread (open) and the libusb event thread (handleCaptureComplete).
    std::atomic<bool> capFirstActivationAfterOpen{true};
    // Per-transfer diagnostic counters for the empty-WAV bug. Tallied in
    // handleCaptureComplete() and emitted as a one-line LOGI every
    // CAP_DIAG_LOG_EVERY transfers. All accesses happen on the libusb event
    // thread; no synchronization needed.
    static const int CAP_DIAG_LOG_EVERY = 1000;
    int capDiagTransfersSinceLog = 0;
    int capDiagPacketsWithData = 0;
    int capDiagPacketsCompletedEmpty = 0;
    int capDiagPacketsNonCompleted = 0;
};

#endif // USB_AUDIO_H
