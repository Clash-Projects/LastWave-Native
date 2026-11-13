package com.lastwave.app.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.update.AppUpdateManager
import com.lastwave.app.data.update.UpdateInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

@HiltViewModel
class AppUpdateDialogViewModel @Inject constructor(
    val manager: AppUpdateManager,
) : ViewModel() {
    val info = manager.updateInfo
}

/**
 * The in-app update dialog, as on desktop: opens by itself when a new release
 * is found on launch, and from Settings' "Update Ready". Update now opens
 * the APK download in the browser, as before. Hosted once at the app root.
 */
@Composable
fun AppUpdateDialogHost(viewModel: AppUpdateDialogViewModel = hiltViewModel()) {
    val info by viewModel.info.collectAsStateWithLifecycle()
    if (!info.showDialog || !info.isUpdateAvailable) return
    val context = LocalContext.current
    val manager = viewModel.manager
    AppUpdateDialog(
        info = info,
        onUpdate = {
            manager.openDownload(context)
            manager.hideDialog()
        },
        onOpenGitHub = { manager.openReleasePage(context) },
        onClose = { skipThisVersion ->
            if (skipThisVersion) manager.dismissUpdate(info.latestVersion)
            manager.hideDialog()
        },
    )
}

@Composable
private fun AppUpdateDialog(
    info: UpdateInfo,
    onUpdate: () -> Unit,
    onOpenGitHub: () -> Unit,
    onClose: (skipThisVersion: Boolean) -> Unit,
) {
    var skipThisVersion by rememberSaveable { mutableStateOf(false) }
    Dialog(
        onDismissRequest = { onClose(skipThisVersion) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                // Phone width, or a comfortable dialog width on tablets.
                .widthIn(max = 560.dp),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Update arrived", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            buildString {
                                append("v${info.latestVersion}")
                                if (info.currentVersion.isNotBlank()) append("  •  you have v${info.currentVersion}")
                                formatReleaseDate(info.publishedAt)?.let { append("  •  $it") }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (info.releaseTitle.isNotBlank() && !info.releaseTitle.contains(info.latestVersion)) {
                    Text(info.releaseTitle, style = MaterialTheme.typography.titleSmall)
                }

                val linkColor = MaterialTheme.colorScheme.primary
                val notes = remember(info.releaseNotes, linkColor) {
                    releaseNotesText(info.releaseNotes.ifBlank { "No release notes were published for this version." }, linkColor)
                }
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        notes,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .heightIn(max = 340.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    )
                }

                info.downloadFileName?.let { name ->
                    Text(
                        if (info.downloadSizeBytes > 0) "$name  •  ${formatBytes(info.downloadSizeBytes)}" else name,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = skipThisVersion, onCheckedChange = { skipThisVersion = it })
                    Text("Don't show again for this version", style = MaterialTheme.typography.bodyMedium)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onOpenGitHub) { Text("GitHub") }
                    TextButton(onClick = { onClose(skipThisVersion) }) { Text("Remind me later") }
                    Button(onClick = onUpdate) { Text("Update now") }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatReleaseDate(isoInstant: String): String? = runCatching {
    Instant.parse(isoInstant)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
}.getOrNull()
