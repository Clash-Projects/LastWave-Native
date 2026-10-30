package com.lastwave.app.ui.jam

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Sync
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

    // High contrast adaptive theme colors: deep rich green in light mode, neon lime in dark mode
    val isDark = LocalIsDarkTheme.current
    val jamAccent = if (isDark) Color(0xFFC9FB00) else Color(0xFF2E6B12)
    val onJamAccent = if (isDark) Color(0xFF012226) else Color.White
    val jamAccentContainer = if (isDark) Color(0xFFC9FB00).copy(alpha = 0.16f) else Color(0xFF2E6B12).copy(alpha = 0.12f)
    val onJamAccentContainer = if (isDark) Color(0xFFC9FB00) else Color(0xFF2E6B12)
    val liveGreen = if (isDark) Color(0xFF22C55E) else Color(0xFF16A34A)

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var setupModeIndex by remember { mutableIntStateOf(0) } // 0 = Join, 1 = Create
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
                // Top Header with live status badge
                ExpressiveHeader(
                    title = "Jam",
                    subtitle = if (uiState.isConnected) "Room ${uiState.roomCode}" else "Listen together in real time",
                    onBack = onBack,
                    actions = {
                        if (uiState.isConnected) {
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
                                    .clip(CircleShape)
                                    .background(liveGreen.copy(alpha = 0.14f))
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

                // ==========================================
                // DISCONNECTED / SETUP VIEW (Simple & Minimal)
                // ==========================================
                if (!uiState.isConnected) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .imePadding()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        // Segmented Action Bar: Join Room vs Create Room
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(CircleShape)
                                .background(
                                    if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                )
                                .padding(4.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(CircleShape)
                                    .background(if (setupModeIndex == 0) jamAccent else Color.Transparent)
                                    .clickable { setupModeIndex = 0 }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "Join Room",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = if (setupModeIndex == 0) onJamAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(CircleShape)
                                    .background(if (setupModeIndex == 1) jamAccent else Color.Transparent)
                                    .clickable { setupModeIndex = 1 }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "Create Room",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = if (setupModeIndex == 1) onJamAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
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
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            ),
                        )

                        // Mode Specific Form
                        if (setupModeIndex == 0) {
                            // JOIN ROOM FORM
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedTextField(
                                    value = uiState.pendingInputCode,
                                    onValueChange = { viewModel.setPendingCode(it.take(8)) },
                                    label = { Text("Room Code") },
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

                            Button(
                                onClick = { viewModel.joinJamSession() },
                                enabled = !uiState.isConnecting && uiState.pendingInputCode.isNotBlank(),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = jamAccent,
                                    contentColor = onJamAccent,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp),
                            ) {
                                if (uiState.isConnecting) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = onJamAccent, strokeWidth = 2.5.dp)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text("Connecting...", fontWeight = FontWeight.Bold)
                                } else {
                                    Text("Join Jam Room", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                }
                            }
                        } else {
                            // CREATE ROOM FORM
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
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
                                    .height(50.dp),
                            ) {
                                if (uiState.isConnecting) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = onJamAccent, strokeWidth = 2.5.dp)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text("Creating...", fontWeight = FontWeight.Bold)
                                } else {
                                    Icon(Icons.Filled.Group, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Create Jam Room", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                }
                            }
                        }

                        // Error message banner
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

                        // Advanced Server URL Settings (Collapsed by default)
                        AnimatedVisibility(visible = showAdvancedServer) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                ),
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(text = "Sync Server Address", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    OutlinedTextField(
                                        value = customServerUrlInput,
                                        onValueChange = { customServerUrlInput = it },
                                        singleLine = true,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
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
                                            Text("Reset")
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    }
                } else {
                    // ==========================================
                    // ACTIVE JAM ROOM VIEW (Simple & Minimal)
                    // ==========================================
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Sleek Segmented Pill Tab Selector
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                )
                                .padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            listOf(
                                "Controls" to null,
                                "Chat" to if (chatMessages.isNotEmpty()) chatMessages.size else null,
                                "Share" to null,
                                "Panel" to uiState.participants.size,
                            ).forEachIndexed { index, (label, count) ->
                                val selected = selectedTabIndex == index
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(CircleShape)
                                        .background(if (selected) jamAccent else Color.Transparent)
                                        .clickable { selectedTabIndex = index }
                                        .padding(vertical = 7.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    val displayLabel = if (count != null && count > 0) "$label $count" else label
                                    Text(
                                        text = displayLabel,
                                        fontSize = 12.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (selected) onJamAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }

                        // ==========================================
                        // TAB 0: CONTROLS (Pure, Focused Player)
                        // ==========================================
                        if (selectedTabIndex == 0) {
                            val canControl = uiState.isHost || uiState.roomMode == JamProtocolConstants.MODE_COLLABORATIVE

                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 24.dp, vertical = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Spacer(modifier = Modifier.height(8.dp))

                                // Floating Artwork
                                Box(
                                    modifier = Modifier
                                        .size(190.dp)
                                        .clip(RoundedCornerShape(22.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    ArtworkImage(
                                        name = uiState.currentTrackTitle,
                                        artist = uiState.currentTrackArtist,
                                        embeddedUrl = uiState.currentArtworkUrl,
                                        fallbackIcon = Icons.Filled.MusicNote,
                                        modifier = Modifier.size(190.dp),
                                    )
                                }

                                Spacer(modifier = Modifier.height(20.dp))

                                // Song Title & Artist
                                Text(
                                    text = uiState.currentTrackTitle.ifBlank { "No track playing" },
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 19.sp,
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
                                    text = uiState.currentTrackArtist.ifBlank { "Jam timeline" },
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                )

                                Spacer(modifier = Modifier.height(20.dp))

                                // Minimal Timeline Seek Slider
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
                                        inactiveTrackColor = jamAccent.copy(alpha = 0.2f),
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

                                Spacer(modifier = Modifier.height(20.dp))

                                // Transport Row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    IconButton(
                                        onClick = { viewModel.skipPrevious() },
                                        enabled = canControl,
                                        modifier = Modifier.size(50.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.SkipPrevious,
                                            contentDescription = "Previous",
                                            tint = if (canControl) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                            modifier = Modifier.size(32.dp),
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(24.dp))

                                    Surface(
                                        modifier = Modifier
                                            .size(64.dp)
                                            .clip(CircleShape)
                                            .clickable(enabled = canControl) { viewModel.togglePlayback() },
                                        color = if (canControl) jamAccent else jamAccent.copy(alpha = 0.4f),
                                        shape = CircleShape,
                                        shadowElevation = 2.dp,
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

                                    Spacer(modifier = Modifier.width(24.dp))

                                    IconButton(
                                        onClick = { viewModel.skipNext() },
                                        enabled = canControl,
                                        modifier = Modifier.size(50.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.SkipNext,
                                            contentDescription = "Next",
                                            tint = if (canControl) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                            modifier = Modifier.size(32.dp),
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(24.dp))

                                // Mode Chip (Tap to toggle if host)
                                Surface(
                                    shape = CircleShape,
                                    color = jamAccentContainer,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .clickable(enabled = uiState.isHost) {
                                            val newMode = if (uiState.roomMode == JamProtocolConstants.MODE_COLLABORATIVE)
                                                JamProtocolConstants.MODE_HOST_ONLY else JamProtocolConstants.MODE_COLLABORATIVE
                                            viewModel.changeActiveRoomMode(newMode)
                                        },
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(7.dp)
                                                .clip(CircleShape)
                                                .background(liveGreen)
                                        )
                                        Text(
                                            text = if (uiState.roomMode == JamProtocolConstants.MODE_COLLABORATIVE)
                                                "Collaborative \u2022 Anyone can control"
                                            else
                                                "Host Only \u2022 Only host controls",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = onJamAccentContainer,
                                        )
                                    }
                                }
                            }
                        }

                        // ==========================================
                        // TAB 1: CHAT (Clean Message Stream)
                        // ==========================================
                        if (selectedTabIndex == 1) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .imePadding(),
                            ) {
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
                                                Text(
                                                    text = "No messages yet. Say hello!",
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    fontSize = 13.sp,
                                                )
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
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                ) {
                                                    Text(
                                                        text = if (isMe) "You" else msg.userName,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (msg.isHost) jamAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                    if (msg.isHost) {
                                                        Text(
                                                            text = "HOST",
                                                            fontSize = 9.sp,
                                                            fontWeight = FontWeight.ExtraBold,
                                                            color = jamAccent,
                                                        )
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
                                                ) {
                                                    Text(
                                                        text = msg.text,
                                                        fontSize = 14.sp,
                                                        color = if (isMe) onJamAccent else MaterialTheme.colorScheme.onSurface,
                                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // Minimalist floating pill chat input
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.surface,
                                    shadowElevation = 4.dp,
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        OutlinedTextField(
                                            value = chatInputText,
                                            onValueChange = { chatInputText = it },
                                            placeholder = { Text("Say something...", fontSize = 13.sp) },
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
                                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                                            ),
                                        )

                                        Surface(
                                            modifier = Modifier
                                                .size(44.dp)
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
                                                    contentDescription = "Send",
                                                    tint = onJamAccent,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // ==========================================
                        // TAB 2: SHARE (Minimalist Hero Code)
                        // ==========================================
                        if (selectedTabIndex == 2) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 24.dp, vertical = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(20.dp),
                            ) {
                                Spacer(modifier = Modifier.height(12.dp))

                                Text(
                                    text = "ROOM CODE",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    letterSpacing = 2.sp,
                                )

                                Text(
                                    text = uiState.roomCode,
                                    fontSize = 42.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace,
                                    color = jamAccent,
                                    letterSpacing = 4.sp,
                                    modifier = Modifier.clickable {
                                        clipboard.setText(AnnotatedString(uiState.roomCode))
                                        Toast.makeText(context, "Room code copied", Toast.LENGTH_SHORT).show()
                                    },
                                )

                                Text(
                                    text = "Friends can join with this code or invite link to listen synchronously.",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            clipboard.setText(AnnotatedString(uiState.roomCode))
                                            Toast.makeText(context, "Room code copied", Toast.LENGTH_SHORT).show()
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                    ) {
                                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Copy Code")
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
                                        Text("Share Invite")
                                    }
                                }
                            }
                        }

                        // ==========================================
                        // TAB 3: PANEL (Clean Listeners Roster)
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
                                        text = "Listeners (${uiState.participants.size})",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )

                                    if (uiState.isHost) {
                                        Text(
                                            text = "You are Host",
                                            fontSize = 12.sp,
                                            color = jamAccent,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }

                                LazyColumn(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    contentPadding = PaddingValues(bottom = 12.dp),
                                ) {
                                    items(uiState.participants, key = { it.userId }) { p ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(14.dp))
                                                .background(
                                                    if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                                )
                                                .padding(horizontal = 14.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Surface(
                                                modifier = Modifier.size(36.dp),
                                                shape = CircleShape,
                                                color = if (p.isHost) jamAccentContainer else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Text(
                                                        text = p.name.take(1).uppercase(),
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 14.sp,
                                                        color = if (p.isHost) onJamAccentContainer else MaterialTheme.colorScheme.primary,
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.width(12.dp))

                                            Text(
                                                text = p.name + if (p.name == uiState.pendingDisplayName) " (You)" else "",
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 14.sp,
                                                modifier = Modifier.weight(1f),
                                            )

                                            if (p.isHost) {
                                                Text(
                                                    text = "HOST",
                                                    color = jamAccent,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .background(jamAccentContainer)
                                                        .padding(horizontal = 6.dp, vertical = 3.dp),
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                OutlinedButton(
                                    onClick = { viewModel.leaveSession() },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error,
                                    ),
                                ) {
                                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(if (uiState.isHost) "End Jam Session" else "Leave Jam Session", fontWeight = FontWeight.Bold)
                                }

                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
