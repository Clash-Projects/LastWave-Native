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
import androidx.compose.foundation.background
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import com.lastwave.app.ui.theme.LocalLiquidGlass
import com.lastwave.app.ui.theme.liquidGlassChrome

private val LimeAccent = Color(0xFFC9FB00)
private val DarkTeal = Color(0xFF012226)

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
                .background(MaterialTheme.colorScheme.background),
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
                    subtitle = if (uiState.isConnected) "Room ${uiState.roomCode}" else "Listen together in real time",
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
                                    .background(Color(0xFF22C55E).copy(alpha = 0.15f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .alpha(pulseAlpha)
                                        .clip(CircleShape)
                                        .background(Color(0xFF22C55E)),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "LIVE",
                                    color = Color(0xFF22C55E),
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
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        // Hero Intro Card
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .liquidGlassChrome(shape = RoundedCornerShape(20.dp)),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                        ) {
                            Row(
                                modifier = Modifier.padding(20.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    modifier = Modifier.size(52.dp),
                                    shape = CircleShape,
                                    color = LimeAccent.copy(alpha = 0.18f),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Filled.Sync,
                                            contentDescription = null,
                                            tint = LimeAccent,
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
                                Icon(Icons.Filled.AccountCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = LimeAccent,
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
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                        ) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Text(
                                    text = "Start a New Session",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
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
                                            .background(if (isCollab) LimeAccent else Color.Transparent)
                                            .clickable { viewModel.setPendingRoomMode(JamProtocolConstants.MODE_COLLABORATIVE) }
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = "Collaborative",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = if (isCollab) DarkTeal else MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (!isCollab) LimeAccent else Color.Transparent)
                                            .clickable { viewModel.setPendingRoomMode(JamProtocolConstants.MODE_HOST_ONLY) }
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = "Host Only",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = if (!isCollab) DarkTeal else MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                Button(
                                    onClick = { viewModel.startJamSession() },
                                    enabled = !uiState.isConnecting,
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = LimeAccent,
                                        contentColor = DarkTeal,
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp),
                                ) {
                                    if (uiState.isConnecting) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = DarkTeal, strokeWidth = 2.5.dp)
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
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                        ) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Text(
                                    text = "Join with Room Code",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
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
                                            focusedBorderColor = LimeAccent,
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
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
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
                    }
                } else {
                    // ACTIVE JAM ROOM VIEW
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Room Overview Card
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 8.dp)
                                .liquidGlassChrome(shape = RoundedCornerShape(20.dp)),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                        ) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column {
                                        Text(
                                            text = "ROOM CODE",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            letterSpacing = 1.sp,
                                        )
                                        Text(
                                            text = uiState.roomCode,
                                            fontSize = 28.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontFamily = FontFamily.Monospace,
                                            color = LimeAccent,
                                            letterSpacing = 2.sp,
                                        )
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        IconButton(
                                            onClick = {
                                                clipboard.setText(AnnotatedString(uiState.roomCode))
                                                Toast.makeText(context, "Room code copied", Toast.LENGTH_SHORT).show()
                                            },
                                        ) {
                                            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy code", tint = MaterialTheme.colorScheme.onSurface)
                                        }
                                        IconButton(
                                            onClick = { viewModel.shareInvite(uiState.roomCode) },
                                        ) {
                                            Icon(Icons.Filled.Share, contentDescription = "Share invite", tint = MaterialTheme.colorScheme.onSurface)
                                        }
                                        IconButton(
                                            onClick = { viewModel.leaveSession() },
                                        ) {
                                            Icon(Icons.Filled.Close, contentDescription = "Leave", tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                // Badges
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // Host vs Listener badge
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (uiState.isHost) LimeAccent.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
                                    ) {
                                        Text(
                                            text = if (uiState.isHost) "Room Host" else "Listener",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (uiState.isHost) LimeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        )
                                    }

                                    // Mode badge
                                    val isCollab = uiState.roomMode == JamProtocolConstants.MODE_COLLABORATIVE
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                    ) {
                                        Text(
                                            text = if (isCollab) "Collaborative Controls" else "Host Controlled",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        )
                                    }
                                }

                                // Mode Toggle for Host
                                if (uiState.isHost) {
                                    Spacer(modifier = Modifier.height(14.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "Allow listeners to change songs",
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
                                                checkedThumbColor = LimeAccent,
                                                checkedTrackColor = LimeAccent.copy(alpha = 0.4f),
                                            ),
                                        )
                                    }
                                }
                            }
                        }

                        // Now Playing in Jam Card
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    ArtworkImage(
                                        name = uiState.currentTrackTitle,
                                        artist = uiState.currentTrackArtist,
                                        embeddedUrl = uiState.currentArtworkUrl,
                                        fallbackIcon = Icons.Filled.MusicNote,
                                        modifier = Modifier
                                            .size(56.dp)
                                            .clip(RoundedCornerShape(12.dp)),
                                    )

                                    Spacer(modifier = Modifier.width(14.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = uiState.currentTrackTitle.ifBlank { "No song playing" },
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = uiState.currentTrackArtist.ifBlank { "Queue is idle" },
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }

                                    // Transport Controls
                                    val canControl = uiState.isHost || uiState.roomMode == JamProtocolConstants.MODE_COLLABORATIVE
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = { viewModel.togglePlayback() },
                                            enabled = canControl,
                                        ) {
                                            Icon(
                                                imageVector = if (uiState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                                contentDescription = if (uiState.isPlaying) "Pause" else "Play",
                                                tint = if (canControl) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                                modifier = Modifier.size(28.dp),
                                            )
                                        }
                                        IconButton(
                                            onClick = { viewModel.skipNext() },
                                            enabled = canControl,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.SkipNext,
                                                contentDescription = "Next",
                                                tint = if (canControl) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                                            )
                                        }
                                    }
                                }

                                if (uiState.durationMs > 0L) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    val progressFraction = (uiState.currentPositionMs.toFloat() / uiState.durationMs.toFloat()).coerceIn(0f, 1f)
                                    LinearProgressIndicator(
                                        progress = { progressFraction },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(4.dp)
                                            .clip(RoundedCornerShape(2.dp)),
                                        color = LimeAccent,
                                        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(text = formatMs(uiState.currentPositionMs), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(text = formatMs(uiState.durationMs), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }

                        // Navigation Tabs: Participants / Chat
                        TabRow(
                            selectedTabIndex = selectedTabIndex,
                            containerColor = Color.Transparent,
                            contentColor = MaterialTheme.colorScheme.primary,
                            indicator = { tabPositions ->
                                TabRowDefaults.SecondaryIndicator(
                                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                                    color = LimeAccent,
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 6.dp),
                        ) {
                            Tab(
                                selected = selectedTabIndex == 0,
                                onClick = { selectedTabIndex = 0 },
                                text = { Text("Listeners (${uiState.participants.size})", fontWeight = FontWeight.Bold) },
                            )
                            Tab(
                                selected = selectedTabIndex == 1,
                                onClick = { selectedTabIndex = 1 },
                                text = { Text("Chat (${chatMessages.size})", fontWeight = FontWeight.Bold) },
                            )
                        }

                        // Tab 0: Participants List
                        if (selectedTabIndex == 0) {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .padding(horizontal = 20.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(vertical = 12.dp),
                            ) {
                                items(uiState.participants, key = { it.userId }) { p ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(14.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Surface(
                                                modifier = Modifier.size(38.dp),
                                                shape = CircleShape,
                                                color = if (p.isHost) LimeAccent.copy(alpha = 0.2f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Text(
                                                        text = p.name.take(1).uppercase(),
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (p.isHost) LimeAccent else MaterialTheme.colorScheme.primary,
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.width(12.dp))

                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = p.name,
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 14.sp,
                                                )
                                                if (p.isHost) {
                                                    Text(
                                                        text = "Host",
                                                        fontSize = 11.sp,
                                                        color = LimeAccent,
                                                        fontWeight = FontWeight.Bold,
                                                    )
                                                }
                                            }

                                            if (p.isHost) {
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = LimeAccent.copy(alpha = 0.15f),
                                                ) {
                                                    Text(
                                                        text = "HOST",
                                                        color = LimeAccent,
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
                        } else {
                            // Tab 1: In-Room Live Chat
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .padding(horizontal = 20.dp),
                            ) {
                                LazyColumn(
                                    state = chatListState,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    contentPadding = PaddingValues(vertical = 12.dp),
                                ) {
                                    if (chatMessages.isEmpty()) {
                                        item {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = 40.dp),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Text(
                                                    text = "No messages yet. Say hello to the room!",
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
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                ) {
                                                    Text(
                                                        text = if (isMe) "You" else msg.userName,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (msg.isHost) LimeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                    if (msg.isHost) {
                                                        Surface(
                                                            shape = RoundedCornerShape(4.dp),
                                                            color = LimeAccent.copy(alpha = 0.2f),
                                                        ) {
                                                            Text(
                                                                text = "HOST",
                                                                fontSize = 9.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = LimeAccent,
                                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                            )
                                                        }
                                                    }
                                                }
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(12.dp),
                                                    color = if (isMe) LimeAccent else MaterialTheme.colorScheme.surfaceVariant,
                                                ) {
                                                    Text(
                                                        text = msg.text,
                                                        fontSize = 13.sp,
                                                        color = if (isMe) DarkTeal else MaterialTheme.colorScheme.onSurface,
                                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // Chat Input Field
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    OutlinedTextField(
                                        value = chatInputText,
                                        onValueChange = { chatInputText = it },
                                        placeholder = { Text("Type a message...") },
                                        singleLine = true,
                                        shape = RoundedCornerShape(20.dp),
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
                                    )
                                    IconButton(
                                        onClick = {
                                            if (chatInputText.isNotBlank()) {
                                                viewModel.sendChat(chatInputText)
                                                chatInputText = ""
                                            }
                                        },
                                    ) {
                                        Icon(Icons.Filled.Send, contentDescription = "Send", tint = LimeAccent)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
