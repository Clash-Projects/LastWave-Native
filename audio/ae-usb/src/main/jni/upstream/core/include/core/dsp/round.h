#ifndef AE_CORE_DSP_ROUND_H
#define AE_CORE_DSP_ROUND_H

// Exact, inline replacements for llround()/lrint() on the quantize hot paths.
//
// Why this file exists: neither is an instruction. GCC 15 at -O3 compiles
// `lrint(x)` to `jmp lrint@PLT` and `llround(x)` to `jmp llround@PLT`, and
// -fno-math-errno only rescues lrint — llround's round-half-away-from-zero tie
// rule has no single-instruction equivalent, so it stays a libm call under
// every flag short of -ffast-math (which we will never enable; see the root
// CLAUDE.md). The quantize paths call one of these ONCE PER SAMPLE, so that is
// a function call per sample on the Reference EQ output stage.
//
// These are exact, not approximate. tests/dsp_null_test.cpp validates both
// against glibc over every IEEE tie in the working range, both nextafter()
// neighbours of each .5 boundary, the clamp edges, and 10M random doubles.
// If you change anything here, that test is the contract.

#include <cmath>
#include <cstdint>

#if defined(__x86_64__) || defined(_M_X64)
#include <emmintrin.h>
#define AE_ROUND_HAS_SSE2 1
#else
#define AE_ROUND_HAS_SSE2 0
#endif

#if defined(__aarch64__)
#include <arm_neon.h>
#define AE_ROUND_HAS_NEON64 1
#else
#define AE_ROUND_HAS_NEON64 0
#endif

namespace ae {

// lrint()/nearbyint() semantics under the default rounding mode: round half to
// EVEN. This is exactly what cvtsd2si does on x86-64 (honouring MXCSR, which
// audio code never changes) and what fcvtns does on ARM64 — one instruction on
// both. std::nearbyint is NOT a valid stand-in: it compiles to a libm call.
//
// Precondition: |x| must be within int64 range. The hardware conversion returns
// the "integer indefinite" value on overflow rather than saturating. Every
// caller in this engine clamps to [-1, 1] before scaling, so this holds; the
// null test covers the clamp edges explicitly.
inline int64_t roundHalfEven(double x) {
#if AE_ROUND_HAS_SSE2
    return _mm_cvtsd_si64(_mm_set_sd(x));
#elif AE_ROUND_HAS_NEON64
    return vcvtnd_s64_f64(x);
#else
    return static_cast<int64_t>(std::lrint(x));
#endif
}

// llround() semantics: round half AWAY FROM ZERO.
//
// Built on roundHalfEven because half-to-even is the one rounding the hardware
// gives us for free; the two rules differ ONLY at exact ties, so correcting
// those is the whole job.
//
//   d = x - (double)n is EXACT for every |x| < 2^52: n is within 0.5 of x, and
//   both are multiples of ulp(x), so the difference is representable. That
//   exactness is what makes the `== 0.5` comparisons below trustworthy.
//
//   At a tie, half-to-even picks whichever neighbour is even; half-away wants
//   whichever is further from zero. n is the wrong one exactly when it fell
//   INWARD, i.e. when d has the same sign as x. Worked through:
//     x =  2.5 -> n =  2, d = +0.5, same sign -> 3   (half-even said 2)
//     x =  3.5 -> n =  4, d = -0.5, opposite  -> 4   (already away)
//     x = -2.5 -> n = -2, d = -0.5, same sign -> -3
//     x = -3.5 -> n = -4, d = +0.5, opposite  -> -4  (already away)
//
// Do NOT "simplify" this to trunc(x + copysign(0.5, x)) with a
// |r - x| > 0.5 fixup. That is the form every reference suggests and it is
// wrong: for x = 0.49999999999999994 the sum rounds to exactly 1.0, and then
// 1.0 - x rounds to exactly 0.5 — so the guard never fires and it returns 1
// where llround returns 0. dsp_null_test asserts this specific case.
inline int64_t roundHalfAway(double x) {
    const int64_t n = roundHalfEven(x);
    const double  d = x - static_cast<double>(n);
    if (d ==  0.5 && x > 0.0) return n + 1;
    if (d == -0.5 && x < 0.0) return n - 1;
    return n;
}

} // namespace ae

#endif // AE_CORE_DSP_ROUND_H
