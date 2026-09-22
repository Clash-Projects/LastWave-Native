package com.lastwave.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.lastwave.app.ui.theme.isLiquidGlassEnabled
import com.lastwave.app.ui.theme.liquidGlassChrome
import com.lastwave.app.ui.theme.liquidGlassContainerColor

/**
 * Keeps content composition stable when Liquid Glass is toggled.
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

    val baseColor = if (tintColor.isSpecified) tintColor else MaterialTheme.colorScheme.surfaceContainer
    val glassInteraction = remember { MutableInteractionSource() }
    val clickModifier = if (onClick != null) {
        Modifier.clickable(
            interactionSource = glassInteraction,
            indication = null,
            role = Role.Button,
            onClick = onClick,
        )
    } else Modifier

    Card(
        modifier = modifier
            .liquidGlassChrome(shape, enabled, interactionSource = glassInteraction)
            .then(clickModifier),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = liquidGlassContainerColor(baseColor, enabled = enabled),
            contentColor = resolvedContentColor,
        ),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}
