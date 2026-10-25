package com.lastwave.app.ui.feed.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Visual verification previews for uniform card height, 16:9 wide aspect ratio,
 * small corner radius (4dp), 2-line fixed text container, and non-Latin text rendering
 * across square and wide card variants.
 */

@Composable
private fun PreviewCardArtworkPlaceholder(
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    label: String,
) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(CardTokens.coverCornerRadius))
            .background(Color(0xFF282828)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color(0xFFAAAAAA),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Preview(name = "1. Uniform Height Comparison (Square vs Wide)", showBackground = true, backgroundColor = 0xFF121212)
@Composable
fun CardUniformHeightComparisonPreview() {
    val coverHeight = 160.dp
    val squareWidth = coverHeight
    val wideWidth = coverHeight * (16f / 9f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(CardTokens.cardGap),
        verticalAlignment = Alignment.Top,
    ) {
        // Square Card
        Column(modifier = Modifier.width(squareWidth)) {
            PreviewCardArtworkPlaceholder(width = squareWidth, height = coverHeight, label = "Square (1:1)")
            Spacer(modifier = Modifier.height(CardTokens.coverToTextGap))
            CardTextArea(
                title = "Naal Naal Ve",
                infoLine = "Darshan Raval & Suvarna Tiwari • 4.9M",
            )
        }

        // Wide Card
        Column(modifier = Modifier.width(wideWidth)) {
            PreviewCardArtworkPlaceholder(width = wideWidth, height = coverHeight, label = "Wide (16:9)")
            Spacer(modifier = Modifier.height(CardTokens.coverToTextGap))
            CardTextArea(
                title = "The Everafter",
                infoLine = "EP • Darshan Raval",
            )
        }
    }
}

@Preview(name = "2. Long Title Ellipsis (1 Line)", showBackground = true, backgroundColor = 0xFF121212)
@Composable
fun CardLongTitleEllipsisPreview() {
    val coverHeight = 160.dp
    val squareWidth = coverHeight
    val wideWidth = coverHeight * (16f / 9f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(CardTokens.cardGap),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.width(squareWidth)) {
            PreviewCardArtworkPlaceholder(width = squareWidth, height = coverHeight, label = "Square")
            Spacer(modifier = Modifier.height(CardTokens.coverToTextGap))
            CardTextArea(
                title = "Extremely Long Song Title That Definitely Exceeds One Line And Must Ellipsize Cleanly",
                infoLine = "Very Long Artist Name Feat. Collaborator One & Collaborator Two • 120M views",
            )
        }

        Column(modifier = Modifier.width(wideWidth)) {
            PreviewCardArtworkPlaceholder(width = wideWidth, height = coverHeight, label = "Wide")
            Spacer(modifier = Modifier.height(CardTokens.coverToTextGap))
            CardTextArea(
                title = "Black Eyed Peas - Pump It [Extreme Bass Boosted Remix 2024 Extended Edition]",
                infoLine = "Manu!¡ Bass Music • 42M views • 3:45",
            )
        }
    }
}

@Preview(name = "3. Non-Latin Script Rendering (Hindi, Marathi, Tamil)", showBackground = true, backgroundColor = 0xFF121212)
@Composable
fun CardNonLatinScriptPreview() {
    val coverHeight = 160.dp
    val squareWidth = coverHeight

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(CardTokens.cardGap),
        verticalAlignment = Alignment.Top,
    ) {
        // Hindi
        Column(modifier = Modifier.width(squareWidth)) {
            PreviewCardArtworkPlaceholder(width = squareWidth, height = coverHeight, label = "Hindi")
            Spacer(modifier = Modifier.height(CardTokens.coverToTextGap))
            CardTextArea(
                title = "दिल संभल जा ज़रा - फिर मोहब्बत करने चला है",
                infoLine = "अरिजीत सिंह, मोहम्मद इरफ़ान • मर्डर 2",
            )
        }

        // Marathi
        Column(modifier = Modifier.width(squareWidth)) {
            PreviewCardArtworkPlaceholder(width = squareWidth, height = coverHeight, label = "Marathi")
            Spacer(modifier = Modifier.height(CardTokens.coverToTextGap))
            CardTextArea(
                title = "देवा श्री गणेशा - देवा तुझ्या चरणी",
                infoLine = "अजय-अतुल • अग्निपथ",
            )
        }

        // Tamil
        Column(modifier = Modifier.width(squareWidth)) {
            PreviewCardArtworkPlaceholder(width = squareWidth, height = coverHeight, label = "Tamil")
            Spacer(modifier = Modifier.height(CardTokens.coverToTextGap))
            CardTextArea(
                title = "காதல் ரோஜாவே எங்கே எங்கே கண்ணீர் வழியுதடி கண்ணே",
                infoLine = "எஸ். பி. பாலசுப்பிரமணியம் • ரோஜா",
            )
        }
    }
}

@Preview(name = "4. Missing Info Line Reserved Height", showBackground = true, backgroundColor = 0xFF121212)
@Composable
fun CardMissingInfoLinePreview() {
    val coverHeight = 160.dp
    val squareWidth = coverHeight

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(CardTokens.cardGap),
        verticalAlignment = Alignment.Top,
    ) {
        // Card with title and info line
        Column(modifier = Modifier.width(squareWidth)) {
            PreviewCardArtworkPlaceholder(width = squareWidth, height = coverHeight, label = "Full Info")
            Spacer(modifier = Modifier.height(CardTokens.coverToTextGap))
            CardTextArea(
                title = "Short Title",
                infoLine = "Artist • Album",
            )
        }

        // Card with null info line: fixed text container preserves exact height
        Column(modifier = Modifier.width(squareWidth)) {
            PreviewCardArtworkPlaceholder(width = squareWidth, height = coverHeight, label = "Missing Info")
            Spacer(modifier = Modifier.height(CardTokens.coverToTextGap))
            CardTextArea(
                title = "Short Title Only",
                infoLine = null,
            )
        }

        // Card with empty string info line: fixed text container preserves exact height
        Column(modifier = Modifier.width(squareWidth)) {
            PreviewCardArtworkPlaceholder(width = squareWidth, height = coverHeight, label = "Empty Info")
            Spacer(modifier = Modifier.height(CardTokens.coverToTextGap))
            CardTextArea(
                title = "Short Title Only",
                infoLine = "",
            )
        }
    }
}

@Preview(name = "5. Mixed Shelf Row Preview", showBackground = true, backgroundColor = 0xFF121212)
@Composable
fun CardMixedShelfRowPreview() {
    val coverHeight = 150.dp
    val squareWidth = coverHeight
    val wideWidth = coverHeight * (16f / 9f)

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFF1E1E1E),
        modifier = Modifier.padding(16.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = 16.dp)) {
            Text(
                text = "Darshan's Love Notes",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(CardTokens.cardGap),
            ) {
                item {
                    Column(modifier = Modifier.width(squareWidth)) {
                        PreviewCardArtworkPlaceholder(width = squareWidth, height = coverHeight, label = "Square 1")
                        Spacer(modifier = Modifier.height(CardTokens.coverToTextGap))
                        CardTextArea(title = "Desi Pop Party", infoLine = "Guru Randhawa, Diljit...")
                    }
                }
                item {
                    Column(modifier = Modifier.width(wideWidth)) {
                        PreviewCardArtworkPlaceholder(width = wideWidth, height = coverHeight, label = "Wide 1")
                        Spacer(modifier = Modifier.height(CardTokens.coverToTextGap))
                        CardTextArea(title = "Naal Naal Ve", infoLine = "Darshan Raval & Suvarna Tiwari • 4.9M")
                    }
                }
                item {
                    Column(modifier = Modifier.width(squareWidth)) {
                        PreviewCardArtworkPlaceholder(width = squareWidth, height = coverHeight, label = "Square 2")
                        Spacer(modifier = Modifier.height(CardTokens.coverToTextGap))
                        CardTextArea(title = "The Everafter", infoLine = "EP • Darshan Raval")
                    }
                }
            }
        }
    }
}
