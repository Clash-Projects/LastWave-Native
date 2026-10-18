package com.lastwave.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lastwave.app.R
import com.lastwave.app.data.ytmusic.CookieNormalizer

@Composable
fun CookieLoginDialog(
    onDismiss: () -> Unit,
    onConnect: (String) -> Unit,
    isConnecting: Boolean = false,
) {
    var rawText by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    val clipboardManager = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = { if (!isConnecting) onDismiss() },
        shape = RoundedCornerShape(28.dp),
        icon = {
            Icon(
                Icons.Filled.Key,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
        },
        title = {
            Text(
                stringResource(R.string.settings_cookie_login),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.cookie_login_instructions),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = rawText,
                    onValueChange = {
                        rawText = it
                        if (errorText != null) errorText = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            stringResource(R.string.cookie_login_placeholder),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    },
                    minLines = 3,
                    maxLines = 6,
                    shape = RoundedCornerShape(12.dp),
                    isError = errorText != null,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = {
                            val clip = clipboardManager.getText()?.text
                            if (!clip.isNullOrBlank()) {
                                rawText = clip
                                errorText = null
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        enabled = !isConnecting,
                    ) {
                        Icon(
                            Icons.Filled.ContentPaste,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.cookie_login_paste_clipboard),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                if (errorText != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = errorText.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val text = rawText.trim()
                    if (text.isEmpty()) {
                        errorText = "Please enter cookie text."
                        return@Button
                    }
                    try {
                        val normalized = CookieNormalizer.normalize(text)
                        errorText = null
                        onConnect(normalized)
                    } catch (e: Exception) {
                        errorText = e.message ?: "Invalid cookies. Make sure SAPISID is present."
                    }
                },
                enabled = !isConnecting && rawText.isNotBlank(),
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.cookie_login_connecting))
                } else {
                    Text(stringResource(R.string.cookie_login_connect))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isConnecting,
            ) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
