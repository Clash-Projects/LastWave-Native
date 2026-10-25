package com.lastwave.app.ui.feed.cards

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.lastwave.app.data.music.YouTubeMusicTrack

/** Actual card composables, with preview-only sample text and deterministic missing-art fallbacks. */
@Composable
private fun PreviewFrame(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface { Box(Modifier.padding(16.dp)) { content() } }
    }
}

private val previewSongs = listOf(
    YouTubeMusicTrack("preview1", "Ad Kolima", "Demeter & ISHNLV"),
    YouTubeMusicTrack("preview2", "Smash", "Nebezao"),
    YouTubeMusicTrack("preview3", "Minimum (Umut Dogan Remix)", "Ямаджи & Фейджи"),
)

@Composable
private fun CommunityExample(title: String = "cool", loading: Boolean = false, failed: Boolean = false, localized: Boolean = false) {
    PreviewFrame {
        ShelfCommunityPlaylistCard(
            title = title, subtitle = "Rashmi Mishra • 155K views • 25 songs",
            collageUrls = emptyList(), artworkUrl = null,
            songs = if (localized) listOf(
                YouTubeMusicTrack("p1", "नाथ भगव्या कफनी वाला", "श्री देव मामलेदार ब्रास बँड"),
                YouTubeMusicTrack("p2", "कैसे हो तुम", "अग्नि"),
                YouTubeMusicTrack("p3", "मन उधाण वाऱ्याचे", "शंकर महादेवन"),
            ) else previewSongs,
            onClick = {}, onPlayClick = {}, onSongClick = {}, onSongMenuClick = {},
            cardWidth = 330.dp, isLoading = loading, previewFailed = failed,
            songPlayCounts = mapOf("preview1" to "96M plays", "preview2" to "191M plays", "preview3" to "1.7M plays"),
        )
    }
}

@Preview(name = "Community • normal", widthDp = 362)
@Composable private fun CommunityNormal() = CommunityExample()

@Preview(name = "Community • loading", widthDp = 362)
@Composable private fun CommunityLoading() = CommunityExample(loading = true)

@Preview(name = "Community • failed preview", widthDp = 362)
@Composable private fun CommunityFailed() = CommunityExample(failed = true)

@Preview(name = "Community • long title", widthDp = 362, fontScale = 1.3f)
@Composable private fun CommunityLong() = CommunityExample(title = "Late night discoveries and songs for the long way home")

@Preview(name = "Community • Hindi and Marathi", widthDp = 362)
@Composable private fun CommunityIndic() = CommunityExample(title = "आपली आवडती गाणी", localized = true)

@Preview(name = "Community • RTL", widthDp = 362)
@Composable private fun CommunityRtl() {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { CommunityExample() }
}

@Composable
private fun DailyExample(title: String = "no more regrets", artist: String = "almost monday • 2.8M plays", wide: Boolean = false) {
    PreviewFrame {
        ShelfFeaturedCard(title = title, artist = artist, artworkUrl = null, caption = null,
            onClick = {}, onMenuClick = {}, cardWidth = if (wide) 420.dp else 330.dp, isVisible = false)
    }
}

@Preview(name = "Daily • normal / no caption", widthDp = 362)
@Composable private fun DailyNormal() = DailyExample()

@Preview(name = "Daily • loading or failed artwork", widthDp = 362)
@Composable private fun DailyMissingArtwork() = DailyExample("Kaise Ho Tum", "Agnee • 15M plays")

@Preview(name = "Daily • long title", widthDp = 362, fontScale = 1.3f)
@Composable private fun DailyLong() = DailyExample("A very long song title that must stay on a single line", "An equally long artist name • 2.8M plays")

@Preview(name = "Daily • Hindi and Marathi", widthDp = 362)
@Composable private fun DailyIndic() = DailyExample("मन उधाण वाऱ्याचे", "शंकर महादेवन")

@Preview(name = "Daily • tablet and landscape clamp", widthDp = 452)
@Composable private fun DailyTablet() = DailyExample(wide = true)
