package com.lastwave.desktop.ui.theme

import com.lastwave.desktop.util.DependencyInjectionKt
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

object LastWaveDesktopTheme {
    @Composable
    fun Theme(
        darkTheme: Boolean = true,
        content: @Composable () -> Unit
    ) {
        val colorScheme = if (darkTheme) {
            darkColorScheme(
                primary = Color(0xFFFFBB86FC), // Pink/purple accent
                secondary = Color(0xFF03DAC6), // Cyan accent
                surface = Color(0xFF1E1E2F),
                background = Color(0xFF0D0D17),
                onPrimary = Color(0xFF000000),
                onSurface = Color(0xFFFFFFFF),
                onBackground = Color(0xFFFFFFFF)
            )
        } else {
            lightColorScheme(
                primary = Color(0xFF03DAC6),
                secondary = Color(0xFFFFBB86FC),
                surface = Color(0xFFFFFFFF),
                background = Color(0xFFE8EAED),
                onPrimary = Color(0xFF000000),
                onSurface = Color(0xFF000000),
                onBackground = Color(0xFF000000)
            )
        }

        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = Shapes
        ) {
            content()
        }
    }

    val typography = Typography(
        h1 = TextStyle(
            fontSize = 32.sp,
            fontWeight = androidx.compose.ui.font.FontWeight.ExtraBold,
            color = Color(0xFFFFFFFF)
        ),
        h2 = TextStyle(
            fontSize = 24.sp,
            fontWeight = androidx.compose.ui.font.FontWeight.ExtraBold,
            color = Color(0xFFFFFFFF)
        ),
        h3 = TextStyle(
            fontSize = 20.sp,
            fontWeight = androidx.compose.ui.font.FontWeight.ExtraBold,
            color = Color(0xFFFFFFFF)
        ),
        h4 = TextStyle(
            fontSize = 18.sp,
            fontWeight = androidx.compose.ui.font.FontWeight.Bold,
            color = Color(0xFFFFFFFF)
        ),
        h5 = TextStyle(
            fontSize = 16.sp,
            fontWeight = androidx.compose.ui.font.FontWeight.Bold,
            color = Color(0xFFFFFFFF)
        ),
        h6 = TextStyle(
            fontSize = 14.sp,
            fontWeight = androidx.compose.ui.font.FontWeight.SemiBold,
            color = Color(0xFFFFFFFF)
        ),
        body1 = TextStyle(
            fontSize = 12.sp,
            fontWeight = androidx.compose.ui.font.FontWeight.Normal,
            color = Color(0xFFB0B0C4)
        ),
        body2 = TextStyle(
            fontSize = 11.sp,
            fontWeight = androidx.compose.ui.font.FontWeight.Normal,
            color = Color(0xFF7F8C8D)
        ),
        caption = TextStyle(
            fontSize = 9.sp,
            fontWeight = androidx.compose.ui.font.FontWeight.Normal,
            color = Color(0xFF7F8C8D)
        )
    )

    data class Shapes(
        val small = RoundedCornerShape(4.dp),
        val medium = RoundedCornerShape(8.dp),
        val large = RoundedCornerShape(16.dp),
        val extraLarge = RoundedCornerShape(24.dp)
    )
}