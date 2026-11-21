package com.lastwave.app.ui.player

import android.os.Build
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Applies a Gaussian blur effect to the composable for the lyrics themes.
 *
 * Strategy:
 * - API 31+ (Android 12): hardware-accelerated RenderEffect, zero per-frame allocations.
 * - API < 31: graceful alpha-only degradation (no actual blur).
 */
fun Modifier.lyricsBlurEffect(blurRadius: Float): Modifier {
    if (blurRadius < 0.5f) return this
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        this.graphicsLayer {
            val effect = android.graphics.RenderEffect.createBlurEffect(
                blurRadius, blurRadius,
                android.graphics.Shader.TileMode.CLAMP
            )
            renderEffect = effect.asComposeRenderEffect()
        }
    } else {
        // Pre-API 31 fallback: reduce alpha proportionally to blur amount.
        this.graphicsLayer {
            alpha *= (1f - (blurRadius / 40f).coerceIn(0f, 0.4f))
        }
    }
}
