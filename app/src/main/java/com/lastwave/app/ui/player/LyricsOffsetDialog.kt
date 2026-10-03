package com.lastwave.app.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lastwave.app.R
import kotlin.math.roundToLong

@Composable
fun LyricsOffsetDialog(
    currentMs: Long,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var draftMs by remember(currentMs) { mutableLongStateOf(currentMs) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lyrics sync offset") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Nudges the highlight when a provider's timestamps run early or late. + shows lyrics early (fixes late lyrics), \u2212 delays them. Seekbar is unaffected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (draftMs == 0L) "0 ms (off)" else "${if (draftMs > 0) "+" else ""}$draftMs ms",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Slider(
                    value = draftMs.toFloat().coerceIn(-5000f, 5000f),
                    onValueChange = {
                        val rounded = it.roundToLong()
                        draftMs = rounded
                        onSelect(rounded)
                    },
                    valueRange = -5000f..5000f,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = {
                            draftMs = (draftMs - 1000).coerceIn(-10000L, 10000L)
                            onSelect(draftMs)
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
                    ) {
                        Text(
                            "−1s",
                            maxLines = 1,
                            softWrap = false,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            draftMs = (draftMs - 250).coerceIn(-10000L, 10000L)
                            onSelect(draftMs)
                        },
                        modifier = Modifier.weight(1.1f),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
                    ) {
                        Text(
                            "−250ms",
                            maxLines = 1,
                            softWrap = false,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            draftMs = (draftMs + 250).coerceIn(-10000L, 10000L)
                            onSelect(draftMs)
                        },
                        modifier = Modifier.weight(1.1f),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
                    ) {
                        Text(
                            "+250ms",
                            maxLines = 1,
                            softWrap = false,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            draftMs = (draftMs + 1000).coerceIn(-10000L, 10000L)
                            onSelect(draftMs)
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
                    ) {
                        Text(
                            "+1s",
                            maxLines = 1,
                            softWrap = false,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    FilledTonalButton(
                        onClick = {
                            draftMs = 0L
                            onSelect(0L)
                        },
                        modifier = Modifier.weight(1.2f),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.RestartAlt,
                            contentDescription = "Reset",
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            "Reset",
                            maxLines = 1,
                            softWrap = false,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
        },
    )
}
