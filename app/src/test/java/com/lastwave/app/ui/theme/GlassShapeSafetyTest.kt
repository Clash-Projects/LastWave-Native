package com.lastwave.app.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GlassShapeSafetyTest {

    @Test
    fun rectangleShape_mapsToCornerBasedShape() {
        val safe = lensSafeGlassShape(RectangleShape)

        assertThat(safe).isInstanceOf(CornerBasedShape::class.java)
        assertThat(safe).isEqualTo(RoundedCornerShape(0.dp))
    }

    @Test
    fun supportedShapes_passThroughUnchanged() {
        val circle: Shape = CircleShape
        val rounded: Shape = RoundedCornerShape(24.dp)

        assertThat(lensSafeGlassShape(circle)).isSameInstanceAs(circle)
        assertThat(lensSafeGlassShape(rounded)).isSameInstanceAs(rounded)
    }
}
