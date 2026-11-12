package com.lastwave.app.ui.playlist

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lastwave.app.R
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.localmusic.LocalMusicLibrary
import com.lastwave.app.data.localmusic.LocalMusicLibrary.ImportState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Local songs: folder import controls shown at the top of the "Local songs" playlist. */
@HiltViewModel
class LocalSongsViewModel @Inject constructor(
    private val library: LocalMusicLibrary,
) : ViewModel() {
    val importState: StateFlow<ImportState> = library.state
    val folders: StateFlow<List<Uri>> = library.folders
    val trackFolders: StateFlow<Map<String, String>> = library.trackFolders

    init {
        if (folders.value.isNotEmpty() && (trackFolders.value.isEmpty() || trackFolders.value.keys.any { it.contains("/local_music/") })) {
            library.rescanAll()
        }
    }

    private val _storageBytes = MutableStateFlow(0L)
    val storageBytes: StateFlow<Long> = _storageBytes.asStateFlow()

    fun refreshStorage() {
        viewModelScope.launch(Dispatchers.IO) { _storageBytes.value = library.storageBytes() }
    }

    fun addFolder(uri: Uri) = library.addFolderAndImport(uri)
    fun rescan() = library.rescanAll()
    fun forget(uri: Uri) = library.forgetFolder(uri)
    fun acknowledge() = library.acknowledgeReport()
    fun label(uri: Uri): String = library.folderLabel(uri)

    fun tracksForFolder(folder: Uri, allTracks: List<GeneratedTrack>): List<GeneratedTrack> {
        val uriStr = folder.toString()
        val mapping = trackFolders.value
        return allTracks.filter { track ->
            val trackFolder = mapping[track.url]
            trackFolder == uriStr || (trackFolder == null && folders.value.size == 1 && folders.value.first() == folder)
        }
    }
}

@Composable
fun LocalSongsPanel(
    trackCount: Int,
    viewModel: LocalSongsViewModel = hiltViewModel(),
) {
    val state by viewModel.importState.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val storageBytes by viewModel.storageBytes.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.addFolder(uri)
    }
    val running = state is ImportState.Scanning || state is ImportState.Copying

    LaunchedEffect(state is ImportState.Finished, trackCount) { viewModel.refreshStorage() }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.local_panel_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.local_panel_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (folders.isEmpty()) {
                Text(
                    stringResource(R.string.local_no_folder),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                folders.forEach { folder ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            viewModel.label(folder),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { viewModel.forget(folder) }, enabled = !running) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.local_forget_folder),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { picker.launch(null) }, enabled = !running, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.local_choose_folder), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = viewModel::rescan,
                    enabled = !running && folders.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.local_rescan), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            when (val s = state) {
                is ImportState.Scanning -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.local_scanning, s.filesFound),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                is ImportState.Copying -> {
                    val fraction = if (s.total > 0) s.done.toFloat() / s.total else 0f
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.local_copying, s.done, s.total, s.currentName),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                is ImportState.Finished -> {
                    val r = s.report
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            when {
                                r.outOfSpace -> Text(
                                    stringResource(R.string.local_report_space),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                else -> Text(
                                    stringResource(R.string.local_report_summary, r.added, r.updated, r.unchanged),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (r.unsupported.isNotEmpty()) {
                                val total = r.unsupported.values.sum()
                                Text(
                                    pluralStringResource(
                                        R.plurals.local_report_unsupported,
                                        total,
                                        total,
                                        r.unsupported.keys.sorted().joinToString(", ") { ".$it" },
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (r.failed > 0) {
                                Text(
                                    stringResource(R.string.local_report_failed, r.failed),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            if (r.unreachableFolders > 0) {
                                Text(
                                    stringResource(R.string.local_report_unreachable, r.unreachableFolders),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        IconButton(onClick = viewModel::acknowledge) {
                            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                ImportState.Idle -> Unit
            }

            if (storageBytes > 0L) {
                Text(
                    stringResource(R.string.local_storage_used, formatMegabytes(storageBytes)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatMegabytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) "%.1f GB".format(mb / 1024.0) else "%.0f MB".format(mb)
}
