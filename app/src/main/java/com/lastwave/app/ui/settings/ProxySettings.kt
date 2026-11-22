package com.lastwave.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lastwave.app.R
import com.lastwave.app.data.network.NetworkProxy

/** Settings row + dialog for the app-wide [NetworkProxy]. */
@Composable
internal fun NetworkProxySection() {
    var current by remember { mutableStateOf(NetworkProxy.url) }
    var editing by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(stringResource(R.string.settings_section_network))
        SettingsGroup(rowCount = 1) { _, position ->
            SettingsActionCard(
                icon = Icons.Filled.Public,
                iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                title = stringResource(R.string.settings_proxy_title),
                // The URL may carry a SOCKS password; show only where it points.
                subtitle = current?.substringAfterLast('@') ?: stringResource(R.string.settings_proxy_off),
                onClick = { editing = true },
                position = position,
            )
        }
    }
    if (editing) {
        var text by remember { mutableStateOf(current.orEmpty()) }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.settings_proxy_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.settings_proxy_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it; error = null },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("socks5://host:1080") },
                        isError = error != null,
                        supportingText = error?.let { { Text(it) } },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    NetworkProxy.set(text)
                        .onSuccess { current = NetworkProxy.url; editing = false }
                        .onFailure { error = it.message }
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}
