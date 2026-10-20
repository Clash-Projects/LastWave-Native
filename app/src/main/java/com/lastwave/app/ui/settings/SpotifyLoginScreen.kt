package com.lastwave.app.ui.settings

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.ui.common.ExpressiveHeader
import com.lastwave.app.ui.common.adaptiveContentWidth

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyLoginScreen(
    onBack: () -> Unit,
    onConnected: () -> Unit,
    viewModel: SpotifyLoginViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    var isLoading by remember { mutableStateOf(true) }
    var showManualDialog by remember { mutableStateOf(false) }
    var manualSpDcInput by remember { mutableStateOf("") }
    var captured by remember { mutableStateOf(false) }

    val statusRegex = remember {
        Regex("""^https://accounts\.spotify\.com/(?:[^/]+/)?status.*""")
    }

    fun captureSpDc(url: String?) {
        if (captured) return
        val currentUrl = url.orEmpty()
        val isTarget = statusRegex.matches(currentUrl) ||
            currentUrl.startsWith("https://open.spotify.com") ||
            currentUrl.contains("accounts.spotify.com")

        if (isTarget) {
            val cm = CookieManager.getInstance()
            val cookieStr = cm.getCookie(currentUrl) ?: cm.getCookie("https://open.spotify.com")
            val spdc = extractSpDc(cookieStr)
            if (!spdc.isNullOrBlank()) {
                captured = true
                viewModel.saveSpdc(spdc) {
                    Toast.makeText(context, "Spotify Canvas connected!", Toast.LENGTH_SHORT).show()
                    onConnected()
                }
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .adaptiveContentWidth(maxWidth = 760.dp),
        ) {
            ExpressiveHeader(
                title = "Connect Spotify",
                subtitle = "Sign in to unlock Spotify Canvas video loops",
                onBack = onBack,
                actions = {
                    FilledTonalIconButton(
                        onClick = { showManualDialog = true },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = "Manual sp_dc cookie entry",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )

            AnimatedVisibility(
                visible = isLoading,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.userAgentString =
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

                            val cm = CookieManager.getInstance()
                            cm.setAcceptCookie(true)
                            cm.setAcceptThirdPartyCookies(this, true)

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    isLoading = true
                                    captureSpDc(url)
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    isLoading = false
                                    captureSpDc(url)
                                }

                                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                    super.doUpdateVisitedHistory(view, url, isReload)
                                    captureSpDc(url)
                                }
                            }

                            loadUrl(SPOTIFY_LOGIN_URL)
                        }
                    },
                )
            }
        }

        if (showManualDialog) {
            AlertDialog(
                onDismissRequest = { showManualDialog = false },
                title = { Text("Manual sp_dc Entry") },
                text = {
                    Column {
                        Text(
                            "Paste your Spotify 'sp_dc' cookie value from your web browser developer tools:",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = manualSpDcInput,
                            onValueChange = { manualSpDcInput = it },
                            label = { Text("sp_dc cookie") },
                            placeholder = { Text("AQB...") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (manualSpDcInput.isNotBlank()) {
                                viewModel.saveSpdc(manualSpDcInput) {
                                    Toast.makeText(context, "Spotify connected!", Toast.LENGTH_SHORT).show()
                                    showManualDialog = false
                                    onConnected()
                                }
                            }
                        },
                    ) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showManualDialog = false }) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}

private fun extractSpDc(cookieHeader: String?): String? {
    if (cookieHeader.isNullOrBlank()) return null
    val pairs = cookieHeader.split(";")
    for (pair in pairs) {
        val parts = pair.trim().split("=", limit = 2)
        if (parts.size == 2 && parts[0].trim() == "sp_dc") {
            return parts[1].trim()
        }
    }
    return null
}

private const val SPOTIFY_LOGIN_URL = "https://accounts.spotify.com/en/login"
