package com.lastwave.app.data.network

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import java.io.IOException
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * Optional fixed proxy for all app traffic. Installed as the process-wide
 * [ProxySelector], which every OkHttp client (API, player, InnerTubeX's Ktor
 * engine, NewPipe) and HttpURLConnection consult per connection, so a VPN
 * exit that YouTube blocks can be bypassed without touching each client.
 * WebView traffic (BotGuard) does not use it.
 */
object NetworkProxy {
    private const val PREFS = "network_proxy"
    private const val KEY_URL = "url"

    private class Config(val url: String, val proxy: Proxy, val host: String, val credentials: PasswordAuthentication?)

    @Volatile private var config: Config? = null
    private var prefs: SharedPreferences? = null

    /** The saved proxy URL, or null for a direct connection. */
    val url: String? get() = config?.url

    fun install(context: Context) {
        val store = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = store
        config = store.getString(KEY_URL, null)?.let { parse(it).getOrNull() }
        val system = ProxySelector.getDefault()
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> {
                val active = config
                if (active == null || uri?.host == null || isLocal(uri.host)) {
                    return system?.select(uri) ?: listOf(Proxy.NO_PROXY)
                }
                return listOf(active.proxy)
            }

            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
                system?.connectFailed(uri, sa, ioe)
            }
        })
        // The JDK SOCKS client asks this for the SOCKS5 username/password.
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? =
                config?.takeIf { requestingHost.equals(it.host, ignoreCase = true) }?.credentials
        })
    }

    /** Saves [raw] (blank = off); returns the error text when it is not a usable proxy URL. */
    fun set(raw: String): Result<Unit> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            config = null
            prefs?.edit()?.remove(KEY_URL)?.apply()
            return Result.success(Unit)
        }
        return parse(trimmed).map { parsed ->
            config = parsed
            prefs?.edit()?.putString(KEY_URL, parsed.url)?.apply()
        }
    }

    private fun parse(raw: String): Result<Config> = runCatching {
        val uri = Uri.parse(if ("://" in raw) raw else "http://$raw")
        val type = when (uri.scheme?.lowercase()) {
            "socks", "socks5" -> Proxy.Type.SOCKS
            "http" -> Proxy.Type.HTTP
            else -> throw IllegalArgumentException("Use socks5:// or http://")
        }
        val host = uri.host?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Host missing")
        val port = uri.port.takeIf { it in 1..65535 } ?: throw IllegalArgumentException("Port missing")
        val userInfo = uri.userInfo
        // OkHttp answers an HTTP proxy's 407 only through per-client authenticators,
        // which InnerTubeX's internal client lacks; SOCKS5 auth works process-wide.
        if (userInfo != null && type == Proxy.Type.HTTP) {
            throw IllegalArgumentException("HTTP proxy login is not supported; use socks5://user:pass@host:port")
        }
        val credentials = userInfo?.let {
            PasswordAuthentication(it.substringBefore(':'), it.substringAfter(':', "").toCharArray())
        }
        Config(raw, Proxy(type, InetSocketAddress.createUnresolved(host, port)), host, credentials)
    }

    private fun isLocal(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        return h == "localhost" || h.endsWith(".local") || h == "::1" || h.startsWith("127.") ||
            h.startsWith("10.") || h.startsWith("192.168.") ||
            (':' in h && (h.startsWith("fd") || h.startsWith("fe80:"))) ||
            Regex("""172\.(1[6-9]|2\d|3[01])\..*""").matches(h)
    }
}
