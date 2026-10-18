package com.lastwave.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.lastwave.app.R
import com.lastwave.app.playback.InternalDirectAvailability
import com.lastwave.app.playback.InternalDirectStream
import com.lastwave.app.playback.InternalRoute
import com.lastwave.app.playback.SignalPathReport
import com.lastwave.app.playback.internalFailureDetail
import com.lastwave.app.ui.theme.LiquidGlassPreset
import com.lastwave.app.ui.theme.LocalLiquidGlass
import com.lastwave.app.ui.theme.LocalLiquidGlassOverlayBackdrop
import com.lastwave.app.ui.theme.liquidGlassChrome
import java.util.Locale

/**
 * Detailed signal-path popup opened from the quality badge (FLAC 24/96…).
 * Every row is a measured check — the BIT-PERFECT verdict appears only when
 * all of them pass. Drift refreshes live while playing (1 Hz report ticks).
 */
@Composable
fun SignalPathDialog(
    report: SignalPathReport,
    needsUsbPermission: Boolean,
    onRequestUsbAccess: () -> Unit,
    onDismiss: () -> Unit,
    internalAvailability: InternalDirectAvailability? = null,
    internalEnabled: Boolean = false,
    internalUnderruns: Long? = null,
    usbDiagnostic: (() -> String)? = null,
) {
    Dialog(onDismissRequest = onDismiss) {
        val glass = LocalLiquidGlass.current
        Surface(
            shape = RoundedCornerShape(24.dp),
            // Dense diagnostic text: keep this dialog opaque even when liquid
            // glass is on. liquidGlassContainerColor goes fully transparent
            // under glass (ModalSheet chrome is a no-op), which lets the
            // player artwork/title bleed through the rows and makes them
            // unreadable.
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = if (glass) 0.dp else 6.dp,
            modifier = Modifier.liquidGlassChrome(
                RoundedCornerShape(24.dp),
                glass,
                LiquidGlassPreset.ModalSheet,
                LocalLiquidGlassOverlayBackdrop.current,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.signal_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    VerdictPill(report = report)
                }
                Text(
                    verdictText(report),
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        report.bitPerfect -> MaterialTheme.colorScheme.primary
                        report.directToHal -> MaterialTheme.colorScheme.tertiary
                        report.clockFallbackResampled -> Color(0xFFFFB74D)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(top = 4.dp),
                )
                // Poweramp-style Pipeline Stages
                val dspRateKHz = (report.appRateHz / 1000.0).let { if (it % 1.0 == 0.0) "${it.toInt()} kHz" else "$it kHz" }
                val outRateKHz = (report.outputRateHz / 1000.0).let { if (it % 1.0 == 0.0) "${it.toInt()} kHz" else "$it kHz" }
                val srcKHz = ((report.sourceRateHz ?: report.appRateHz.takeIf { it > 0 } ?: 44100) / 1000.0).let { if (it % 1.0 == 0.0) "${it.toInt()} kHz" else "$it kHz" }
                val srcBits = report.sourceBitDepth ?: 16

                // 1. Source Stage
                PipelineNode(
                    iconContent = {
                        Text("♪", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    },
                    title = "Source",
                    badge = if (report.bitPerfect) "LOSSLESS" else null,
                    isLast = false,
                ) {
                    Text(report.sourceLabel, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text("$srcKHz\n$srcBits bit", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }

                // 2. DSP Stage
                PipelineNode(
                    iconContent = {
                        Text("≈", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    },
                    title = "DSP",
                    badge = if (report.dspScore == "—") "NO DSP" else null,
                    isLast = false,
                ) {
                    Text(dspRateKHz, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(report.dspSummary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text("Equalizer", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (report.dspScore == "—") "Equ Off • Balance (0%)" else "Equalizer Mode: graphic (10-band)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text("Tone", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, fontWeight = FontWeight.SemiBold)
                    Text("Tone Off", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    Text("Crossfade", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (report.checks.any { it.detailRes == R.string.signal_detail_dsp_crossfade }) "Crossfade active" else "No fading / Short crossfade",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text("Buffers", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, fontWeight = FontWeight.SemiBold)
                    Text("2x (50 ms, ${(report.appRateHz * 0.05).toInt()} audioframes)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    Text("Latency", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, fontWeight = FontWeight.SemiBold)
                    Text("${report.outputLatencyMs * 2} ms", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }

                // 3. Volume Control Stage
                PipelineNode(
                    iconContent = {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape),
                            contentAlignment = Alignment.TopStart,
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(2.5.dp)
                                    .size(3.5.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                        }
                    },
                    title = "Volume Control",
                    isLast = false,
                ) {
                    Text(report.volumeMode, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text(report.volumeDetail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    Text("Preamp", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, fontWeight = FontWeight.SemiBold)
                    Text("0.0dB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }

                // 4. Output Engine Stage
                PipelineNode(
                    iconContent = {
                        Text("ψ", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    },
                    title = "Output",
                    badge = if (report.usbExclusiveActive) "EXCLUSIVE" else null,
                    isLast = false,
                ) {
                    Text(
                        report.outputEngineName,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            textDecoration = TextDecoration.Underline,
                        ),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(outRateKHz, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text("${report.outputBits} bit", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text("Flags", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, fontWeight = FontWeight.SemiBold)
                    Text(
                        report.outputFlags.joinToString(" "),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("Latency", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, fontWeight = FontWeight.SemiBold)
                    Text("${report.outputLatencyMs} ms", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }

                // 5. Output Device Stage
                PipelineNode(
                    iconContent = {
                        Text("⎋", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    },
                    title = "Output Device",
                    isLast = true,
                ) {
                    Text(
                        report.dacName ?: if (report.internalDirectActive) "Internal Hi-Res DAC" else "Android Default Output",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            textDecoration = TextDecoration.Underline,
                        ),
                        fontWeight = FontWeight.Bold,
                    )
                    Text(outRateKHz, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text("${report.outputBits} bit", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }

                // Poweramp Fidelity & DSP Bottom Card
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Fidelity",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "i",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 10.sp,
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${report.fidelityScore}",
                                style = MaterialTheme.typography.displayMedium,
                                fontWeight = FontWeight.Black,
                                color = if (report.bitPerfect) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "DSP",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "i",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 10.sp,
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                report.dspScore,
                                style = MaterialTheme.typography.displayMedium,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }


                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.signal_health),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                val measuring = stringResource(R.string.signal_measuring)
                HealthRow(
                    stringResource(R.string.signal_drift),
                    report.driftPpm?.let { String.format(Locale.ROOT, "%+.1f PPM", it) }
                        ?: if (report.isPlaying) measuring else "—",
                )
                val streamState = if (report.isPlaying) {
                    stringResource(R.string.signal_playing)
                } else {
                    stringResource(R.string.signal_idle)
                }
                // Exclusive usbdevfs never uses AudioTrack. Gold is a stricter
                // verdict (clock + unity/FU + DSP off); do not call exclusive
                // output "Shared AudioTrack" just because a metadata check failed.
                val routeTrack = if (report.internalDirectActive) {
                    stringResource(R.string.signal_internal_track)
                } else if (report.usbExclusiveActive || report.bitPerfect) {
                    stringResource(R.string.signal_direct_track)
                } else {
                    stringResource(R.string.signal_shared_track)
                }
                HealthRow(
                    stringResource(R.string.signal_stream),
                    buildString {
                        append(streamState)
                        if (report.appRateHz > 0) append(" • ${report.appRateHz} Hz")
                        append(" • $routeTrack")
                    },
                )
                HealthRow(stringResource(R.string.signal_glitches), report.glitchCount.toString())

                // Route/pipeline details: what the output allows and what is in use.
                Spacer(Modifier.height(8.dp))
                if (report.usbExclusiveActive) {
                    Text(
                        stringResource(R.string.signal_usb_hardware_section),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    HealthRow(
                        stringResource(R.string.signal_usb_interface),
                        stringResource(R.string.signal_usb_interface_value),
                    )
                    HealthRow(
                        stringResource(R.string.signal_android_mixer),
                        stringResource(R.string.signal_android_mixer_bypassed),
                    )
                    val epRate = if (report.outputRateHz > 0) report.outputRateHz else report.appRateHz
                    HealthRow(
                        stringResource(R.string.signal_internal_endpoint),
                        if (epRate > 0) {
                            stringResource(
                                R.string.signal_internal_endpoint_value,
                                epRate,
                                "${report.outputBits}-bit PCM",
                            )
                        } else {
                            "—"
                        },
                    )
                } else {
                    Text(
                        stringResource(R.string.signal_internal_section),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    val session = report.internalSession
                    val route = session?.route ?: internalAvailability?.route ?: InternalRoute.UNKNOWN
                    val routeName = session?.routeName ?: internalAvailability?.routeName.orEmpty()
                    HealthRow(
                        stringResource(R.string.signal_internal_route),
                        routeLabel(route) + if (routeName.isNotBlank()) " • $routeName" else "",
                    )
                    HealthRow(
                        stringResource(R.string.signal_internal_status),
                        when {
                            report.internalDirectActive -> stringResource(R.string.internal_reason_active)
                            !internalEnabled -> stringResource(R.string.signal_internal_off)
                            internalAvailability != null -> stringResource(internalFailureDetail(internalAvailability.reason))
                            else -> "—"
                        },
                    )
                    val probe = internalAvailability?.probe
                    HealthRow(
                        stringResource(R.string.signal_internal_endpoint),
                        when {
                            session != null -> stringResource(
                                R.string.signal_internal_endpoint_value,
                                session.deviceRateHz,
                                InternalDirectStream.labelOf(session.deviceEncoding),
                            ) + when {
                                session.backend == com.lastwave.app.playback.InternalBackend.DIRECT_TRACK ->
                                    " • " + stringResource(R.string.signal_internal_backend_direct)
                                session.mmap -> " • MMAP"
                                else -> ""
                            }
                            probe != null && probe.usable -> stringResource(
                                R.string.signal_internal_endpoint_value,
                                probe.sampleRateHz,
                                InternalDirectStream.labelOf(probe.encoding),
                            ) + if (probe.mmap) " • MMAP" else ""
                            probe != null && internalAvailability != null ->
                                stringResource(internalFailureDetail(internalAvailability.reason))
                            else -> stringResource(R.string.signal_internal_not_checked)
                        },
                    )
                    if (internalUnderruns != null) {
                        HealthRow(stringResource(R.string.signal_internal_underruns), internalUnderruns.toString())
                    }
                }

                if (usbDiagnostic != null) {
                    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                    val copiedLabel = stringResource(R.string.signal_usb_diag_copied)
                    var copied by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(usbDiagnostic()))
                            copied = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (copied) copiedLabel else stringResource(R.string.signal_usb_diag_copy))
                    }
                }

                if (needsUsbPermission) {
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onRequestUsbAccess,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.signal_grant_usb))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
                }
            }
        }
    }
}

/** Verdict line: confirmation when perfect, otherwise the first failing check. */
@Composable
private fun verdictText(report: SignalPathReport): String {
    if (report.bitPerfect) return stringResource(R.string.signal_verdict_ok)
    if (report.directToHal) return stringResource(R.string.signal_verdict_direct_hal)
    val failing = report.checks.firstOrNull { !it.passed } ?: return ""
    return stringResource(failing.labelRes) +
        ": " +
        stringResource(failing.detailRes, *failing.detailArgs.toTypedArray())
}

@Composable
private fun VerdictPill(report: SignalPathReport) {
    val bg = when {
        report.bitPerfect -> MaterialTheme.colorScheme.primary
        report.directToHal -> MaterialTheme.colorScheme.tertiary
        report.clockFallbackResampled -> Color(0xFFE65100).copy(alpha = 0.25f)
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val fg = when {
        report.bitPerfect -> MaterialTheme.colorScheme.onPrimary
        report.directToHal -> MaterialTheme.colorScheme.onTertiary
        report.clockFallbackResampled -> Color(0xFFFFB74D)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val text = when {
        report.bitPerfect -> stringResource(R.string.signal_bit_perfect)
        report.directToHal -> stringResource(R.string.signal_direct_hal)
        report.clockFallbackResampled && !report.internalDirectActive -> stringResource(R.string.signal_resampled_dac)
        else -> stringResource(R.string.signal_check_path)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.ExtraBold,
            color = fg,
        )
    }
}

@Composable
private fun HealthRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun routeLabel(route: InternalRoute): String = stringResource(
    when (route) {
        InternalRoute.SPEAKER -> R.string.route_speaker
        InternalRoute.WIRED -> R.string.route_wired
        InternalRoute.BLUETOOTH -> R.string.route_bluetooth
        InternalRoute.USB -> R.string.route_usb
        InternalRoute.OTHER -> R.string.route_other
        InternalRoute.UNKNOWN -> R.string.route_unknown
    },
)

@Composable
private fun PipelineNode(
    iconContent: @Composable () -> Unit,
    title: String,
    badge: String? = null,
    isLast: Boolean = false,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(36.dp)
                .fillMaxHeight(),
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f)),
                contentAlignment = Alignment.Center,
            ) {
                iconContent()
            }
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .width(1.5.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                )
                Text(
                    "↓",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.8f),
                    lineHeight = 11.sp,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .width(1.5.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (isLast) 6.dp else 18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        textDecoration = TextDecoration.Underline,
                    ),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            badge,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontSize = 9.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

