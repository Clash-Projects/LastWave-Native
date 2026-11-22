package com.lastwave.app.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Custom DNS resolver designed for Realme UI / ColorOS and Indian carriers where IPv6 (AAAA)
 * connections frequently blackhole or time out, and ISP DNS resolvers intermittently drop InnerTube queries.
 *
 * 1) When on standard cellular/Wi-Fi, prioritizes IPv4 (Inet4Address) ahead of IPv6 (Inet6Address)
 *    to avoid cellular IPv6 blackholing.
 * 2) When Android reports the active network as a VPN, or a common VPN tunnel interface is exposed,
 *    preserves the system / VPN DNS ordering so VPN-specific routes are not reordered.
 * 3) Automatically falls back to DNS-over-HTTPS (Cloudflare and Google DoH) for both A and AAAA records
 *    if system DNS throws UnknownHostException, times out, or fails.
 *
 * Both the VPN check and successful answers are cached briefly. OkHttp calls
 * lookup() for every new connection, and on MIUI / ColorOS enumerating network
 * interfaces is slow; with flaky carrier DNS every lookup also used to re-run
 * the sequential DoH chain (up to ~10s), which made songs take ages to start.
 */
class Ipv4FirstDnsResolver(
    private val bootstrapClient: OkHttpClient? = null,
    private val context: Context? = null,
) : Dns {

    private val dohClient: OkHttpClient by lazy {
        val builder = bootstrapClient?.newBuilder() ?: OkHttpClient.Builder()
        builder
            .dns(Dns.SYSTEM)
            .connectTimeout(2500, TimeUnit.MILLISECONDS)
            .readTimeout(2500, TimeUnit.MILLISECONDS)
            .writeTimeout(2500, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private class CachedAnswer(val addresses: List<InetAddress>, val vpn: Boolean, val atMs: Long)

    private val answers = java.util.concurrent.ConcurrentHashMap<String, CachedAnswer>()
    @Volatile private var vpnCheckedAtMs = 0L
    @Volatile private var vpnCached = false

    override fun lookup(hostname: String): List<InetAddress> {
        val vpnActive = isVpnActiveCached()
        val now = android.os.SystemClock.elapsedRealtime()
        answers[hostname]?.let { cached ->
            // A VPN toggle changes routing: never reuse an answer across it.
            if (cached.vpn == vpnActive && now - cached.atMs < ANSWER_TTL_MS) return cached.addresses
        }
        val resolved = resolveUncached(hostname, vpnActive)
        if (answers.size > MAX_CACHED_HOSTS) answers.clear()
        answers[hostname] = CachedAnswer(resolved, vpnActive, now)
        return resolved
    }

    private fun resolveUncached(hostname: String, vpnActive: Boolean): List<InetAddress> {
        try {
            val systemAddresses = Dns.SYSTEM.lookup(hostname)
            if (systemAddresses.isNotEmpty()) {
                // If on VPN, preserve system DNS order directly to honor the VPN's internal routing
                return if (vpnActive) systemAddresses else sortIpv4First(systemAddresses)
            }
        } catch (e: Exception) {
            Log.w(TAG, "System DNS lookup failed for $hostname (${e.message}), falling back to DoH")
        }

        val dohResults = resolveDoh(hostname, wantIpv6First = vpnActive)
        if (dohResults.isNotEmpty()) {
            return if (vpnActive) dohResults else sortIpv4First(dohResults)
        }

        throw UnknownHostException("Unable to resolve host '$hostname' via system DNS or DoH fallback")
    }

    private fun isVpnActiveCached(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - vpnCheckedAtMs < VPN_CHECK_TTL_MS) return vpnCached
        return isVpnActive().also {
            vpnCached = it
            vpnCheckedAtMs = now
        }
    }

    private fun isVpnActive(): Boolean {
        val platformReportsVpn = context?.let { appContext ->
            runCatching {
                val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
                    ?: return@runCatching false
                val activeNetwork = connectivity.activeNetwork ?: return@runCatching false
                val capabilities = connectivity.getNetworkCapabilities(activeNetwork)
                    ?: return@runCatching false
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }.getOrDefault(false)
        } ?: false
        if (platformReportsVpn) return true

        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return false
            interfaces.asSequence().any { iface ->
                iface.isUp && (
                    iface.name.startsWith("tun", ignoreCase = true) ||
                    iface.name.startsWith("ppp", ignoreCase = true) ||
                    iface.name.startsWith("p2p", ignoreCase = true) ||
                    iface.name.contains("vpn", ignoreCase = true) ||
                    iface.name.startsWith("wg", ignoreCase = true) ||
                    iface.name.startsWith("ipsec", ignoreCase = true)
                )
            }
        }.getOrDefault(false)
    }

    private fun sortIpv4First(addresses: List<InetAddress>): List<InetAddress> {
        val hasIpv4 = addresses.any { it is Inet4Address }
        val hasIpv6 = addresses.any { it is Inet6Address }
        if (!hasIpv4 || !hasIpv6) return addresses
        return addresses.sortedWith(compareBy { if (it is Inet4Address) 0 else 1 })
    }

    /**
     * A records first; AAAA only when there is no IPv4 answer (or on a VPN,
     * which keeps its own ordering). Off a VPN, IPv4 is sorted first anyway,
     * so the extra AAAA round trip only added latency.
     */
    private fun resolveDoh(hostname: String, wantIpv6First: Boolean): List<InetAddress> {
        for (endpoint in DOH_ENDPOINTS) {
            val result = mutableListOf<InetAddress>()
            queryDohEndpoint("$endpoint?name=$hostname&type=A", result)
            if (wantIpv6First || result.none { it is Inet4Address }) {
                queryDohEndpoint("$endpoint?name=$hostname&type=AAAA", result)
            }
            if (result.isNotEmpty()) return result
        }
        return emptyList()
    }

    private fun queryDohEndpoint(url: String, out: MutableList<InetAddress>) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/dns-json, application/json")
                .build()
            dohClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    parseDohAnswers(body, out)
                }
            }
        }.onFailure {
            Log.w(TAG, "DoH query failed for $url: ${it.message}")
        }
    }

    private fun parseDohAnswers(jsonStr: String, out: MutableList<InetAddress>) {
        val json = JSONObject(jsonStr)
        val answers = json.optJSONArray("Answer") ?: return
        for (i in 0 until answers.length()) {
            val ans = answers.optJSONObject(i) ?: continue
            val type = ans.optInt("type")
            val data = ans.optString("data").trim()
            // type 1 = A (IPv4), type 28 = AAAA (IPv6)
            if ((type == 1 || type == 28) && data.isNotBlank()) {
                runCatching {
                    val address = InetAddress.getByName(data)
                    if (!out.contains(address)) {
                        out.add(address)
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "Ipv4FirstDns"
        const val ANSWER_TTL_MS = 60_000L
        const val VPN_CHECK_TTL_MS = 10_000L
        const val MAX_CACHED_HOSTS = 256
        val DOH_ENDPOINTS = listOf("https://1.1.1.1/dns-query", "https://8.8.8.8/resolve")
    }
}
