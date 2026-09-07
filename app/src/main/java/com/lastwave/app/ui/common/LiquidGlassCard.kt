package com.lastwave.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.lastwave.app.ui.theme.isLiquidGlassEnabled
import com.lastwave.app.ui.theme.liquidGlassChrome
import com.lastwave.app.ui.theme.liquidGlassContainerColor

/**
 * Shared Kyant0 glass card. Effects sample the background, never the foreground.
 */
@Composable
fun LiquidGlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    enabled: Boolean = isLiquidGlassEnabled(),
    tintColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val resolvedContentColor = if (contentColor.isSpecified) {
        contentColor
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    if (!enabled) {
        val baseModifier = if (onClick != null) {
            modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
        } else {
            modifier
        }
        Card(
            modifier = baseModifier,
            shape = shape,
            colors = CardDefaults.cardColors(
                containerColor = if (tintColor.isSpecified) tintColor else MaterialTheme.colorScheme.surfaceContainer,
                contentColor = resolvedContentColor,
            ),
        ) {
            Column(Modifier.padding(16.dp), content = content)
        }
        return
    }

    val glassTint = (if (tintColor.isSpecified) tintColor else MaterialTheme.colorScheme.surfaceContainerHigh)
        .let { it.copy(alpha = minOf(it.alpha, 0.72f)) }
    val clickModifier = if (onClick != null) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            role = Role.Button,
            onClick = onClick,
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .liquidGlassChrome(shape, enabled = true)
            .clip(shape)
            .background(liquidGlassContainerColor(glassTint), shape)
            .then(clickModifier)
            .padding(16.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        CompositionLocalProvider(LocalContentColor provides resolvedContentColor) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}
