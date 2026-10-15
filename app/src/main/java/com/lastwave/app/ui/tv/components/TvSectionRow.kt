package com.lastwave.app.ui.tv.components

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lastwave.app.ui.tv.focus.PositionFocusedItemInLazyLayout

@Composable
fun TvSectionRow(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
    content: LazyListScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 48.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        PositionFocusedItemInLazyLayout(
            parentFraction = 0.2f,
            childFraction = 0.0f,
        ) {
            LazyRow(
                contentPadding = contentPadding,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusGroup()
                    .focusRestorer(),
                content = content,
            )
        }
    }
}
