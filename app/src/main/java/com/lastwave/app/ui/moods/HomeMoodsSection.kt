package com.lastwave.app.ui.moods

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.R
import com.lastwave.app.ui.feed.FeedSectionHeader

@Composable
internal fun HomeMoodsSection(
    onLongClick: () -> Unit,
    viewModel: MoodsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val loadingDescription = stringResource(R.string.moods_loading)
    val tiles = remember(state.groups) {
        state.groups.flatMap { it.tiles }.distinctBy { it.key }.take(12)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FeedSectionHeader(
            title = stringResource(R.string.moods_title),
            onActionClick = viewModel::openAll,
            onLongClick = onLongClick,
        )
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
