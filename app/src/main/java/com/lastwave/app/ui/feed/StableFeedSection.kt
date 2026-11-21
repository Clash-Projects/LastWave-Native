package com.lastwave.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.lastwave.app.R

@Composable
internal fun StableFeedSection(
    unavailable: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    var heightPx by rememberSaveable(configuration.screenWidthDp, density.density, density.fontScale) {
        mutableIntStateOf(0)
    }
    Box(
        modifier.fillMaxWidth().onSizeChanged { size ->
            if (size.height > 0 && (!unavailable || heightPx == 0)) heightPx = size.height
        },
    ) {
        if (!unavailable) {
            content()
        } else {
            if (heightPx == 0) {
                Box(Modifier.alpha(0f).clearAndSetSemantics { }) { content() }
            } else {
                Spacer(Modifier.height(with(density) { heightPx.toDp() }))
            }
            Box(
                modifier = Modifier.matchParentSize().padding(horizontal = 16.dp).padding(bottom = 3.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .pointerInput(Unit) { detectTapGestures { } },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.feed_section_not_in_refresh),
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
