#ifndef AUDIO_CONVERT_H
#define AUDIO_CONVERT_H

#include <cstdint>
#include <algorithm>

#if defined(__aarch64__) && defined(__ARM_NEON)
#include <arm_neon.h>
#define CONVERT_HAS_NEON 1
#else
#define CONVERT_HAS_NEON 0
#endif

// Simple LCG for fast deterministic dither -- no system calls
class DitherLCG {
public:
    DitherLCG() : state(0x12345678u) {}

    // Returns uniform random in [-0.5, +0.5]
    inline float next() {
        state = state * 1664525u + 1013904223u;
        // Convert upper bits to float in [0, 1), subtract 0.5. state >> 8 is
        // a 24-bit value (0..16777215), exactly representable in float's
        // 24-bit mantissa — the cast is exact, not a truncation.
        return static_cast<float>(state >> 8) * (1.0f / 16777216.0f) - 0.5f;
    }

    // TPDF: sum of two uniform randoms -> triangular distribution in [-1, +1]
    inline float nextTPDF() {
        return next() + next();
    }

private:
    uint32_t state;
};

// Convert float32 PCM to int16 PCM with TPDF dither.
// src: float samples (native endian)
// dst: int16 samples (native endian)
// sampleCount: number of samples (not frames)
// rng: caller-owned dither state. Must persist across calls so the dither
//      sequence continues from buffer to buffer; a fresh generator per call
//      repeats the same noise pattern every buffer, which is audible as a
//      correlated tone instead of flat noise.
inline void floatToInt16Dither(const float* src, int16_t* dst, int sampleCount,
                               DitherLCG& rng) {
#if CONVERT_HAS_NEON
    // Process 4 samples at a time with NEON
    const float32x4_t scale = vdupq_n_f32(32767.0f);
    const float32x4_t ditherScale = vdupq_n_f32(1.0f / 65536.0f * 32767.0f);
    int i = 0;
    for (; i + 3 < sampleCount; i += 4) {
        float32x4_t s = vld1q_f32(&src[i]);
        s = vmulq_f32(s, scale);
        // Generate TPDF dither for 4 samples
        float d0 = rng.nextTPDF();
        float d1 = rng.nextTPDF();
        float d2 = rng.nextTPDF();
        float d3 = rng.nextTPDF();
        float ditherArr[4] = {d0, d1, d2, d3};
        float32x4_t dither = vld1q_f32(ditherArr);
        s = vaddq_f32(s, dither);
        // Convert to int32 with rounding
        int32x4_t i32 = vcvtq_s32_f32(s);
        // Saturating narrow to int16
        int16x4_t i16 = vqmovn_s32(i32);
        vst1_s16(&dst[i], i16);
    }
    // Scalar tail
    for (; i < sampleCount; i++) {
        float dither = rng.nextTPDF();
        float scaled = src[i] * 32767.0f + dither;
        int32_t clamped = static_cast<int32_t>(scaled);
        if (clamped > 32767) clamped = 32767;
        else if (clamped < -32768) clamped = -32768;
        dst[i] = static_cast<int16_t>(clamped);
    }
#else
    for (int i = 0; i < sampleCount; i++) {
        float dither = rng.nextTPDF();
        float scaled = src[i] * 32767.0f + dither;
        int32_t clamped = static_cast<int32_t>(scaled);
        if (clamped > 32767) clamped = 32767;
        else if (clamped < -32768) clamped = -32768;
        dst[i] = static_cast<int16_t>(clamped);
    }
#endif
}

#endif // AUDIO_CONVERT_H
