#ifndef AE_CORE_DSP_WIRE_SCALE_H
#define AE_CORE_DSP_WIRE_SCALE_H

// Single source of truth for the PCM full-scale constants the quantize/pack
// stages all need. Before this header existed, 32767.0 / 8388607.0 /
// 2147483647.0 (and the float saturation variants) were hand-typed
// independently in eq_processor.h, dither.h, and usb_pack.h — same numbers,
// three places, nothing tying them together.

#include <cstdint>

namespace ae {

// Symmetric full-scale magnitude for each signed PCM depth: the wire grid is
// [-(max+1), +max], so quantizing scales by `max`, and clamping needs both
// bounds separately (the negative bound is one further out).
inline constexpr double kInt16Max = 32767.0;
inline constexpr double kInt16Min = -32768.0;
inline constexpr double kInt24Max = 8388607.0;
inline constexpr double kInt24Min = -8388608.0;
inline constexpr double kInt32Max = 2147483647.0;
inline constexpr double kInt32Min = -2147483648.0;

// float-typed variants, for gain/clamp math done in float (usb_pack.h's
// gainInt* helpers work on already-float-converted samples). kInt32MaxF is
// deliberately NOT `(float)kInt32Max` — 2147483647.0 is not representable in
// float and rounds UP to 2147483648.0f (one past INT32_MAX); this is the
// largest float strictly below INT32_MAX instead.
inline constexpr float kInt16MaxF = 32767.0f;
inline constexpr float kInt16MinF = -32768.0f;
inline constexpr float kInt24MaxF = 8388607.0f;
inline constexpr float kInt24MinF = -8388608.0f;
inline constexpr float kInt32MaxF = 2147483520.0f;
inline constexpr float kInt32MinF = -2147483648.0f;

// Native-depth full-scale magnitude, selected at runtime by bit depth. Used
// where the caller quantizes to the bit depth's own grid and applies any
// wire-subslot padding as a SEPARATE shift afterward (e.g. UsbAudioDriver::
// writeFloat32, which computes padBits independently).
//
// NOT the same quantity as ae::wireScale() (dither.h): that one pre-bakes the
// subslot left-shift into the scale itself for callers that emit a full int32
// wire value in one step. Two genuinely different conventions, each with one
// canonical implementation now instead of duplicated literals.
inline double wireScaleNative(int bits) {
    return (bits == 24) ? kInt24Max
         : (bits == 32) ? kInt32Max
         :                 kInt16Max;
}

} // namespace ae

#endif // AE_CORE_DSP_WIRE_SCALE_H
