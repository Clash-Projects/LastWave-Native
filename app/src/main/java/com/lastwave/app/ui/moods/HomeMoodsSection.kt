package com.lastwave.app.ui.moods

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.R

@Composable
internal fun HomeMoodsSection(viewModel: MoodsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val loadingDescription = stringResource(R.string.moods_loading)
    val tiles = remember(state.groups) {
        state.groups.flatMap { it.tiles }.distinctBy { it.key }.take(12)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.moods_title),
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp),
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            IconButton(onClick = viewModel::openAll) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.moods_open_all),
                )
            }
        }
        when {
            state.isLoading -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.CenterHorizontally)
                    .semantics { contentDescription = loadingDescription },
            )
            tiles.isNotEmpty() -> MoodMosaic(
                tiles = tiles,
                onTileClick = viewModel::openTile,
                pastelGlass = true,
            )
            else -> MoodsLoadMessage(failed = state.failed, onRetry = viewModel::refresh)
        }
    }
}

@Composable
internal fun MoodsLoadMessage(failed: Boolean, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(if (failed) R.string.moods_load_failed else R.string.moods_empty),
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = onRetry) { Text(stringResource(R.string.moods_retry)) }
    }
}
