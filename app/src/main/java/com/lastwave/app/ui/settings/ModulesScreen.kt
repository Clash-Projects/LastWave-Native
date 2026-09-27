package com.lastwave.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.R
import com.lastwave.app.data.plugin.InstalledProviderModule

import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lastwave.app.data.addon.AddonHealth

/**
 * Settings -> Provider Modules & Addons.
 * Configure external HTTP Addons or install .lwp packages, toggle, remove.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModulesScreen(
    onBack: () -> Unit = {},
    viewModel: ModulesViewModel = hiltViewModel(),
) {
    val modules by viewModel.modules.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val preferModules by viewModel.preferModules.collectAsStateWithLifecycle()
    val addonUrl by viewModel.addonUrl.collectAsStateWithLifecycle()
    val addonName by viewModel.addonName.collectAsStateWithLifecycle()
    val addonEnabled by viewModel.addonEnabled.collectAsStateWithLifecycle()
    val addonHealth by viewModel.addonHealth.collectAsStateWithLifecycle()

    var showAddonDialog by remember { mutableStateOf(false) }
    var addonInputText by remember { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    // "*/*": providers misreport .lwp as octet-stream; real validation
    // happens in ModuleManager.install after the file is read.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.install(uri)
    }

    LaunchedEffect(notice) {
        notice?.let {
            snackbar.showSnackbar(it)
            viewModel.clearNotice()
        }
    }

    if (showAddonDialog) {
        AlertDialog(
            onDismissRequest = { showAddonDialog = false },
            title = { Text(if (addonUrl.isNullOrBlank()) "Add Streaming Addon" else "Edit Streaming Addon") },
            text = {
                Column {
                    Text(
                        "Enter the URL of your personal Addon service (e.g. http://10.0.2.2:8787/a/<token>/ or custom host):",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = addonInputText,
                        onValueChange = { addonInputText = it },
                        label = { Text("Addon URL") },
                        placeholder = { Text("https://example.com/a/...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = { openAddonTelegramChannel(context, "clashprojects") },
                    ) {
                        Text("Don't have an addon? Join @clashprojects")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val toSave = addonInputText.trim()
                        if (toSave.isNotBlank()) {
                            viewModel.saveAddon(toSave)
                            showAddonDialog = false
                        }
                    },
                ) {
                    Text("Connect")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddonDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_modules_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // --- Section 1: HTTP Addon ---
            item {
                Text(
                    "Streaming Addon",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Take requests directly from a personal or community Addon service",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!addonUrl.isNullOrBlank()) {
                item {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        ),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Filled.Cloud,
                                    contentDescription = null,
                                    tint = if (addonHealth is AddonHealth.Unreachable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp),
                                )
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        addonName?.ifBlank { "HTTP Addon" } ?: "HTTP Addon",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    val masked = addonUrl?.let { url ->
                                        if (url.length > 36) url.take(24) + "..." + url.takeLast(8) else url
                                    }.orEmpty()
                                    Text(
                                        masked,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = addonEnabled,
                                    onCheckedChange = viewModel::setAddonEnabled,
                                )
                            }

                            Spacer(Modifier.height(8.dp))

                            val statusText = when (val h = addonHealth) {
                                is AddonHealth.Ok -> h.info ?: "Connected"
                                is AddonHealth.Unreachable -> "Unreachable: ${h.reason}"
                                is AddonHealth.Rejected -> "Rejected: ${h.reason}"
                                null -> if (addonEnabled) "Enabled" else "Disabled"
                            }
                            val statusColor = when (addonHealth) {
                                is AddonHealth.Ok -> MaterialTheme.colorScheme.primary
                                is AddonHealth.Unreachable, is AddonHealth.Rejected -> MaterialTheme.colorScheme.error
                                null -> MaterialTheme.colorScheme.onSurfaceVariant
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    statusText,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = statusColor,
                                    modifier = Modifier.weight(1f),
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { viewModel.testAddon() },
                                        enabled = !busy,
                                    ) {
                                        Icon(Icons.Filled.Refresh, contentDescription = "Test connection")
                                    }
                                    IconButton(
                                        onClick = {
                                            addonInputText = addonUrl.orEmpty()
                                            showAddonDialog = true
                                        },
                                    ) {
                                        Icon(Icons.Filled.Edit, contentDescription = "Edit Addon URL")
                                    }
                                    IconButton(
                                        onClick = { viewModel.removeAddon() },
                                    ) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Remove Addon")
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                item {
                    Card(
                        onClick = {
                            addonInputText = ""
                            showAddonDialog = true
                        },
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Cloud, contentDescription = null)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Add Streaming Addon",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    "Connect to your personal or custom Addon server URL",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }

            // --- Get addons: same TG channel as Support section ---
            item {
                Card(
                    onClick = { openAddonTelegramChannel(context, "clashprojects") },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Get Addons",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "Join @clashprojects on Telegram for addons",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            // --- Section 2: Local .lwp Packages ---
            item {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_modules_query_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            stringResource(R.string.settings_modules_query_sub),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = preferModules, onCheckedChange = viewModel::setPreferModules)
                }
            }

            item {
                Card(
                    onClick = { if (!busy) picker.launch(arrayOf("*/*")) },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (busy) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp))
                        } else {
                            Icon(Icons.Filled.Add, contentDescription = null)
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.settings_modules_add),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                stringResource(R.string.settings_modules_add_sub),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            if (modules.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.settings_modules_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
            items(modules, key = { it.manifest.id }) { module ->
                ModuleRow(
                    module = module,
                    onToggle = { viewModel.setEnabled(module.manifest.id, it) },
                    onRemove = { viewModel.remove(module.manifest.id) },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ModuleRow(
    module: InstalledProviderModule,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val manifest = module.manifest
    Card(
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Extension, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(manifest.name.ifBlank { manifest.id }, fontWeight = FontWeight.Bold)
                Text(
                    "${manifest.version.ifBlank { "?" }} • ${manifest.capabilities.take(4).joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = module.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Delete, contentDescription = null)
            }
        }
    }
}

private fun openAddonTelegramChannel(context: android.content.Context, handleOrUrl: String): Boolean {
    val username = handleOrUrl
        .removePrefix("https://t.me/")
        .removePrefix("http://t.me/")
        .removePrefix("@")
        .trim()
    val tgIntent = Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=$username")).apply {
        setPackage("org.telegram.messenger")
    }
    val genericTgIntent = Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=$username"))
    val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/$username"))
    return startAddonActivitySafely(context, tgIntent) ||
        startAddonActivitySafely(context, genericTgIntent) ||
        startAddonActivitySafely(context, webIntent)
}

private fun startAddonActivitySafely(context: android.content.Context, intent: Intent): Boolean {
    val safeIntent = Intent(intent).apply {
        if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(safeIntent)
        true
    } catch (_: Exception) {
        false
    } catch (_: LinkageError) {
        false
    }
}
