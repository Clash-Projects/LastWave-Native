package com.lastwave.app.ui.settings

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebStorage
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.lastwave.app.data.spotify.SpotifyLoginUrls
import com.lastwave.app.ui.common.ExpressiveHeader
import com.lastwave.app.ui.common.adaptiveContentWidth

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyLoginScreen(
    onBack: () -> Unit,
    onConnected: () -> Unit,
    openManualEntryInitially: Boolean = false,
    viewModel: SpotifyLoginViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    // The sign-in and the pasted cookie are two different ways in, and a caller that
    // only wants the cookie should not have to walk past the browser to get to it.
    var showManualDialog by remember { mutableStateOf(openManualEntryInitially) }
    var manualSpDcInput by remember { mutableStateOf("") }
    var captured by remember { mutableStateOf(false) }
    var capturedSpdc by remember { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var lastUrl by remember { mutableStateOf<String?>(null) }
    var webViewError by remember { mutableStateOf<String?>(null) }

    /**
     * What to say once the cookie has been stored, and whether to leave the screen.
     *
     * A session Spotify refuses is not the same as one it accepts, and the difference
     * is worth saying out loud: without it, a sign-in that cannot be used looks
     * exactly like one that can, and every later lookup fails quietly.
     */
    fun onSignInOutcome(outcome: SpotifyLoginViewModel.Outcome) {
        when (outcome) {
            SpotifyLoginViewModel.Outcome.CONNECTED -> {
                if (captured) return
                captured = true
                Toast.makeText(context, "Spotify Canvas connected!", Toast.LENGTH_SHORT).show()
                onConnected()
            }
            SpotifyLoginViewModel.Outcome.SESSION_REFUSED -> {
                Log.w(LOG_TAG, "Spotify refused the session cookie")
                Toast.makeText(
                    context,
                    "Signed in, but Spotify refused the session. Sign in again, or paste a fresh cookie.",
                    Toast.LENGTH_LONG,
                ).show()
            }
            SpotifyLoginViewModel.Outcome.FAILED -> {
                Log.w(LOG_TAG, "Failed to store sp_dc")
                Toast.makeText(context, "Couldn't save the Spotify session.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Reads the `sp_dc` cookie and stores it.
     *
     * The cookie is only issued by Spotify's *authentication* response, so it
     * is read on the post-login landing page (the accounts status page) and
     * nowhere else — reading it on the login page would latch a cookie that
     * belongs to an anonymous session.
     *
     * A cookie that is already stored is not stored again: the same one is read
     * on every page the user visits after the sign-in, and the toast is only
     * wanted once. A cookie that differs from the stored one is a newer session
     * and replaces it.
     */
    fun captureSpDc(url: String?): Boolean {
        if (url == null || !SpotifyLoginUrls.isPostAuth(url)) {
            Log.d(LOG_TAG, "Not a post-login page, skipping capture: $url")
            return false
        }
        val cm = CookieManager.getInstance()
        val cookieCandidates = listOfNotNull(
            cm.getCookie(url),
            cm.getCookie("https://accounts.spotify.com"),
            cm.getCookie("https://open.spotify.com"),
        )
        for (cookieStr in cookieCandidates) {
            val spdc = extractSpDc(cookieStr)
            if (spdc.isNullOrBlank()) continue
            if (spdc == capturedSpdc) return true
            capturedSpdc = spdc
            Log.i(LOG_TAG, "Post-login page reached, storing sp_dc (${spdc.length} chars)")
            viewModel.saveSpdc(spdc) { outcome -> onSignInOutcome(outcome) }
            return true
        }
        Log.d(LOG_TAG, "Post-login page reached but no sp_dc in the cookie jar")
        return false
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .adaptiveContentWidth(maxWidth = 760.dp)
                // The app draws edge to edge and never resizes the window for
                // the keyboard, so without this the keyboard would cover the
                // form the user is typing into.
                .imePadding(),
        ) {
            ExpressiveHeader(
                title = "Connect Spotify",
                subtitle = "Sign in to unlock Spotify Canvas video loops",
                onBack = onBack,
                actions = {
                    if (isConnected) {
                        FilledTonalIconButton(
                            onClick = {
                                viewModel.clearConnection()
                                runCatching {
                                    val cm = CookieManager.getInstance()
                                    for (domain in listOf("https://spotify.com", "https://accounts.spotify.com", "https://open.spotify.com")) {
                                        val cookies = cm.getCookie(domain) ?: continue
                                        for (cookie in cookies.split(";")) {
                                            val name = cookie.substringBefore("=").trim()
                                            if (name.isNotEmpty()) {
                                                cm.setCookie(domain, "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/")
                                            }
                                        }
                                    }
                                    cm.flush()
                                }
                                captured = false
                                capturedSpdc = null
                                webViewRef?.loadUrl(SpotifyLoginUrls.LOGIN_URL)
                                android.widget.Toast.makeText(context, "Spotify disconnected", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Logout,
                                contentDescription = "Log out of Spotify",
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                    }
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

            // WebView Container.
            // No clip and no forced background: clipping a WebView makes
            // Compose render it into an offscreen layer, and a WebView in an
            // offscreen layer can come up completely blank on some ROMs.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        var createdWebView: WebView? = null
                        try {
                            WebView(ctx).also {
                                createdWebView = it
                                webViewRef = it
                            }.apply {
                                // The WebView must fill the holder: a
                                // wrap-content child measures to zero height
                                // and the page never appears.
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    databaseEnabled = true
                                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                    cacheMode = WebSettings.LOAD_DEFAULT
                                    // Keep the default user agent: the accounts page
                                    // rejects a UA that claims to be Chrome while
                                    // the WebView client hints still say WebView.
                                    setSupportMultipleWindows(false)
                                }

                                val cm = CookieManager.getInstance()
                                cm.setAcceptCookie(true)
                                cm.setAcceptThirdPartyCookies(this, true)

                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        super.onProgressChanged(view, newProgress)
                                        // Main-frame progress only: a sub-frame
                                        // load must never flip the loading
                                        // state.
                                        val loading = newProgress < 100
                                        if (loading != isLoading) isLoading = loading
                                    }
                                }

                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                        super.onPageStarted(view, url, favicon)
                                        // Sub-frames (captcha, email hints)
                                        // report here as well. Nothing is
                                        // derived from this callback: the
                                        // loading state comes from the
                                        // main-frame progress only.
                                        hasError = false
                                        Log.d(LOG_TAG, "onPageStarted: $url")
                                    }

                                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                        super.doUpdateVisitedHistory(view, url, isReload)
                                        // Main frame only: keeps the current
                                        // page URL correct for the SPA.
                                        if (!url.isNullOrBlank()) lastUrl = url
                                        // The accounts page is a single-page app, so the
                                        // sign-in can complete without a new page being
                                        // loaded and the landing page reached without a
                                        // page-finish either. The capture is attempted
                                        // on every history entry so that route cannot
                                        // hide the cookie.
                                        captureSpDc(url)
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        // view.url is always the main frame's
                                        // URL, so a sub-frame finishing can
                                        // never make this look like a
                                        // different page.
                                        val mainUrl = view?.url?.takeIf { it.isNotBlank() } ?: url
                                        lastUrl = mainUrl
                                        Log.d(LOG_TAG, "onPageFinished: $mainUrl")
                                        // The cookie is only present once the page
                                        // has loaded, so this is the only place the
                                        // capture may run.
                                        captureSpDc(mainUrl)
                                    }

                                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                        super.onReceivedError(view, request, error)
                                        if (request?.isForMainFrame == true) {
                                            hasError = true
                                            isLoading = false
                                            Log.w(
                                                LOG_TAG,
                                                "Main frame error for ${request.url}: " +
                                                    "${error?.errorCode} ${error?.description}",
                                            )
                                        }
                                    }
                                }

                                // Start from a clean jar. This has to happen here, right
                                // before the page is asked for: a session left behind by an
                                // earlier attempt would otherwise be picked up as if the user
                                // had just signed in, and wiping it later would race the
                                // response that sets the session cookies.
                                runCatching {
                                    val cm = CookieManager.getInstance()
                                    cm.removeAllCookies(null)
                                    cm.flush()
                                    WebStorage.getInstance().deleteAllData()
                                }

                                loadUrl(SpotifyLoginUrls.LOGIN_URL)
                            }
                        } catch (error: Exception) {
                            // A missing or broken WebView provider must not
                            // leave a blank white box on screen.
                            runCatching { createdWebView?.destroy() }
                            webViewRef = null
                            isLoading = false
                            webViewError = "Android System WebView is unavailable. Install or enable a WebView provider to connect Spotify."
                            Log.e(LOG_TAG, "WebView unavailable", error)
                            android.widget.FrameLayout(ctx)
                        } catch (error: LinkageError) {
                            runCatching { createdWebView?.destroy() }
                            webViewRef = null
                            isLoading = false
                            webViewError = "This ROM's WebView implementation is incompatible. Spotify sign-in is disabled safely."
                            Log.e(LOG_TAG, "WebView linkage error", error)
                            android.widget.FrameLayout(ctx)
                        }
                    },
                )

                // The progress bar floats over the page instead of sitting
                // above it: the WebView's bounds must never change, because
                // resizing it re-lays out the web content, which shows up as
                // a flicker (and can steal the focus of the field being
                // typed into).
                if (isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                    )
                }

                if (webViewError != null) {
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    ) {
                        Text(
                            webViewError.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                }

                if (hasError) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                "Unable to load Spotify login page directly.",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "You can reload the page or paste your sp_dc cookie manually.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    onClick = {
                                        hasError = false
                                        webViewRef?.reload()
                                    },
                                ) {
                                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.size(6.dp))
                                    Text("Reload")
                                }
                                OutlinedButton(
                                    onClick = { showManualDialog = true },
                                ) {
                                    Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.size(6.dp))
                                    Text("Enter Cookie")
                                }
                            }
                        }
                    }
                }
            }

            // Quick Actions Bar
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(
                        onClick = {
                            // Re-check the page the WebView is currently on:
                            // the cookie is only readable once the
                            // post-login page has loaded.
                            val found = captureSpDc(lastUrl ?: webViewRef?.url)
                            if (!found) {
                                Toast.makeText(context, "No Spotify session detected. Log in above first.", Toast.LENGTH_SHORT).show()
                            }
                        },
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Check Login")
                    }

                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty().trim()
                            if (clipText.startsWith("AQ") || clipText.length >= 30) {
                                manualSpDcInput = clipText
                                viewModel.saveSpdc(clipText) { outcome -> onSignInOutcome(outcome) }
                            } else {
                                showManualDialog = true
                            }
                        },
                    ) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Paste sp_dc")
                    }

                    FilledTonalIconButton(
                        onClick = {
                            runCatching {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(SpotifyLoginUrls.LOGIN_URL))
                                context.startActivity(intent)
                            }
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open in Browser")
                    }
                }
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
                                viewModel.saveSpdc(manualSpDcInput.trim()) { outcome ->
                                    // The dialog stays open only when the cookie could not
                                    // be stored, so the value can be corrected.
                                    if (outcome != SpotifyLoginViewModel.Outcome.FAILED) {
                                        showManualDialog = false
                                    }
                                    onSignInOutcome(outcome)
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
        if (parts.size == 2 && parts[0].trim().equals("sp_dc", ignoreCase = true)) {
            return parts[1].trim()
        }
    }
    return null
}

private const val LOG_TAG = "SpotifyLogin"
