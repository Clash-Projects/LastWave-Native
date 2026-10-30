package com.lastwave.app.ui.jam

import android.content.Intent
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.jam.ChatMessagePayload
import com.lastwave.app.data.jam.JamProtocolConstants
import com.lastwave.app.data.jam.JamRepository
import com.lastwave.app.data.jam.ParticipantPayload
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.common.ExpressiveHeader
import com.lastwave.app.ui.common.HeaderActionIcon
import com.lastwave.app.ui.common.PredictiveBackScreen
import com.lastwave.app.ui.common.adaptiveContentWidth
import com.lastwave.app.ui.theme.LocalIsDarkTheme
import com.lastwave.app.ui.theme.LocalLiquidGlass
import com.lastwave.app.ui.theme.liquidGlassChrome

private fun formatMs(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format("%02d:%02d", min, sec)
}

@Composable
fun JamScreen(
    viewModel: LastWaveJamViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val chatMessages by viewModel.chatMessages.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    // Adaptive Theme Colors: High contrast in light mode, vibrant neon lime in dark mode
    val isDark = LocalIsDarkTheme.current
    val jamAccent = if (isDark) Color(0xFFC9FB00) else Color(0xFF2E6B12)
    val onJamAccent = if (isDark) Color(0xFF012226) else Color.White
    val jamAccentContainer = if (isDark) Color(0xFFC9FB00).copy(alpha = 0.18f) else Color(0xFF2E6B12).copy(alpha = 0.12f)
    val onJamAccentContainer = if (isDark) Color(0xFFC9FB00) else Color(0xFF2E6B12)
    val liveGreen = if (isDark) Color(0xFF22C55E) else Color(0xFF16A34A)

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var chatInputText by remember { mutableStateOf("") }
    var showAdvancedServer by remember { mutableStateOf(false) }
    var customServerUrlInput by remember(uiState.serverUrl) { mutableStateOf(uiState.serverUrl) }

    val chatListState = rememberLazyListState()

    LaunchedEffect(chatMessages.size) {
        if (chatMessages.isNotEmpty()) {
            chatListState.animateScrollToItem(chatMessages.size - 1)
        }
    }

    PredictiveBackScreen(onBack = onBack) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .navigationBarsPadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .adaptiveContentWidth(maxWidth = 860.dp),
            ) {
                // Top Expressive Header with Liquid Glass styling
                ExpressiveHeader(
                    title = "Jam",
                    subtitle = if (uiState.isConnected) "Room ${uiState.roomCode} · Live Sync" else "Listen together in real time",
                    onBack = onBack,
                    actions = {
                        if (uiState.isConnected) {
                            // Pulsing live sync badge
                            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                            val pulseAlpha by infiniteTransition.animateFloat(
                                initialValue = 0.4f,
                                targetValue = 1f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(1000, easing = LinearEasing),
                                    repeatMode = RepeatMode.Reverse,
                                ),
                                label = "pulseAlpha",
                            )
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(liveGreen.copy(alpha = 0.15f))
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .alpha(pulseAlpha)
                                        .clip(CircleShape)
                                        .background(liveGreen),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "LIVE",
                                    color = liveGreen,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        } else {
                            HeaderActionIcon(
                                icon = Icons.Filled.Dns,
                                contentDescription = "Server settings",
                                onClick = { showAdvancedServer = !showAdvancedServer },
                            )
                        }
                    },
                )

                // Main Content
                if (!uiState.isConnected) {
                    // DISCONNECTED / SETUP VIEW
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .imePadding()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        // Hero Intro Card
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .liquidGlassChrome(shape = RoundedCornerShape(20.dp)),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            ),
                        ) {
                            Row(
                                modifier = Modifier.padding(20.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    modifier = Modifier.size(52.dp),
                                    shape = CircleShape,
                                    color = jamAccentContainer,
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Filled.Sync,
                                            contentDescription = null,
                                            tint = jamAccent,
                                            modifier = Modifier.size(28.dp),
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    Text(
                                        text = "Synchronized Listening",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Stream tracks with friends anywhere with sub-second synchronization and live queue controls.",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        lineHeight = 18.sp,
                                    )
                                }
                            }
                        }

                        // Display Name Input
                        OutlinedTextField(
                            value = uiState.pendingDisplayName,
                            onValueChange = viewModel::setPendingDisplayName,
                            label = { Text("Your Display Name") },
                            leadingIcon = {
                                Icon(Icons.Filled.AccountCircle, contentDescription = null, tint = jamAccent)
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = jamAccent,
                                focusedLabelColor = jamAccent,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                            ),
                        )

                        // Mode Selector / Action Cards
                        Text(
                            text = "Get Started",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        // Create Room Section
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                            ),
                        ) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Text(
                                    text = "Start a New Session",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Host a room and invite others to join your timeline.",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.height(14.dp))

                                // Room Mode Selection
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.6f))
                                        .padding(4.dp),
                                ) {
                                    val isCollab = uiState.roomMode == JamProtocolConstants.MODE_COLLABORATIVE
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (isCollab) jamAccent else Color.Transparent)
                                            .clickable { viewModel.setPendingRoomMode(JamProtocolConstants.MODE_COLLABORATIVE) }
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = "Collaborative",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = if (isCollab) onJamAccent else MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (!isCollab) jamAccent else Color.Transparent)
                                            .clickable { viewModel.setPendingRoomMode(JamProtocolConstants.MODE_HOST_ONLY) }
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = "Host Only",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = if (!isCollab) onJamAccent else MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                Button(
                                    onClick = { viewModel.startJamSession() },
                                    enabled = !uiState.isConnecting,
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = jamAccent,
                                        contentColor = onJamAccent,
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp),
                                ) {
                                    if (uiState.isConnecting) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = onJamAccent, strokeWidth = 2.5.dp)
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text("Connecting...", fontWeight = FontWeight.Bold)
                                    } else {
                                        Icon(Icons.Filled.Group, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Create Jam Room", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        // Join with Room Code Section
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                            ),
                        ) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Text(
                                    text = "Join with Room Code",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Enter a 6-character room code shared by your friend.",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.height(14.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    OutlinedTextField(
                                        value = uiState.pendingInputCode,
                                        onValueChange = { viewModel.setPendingCode(it.take(8)) },
                                        placeholder = { Text("e.g. WAVE01") },
                                        singleLine = true,
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.weight(1f),
                                        keyboardOptions = KeyboardOptions(
                                            capitalization = KeyboardCapitalization.Characters,
                                            imeAction = ImeAction.Done,
                                        ),
                                        keyboardActions = KeyboardActions(
                                            onDone = { viewModel.joinJamSession() }
                                        ),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = jamAccent,
                                            focusedLabelColor = jamAccent,
                                        ),
                                    )

                                    OutlinedButton(
                                        onClick = {
                                            val clip = clipboard.getText()?.text?.trim()
                                            if (!clip.isNullOrBlank()) {
                                                val cleanCode = clip.substringAfter("code=").take(8).uppercase()
                                                viewModel.setPendingCode(cleanCode)
                                            }
                                        },
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.height(54.dp),
                                    ) {
                                        Text("Paste")
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                Button(
                                    onClick = { viewModel.joinJamSession() },
                                    enabled = !uiState.isConnecting && uiState.pendingInputCode.isNotBlank(),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp),
                                ) {
                                    Text("Join Jam Room", fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // Error message if any
                        uiState.errorMessage?.let { error ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            ) {
                                Text(
                                    text = error,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(14.dp),
                                    fontSize = 13.sp,
                                )
                            }
                        }

                        // Advanced Server URL Settings
                        AnimatedVisibility(visible = showAdvancedServer) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                ),
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = "Sync Server Address",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Custom WebSocket server endpoint for private Jam deployments.",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    OutlinedTextField(
                                        value = customServerUrlInput,
                                        onValueChange = { customServerUrlInput = it },
                                        singleLine = true,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = {
                                                viewModel.setCustomServerUrl(customServerUrlInput)
                                                Toast.makeText(context, "Server URL updated", Toast.LENGTH_SHORT).show()
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = jamAccent,
                                                contentColor = onJamAccent,
                                            ),
                                        ) {
                                            Text("Save")
                                        }
                                        TextButton(
                                            onClick = {
                                                viewModel.resetServerUrl()
                                                customServerUrlInput = JamRepository.DEFAULT_JAM_SERVER_WS
                                            },
                                        ) {
                                            Text("Reset to Default")
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                    }
                } else {
                    // ACTIVE JAM ROOM VIEW
                    // Clean, modular 4-tab interface:
                    // Tab 0: Controls (Normal jam playback & queue)
                    // Tab 1: Chat (In-room live chat with keyboard-adaptive input)
                    // Tab 2: Share (Room code & invite link sharing)
                    // Tab 3: Panel (Listeners list & room permissions)
                    Column(modifier = Modifier.fillMaxSize()) {
                        TabRow(
                            selectedTabIndex = selectedTabIndex,
                            containerColor = Color.Transparent,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            indicator = { tabPositions ->
                                TabRowDefaults.SecondaryIndicator(
                                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                                    color = jamAccent,
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 2.dp),
                        ) {
                            Tab(
                                selected = selectedTabIndex == 0,
                                onClick = { selectedTabIndex = 0 },
                                text = { Text("Controls", fontWeight = FontWeight.Bold, fontSize = 12.sp) },
                                icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                selectedContentColor = jamAccent,
                                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Tab(
                                selected = selectedTabIndex == 1,
                                onClick = { selectedTabIndex = 1 },
                                text = {
                                    Text(
                                        text = if (chatMessages.isNotEmpty()) "Chat (${chatMessages.size})" else "Chat",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                    )
                                },
                                icon = { Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                selectedContentColor = jamAccent,
                                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Tab(
                                selected = selectedTabIndex == 2,
                                onClick = { selectedTabIndex = 2 },
                                text = { Text("Share", fontWeight = FontWeight.Bold, fontSize = 12.sp) },
                                icon = { Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                selectedContentColor = jamAccent,
                                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Tab(
                                selected = selectedTabIndex == 3,
                                onClick = { selectedTabIndex = 3 },
                                text = {
                                    Text(
                                        text = "Panel (${uiState.participants.size})",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                    )
                                },
                                icon = { Icon(Icons.Filled.Group, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                selectedContentColor = jamAccent,
                                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        // ==========================================
                        // TAB 0: NORMAL JAM PLAYBACK CONTROLS
                        // ==========================================
                        if (selectedTabIndex == 0) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 20.dp, vertical = 14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                val canControl = uiState.isHost || uiState.roomMode == JamProtocolConstants.MODE_COLLABORATIVE

                                // Large Album Art & Track Info Card
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .liquidGlassChrome(shape = RoundedCornerShape(24.dp)),
                                    shape = RoundedCornerShape(24.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                                    ),
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(20.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        // Artwork
                                        Box(
                                            modifier = Modifier
                                                .size(170.dp)
                                                .clip(RoundedCornerShape(20.dp)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            ArtworkImage(
                                                name = uiState.currentTrackTitle,
                                                artist = uiState.currentTrackArtist,
                                                embeddedUrl = uiState.currentArtworkUrl,
                                                fallbackIcon = Icons.Filled.MusicNote,
                                                modifier = Modifier.size(170.dp),
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(18.dp))

                                        // Title & Artist
                                        Text(
                                            text = uiState.currentTrackTitle.ifBlank { "No song currently playing" },
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 18.sp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .basicMarquee(),
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = uiState.currentTrackArtist.ifBlank { "Timeline is idle" },
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center,
                                        )

                                        Spacer(modifier = Modifier.height(18.dp))

                                        // Interactive Timeline Seek Slider
                                        var isDraggingSlider by remember { mutableStateOf(false) }
                                        var sliderPosition by remember { mutableFloatStateOf(0f) }

                                        LaunchedEffect(uiState.currentPositionMs) {
                                            if (!isDraggingSlider) {
                                                sliderPosition = uiState.currentPositionMs.toFloat()
                                            }
                                        }

                                        val maxDuration = uiState.durationMs.coerceAtLeast(1L).toFloat()
                                        Slider(
                                            value = sliderPosition.coerceIn(0f, maxDuration),
                                            onValueChange = {
                                                isDraggingSlider = true
                                                sliderPosition = it
                                            },
                                            onValueChangeFinished = {
                                                isDraggingSlider = false
                                                viewModel.seekTo(sliderPosition.toLong())
                                            },
                                            valueRange = 0f..maxDuration,
                                            enabled = canControl && uiState.durationMs > 0L,
                                            colors = SliderDefaults.colors(
                                                thumbColor = jamAccent,
                                                activeTrackColor = jamAccent,
                                                inactiveTrackColor = jamAccent.copy(alpha = 0.22f),
                                            ),
                                            modifier = Modifier.fillMaxWidth(),
                                        )

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 4.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Text(
                                                text = formatMs(sliderPosition.toLong()),
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontFamily = FontFamily.Monospace,
                                            )
                                            Text(
                                                text = formatMs(uiState.durationMs),
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontFamily = FontFamily.Monospace,
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(16.dp))

                                        // Big Transport Controls Row
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            // Previous Track Button
                                            IconButton(
                                                onClick = { viewModel.skipPrevious() },
                                                enabled = canControl,
                                                modifier = Modifier.size(52.dp),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.SkipPrevious,
                                                    contentDescription = "Previous Track",
                                                    tint = if (canControl) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                                                    modifier = Modifier.size(32.dp),
                                                )
                                            }

                                            Spacer(modifier = Modifier.width(20.dp))

                                            // Play / Pause Primary Button
                                            Surface(
                                                modifier = Modifier
                                                    .size(66.dp)
                                                    .clip(CircleShape)
                                                    .clickable(enabled = canControl) { viewModel.togglePlayback() },
                                                color = if (canControl) jamAccent else jamAccent.copy(alpha = 0.4f),
                                                shape = CircleShape,
                                                shadowElevation = 4.dp,
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(
                                                        imageVector = if (uiState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                                        contentDescription = if (uiState.isPlaying) "Pause" else "Play",
                                                        tint = onJamAccent,
                                                        modifier = Modifier.size(34.dp),
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.width(20.dp))

                                            // Next Track Button
                                            IconButton(
                                                onClick = { viewModel.skipNext() },
                                                enabled = canControl,
                                                modifier = Modifier.size(52.dp),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.SkipNext,
                                                    contentDescription = "Next Track",
                                                    tint = if (canControl) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                                                    modifier = Modifier.size(32.dp),
                                                )
                                            }
                                        }

                                        if (!canControl) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                text = "Host-only session · Playback is controlled by room host",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                textAlign = TextAlign.Center,
                                            )
                                        }
                                    }
                                }

                                // Status & Permissions Info Card
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(18.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                    ),
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column {
                                                Text(
                                                    text = "Room Control Mode",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 14.sp,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                )
                                                Text(
                                                    text = if (uiState.roomMode == JamProtocolConstants.MODE_COLLABORATIVE)
                                                        "Collaborative · Anyone can control playback"
                                                    else
                                                        "Host Only · Only host controls playback",
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }

                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = jamAccentContainer,
                                            ) {
                                                Text(
                                                    text = if (uiState.isHost) "HOST" else "GUEST",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = onJamAccentContainer,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                )
                                            }
                                        }

                                        if (uiState.isHost) {
                                            Spacer(modifier = Modifier.height(14.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(
                                                    text = "Allow guests to change songs",
                                                    fontSize = 13.sp,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                )
                                                Switch(
                                                    checked = uiState.roomMode == JamProtocolConstants.MODE_COLLABORATIVE,
                                                    onCheckedChange = { isChecked ->
                                                        val newMode = if (isChecked) JamProtocolConstants.MODE_COLLABORATIVE else JamProtocolConstants.MODE_HOST_ONLY
                                                        viewModel.changeActiveRoomMode(newMode)
                                                    },
                                                    colors = SwitchDefaults.colors(
                                                        checkedThumbColor = jamAccent,
                                                        checkedTrackColor = jamAccentContainer,
                                                    ),
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }

                        // ==========================================
                        // TAB 1: IN-ROOM LIVE CHAT
                        // ==========================================
                        if (selectedTabIndex == 1) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .imePadding(),
                            ) {
                                // Live chat message history filling available height
                                LazyColumn(
                                    state = chatListState,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    contentPadding = PaddingValues(vertical = 12.dp),
                                ) {
                                    if (chatMessages.isEmpty()) {
                                        item {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = 48.dp),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Send,
                                                        contentDescription = null,
                                                        tint = jamAccent.copy(alpha = 0.5f),
                                                        modifier = Modifier.size(36.dp),
                                                    )
                                                    Spacer(modifier = Modifier.height(8.dp))
                                                    Text(
                                                        text = "No messages yet. Say hello to everyone in the room!",
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        fontSize = 13.sp,
                                                        textAlign = TextAlign.Center,
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        items(chatMessages, key = { it.id.ifEmpty { it.timestamp.toString() + it.userId } }) { msg ->
                                            val isMe = msg.userName == uiState.pendingDisplayName
                                            Column(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalAlignment = if (isMe) Alignment.End else Alignment.Start,
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                ) {
                                                    Text(
                                                        text = if (isMe) "You" else msg.userName,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (msg.isHost) jamAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                    if (msg.isHost) {
                                                        Surface(
                                                            shape = RoundedCornerShape(4.dp),
                                                            color = jamAccentContainer,
                                                        ) {
                                                            Text(
                                                                text = "HOST",
                                                                fontSize = 9.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = onJamAccentContainer,
                                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                            )
                                                        }
                                                    }
                                                }
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(
                                                        topStart = 14.dp,
                                                        topEnd = 14.dp,
                                                        bottomStart = if (isMe) 14.dp else 2.dp,
                                                        bottomEnd = if (isMe) 2.dp else 14.dp,
                                                    ),
                                                    color = if (isMe) jamAccent else MaterialTheme.colorScheme.surfaceVariant,
                                                    shadowElevation = 1.dp,
                                                ) {
                                                    Text(
                                                        text = msg.text,
                                                        fontSize = 14.sp,
                                                        color = if (isMe) onJamAccent else MaterialTheme.colorScheme.onSurface,
                                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // Typing area properly padded and shifting above keyboard
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.surface,
                                    shadowElevation = 6.dp,
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        OutlinedTextField(
                                            value = chatInputText,
                                            onValueChange = { chatInputText = it },
                                            placeholder = { Text("Type a message...", fontSize = 13.sp) },
                                            singleLine = true,
                                            shape = RoundedCornerShape(24.dp),
                                            modifier = Modifier.weight(1f),
                                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                            keyboardActions = KeyboardActions(
                                                onSend = {
                                                    if (chatInputText.isNotBlank()) {
                                                        viewModel.sendChat(chatInputText)
                                                        chatInputText = ""
                                                    }
                                                }
                                            ),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = jamAccent,
                                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                            ),
                                        )

                                        Surface(
                                            modifier = Modifier
                                                .size(46.dp)
                                                .clip(CircleShape)
                                                .clickable {
                                                    if (chatInputText.isNotBlank()) {
                                                        viewModel.sendChat(chatInputText)
                                                        chatInputText = ""
                                                    }
                                                },
                                            shape = CircleShape,
                                            color = jamAccent,
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Filled.Send,
                                                    contentDescription = "Send Message",
                                                    tint = onJamAccent,
                                                    modifier = Modifier.size(20.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // ==========================================
                        // TAB 2: ROOM CODE & SHARE
                        // ==========================================
                        if (selectedTabIndex == 2) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 20.dp, vertical = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                // Prominent Room Code Card
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .liquidGlassChrome(shape = RoundedCornerShape(24.dp)),
                                    shape = RoundedCornerShape(24.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                                    ),
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Text(
                                            text = "ROOM CODE",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = jamAccent,
                                            letterSpacing = 2.sp,
                                        )

                                        Spacer(modifier = Modifier.height(6.dp))

                                        Text(
                                            text = uiState.roomCode,
                                            fontSize = 38.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontFamily = FontFamily.Monospace,
                                            color = jamAccent,
                                            letterSpacing = 4.sp,
                                        )

                                        Spacer(modifier = Modifier.height(18.dp))

                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            OutlinedButton(
                                                onClick = {
                                                    clipboard.setText(AnnotatedString(uiState.roomCode))
                                                    Toast.makeText(context, "Room code copied to clipboard", Toast.LENGTH_SHORT).show()
                                                },
                                                shape = RoundedCornerShape(12.dp),
                                                colors = ButtonDefaults.outlinedButtonColors(
                                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                                ),
                                            ) {
                                                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Copy Code", fontWeight = FontWeight.Bold)
                                            }

                                            Button(
                                                onClick = { viewModel.shareInvite(uiState.roomCode) },
                                                shape = RoundedCornerShape(12.dp),
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = jamAccent,
                                                    contentColor = onJamAccent,
                                                ),
                                            ) {
                                                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Share Invite", fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }

                                // How to Join Instructions Card
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(20.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                    ),
                                ) {
                                    Column(modifier = Modifier.padding(18.dp)) {
                                        Text(
                                            text = "How to invite friends",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "1. Tap 'Share Invite' to send a direct link to any app.\n" +
                                                "2. Or share the 6-character room code above.\n" +
                                                "3. Friends can enter the code in LastWave to listen together with zero latency.",
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            lineHeight = 20.sp,
                                        )
                                    }
                                }

                                // Server Connection Info
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(18.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    ),
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = "Sync Relay",
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 13.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Text(
                                                text = "Connected",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                color = liveGreen,
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = uiState.serverUrl,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                            fontFamily = FontFamily.Monospace,
                                        )
                                    }
                                }
                            }
                        }

                        // ==========================================
                        // TAB 3: LISTENERS & SESSION PANEL
                        // ==========================================
                        if (selectedTabIndex == 3) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "Room Listeners (${uiState.participants.size})",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )

                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = jamAccentContainer,
                                    ) {
                                        Text(
                                            text = if (uiState.isHost) "You are Host" else "Listener Mode",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = onJamAccentContainer,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                LazyColumn(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    contentPadding = PaddingValues(bottom = 12.dp),
                                ) {
                                    items(uiState.participants, key = { it.userId }) { p ->
                                        Card(
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(16.dp),
                                            colors = CardDefaults.cardColors(
                                                containerColor = if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                                            ),
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Surface(
                                                    modifier = Modifier.size(42.dp),
                                                    shape = CircleShape,
                                                    color = if (p.isHost) jamAccentContainer else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        Text(
                                                            text = p.name.take(1).uppercase(),
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 16.sp,
                                                            color = if (p.isHost) onJamAccentContainer else MaterialTheme.colorScheme.primary,
                                                        )
                                                    }
                                                }

                                                Spacer(modifier = Modifier.width(14.dp))

                                                Column(modifier = Modifier.weight(1f)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                        Text(
                                                            text = p.name,
                                                            fontWeight = FontWeight.SemiBold,
                                                            fontSize = 15.sp,
                                                            color = MaterialTheme.colorScheme.onSurface,
                                                        )
                                                        if (p.name == uiState.pendingDisplayName) {
                                                            Text(
                                                                text = "(You)",
                                                                fontSize = 12.sp,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            )
                                                        }
                                                    }
                                                    Text(
                                                        text = if (p.isHost) "Room Host · Controls playback" else "Listener · Synced",
                                                        fontSize = 12.sp,
                                                        color = if (p.isHost) jamAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }

                                                if (p.isHost) {
                                                    Surface(
                                                        shape = RoundedCornerShape(6.dp),
                                                        color = jamAccentContainer,
                                                    ) {
                                                        Text(
                                                            text = "HOST",
                                                            color = onJamAccentContainer,
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.ExtraBold,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // Leave / End Jam Button
                                Button(
                                    onClick = { viewModel.leaveSession() },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                    ),
                                ) {
                                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (uiState.isHost) "Leave / End Jam Session" else "Leave Jam Session",
                                        fontWeight = FontWeight.Bold,
                                    )
                                }

                                Spacer(modifier = Modifier.height(6.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
