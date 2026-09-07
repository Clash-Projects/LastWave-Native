package com.lastwave.app.ui.common

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Old-school HiFi analog VU meter with a glowing yellow-orange backlight.
 *
 * The needle is driven by a lively pseudo-dB signal (fast attack, wobbly
 * release like a real moving-coil meter). Pass [isPlaying] so it falls to
 * rest when paused. Pass an explicit [level] 0..1 to drive it from a real
 * analyser (Visualizer/PCM RMS) — when null it self-animates.
 */
@Composable
fun AnalogVuMeter(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    level: Float? = null,
) {
    var simulatedTarget by remember { mutableFloatStateOf(0f) }

    // Bass-first pseudo-dB generator (~9 ticks/sec, fallback only when no
    // real PCM flows): a ~124 BPM kick envelope (sharp attack, exp decay)
    // dominates; non-bass content only lifts the floor. Calm by design —
    // the needle follower above tracks it 1:1.
    LaunchedEffect(isPlaying, level) {
        if (level != null) return@LaunchedEffect
        var beatMs = 0L
        var wobble = 0.0
        var otherTimer = 0L
        var otherTarget = 0.08f
        var otherLevel = 0.08f
        val bpm = 124.0
        while (true) {
            if (isPlaying) {
                delay(110)
                beatMs += 110
                wobble += 0.21
                // 0 right on the kick, decays to ~0 before the next kick.
                val phase = ((beatMs * bpm / 60_000.0) % 1.0).toFloat()
                val kick = exp(-phase * 5.5f)
                // Weak off-beat ghost kick — still bass, keeps the groove.
                val ghost = exp(-((phase + 0.5f) % 1f) * 9f) * 0.18f
                // Other frequencies: slow retarget + glide, no spikes.
                otherTimer += 110
                if (otherTimer >= 220) {
                    otherTimer = 0L
                    val wob = ((sin(wobble * 2.1) * 0.5 + 0.5).toFloat() * 0.05f)
                    otherTarget = 0.03f + Random.nextFloat() * 0.08f + wob
                }
                otherLevel += (otherTarget - otherLevel) * 0.35f
                simulatedTarget = (0.07f + (kick + ghost) * 0.65f + otherLevel).coerceIn(0f, 1f)
            } else {
                simulatedTarget = 0f
                delay(220)
            }
        }
    }

    val target = (level ?: simulatedTarget).coerceIn(0f, 1f)

    // Needle follower: deliberately transparent (fast, critically damped).
    // All the character — 300 ms rise/fall per IEC 60268-17 — already lives
    // in the monitor's ballistics; this just tracks it 1:1 without adding
    // lag or bounce on top.
    val needleLevel by animateFloatAsState(
        targetValue = target,
        animationSpec = spring(
            dampingRatio = 1f,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "vuNeedle",
    )
    val glowBoost by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.35f,
        animationSpec = tween(600),
        label = "vuGlow",
    )

    // Walnut frame + amber halo.
    Box(
        modifier = modifier
            .shadow(
                elevation = (10 + 14 * glowBoost).dp,
                shape = RoundedCornerShape(18.dp),
                ambientColor = Color(0xFFFF9A1F).copy(alpha = 0.55f * glowBoost),
                spotColor = Color(0xFFFFB300).copy(alpha = 0.65f * glowBoost),
            )
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF3A2008), Color(0xFF1E1004)),
                ),
            )
            .padding(5.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFFFC64D))
            .height(132.dp)
            .fillMaxWidth(),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Glowing yellow-orange back.
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color(0xFFFFF3C4),
                    0.45f to Color(0xFFFFD97A),
                    0.78f to Color(0xFFFFB02E),
                    1f to Color(0xFFF68B1A),
                ),
                size = size,
            )
            // Warm lamp bloom top-center, pulses with the music.
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color.White.copy(alpha = 0.55f * glowBoost + 0.12f * needleLevel),
                    0.6f to Color(0xFFFFE9A8).copy(alpha = 0.30f * glowBoost),
                    1f to Color.Transparent,
                    center = Offset(w * 0.5f, h * 0.12f),
                    radius = w * 0.55f,
                ),
                radius = w * 0.55f,
                center = Offset(w * 0.5f, h * 0.12f),
            )
            // Bottom warmth so the pivot area never looks muddy.
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to Color(0xFFB25A00).copy(alpha = 0.28f),
                ),
                size = size,
            )

            // Geometry fitted to the visible box: the arc top sits 24dp below
            // the top edge and the arc ends stay inside on phones and wide
            // screens alike. The pivot ends up below the box, so the needle
            // rises from the bottom edge like on compact HiFi dials.
            // (The old pivot/radius pushed the whole scale above the canvas,
            // leaving only the resting needle visible on the left.)
            val sweepHalf = 40f // degrees from vertical, each side
            val sweepRad = Math.toRadians(sweepHalf.toDouble())
            val sinS = sin(sweepRad).toFloat()
            val cosS = cos(sweepRad).toFloat()
            val topM = 24.dp.toPx()
            val radius = minOf(
                (w / 2f - 12.dp.toPx()) / sinS,
                (h - 8.dp.toPx() - topM) / (1f - cosS),
            ).coerceAtLeast(80.dp.toPx())
            val pivot = Offset(w * 0.5f, radius + topM)
            val minAngle = -sweepHalf // degrees from vertical, left
            val maxAngle = sweepHalf // degrees from vertical, right

            // dB scale: -20 .. +3, 0 VU sits ~72% across (classic).
            val dbMarks = listOf(-20, -10, -7, -5, -3, -2, -1, 0, 1, 2, 3)
            fun dbToFraction(db: Int): Float = (db + 20) / 23f

            // Scale arc.
            drawArc(
                color = Color(0xFF4A2A00).copy(alpha = 0.55f),
                startAngle = 270f - maxAngle,
                sweepAngle = maxAngle - minAngle,
                useCenter = false,
                topLeft = Offset(pivot.x - radius, pivot.y - radius),
                size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
            )

            // Ticks + numbers.
            dbMarks.forEach { db ->
                val frac = dbToFraction(db)
                val angleDeg = minAngle + frac * (maxAngle - minAngle)
                val angleRad = Math.toRadians(angleDeg.toDouble())
                val isRed = db > 0
                val isMajor = db == -20 || db == -10 || db == -5 || db == 0 || db == 3
                val tickColor = if (isRed) Color(0xFFC81E1E) else Color(0xFF3B2200)
                val outer = radius - 6.dp.toPx()
                val inner = radius - (if (isMajor) 22.dp.toPx() else 15.dp.toPx())
                val dir = Offset(sin(angleRad).toFloat(), -cos(angleRad).toFloat())
                drawLine(
                    color = tickColor,
                    start = pivot + dir * inner,
                    end = pivot + dir * outer,
                    strokeWidth = if (isMajor) 3.dp.toPx() else 2.dp.toPx(),
                )
                // Minor subdivisions between majors.
                if (isMajor && db < 3) {
                    repeat(4) { k ->
                        val subFrac = frac + (k + 1) / 5f * (1f / dbMarks.size)
                        val subAngle = minAngle + subFrac.coerceIn(0f, 1f) * (maxAngle - minAngle)
                        val subRad = Math.toRadians(subAngle.toDouble())
                        val subDir = Offset(sin(subRad).toFloat(), -cos(subRad).toFloat())
                        drawLine(
                            color = tickColor.copy(alpha = 0.55f),
                            start = pivot + subDir * (radius - 12.dp.toPx()),
                            end = pivot + subDir * (radius - 6.dp.toPx()),
                            strokeWidth = 1.2.dp.toPx(),
                        )
                    }
                }
            }

            // Perceptual log scale, silence (needle rest) at far left:
            // 0.001 -> 0%, 0.01 -> 30%, 0.1 -> 55%, 1 -> 80%,
            // 10 -> 95%, 100 -> 100%. Interpolated linearly in log space
            // between these anchors.
            val logMarks = listOf(0.001, 0.01, 0.1, 1.0, 10.0, 100.0)
            val logAnchors = listOf(
                -3.0 to 0.00f, // 0.001
                -2.0 to 0.30f, // 0.01
                -1.0 to 0.55f, // 0.1
                0.0 to 0.80f, // 1
                1.0 to 0.95f, // 10
                2.0 to 1.00f, // 100
            )
            fun logToFraction(v: Double): Float {
                val l = kotlin.math.log10(v.coerceAtLeast(0.001))
                if (l <= -3.0) return 0f
                if (l >= 2.0) return 1f
                for (i in 0 until logAnchors.lastIndex) {
                    val (l0, f0) = logAnchors[i]
                    val (l1, f1) = logAnchors[i + 1]
                    if (l <= l1) {
                        val t = ((l - l0) / (l1 - l0)).toFloat()
                        return f0 + (f1 - f0) * t
                    }
                }
                return 1f
            }
            val logOuter = radius - 50.dp.toPx()
            drawArc(
                color = Color(0xFF4A2A00).copy(alpha = 0.45f),
                startAngle = 270f - maxAngle,
                sweepAngle = maxAngle - minAngle,
                useCenter = false,
                topLeft = Offset(pivot.x - logOuter, pivot.y - logOuter),
                size = androidx.compose.ui.geometry.Size(logOuter * 2, logOuter * 2),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6.dp.toPx()),
            )
            logMarks.forEach { v ->
                val frac = logToFraction(v)
                val angleDeg = minAngle + frac * (maxAngle - minAngle)
                val angleRad = Math.toRadians(angleDeg.toDouble())
                val isCenter = v == 1.0
                val dir = Offset(sin(angleRad).toFloat(), -cos(angleRad).toFloat())
                val inner = logOuter - (if (isCenter) 17.dp.toPx() else 13.dp.toPx())
                drawLine(
                    color = Color(0xFF3B2200),
                    start = pivot + dir * inner,
                    end = pivot + dir * logOuter,
                    strokeWidth = if (isCenter) 3.dp.toPx() else 2.2.dp.toPx(),
                )
            }
            // Log decade lines: 2..9 inside each decade.
            for (decade in -3..1) {
                val base = Math.pow(10.0, decade.toDouble())
                for (m in 2..9) {
                    val frac = logToFraction(base * m)
                    val subAngle = minAngle + frac * (maxAngle - minAngle)
                    val subRad = Math.toRadians(subAngle.toDouble())
                    val subDir = Offset(sin(subRad).toFloat(), -cos(subRad).toFloat())
                    drawLine(
                        color = Color(0xFF3B2200).copy(alpha = 0.55f),
                        start = pivot + subDir * (logOuter - 7.dp.toPx()),
                        end = pivot + subDir * logOuter,
                        strokeWidth = 1.1.dp.toPx(),
                    )
                }
            }

            // Red zone arc past 0 VU.
            val zeroFrac = dbToFraction(0)
            val zeroAngle = minAngle + zeroFrac * (maxAngle - minAngle)
            drawArc(
                color = Color(0xFFC81E1E),
                startAngle = 270f + zeroAngle,
                sweepAngle = maxAngle - zeroAngle,
                useCenter = false,
                topLeft = Offset(pivot.x - radius + 6.dp.toPx(), pivot.y - radius + 6.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(
                    (radius - 6.dp.toPx()) * 2,
                    (radius - 6.dp.toPx()) * 2,
                ),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4.dp.toPx()),
            )

            // Numbers + "VU" drawn with the platform canvas for crisp serif text.
            val textRadius = radius - 34.dp.toPx()
            drawContext.canvas.nativeCanvas.apply {
                val paint = Paint().apply {
                    color = android.graphics.Color.rgb(0x3B, 0x22, 0x00)
                    textAlign = Paint.Align.CENTER
                    isAntiAlias = true
                    typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                }
                dbMarks.forEach { db ->
                    val frac = dbToFraction(db)
                    val angleDeg = minAngle + frac * (maxAngle - minAngle)
                    val rad = Math.toRadians(angleDeg.toDouble())
                    val pos = pivot + Offset(sin(rad).toFloat(), -cos(rad).toFloat()) * textRadius
                    paint.textSize = if (db == 0) 13.dp.toPx() else 10.dp.toPx()
                    if (db > 0) paint.color = android.graphics.Color.rgb(0xC8, 0x1E, 0x1E)
                    else paint.color = android.graphics.Color.rgb(0x3B, 0x22, 0x00)
                    val label = if (db > 0) "+$db" else "$db"
                    val labelY = (pos.y + 4.dp.toPx()).coerceIn(12.dp.toPx(), h - 8.dp.toPx())
                    drawText(label, pos.x, labelY, paint)
                }
                // Log scale numbers: 0.001 silent at far left … 1 (bold) at 80% …
                // 10 at 95% … 100 far right. End labels sit one row higher so
                // they stay clear of the VU numbers and the bottom edge.
                val logTextRadius = logOuter - 26.dp.toPx()
                logMarks.forEach { v ->
                    val frac = logToFraction(v)
                    val angleDeg = minAngle + frac * (maxAngle - minAngle)
                    val rad = Math.toRadians(angleDeg.toDouble())
                    val rr = when (v) {
                        0.001, 100.0 -> logOuter + 3.dp.toPx()
                        10.0 -> logOuter - 12.dp.toPx()
                        else -> logTextRadius
                    }
                    val pos = pivot + Offset(sin(rad).toFloat(), -cos(rad).toFloat()) * rr
                    val isCenter = v == 1.0
                    paint.textSize = when (v) {
                        1.0 -> 12.dp.toPx()
                        0.001, 100.0 -> 7.5.dp.toPx()
                        else -> 8.5.dp.toPx()
                    }
                    paint.color = android.graphics.Color.rgb(0x3B, 0x22, 0x00)
                    paint.alpha = if (isCenter) 255 else 220
                    val label = when (v) {
                        0.001 -> "0.001"
                        0.01 -> "0.01"
                        0.1 -> "0.1"
                        1.0 -> "1"
                        10.0 -> "10"
                        else -> "100"
                    }
                    val labelY = (pos.y + 3.dp.toPx()).coerceIn(12.dp.toPx(), h - 6.dp.toPx())
                    drawText(label, pos.x, labelY, paint)
                }
                paint.textSize = 22.dp.toPx()
                paint.color = android.graphics.Color.rgb(0x4A, 0x2A, 0x00)
                paint.alpha = 235
                drawText("VU", w * 0.5f, h * 0.32f, paint)
            }

            // Needle with drop shadow, then the needle itself.
            val needleAngle = minAngle + needleLevel * (maxAngle - minAngle)
            val needleLen = radius - 12.dp.toPx()
            rotate(degrees = needleAngle, pivot = pivot) {
                drawLine(
                    color = Color.Black.copy(alpha = 0.25f),
                    start = pivot + Offset(2.dp.toPx(), 3.dp.toPx()),
                    end = pivot + Offset(2.dp.toPx(), -needleLen),
                    strokeWidth = 4.dp.toPx(),
                )
                drawLine(
                    color = Color(0xFF1A0F00),
                    start = pivot + Offset(0f, 14.dp.toPx()),
                    end = pivot + Offset(0f, -needleLen),
                    strokeWidth = 2.6.dp.toPx(),
                )
                // Red tip in the hot zone.
                drawLine(
                    color = Color(0xFFC81E1E),
                    start = pivot + Offset(0f, -needleLen + 26.dp.toPx()),
                    end = pivot + Offset(0f, -needleLen),
                    strokeWidth = 2.6.dp.toPx(),
                )
            }

            // Brass pivot cap.
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color(0xFFFFF6DC),
                    0.45f to Color(0xFFD9A441),
                    1f to Color(0xFF6B3D00),
                    center = pivot,
                    radius = 15.dp.toPx(),
                ),
                radius = 15.dp.toPx(),
                center = pivot,
            )
            drawCircle(color = Color(0xFF2A1600), radius = 3.2.dp.toPx(), center = pivot)

            // Glass reflection.
            drawRect(
                brush = Brush.linearGradient(
                    0f to Color.White.copy(alpha = 0.20f),
                    0.35f to Color.White.copy(alpha = 0.05f),
                    0.6f to Color.Transparent,
                    start = Offset(0f, 0f),
                    end = Offset(w * 0.7f, h),
                ),
                size = size,
            )

            // Corner screws of the faceplate.
            listOf(
                Offset(9.dp.toPx(), 9.dp.toPx()),
                Offset(w - 9.dp.toPx(), 9.dp.toPx()),
                Offset(9.dp.toPx(), h - 9.dp.toPx()),
                Offset(w - 9.dp.toPx(), h - 9.dp.toPx()),
            ).forEach { screw ->
                drawCircle(color = Color(0xFF5A3A10), radius = 4.dp.toPx(), center = screw)
                drawLine(
                    color = Color(0xFF2A1600),
                    start = screw + Offset(-2.4.dp.toPx(), 0f),
                    end = screw + Offset(2.4.dp.toPx(), 0f),
                    strokeWidth = 1.2.dp.toPx(),
                )
            }
        }
    }
}

/** Convenience: VU meter bound to Material colors for previews/tests. */
@Composable
fun PlaybackVuMeter(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    level: Float? = null,
) {
    // Keeps call sites tiny: <PlaybackVuMeter(isPlaying = state.isPlaying) />
    AnalogVuMeter(isPlaying = isPlaying, modifier = modifier, level = level)
}

/**
 * Real bass level from the decoded PCM stream, or null when no fresh data
 * flows (paused, fallback sink, Cast) so callers fall back to the simulated
 * groove: `level = rememberRealBassLevel(isPlaying)`.
 */
@Composable
fun rememberRealBassLevel(isPlaying: Boolean): Float? {
    val levels by com.lastwave.app.playback.AudioLevelMonitor.levels.collectAsStateWithLifecycle()
    var fresh by remember { mutableStateOf(false) }
    LaunchedEffect(levels.updatedMs) {
        if (levels.updatedMs == 0L) {
            fresh = false
            return@LaunchedEffect
        }
        fresh = true
        delay(400)
        fresh = false
    }
    return if (isPlaying && fresh) levels.bass else null
}
