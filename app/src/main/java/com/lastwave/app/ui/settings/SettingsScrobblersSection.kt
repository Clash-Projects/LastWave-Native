package com.lastwave.app.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lastwave.app.R
import com.lastwave.app.data.scrobbler.ExtraService
import com.lastwave.app.data.scrobbler.ScrobblerAccount
import com.lastwave.app.ui.common.ExpressiveLoadingIndicator
import com.lastwave.app.ui.common.ExpressiveMotion
import com.lastwave.app.ui.theme.ExpressivePillShape

@Composable
internal fun ScrobblerIntegrationCard(
    service: ExtraService,
    account: ScrobblerAccount,
    connection: ScrobblerConnectionState,
    onConnect: (String, String, String) -> Unit,
    onDisconnect: () -> Unit,
    isHighlighted: Boolean = false,
    onOpenTokenPage: () -> Unit = {},
) {
    val usesToken = service == ExtraService.LISTENBRAINZ
    var user by rememberSaveable(service) { mutableStateOf("") }
    var secret by remember(service) { mutableStateOf("") }
    var server by rememberSaveable(service) { mutableStateOf("") }
    var showDisconnectConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(account.connected) {
        if (account.connected) secret = ""
    }

    Card(
        shape = CardOuterShape,
        colors = CardDefaults.cardColors(
            containerColor = if (isHighlighted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth().animateContentSize(ExpressiveMotion.spatialDefault())
            .settingHighlightGlow(isHighlighted = isHighlighted, shape = CardOuterShape),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Album,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(service.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (account.connected) stringResource(R.string.set_scrobbler_connected, account.user)
                        else stringResource(R.string.set_scrobbler_not_connected),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (account.connected && account.server.isNotBlank()) {
                        Text(
                            account.server,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (account.connected && !connection.busy) {
                    FilledTonalIconButton(onClick = { showDisconnectConfirm = true }) {
                        Icon(Icons.Filled.Logout, stringResource(R.string.common_disconnect))
                    }
                }
            }

            if (connection.busy) {
                ExpressiveLoadingIndicator(message = stringResource(R.string.set_scrobbler_connecting, service.displayName))
            } else if (!account.connected) {
                Text(
                    stringResource(if (usesToken) R.string.set_scrobbler_lb_desc else R.string.set_scrobbler_lf_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!usesToken) {
                    OutlinedTextField(
                        value = user,
                        onValueChange = { user = it },
                        label = { Text(stringResource(R.string.set_scrobbler_username)) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text(stringResource(if (usesToken) R.string.set_scrobbler_token else R.string.set_scrobbler_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = { Text(stringResource(R.string.set_scrobbler_server)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { onConnect(user, secret, server) },
                    enabled = secret.isNotBlank() && (usesToken || user.isNotBlank()),
                    shape = ExpressivePillShape,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.set_scrobbler_connect, service.displayName)) }
                if (usesToken) {
                    TextButton(onClick = onOpenTokenPage) { Text(stringResource(R.string.set_scrobbler_get_token)) }
                }
            }
            connection.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirm = false },
            title = { Text(stringResource(R.string.set_scrobbler_disconnect_title, service.displayName)) },
            text = { Text(stringResource(R.string.set_scrobbler_disconnect_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showDisconnectConfirm = false
                    onDisconnect()
                }) { Text(stringResource(R.string.common_disconnect)) }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}
