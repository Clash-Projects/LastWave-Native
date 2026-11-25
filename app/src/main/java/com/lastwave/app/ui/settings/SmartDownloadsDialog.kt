package com.lastwave.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.lastwave.app.R
import com.lastwave.app.data.download.smart.SmartDownloadsSettings
import kotlin.math.roundToInt

@Composable
internal fun SmartDownloadsDialog(
    settings: SmartDownloadsSettings,
    signedIn: Boolean,
    onEnabled: (Boolean) -> Unit,
    onSongLimit: (Int) -> Unit,
    onStorageLimit: (Int) -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var songLimit by remember(settings.songLimit) { mutableFloatStateOf(settings.songLimit.toFloat()) }
    var storageLimit by remember(settings.storageLimitGiB) { mutableFloatStateOf(settings.storageLimitGiB.toFloat()) }
    val storageMin = SmartDownloadsSettings.MIN_STORAGE_LIMIT_GIB
    val storageMax = SmartDownloadsSettings.MAX_STORAGE_LIMIT_GIB
    val min = SmartDownloadsSettings.MIN_SONG_LIMIT
    val max = SmartDownloadsSettings.MAX_SONG_LIMIT
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dl_smart_downloads)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(if (signedIn) R.string.dl_smart_description else R.string.dl_smart_sign_in),
                    style = MaterialTheme.typography.bodyMedium,
                )
                SmartDownloadSwitch(stringResource(R.string.dl_smart_enable), settings.enabled, onEnabled)
                if (settings.enabled) {
                    Text(stringResource(R.string.dl_smart_song_limit, songLimit.roundToInt()))
                    Slider(
                        value = songLimit,
                        onValueChange = { songLimit = it },
                        onValueChangeFinished = { onSongLimit(songLimit.roundToInt()) },
                        valueRange = min.toFloat()..max.toFloat(),
                        steps = (max - min) / 10 - 1,
                    )
                    Text(stringResource(R.string.dl_smart_storage_limit, storageLimit.roundToInt()))
                    Slider(
                        value = storageLimit,
                        onValueChange = { storageLimit = it },
                        onValueChangeFinished = { onStorageLimit(storageLimit.roundToInt()) },
                        valueRange = storageMin.toFloat()..storageMax.toFloat(),
                        steps = storageMax - storageMin - 1,
                    )
                    SmartDownloadSwitch(stringResource(R.string.dl_smart_wifi_only), settings.wifiOnly, onWifiOnly)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        },
    )
}

@Composable
private fun SmartDownloadSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}
