package com.lastwave.app.ui.tv

import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.R
import com.lastwave.app.data.spotify.SpotifyLoginUrls
import com.lastwave.app.ui.settings.SpotifyLoginViewModel
import com.lastwave.app.ui.settings.YouTubeLoginViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Which service the TV login WebView is signing into. */
enum class TvLoginTarget {
    YOUTUBE,
    SPOTIFY,
}

/**
 * The phone app's exact YouTube sign-in entry point: Google's own login with
 * a redirect chain that lands on music.youtube.com, where the session cookies
 * are captured — identical to [com.lastwave.app.ui.settings.YouTubeLoginScreen].
 */
private const val YT_LOGIN_URL =
    "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true" +
        "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue" +
        "%26app%3Ddesktop%26hl%3Den%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F&hl=en"

/** Hold OK longer than this and arrow moves become page drags (scroll). */
private const val DRAG_THRESHOLD_MS = 450L

/**
 * Full-screen login for the TV: the **same flow as the phone build**, driven
 * by a virtual mouse cursor so the remote can operate it.
 *
 * Phone parity, per target:
 *  - YouTube: the cookie jar is wiped, Google's ServiceLogin (redirecting to
 *    music.youtube.com) loads, and page events capture the session cookies —
 *    verified and persisted by the phone's own [YouTubeLoginViewModel], which
 *    resolves the account name and kicks off sync.
 *  - Spotify: the cookie jar is wiped right before the login page is asked
 *    for, `sp_dc` is captured only on a post-auth page
 *    ([SpotifyLoginUrls.isPostAuth]), and the phone's [SpotifyLoginViewModel]
 *    verifies that Spotify actually exchanges it for a token before the
 *    screen reports success.
 *
 * The D-pad moves a visible pointer, OK synthesizes a real touch at its
 * position, PageUp/PageDown scrolls, and BACK goes one page back inside the
 * flow — on the first page it cancels.
 */
@Composable
fun TvLoginScreen(
    target: TvLoginTarget,
    onFinished: () -> Unit,
    onCancel: () -> Unit,
    ytViewModel: YouTubeLoginViewModel = hiltViewModel(),
    spViewModel: SpotifyLoginViewModel = hiltViewModel(),
) {
    val title = when (target) {
        TvLoginTarget.YOUTUBE -> stringResource(R.string.tv_login_youtube_title)
        TvLoginTarget.SPOTIFY -> stringResource(R.string.tv_login_spotify_title)
    }
    val ytState by ytViewModel.uiState.collectAsStateWithLifecycle()

    // The virtual pointer is always on inside sign-in pages — by default and
    // permanently; the Setup/Settings toggles for it no longer exist.
    val pointerEnabled = true

    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageLoading by remember { mutableStateOf(true) }
    var pageError by remember { mutableStateOf(false) }

    // Virtual cursor position in WebView pixel coordinates; NaN = hidden
    // until the first D-pad press places it at the page center.
    var cursorX by remember { mutableStateOf(Float.NaN) }
    var cursorY by remember { mutableStateOf(Float.NaN) }
    // OK-gesture state: quick press = click, held press + arrow moves =
    // drag the page (scrolls without any PageUp/PageDown button).
    var okDownAt by remember { mutableStateOf(0L) }
    var pointerDown by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val density = LocalDensity.current
    val cursorSize = 22.dp
    val cursorHalfPx = with(density) { cursorSize.toPx() / 2f }
    val moveStepPx = with(density) { 42.dp.toPx() }

    // Spotify capture state (phone parity).
    var captured by remember { mutableStateOf(false) }
    var capturedSpdc by remember { mutableStateOf<String?>(null) }

    BackHandler {
        // If a drag was in progress, lift the touch before leaving so the
        // page is not left holding a pressed pointer.
        val held = webView
        if (pointerDown && held != null && !cursorX.isNaN()) {
            val upNow = SystemClock.uptimeMillis()
            val up = MotionEvent.obtain(
                upNow, upNow, MotionEvent.ACTION_UP,
                cursorX, cursorY, 1f, 1f, 0, 1f, 1f, 0, 0,
            )
            held.dispatchTouchEvent(up)
            up.recycle()
        }
        pointerDown = false
        dragging = false
        okDownAt = 0L
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else onCancel()
    }

    // Edge auto-scroll: park the pointer against the bottom edge and the page
    // scrolls down on its own; the top edge scrolls up. Fixes the
    // user-reported gap where riding the edge did nothing without an OK-hold
    // drag. One loop covers both flows — YouTube and Spotify share this
    // screen. Paused while a drag is in progress (the drag scrolls instead).
    LaunchedEffect(cursorX, cursorY, pointerDown, dragging, webView) {
        val wv = webView ?: return@LaunchedEffect
        if (!pointerEnabled || cursorX.isNaN() || pointerDown || dragging) return@LaunchedEffect
        val edgeZone = with(density) { 32.dp.toPx() }
        // Plain View/WebView has no animated-scroll helper (that lives on
        // ScrollView) — frequent small scrollBy steps read just as smooth.
        val step = (wv.height / 8).coerceAtLeast(60)
        while (isActive) {
            val atBottom = cursorY >= wv.height - edgeZone
            val atTop = cursorY <= edgeZone
            if (!atBottom && !atTop) return@LaunchedEffect
            if (atBottom) wv.scrollBy(0, step) else wv.scrollBy(0, -step)
            delay(120)
        }
    }

    // The pointer is permanent inside sign-in pages (see also the summon
    // effect below): it parks at the page
    // centre the moment the WebView exists, with no first D-pad press needed.
    // The Settings toggle still removes it entirely — then the WebView owns
    // the remote natively and no pointer logic runs.
    LaunchedEffect(pointerEnabled, webView) {
        val wv = webView ?: return@LaunchedEffect
        if (!pointerEnabled || !cursorX.isNaN()) return@LaunchedEffect
        // Wait a frame so the WebView reports its real size before parking.
        withFrameNanos { }
        if (wv.width <= 0) withFrameNanos { }
        cursorX = if (wv.width > 0) wv.width / 2f else 400f
        cursorY = if (wv.height > 0) wv.height / 2f else 300f
    }

    /** Reads the `sp_dc` cookie on a post-auth page and stores it (phone logic). */
    fun captureSpDc(url: String?): Boolean {
        if (url == null || !SpotifyLoginUrls.isPostAuth(url)) return false
        val cm = CookieManager.getInstance()
        val candidates = listOfNotNull(
            runCatching { cm.getCookie(url) }.getOrNull(),
            runCatching { cm.getCookie("https://accounts.spotify.com") }.getOrNull(),
            runCatching { cm.getCookie("https://open.spotify.com") }.getOrNull(),
        )
        for (cookieHeader in candidates) {
            val spdc = extractSpDc(cookieHeader)
            if (spdc.isNullOrBlank()) continue
            if (spdc == capturedSpdc) return true
            capturedSpdc = spdc
            spViewModel.saveSpdc(spdc) { outcome ->
                when (outcome) {
                    SpotifyLoginViewModel.Outcome.CONNECTED -> {
                        if (captured) return@saveSpdc
                        captured = true
                        Toast.makeText(
                            context,
                            "Spotify connected!",
                            Toast.LENGTH_SHORT,
                        ).show()
                        onFinished()
                    }
                    SpotifyLoginViewModel.Outcome.SESSION_REFUSED -> Toast.makeText(
                        context,
                        "Signed in, but Spotify refused the session. Sign in again.",
                        Toast.LENGTH_LONG,
                    ).show()
                    SpotifyLoginViewModel.Outcome.FAILED -> Toast.makeText(
                        context,
                        "Couldn't save the Spotify session.",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            return true
        }
        return false
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                if (target == TvLoginTarget.YOUTUBE && ytState.verifying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = "Verifying…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    modifier = Modifier
                        .tvLift(focused = false, shape = RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp))
                        .padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.tv_login_cancel_hint),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = stringResource(R.string.tv_login_instructions),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
            // Phone-parity helper bar: lets the user confirm a finished
            // sign-in without waiting for the redirect chain, and surfaces
            // verification errors.
            if (target == TvLoginTarget.YOUTUBE && ytState.connectedName == null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Surface(
                        onClick = {
                            ytViewModel.attemptConnect(readYouTubeCookies())
                        },
                        enabled = !ytState.verifying,
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Text(
                            text = "I'm signed in — connect",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                        )
                    }
                    if (ytState.errorMessage != null) {
                        Text(
                            text = ytState.errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            // TV rendering: full desktop layout scaled to fit.
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            // Login pages (Google/Spotify) are fully HTTPS;
                            // never let a hostile network inject cleartext
                            // subresources into a credential page.
                            settings.mixedContentMode =
                                android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            overScrollMode = android.view.View.OVER_SCROLL_NEVER
                            settings.setSupportMultipleWindows(false)
                            // The cursor tap needs the WebView to hold focus
                            // (that is also what summons the on-screen
                            // keyboard when an input field is clicked).
                            isFocusable = true
                            isFocusableInTouchMode = true
                            val bootstrapCookies = CookieManager.getInstance()
                            bootstrapCookies.setAcceptCookie(true)
                            bootstrapCookies.setAcceptThirdPartyCookies(this, true)

                            when (target) {
                                TvLoginTarget.YOUTUBE -> {
                                    // Phone parity: no stale session, and the
                                    // device's real Chrome engine version —
                                    // Google refuses sign-in from WebView user
                                    // agents ("may not be secure").
                                    clearCache(true)
                                    clearHistory()
                                    clearFormData()
                                    val defaultUa = settings.userAgentString
                                    settings.userAgentString =
                                        if (!defaultUa.isNullOrBlank()) {
                                            defaultUa
                                                .replace("; wv", "")
                                                .replace("Version/4.0 ", "")
                                                .replace("Version/4.0", "")
                                                .replace(Regex("Version/\\d+\\.\\d+\\s*"), "")
                                        } else {
                                            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                                        }
                                    webViewClient = object : WebViewClient() {
                                        // Renderer crashed or was reclaimed:
                                        // keep the app alive, drop the dead
                                        // WebView.
                                        override fun onRenderProcessGone(
                                            view: WebView?,
                                            detail: android.webkit.RenderProcessGoneDetail?,
                                        ): Boolean {
                                            runCatching {
                                                (view?.parent as? android.view.ViewGroup)?.removeView(view)
                                                view?.destroy()
                                            }
                                            return true
                                        }

                                        override fun doUpdateVisitedHistory(
                                            view: WebView?,
                                            url: String?,
                                            isReload: Boolean,
                                        ) {
                                            super.doUpdateVisitedHistory(view, url, isReload)
                                            val current = ytViewModel.uiState.value
                                            if (current.verifying || current.connectedName != null) return
                                            CookieManager.getInstance().flush()
                                            val cookies = readYouTubeCookies()
                                            if (hasSessionTokens(cookies)) {
                                                ytViewModel.attemptConnect(cookies)
                                            }
                                        }

                                        override fun onPageCommitVisible(
                                            view: WebView?,
                                            url: String?,
                                        ) {
                                            super.onPageCommitVisible(view, url)
                                            val current = ytViewModel.uiState.value
                                            if (current.verifying || current.connectedName != null) return
                                            CookieManager.getInstance().flush()
                                            val cookies = readYouTubeCookies()
                                            if (hasSessionTokens(cookies)) {
                                                ytViewModel.attemptConnect(cookies)
                                            }
                                        }

                                        override fun onPageFinished(
                                            view: WebView?,
                                            url: String?,
                                        ) {
                                            super.onPageFinished(view, url)
                                            pageLoading = false
                                            val current = ytViewModel.uiState.value
                                            if (current.verifying || current.connectedName != null) return
                                            CookieManager.getInstance().flush()
                                            val cookies = readYouTubeCookies()
                                            if (hasSessionTokens(cookies)) {
                                                ytViewModel.attemptConnect(cookies)
                                            }
                                        }
                                    }
                                    // Start from a clean jar, right before the
                                    // page is asked for (phone order — a stale
                                    // session must never be picked up).
                                    runCatching {
                                        val cm = CookieManager.getInstance()
                                        cm.removeAllCookies(null)
                                        cm.flush()
                                        WebStorage.getInstance().deleteAllData()
                                    }
                                    loadUrl(YT_LOGIN_URL)
                                }
                                TvLoginTarget.SPOTIFY -> {
                                    // Phone parity: the accounts page rejects a
                                    // UA that claims to be Chrome while client
                                    // hints still say WebView — keep the
                                    // default agent here.
                                    webViewClient = object : WebViewClient() {
                                        override fun onRenderProcessGone(
                                            view: WebView?,
                                            detail: android.webkit.RenderProcessGoneDetail?,
                                        ): Boolean {
                                            runCatching {
                                                (view?.parent as? android.view.ViewGroup)?.removeView(view)
                                                view?.destroy()
                                            }
                                            return true
                                        }

                                        override fun onPageStarted(
                                            view: WebView?,
                                            url: String?,
                                            favicon: android.graphics.Bitmap?,
                                        ) {
                                            super.onPageStarted(view, url, favicon)
                                            // A fresh main-frame navigation clears
                                            // any earlier error and restarts loading.
                                            pageError = false
                                            pageLoading = true
                                        }

                                        override fun doUpdateVisitedHistory(
                                            view: WebView?,
                                            url: String?,
                                            isReload: Boolean,
                                        ) {
                                            super.doUpdateVisitedHistory(view, url, isReload)
                                            // The accounts page is a single-page
                                            // app: the sign-in can complete
                                            // without a new page load, so the
                                            // capture runs on every history
                                            // entry.
                                            captureSpDc(url)
                                        }

                                        override fun onPageFinished(
                                            view: WebView?,
                                            url: String?,
                                        ) {
                                            super.onPageFinished(view, url)
                                            pageLoading = false
                                            val mainUrl = view?.url?.takeIf { it.isNotBlank() } ?: url
                                            // The cookie is only present once the
                                            // page has loaded.
                                            captureSpDc(mainUrl)
                                        }

                                        override fun onReceivedError(
                                            view: WebView?,
                                            request: WebResourceRequest?,
                                            error: WebResourceError?,
                                        ) {
                                            super.onReceivedError(view, request, error)
                                            if (request?.isForMainFrame == true) {
                                                pageError = true
                                                pageLoading = false
                                            }
                                        }
                                    }
                                    // Start from a clean jar right before the
                                    // page is asked for: a leftover session
                                    // would be read as a completed sign-in.
                                    runCatching {
                                        val cm = CookieManager.getInstance()
                                        cm.removeAllCookies(null)
                                        cm.flush()
                                        WebStorage.getInstance().deleteAllData()
                                    }
                                    loadUrl(SpotifyLoginUrls.LOGIN_URL)
                                }
                            }
                        }.also { webView = it }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .onPreviewKeyEvent { event ->
                            // Pointer off: the WebView handles the remote
                            // natively (arrows traverse links, OK clicks,
                            // Page keys scroll) — no pointer logic runs.
                            if (!pointerEnabled) return@onPreviewKeyEvent false
                            val ke = event.nativeKeyEvent
                            if (ke.action != android.view.KeyEvent.ACTION_DOWN) {
                                // Key release: OK/ENTER lifts the touch — a
                                // quick hold releases as a click, a long hold
                                // (drag) releases where the drag stopped. Every
                                // other handled key just gets its UP swallowed.
                                if (ke.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                                    ke.keyCode == android.view.KeyEvent.KEYCODE_ENTER
                                ) {
                                    val held = webView
                                    if (pointerDown && held != null && !cursorX.isNaN()) {
                                        val upNow = SystemClock.uptimeMillis()
                                        val up = MotionEvent.obtain(
                                            upNow, upNow, MotionEvent.ACTION_UP,
                                            cursorX, cursorY, 1f, 1f, 0, 1f, 1f, 0, 0,
                                        )
                                        held.dispatchTouchEvent(up)
                                        up.recycle()
                                    }
                                    pointerDown = false
                                    dragging = false
                                    okDownAt = 0L
                                    return@onPreviewKeyEvent true
                                }
                                return@onPreviewKeyEvent when (ke.keyCode) {
                                    android.view.KeyEvent.KEYCODE_DPAD_UP,
                                    android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                                    android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                                    android.view.KeyEvent.KEYCODE_PAGE_UP,
                                    android.view.KeyEvent.KEYCODE_PAGE_DOWN,
                                    android.view.KeyEvent.KEYCODE_CHANNEL_UP,
                                    android.view.KeyEvent.KEYCODE_CHANNEL_DOWN,
                                    -> true
                                    else -> false
                                }
                            }
                            val wv = webView ?: return@onPreviewKeyEvent false
                            val code = ke.keyCode
                            when (code) {
                                android.view.KeyEvent.KEYCODE_DPAD_UP,
                                android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                                android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                                android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                                -> {
                                    // First press materializes the pointer at
                                    // the center; afterwards arrows move it.
                                    // Key repeats arrive as more ACTION_DOWNs,
                                    // so holding a direction keeps moving.
                                    val centerX =
                                        if (wv.width > 0) wv.width / 2f else 400f
                                    val centerY =
                                        if (wv.height > 0) wv.height / 2f else 300f
                                    val x = if (cursorX.isNaN()) centerX else cursorX
                                    val y = if (cursorY.isNaN()) centerY else cursorY
                                    val nx = when (code) {
                                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> x - moveStepPx
                                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> x + moveStepPx
                                        else -> x
                                    }
                                    val ny = when (code) {
                                        android.view.KeyEvent.KEYCODE_DPAD_UP -> y - moveStepPx
                                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> y + moveStepPx
                                        else -> y
                                    }
                                    cursorX = nx.coerceIn(0f, (wv.width - 1).coerceAtLeast(0).toFloat())
                                    cursorY = ny.coerceIn(0f, (wv.height - 1).coerceAtLeast(0).toFloat())
                                    if (pointerDown && dragging && !cursorX.isNaN()) {
                                        // OK is held: arrows drag the page with
                                        // the pointer — this is the scroll.
                                        val moveNow = SystemClock.uptimeMillis()
                                        val move = MotionEvent.obtain(
                                            moveNow, moveNow, MotionEvent.ACTION_MOVE,
                                            cursorX, cursorY, 1f, 1f, 0, 1f, 1f, 0, 0,
                                        )
                                        wv.dispatchTouchEvent(move)
                                        move.recycle()
                                    }
                                    true
                                }
                                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                                android.view.KeyEvent.KEYCODE_ENTER,
                                -> {
                                    // OK presses a real touch at the pointer.
                                    // Quick press+release = click. Hold past the
                                    // threshold and move with the arrows = drag
                                    // (scrolls the page like a finger swipe).
                                    val centerX =
                                        if (wv.width > 0) wv.width / 2f else 400f
                                    val centerY =
                                        if (wv.height > 0) wv.height / 2f else 300f
                                    val x = if (cursorX.isNaN()) centerX else cursorX
                                    val y = if (cursorY.isNaN()) centerY else cursorY
                                    cursorX = x
                                    cursorY = y
                                    if (!pointerDown) {
                                        okDownAt = SystemClock.uptimeMillis()
                                        dragging = false
                                        pointerDown = true
                                        wv.requestFocus()
                                        val downNow = SystemClock.uptimeMillis()
                                        val down = MotionEvent.obtain(
                                            downNow, downNow, MotionEvent.ACTION_DOWN, x, y,
                                            1f, 1f, 0, 1f, 1f, 0, 0,
                                        )
                                        wv.dispatchTouchEvent(down)
                                        down.recycle()
                                    } else if (!dragging &&
                                        SystemClock.uptimeMillis() - okDownAt > DRAG_THRESHOLD_MS
                                    ) {
                                        // Auto-repeat while holding: switch to drag.
                                        dragging = true
                                    }
                                    true
                                }
                                android.view.KeyEvent.KEYCODE_PAGE_UP,
                                android.view.KeyEvent.KEYCODE_PAGE_DOWN,
                                android.view.KeyEvent.KEYCODE_CHANNEL_UP,
                                android.view.KeyEvent.KEYCODE_CHANNEL_DOWN,
                                -> {
                                    wv.requestFocus()
                                    wv.dispatchKeyEvent(
                                        android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code),
                                    )
                                    wv.dispatchKeyEvent(
                                        android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code),
                                    )
                                    true
                                }
                                else -> false
                            }
                        },
                )
                if (pageLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter),
                    )
                }
                if (pageError) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp)
                            .clip(RoundedCornerShape(16.dp)),
                    ) {
                        Text(
                            text = "Couldn't load the sign-in page. Check the network, press Back, and try again.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                }
                if (pointerEnabled && !cursorX.isNaN() && !cursorY.isNaN()) {
                    // The pointer dot, drawn above the page.
                    Box(
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    (cursorX - cursorHalfPx).roundToInt(),
                                    (cursorY - cursorHalfPx).roundToInt(),
                                )
                            }
                            .size(cursorSize)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                            .border(2.dp, MaterialTheme.colorScheme.background, CircleShape),
                    )
                }
                if (target == TvLoginTarget.YOUTUBE && ytState.connectedName != null) {
                    // Phone-parity "Connected" card, then the screen closes
                    // itself (the connection is already persisted + syncing).
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shadowElevation = 8.dp,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(40.dp),
                            )
                            Text(
                                text = "Connected",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                text = ytState.connectedName.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(webView) {
        // Give the WebView focus once it is attached and laid out, so the
        // first tap already lands on the page.
        withFrameNanos { }
        webView?.requestFocus()
    }

    // YouTube backup poll (phone parity): if the redirect chain lands on
    // music.youtube.com and the session cookies are present, connect even if
    // a page event was missed.
    if (target == TvLoginTarget.YOUTUBE) {
        LaunchedEffect(webView) {
            while (isActive) {
                delay(1_200L)
                val current = ytViewModel.uiState.value
                if (current.verifying || current.connectedName != null) continue
                val url = webView?.url
                if (url?.contains("music.youtube.com") == true) {
                    val cookies = withContext(Dispatchers.IO) {
                        runCatching { readYouTubeCookies() }.getOrNull()
                    }
                    if (hasSessionTokens(cookies)) {
                        ytViewModel.attemptConnect(cookies)
                    }
                }
            }
        }
    }

    LaunchedEffect(ytState.connectedName) {
        if (target == TvLoginTarget.YOUTUBE && ytState.connectedName != null) {
            // Let the user see the Connected card, then close.
            delay(1_600L)
            onFinished()
        }
    }
}

/** Phone parity: session cookies merged across every YouTube domain. */
private fun readYouTubeCookies(): String? = try {
    val cm = CookieManager.getInstance()
    cm.flush()
    val domains = listOf(
        "https://music.youtube.com",
        "https://www.youtube.com",
        "https://accounts.google.com",
        "https://youtube.com",
    )
    val cookieMap = mutableMapOf<String, String>()
    for (domain in domains) {
        val raw = cm.getCookie(domain) ?: continue
        for (part in raw.split(';')) {
            val idx = part.indexOf('=')
            if (idx > 0) {
                val name = part.substring(0, idx).trim()
                val value = part.substring(idx + 1).trim()
                if (name.isNotBlank() && value.isNotBlank()) {
                    cookieMap[name] = value
                }
            }
        }
    }
    if (cookieMap.isEmpty()) null else cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
} catch (error: Exception) {
    null
} catch (error: LinkageError) {
    null
}

/** Phone parity: a real YouTube session needs SID plus SAPISID tokens. */
private fun hasSessionTokens(cookies: String?): Boolean {
    if (cookies.isNullOrBlank()) return false
    val hasSapisid = cookies.contains("SAPISID=") || cookies.contains("__Secure-3PAPISID=")
    val hasSid = cookies.contains("SID=") || cookies.contains("__Secure-3PSID=")
    return hasSapisid && hasSid
}

/** Phone parity: pull `sp_dc` out of a raw cookie header. */
private fun extractSpDc(cookieHeader: String?): String? {
    if (cookieHeader.isNullOrBlank()) return null
    for (pair in cookieHeader.split(";")) {
        val parts = pair.trim().split("=", limit = 2)
        if (parts.size == 2 && parts[0].trim().equals("sp_dc", ignoreCase = true)) {
            return parts[1].trim()
        }
    }
    return null
}
