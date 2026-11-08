package com.lastwave.app.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.R
import com.lastwave.app.data.local.CacheStoragePolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private val cacheLimitOptions = listOf(128, 192, 256, 320, 512, 768, 1024, 1536, 2048)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(
    onBack: () -> Unit,
    onOpenDownloads: () -> Unit,
    viewModel: StorageViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showClearCacheDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteDownloadsDialog by rememberSaveable { mutableStateOf(false) }
    var pendingCacheLimit by remember { mutableStateOf<Int?>(null) }
    var sliderIndex by remember(state.cacheLimitMb) {
        mutableFloatStateOf(cacheLimitOptions.indexOf(state.cacheLimitMb).coerceAtLeast(0).toFloat())
    }
    val resetCacheLimitSelection = {
        pendingCacheLimit = null
        sliderIndex = cacheLimitOptions.indexOf(state.cacheLimitMb).coerceAtLeast(0).toFloat()
    }

    LaunchedEffect(viewModel) {
        viewModel.refreshCacheUsage()
        viewModel.events.collect { event -> snackbarHostState.showSnackbar(context.getString(event)) }
    }
    LaunchedEffect(viewModel) {
        while (isActive) {
            delay(1_500)
            viewModel.refreshCacheUsage()
        }
    }

    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text(stringResource(R.string.storage_clear_cache_title)) },
            text = { Text(stringResource(R.string.storage_clear_cache_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearCacheDialog = false
                    viewModel.clearCache()
                }) { Text(stringResource(R.string.storage_clear_cache)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (showDeleteDownloadsDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDownloadsDialog = false },
            title = { Text(stringResource(R.string.storage_delete_downloads_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.storage_delete_downloads_message,
                        state.downloadCount,
                        Formatter.formatShortFileSize(context, state.downloadBytes),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDownloadsDialog = false
                        viewModel.deleteDownloadedMusic()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.storage_delete_downloads)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDownloadsDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    pendingCacheLimit?.let { newLimit ->
        AlertDialog(
            onDismissRequest = resetCacheLimitSelection,
            title = { Text(stringResource(R.string.storage_limit_warning_title)) },
            text = { Text(stringResource(R.string.storage_limit_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingCacheLimit = null
                    viewModel.setCacheLimit(newLimit)
                }) { Text(stringResource(R.string.storage_limit_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = resetCacheLimitSelection) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_storage_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.storage_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .padding(bottom = 92.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Filled.Album, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.storage_cached_media), style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        text = stringResource(
                            R.string.storage_cache_usage,
                            Formatter.formatShortFileSize(context, state.cacheBytes),
                            Formatter.formatShortFileSize(
                                context,
                                state.cacheLimitMb * CacheStoragePolicy.MEGABYTE_BYTES,
                            ),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinearProgressIndicator(
                        progress = {
                            (
                                state.cacheBytes.toFloat() /
                                    (state.cacheLimitMb * CacheStoragePolicy.MEGABYTE_BYTES).toFloat()
                            ).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(R.string.storage_cache_limit), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(
                            R.string.storage_cache_limit_summary,
                            Formatter.formatShortFileSize(
                                context,
                                cacheLimitOptions[sliderIndex.toInt().coerceIn(cacheLimitOptions.indices)] * CacheStoragePolicy.MEGABYTE_BYTES,
                            ),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = sliderIndex,
                        onValueChange = { sliderIndex = it },
                        onValueChangeFinished = {
                            val newLimit = cacheLimitOptions[sliderIndex.toInt().coerceIn(cacheLimitOptions.indices)]
                            if (newLimit != state.cacheLimitMb) {
                                val newLimitBytes = newLimit.toLong() * CacheStoragePolicy.MEGABYTE_BYTES
                                if (newLimitBytes < state.cacheBytes) pendingCacheLimit = newLimit
                                else viewModel.setCacheLimit(newLimit)
                            }
                        },
                        steps = cacheLimitOptions.size - 2,
                        valueRange = 0f..(cacheLimitOptions.size - 1).toFloat(),
                    )
                    Text(
                        stringResource(R.string.storage_quality_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(onClick = { showClearCacheDialog = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = null)
                        Text(stringResource(R.string.storage_clear_cache), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Filled.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column {
                            Text(stringResource(R.string.storage_downloaded_music), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(
                                    R.string.storage_download_usage,
                                    state.downloadCount,
                                    Formatter.formatShortFileSize(context, state.downloadBytes),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Button(
                        onClick = { showDeleteDownloadsDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.downloadCount > 0,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = null)
                        Text(stringResource(R.string.storage_delete_downloads), modifier = Modifier.padding(start = 8.dp))
                    }
                    TextButton(onClick = onOpenDownloads, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null)
                        Text(stringResource(R.string.storage_manage_downloads), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}
