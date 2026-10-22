package com.lastwave.app.data.khinsider

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

class KhInsiderException(message: String) : IOException(message)

/**
 * Reads downloads.khinsider.com (no official API) for the "Video games"
 * search filter. Pages are fetched with OkHttp; when Cloudflare answers with
 * a JavaScript challenge, the page is loaded once in an invisible WebView
 * (which solves it) and the clearance cookie is then reused by OkHttp with
 * the same user agent.
 */
@Singleton
class KhInsiderClient @Inject constructor(
    @ApplicationContext private val context: Context,
    okHttpClient: OkHttpClient,
) {
    private val http = okHttpClient.newBuilder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val albumCache = LruCache<String, KhAlbum>(24)
    private val fileCache = LruCache<String, Map<String, String>>(400)
    private val webViewLock = Any()

    private val _format = MutableStateFlow(readFormat())
    val format: StateFlow<KhFormat> = _format.asStateFlow()

    /** Browser user agent: must match the WebView's so its clearance cookie stays valid. */
    val userAgent: String by lazy {
        runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: FALLBACK_UA
    }

    // --- Offline copies ---------------------------------------------------
    // Track page -> where its downloaded file lives (MediaStore uri or path).
    private val downloadsPrefs by lazy { context.getSharedPreferences(DOWNLOADS_PREFS, Context.MODE_PRIVATE) }
    private val _downloadedPages = MutableStateFlow<Set<String>>(emptySet())
    /** Track pages that have a saved file (refreshed by [refreshDownloads]). */
    val downloadedPages: StateFlow<Set<String>> = _downloadedPages.asStateFlow()

    fun recordDownload(pagePath: String, location: String) {
        downloadsPrefs.edit().putString(pagePath, location).apply()
        _downloadedPages.value = _downloadedPages.value + pagePath
    }

    /** Re-reads the saved copies, dropping those deleted outside the app. */
    suspend fun refreshDownloads() = withContext(Dispatchers.IO) {
        val present = downloadsPrefs.all.keys.filterTo(mutableSetOf()) { downloadedLocation(it) != null }
        _downloadedPages.value = present
    }

    /** The saved file for [pagePath] if it is still readable, else null (and forgotten). */
    fun downloadedLocation(pagePath: String): String? {
        val location = downloadsPrefs.getString(pagePath, null) ?: return null
        val readable = runCatching {
            if (location.startsWith("content://")) {
                context.contentResolver.openFileDescriptor(android.net.Uri.parse(location), "r")?.use { true } ?: false
            } else {
                java.io.File(location.removePrefix("file://")).let { it.isFile && it.canRead() }
            }
        }.getOrDefault(false)
        if (!readable) {
            downloadsPrefs.edit().remove(pagePath).apply()
            _downloadedPages.value = _downloadedPages.value - pagePath
            return null
        }
        return location
    }

    /** What to play for a track page: its saved file first, else the site's file link. Blocking. */
    fun playbackSourceBlocking(pagePath: String): String =
        downloadedLocation(pagePath) ?: resolveFileUrlBlocking(pagePath)

    fun setFormat(format: KhFormat) {
        prefs.edit().putString(KEY_FORMAT, format.name).apply()
        _format.value = format
    }

    private fun readFormat(): KhFormat =
        runCatching { KhFormat.valueOf(prefs.getString(KEY_FORMAT, null) ?: KhFormat.FLAC.name) }
            .getOrDefault(KhFormat.FLAC)

    suspend fun search(query: String): List<KhAlbumSummary> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        // KHInsider refuses searches shorter than three characters.
        if (trimmed.length < 3) return@withContext emptyList()
        val page = fetch(KhInsiderParser.searchUrl(trimmed))
        // An exact match redirects straight to the album page.
        if (KhInsiderParser.isAlbumPage(page.finalUrl)) {
            val id = KhInsiderParser.albumIdOf(page.finalUrl) ?: return@withContext emptyList()
            val album = KhInsiderParser.parseAlbum(id, page.html)?.also { albumCache.put(id, it) }
                ?: return@withContext emptyList()
            return@withContext listOf(
                KhAlbumSummary(
                    id = id,
                    name = album.name,
                    thumbnailUrl = album.coverUrl,
                    platform = album.platforms.firstOrNull(),
                    type = album.type,
                    year = album.year,
                ),
            )
        }
        KhInsiderParser.parseSearch(page.html)
    }

    suspend fun albumList(list: KhBrowseList, page: Int = 1): KhAlbumPage = withContext(Dispatchers.IO) {
        val html = fetch(KhInsiderParser.listUrl(list.path, page)).html
        KhInsiderParser.parseAlbumPage(html, page).let { if (list.paged) it else it.copy(hasNext = false) }
    }

    @Volatile private var platformCache: List<Pair<String, String>>? = null

    /** Every platform KHInsider lists; the menu's main platforms if the page can't be read. */
    suspend fun platforms(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        platformCache?.let { return@withContext it }
        val parsed = runCatching {
            KhInsiderParser.parsePlatforms(fetch("${KhInsiderParser.BASE_URL}/console-list").html)
        }.getOrDefault(emptyList())
        (parsed.ifEmpty { KhInsiderParser.MAIN_PLATFORMS }).also { if (parsed.isNotEmpty()) platformCache = it }
    }

    suspend fun album(albumId: String, forceRefresh: Boolean = false): KhAlbum = withContext(Dispatchers.IO) {
        if (!forceRefresh) albumCache.get(albumId)?.let { return@withContext it }
        val page = fetch(KhInsiderParser.albumUrl(albumId))
        val album = KhInsiderParser.parseAlbum(albumId, page.html)
            ?: throw KhInsiderException("Album not found: $albumId")
        albumCache.put(albumId, album)
        album
    }

    /**
     * Direct file URL for a track page in the chosen format (MP3 when the
     * album has no FLAC). Blocking: called on the player's loader thread.
     */
    fun resolveFileUrlBlocking(pagePath: String, format: KhFormat = _format.value): String {
        val files = fileCache.get(pagePath) ?: run {
            val page = fetchBlocking(KhInsiderParser.trackPageUrl(pagePath))
            KhInsiderParser.parseTrackFiles(page.html, page.finalUrl).also {
                if (it.isNotEmpty()) fileCache.put(pagePath, it)
            }
        }
        return KhInsiderParser.pickFile(files, format)
            ?: throw KhInsiderException("No audio file on $pagePath")
    }

    /** Headers to send with file requests (same browser identity as page requests). */
    fun fileRequestHeaders(): Map<String, String> = mapOf(
        "User-Agent" to userAgent,
        "Referer" to "${KhInsiderParser.BASE_URL}/",
    )

    private data class Page(val html: String, val finalUrl: String)

    private suspend fun fetch(url: String): Page = withContext(Dispatchers.IO) { fetchBlocking(url) }

    private fun fetchBlocking(url: String): Page {
        val direct = runCatching { okHttpGet(url) }
        direct.getOrNull()?.let { page ->
            if (!KhInsiderParser.isCloudflareChallenge(page.html) && !KhInsiderParser.isCloudflareBlock(page.html)) {
                return page
            }
            if (KhInsiderParser.isCloudflareBlock(page.html)) {
                throw KhInsiderException("KHInsider blocked this network (Cloudflare)")
            }
        }
        Log.i(TAG, "Cloudflare challenge on $url, solving in WebView")
        val solved = webViewGet(url)
        if (KhInsiderParser.isCloudflareChallenge(solved.html) || KhInsiderParser.isCloudflareBlock(solved.html)) {
            throw KhInsiderException("KHInsider is behind a Cloudflare check that could not be passed")
        }
        return solved
    }

    private fun okHttpGet(url: String): Page {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
        cookiesFor(url)?.let { builder.header("Cookie", it) }
        http.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful && !KhInsiderParser.isCloudflareChallenge(body) && !KhInsiderParser.isCloudflareBlock(body)) {
                throw KhInsiderException("KHInsider HTTP ${response.code}")
            }
            return Page(body, response.request.url.toString())
        }
    }

    private fun cookiesFor(url: String): String? =
        runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Loads [url] in an off-screen WebView until Cloudflare lets it through, then returns its HTML. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun webViewGet(url: String): Page = synchronized(webViewLock) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "webViewGet must not run on the main thread" }
        val result = AtomicReference<Page?>(null)
        val done = CountDownLatch(1)
        val main = Handler(Looper.getMainLooper())
        val webViewRef = AtomicReference<WebView?>(null)
        main.post {
            try {
                val webView = WebView(context)
                webViewRef.set(webView)
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.userAgentString = userAgent
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, finishedUrl: String) {
                        poll(view, main, result, done, attempt = 0)
                    }
                }
                webView.loadUrl(url)
            } catch (t: Throwable) {
                Log.w(TAG, "WebView unavailable: ${t.message}")
                done.countDown()
            }
        }
        val finished = done.await(WEBVIEW_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        main.post {
            webViewRef.getAndSet(null)?.let { view ->
                view.stopLoading()
                view.destroy()
            }
            CookieManager.getInstance().flush()
        }
        if (!finished) throw KhInsiderException("KHInsider took too long to answer")
        result.get() ?: throw KhInsiderException("KHInsider page could not be loaded")
    }

    private fun poll(
        view: WebView,
        main: Handler,
        result: AtomicReference<Page?>,
        done: CountDownLatch,
        attempt: Int,
    ) {
        if (done.count == 0L) return
        view.evaluateJavascript("(function(){return document.documentElement.outerHTML;})()") { raw ->
            val html = decodeJsString(raw)
            val challenged = KhInsiderParser.isCloudflareChallenge(html)
            if (!challenged || attempt >= MAX_POLLS) {
                result.set(Page(html, view.url ?: ""))
                done.countDown()
            } else {
                // The challenge reloads the page by itself when solved.
                main.postDelayed({ poll(view, main, result, done, attempt + 1) }, POLL_INTERVAL_MS)
            }
        }
    }

    private fun decodeJsString(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return runCatching {
            kotlinx.serialization.json.Json.decodeFromString(kotlinx.serialization.serializer<String>(), raw)
        }.getOrDefault(raw)
    }

    private companion object {
        const val TAG = "KhInsider"
        const val PREFS = "khinsider_prefs"
        const val KEY_FORMAT = "format"
        const val DOWNLOADS_PREFS = "khinsider_downloads"
        const val WEBVIEW_TIMEOUT_MS = 25_000L
        const val POLL_INTERVAL_MS = 700L
        const val MAX_POLLS = 25
        const val FALLBACK_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36"
    }
}
