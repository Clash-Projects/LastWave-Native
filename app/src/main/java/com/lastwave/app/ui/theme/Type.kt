@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.lastwave.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.lastwave.app.R

/**
 * Nothing OS redesign: same bundled Google Sans Flex variable font as
 * before (res/font/google_sans_flex.ttf), but every style now pins ROND to
 * 0 and width to the font's own default (100) — the previous scale leaned
 * into extra roundness/width per style for a "friendly premium app" feel,
 * which is exactly the opposite of the flat, "engineered, not designed"
 * character this look calls for. Weight is still varied for hierarchy;
 * that's structural, not decorative.
 */
private fun gsFlex(
    weight: Float,
    opticalSize: Float,
): FontFamily = FontFamily(
    Font(
        R.font.google_sans_flex,
        weight = when {
            weight >= 700f -> FontWeight.Bold
            weight >= 600f -> FontWeight.SemiBold
            weight >= 500f -> FontWeight.Medium
            else -> FontWeight.Normal
        },
        variationSettings = FontVariation.Settings(
            FontVariation.weight(weight.toInt()),
            FontVariation.width(100f),
            FontVariation.Setting("ROND", 0f),
            FontVariation.Setting("opsz", opticalSize),
            FontVariation.Setting("GRAD", 0f),
        ),
    ),
)

/**
 * DSEG7 Classic (res/font/dseg7_classic_*.ttf, SIL OFL — see
 * /licenses/DSEG-LICENSE.txt) — a real seven-segment/LCD-style digital
 * font, not a simulated "digital-looking" sans. This is the single most
 * identifying signature of the Nothing OS look: apply it to any raw
 * number in the UI — track duration/elapsed time, bitrate, track counts,
 * dates, timestamps — wherever it appears, not just in one place. It only
 * covers digits and a handful of segment-drawable symbols, so it must
 * never be used for arbitrary text/labels.
 *
 * Usage: Text("128", style = MaterialTheme.typography.bodyLarge.copy(
 *     fontFamily = NothingDigitsFontFamily, letterSpacing = 1.sp))
 */
val NothingDigitsFontFamily = FontFamily(
    Font(R.font.dseg7_classic_regular, weight = FontWeight.Normal),
    Font(R.font.dseg7_classic_bold, weight = FontWeight.Bold),
)

val NothingDotFontFamily = FontFamily(
    Font(R.font.silkscreen_regular, weight = FontWeight.Normal),
    Font(R.font.silkscreen_bold, weight = FontWeight.Bold),
)

val LastWaveTypography = Typography(
    displayLarge = TextStyle(fontFamily = gsFlex(weight = 700f, opticalSize = 57f), fontWeight = FontWeight.Bold, fontSize = 57.sp, lineHeight = 64.sp),
    displayMedium = TextStyle(fontFamily = gsFlex(weight = 700f, opticalSize = 45f), fontWeight = FontWeight.Bold, fontSize = 45.sp, lineHeight = 52.sp),
    displaySmall = TextStyle(fontFamily = gsFlex(weight = 700f, opticalSize = 36f), fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 44.sp),
    headlineLarge = TextStyle(fontFamily = NothingDotFontFamily, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 38.sp, letterSpacing = 0.4.sp),
    headlineMedium = TextStyle(fontFamily = NothingDotFontFamily, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 34.sp, letterSpacing = 0.4.sp),
    headlineSmall = TextStyle(fontFamily = gsFlex(weight = 600f, opticalSize = 24f), fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = gsFlex(weight = 600f, opticalSize = 22f), fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = gsFlex(weight = 500f, opticalSize = 16f), fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = gsFlex(weight = 500f, opticalSize = 14f), fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = gsFlex(weight = 400f, opticalSize = 16f), fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = gsFlex(weight = 400f, opticalSize = 15f), fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = gsFlex(weight = 400f, opticalSize = 13f), fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    // Section headers / category labels read as stenciled hardware labels:
    // uppercase content (apply .uppercase() at the call site — TextStyle
    // can't transform case on its own) with wide tracking.
    labelLarge = TextStyle(fontFamily = NothingDotFontFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, lineHeight = 20.sp, letterSpacing = 1.2.sp),
    labelMedium = TextStyle(fontFamily = NothingDotFontFamily, fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 1.4.sp),
    labelSmall = TextStyle(fontFamily = NothingDotFontFamily, fontWeight = FontWeight.Normal, fontSize = 10.sp, lineHeight = 16.sp, letterSpacing = 1.6.sp),
)

/**
 * The "Use Application Font" toggle's off-state: plain platform default
 * (FontFamily.Default), same sizes/weights/letterSpacing as
 * [LastWaveTypography] so turning the toggle off changes ONLY the
 * typeface, not the whole scale.
 */
val SystemTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 57.sp, lineHeight = 64.sp),
    displayMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 45.sp, lineHeight = 52.sp),
    displaySmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 44.sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 1.2.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.4.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 1.6.sp),
)
