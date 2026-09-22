#ifndef USB_PACK_H
#define USB_PACK_H

// Sample -> UAC wire-format packing, factored out of UsbAudioDriver's write*()
// methods so the subslot width is a COMPILE-TIME constant.
//
// The loops these replace re-derived, once per sample, things that are fixed
// for the whole call: the shift direction, the gain test, and a
// `for (b = 0; b < subslotBytes; b++)` byte-at-a-time emit. With the width
// known at compile time each of those collapses — a 4-byte subslot at unity
// gain becomes a plain 32-bit store, which is what it always was.
//
// Two behavioural notes, both deliberate:
//
//  * The shifts are performed in UNSIGNED arithmetic and cast back. The
//    originals wrote `v << dataShift` on a signed int32; left-shifting a
//    negative value (or shifting a positive one into the sign bit, which
//    16-bit-into-32-bit padding does at full scale) is undefined behaviour
//    before C++20. The unsigned form is well-defined and produces the identical
//    bit pattern on every target this engine builds for.
//
//  * The multi-byte stores assume a little-endian host, which UAC also
//    assumes — the static_assert below makes that explicit rather than silent.
//
// Equivalence to the original loops is asserted by tests/dsp_null_test.cpp for
// every (bitDepth, subslotSize) pair the descriptor parser can produce.

#include <cstdint>
#include <cstring>

#include "core/dsp/wire_scale.h"

namespace ae {
namespace usbpack {

static_assert(
#if defined(__BYTE_ORDER__) && defined(__ORDER_LITTLE_ENDIAN__)
    __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__,
#else
    true,   // MSVC targets are little-endian; it defines no order macro.
#endif
    "usb_pack.h emits UAC wire bytes via native multi-byte stores and therefore "
    "requires a little-endian host.");

// Gain at or above this is treated as unity: the integer paths then pass
// samples through untouched, which is what makes bit-perfect bit-perfect.
// Mirrors UsbAudioDriver::isUnityGainBitPerfect().
constexpr float kUnityGain = 0.9999f;

// --- little-endian store of the low `Sub` bytes of `wire` --------------------
template <int Sub> inline void storeLE(uint8_t* p, int32_t wire);

template <> inline void storeLE<1>(uint8_t* p, int32_t wire) {
    p[0] = static_cast<uint8_t>(wire);
}
template <> inline void storeLE<2>(uint8_t* p, int32_t wire) {
    const uint16_t v = static_cast<uint16_t>(wire);
    std::memcpy(p, &v, 2);
}
template <> inline void storeLE<3>(uint8_t* p, int32_t wire) {
    const uint32_t v = static_cast<uint32_t>(wire);
    p[0] = static_cast<uint8_t>(v);
    p[1] = static_cast<uint8_t>(v >> 8);
    p[2] = static_cast<uint8_t>(v >> 16);
}
template <> inline void storeLE<4>(uint8_t* p, int32_t wire) {
    const uint32_t v = static_cast<uint32_t>(wire);
    std::memcpy(p, &v, 4);
}

// Runtime-width store, for the one caller (writeFloat32) whose per-sample body
// carries dither and fade state and so cannot be a plain templated loop. The
// switch is on a loop-invariant value, which compilers unswitch out of the
// loop; it still beats the shift-and-mask byte loop it replaces.
inline void storeLEDyn(uint8_t* p, int32_t wire, int subslotBytes) {
    switch (subslotBytes) {
        case 1: storeLE<1>(p, wire); break;
        case 2: storeLE<2>(p, wire); break;
        case 3: storeLE<3>(p, wire); break;
        case 4: storeLE<4>(p, wire); break;
        default: break;
    }
}

// --- shift a sample into its subslot ----------------------------------------
// Positive Shift left-aligns a narrow value in a wider subslot (UAC2 puts the
// padding at the LSB end); negative Shift drops LSBs so the signal's MSBs
// survive when the DAC's subslot is narrower than the source.
template <int Shift> inline int32_t shiftWire(int32_t v) {
    if (Shift >= 0)
        return static_cast<int32_t>(static_cast<uint32_t>(v) << Shift);
    else
        return v >> (-Shift);   // arithmetic, preserves sign
}

// --- gain application, matching each source width's clamp exactly ------------
inline int32_t gainInt32(int32_t v, float gain) {
    // float carries 24 mantissa bits — enough for attenuation work, and this is
    // what the original did. kInt32MaxF is the largest float below INT32_MAX.
    float fs = static_cast<float>(v) * gain;
    if (fs > kInt32MaxF) fs = kInt32MaxF;
    else if (fs < kInt32MinF) fs = kInt32MinF;
    return static_cast<int32_t>(fs);
}

inline int32_t gainInt24(int32_t v, float gain) {
    float fs = static_cast<float>(v) * gain;
    if (fs > kInt24MaxF) fs = kInt24MaxF;
    else if (fs < kInt24MinF) fs = kInt24MinF;
    return static_cast<int32_t>(fs);
}

inline int32_t gainInt16(int16_t s, float gain) {
    float fs = static_cast<float>(s) * gain;
    if (fs > kInt16MaxF) fs = kInt16MaxF;
    else if (fs < kInt16MinF) fs = kInt16MinF;
    return static_cast<int32_t>(fs);
}

// --- int32 source -----------------------------------------------------------
// Returns bytes written. Sub == 4 at unity gain is a straight copy; callers
// should short-circuit that case before ever staging through a buffer.
template <int Sub>
inline int packInt32(const int32_t* __restrict src, int n, uint8_t* __restrict dst, float gain) {
    constexpr int kShift = Sub * 8 - 32;
    if (gain >= kUnityGain) {
        for (int i = 0; i < n; ++i)
            storeLE<Sub>(dst + i * Sub, shiftWire<kShift>(src[i]));
    } else {
        for (int i = 0; i < n; ++i)
            storeLE<Sub>(dst + i * Sub, shiftWire<kShift>(gainInt32(src[i], gain)));
    }
    return n * Sub;
}

// --- int16 source -----------------------------------------------------------
template <int Sub>
inline int packInt16(const int16_t* __restrict src, int n, uint8_t* __restrict dst, float gain) {
    constexpr int kShift = Sub * 8 - 16;
    if (gain >= kUnityGain) {
        for (int i = 0; i < n; ++i)
            storeLE<Sub>(dst + i * Sub, shiftWire<kShift>(static_cast<int32_t>(src[i])));
    } else {
        for (int i = 0; i < n; ++i)
            storeLE<Sub>(dst + i * Sub, shiftWire<kShift>(gainInt16(src[i], gain)));
    }
    return n * Sub;
}

// --- packed 24-bit source (3 bytes LE per sample) ---------------------------
inline int32_t loadInt24(const uint8_t* p) {
    // Load the 3 bytes into bits 8..31, then shift down arithmetically so the
    // sign extends. Cheaper and branch-free versus testing bit 23.
    const int32_t v = static_cast<int32_t>(
        (static_cast<uint32_t>(p[0]) << 8) |
        (static_cast<uint32_t>(p[1]) << 16) |
        (static_cast<uint32_t>(p[2]) << 24));
    return v >> 8;
}

template <int Sub>
inline int packInt24(const uint8_t* __restrict src, int n, uint8_t* __restrict dst, float gain) {
    constexpr int kShift = Sub * 8 - 24;
    if (gain >= kUnityGain) {
        for (int i = 0; i < n; ++i)
            storeLE<Sub>(dst + i * Sub, shiftWire<kShift>(loadInt24(src + i * 3)));
    } else {
        for (int i = 0; i < n; ++i)
            storeLE<Sub>(dst + i * Sub,
                         shiftWire<kShift>(gainInt24(loadInt24(src + i * 3), gain)));
    }
    return n * Sub;
}

// --- dispatch ---------------------------------------------------------------
// One place that turns the runtime subslot width into the compile-time one.
// Subslot widths outside 1..4 cannot come out of parseDescriptors() (UAC's
// bSubslotSize is a byte count for a single PCM sample), so the default arm is
// unreachable in practice and returns 0 rather than guessing.
#define AE_USBPACK_DISPATCH(fn, srcType, ...)              \
    switch (subslotBytes) {                                \
        case 1: return fn<1>(__VA_ARGS__);                 \
        case 2: return fn<2>(__VA_ARGS__);                 \
        case 3: return fn<3>(__VA_ARGS__);                 \
        case 4: return fn<4>(__VA_ARGS__);                 \
        default: return 0;                                 \
    }

inline int packInt32Dyn(const int32_t* src, int n, uint8_t* dst,
                        int subslotBytes, float gain) {
    AE_USBPACK_DISPATCH(packInt32, int32_t, src, n, dst, gain)
}
inline int packInt16Dyn(const int16_t* src, int n, uint8_t* dst,
                        int subslotBytes, float gain) {
    AE_USBPACK_DISPATCH(packInt16, int16_t, src, n, dst, gain)
}
inline int packInt24Dyn(const uint8_t* src, int n, uint8_t* dst,
                        int subslotBytes, float gain) {
    AE_USBPACK_DISPATCH(packInt24, uint8_t, src, n, dst, gain)
}

#undef AE_USBPACK_DISPATCH

} // namespace usbpack
} // namespace ae

#endif // USB_PACK_H
