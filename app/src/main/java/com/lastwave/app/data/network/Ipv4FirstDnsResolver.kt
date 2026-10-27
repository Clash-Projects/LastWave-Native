package com.lastwave.app.data.network

import android.util.Log
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Custom DNS resolver designed for Realme UI / ColorOS and Indian carriers where IPv6 (AAAA)
 * connections frequently blackhole or time out, and ISP DNS resolvers intermittently drop InnerTube queries.
 *
 * 1) Queries system DNS and prioritizes IPv4 (Inet4Address) ahead of IPv6 (Inet6Address).
 * 2) Automatically falls back to DNS-over-HTTPS (https://1.1.1.1/dns-query and https://dns.google/resolve)
 *    if system DNS throws UnknownHostException, times out, or fails.
 */
class Ipv4FirstDnsResolver(
    private val bootstrapClient: OkHttpClient? = null,
) : Dns {

    private val dohClient: OkHttpClient by lazy {
        val builder = bootstrapClient?.newBuilder() ?: OkHttpClient.Builder()
        builder
            .dns(Dns.SYSTEM)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .writeTimeout(4, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    override fun lookup(hostname: String): List<InetAddress> {
        try {
            val systemAddresses = Dns.SYSTEM.lookup(hostname)
            if (systemAddresses.isNotEmpty()) {
                return sortIpv4First(systemAddresses)
            }
        } catch (e: Exception) {
            Log.w(TAG, "System DNS lookup failed for $hostname (${e.message}), falling back to DoH")
        }

        val dohResults = resolveDoh(hostname)
        if (dohResults.isNotEmpty()) {
            return sortIpv4First(dohResults)
        }

        throw UnknownHostException("Unable to resolve host '$hostname' via system DNS or DoH fallback")
    }

    private fun sortIpv4First(addresses: List<InetAddress>): List<InetAddress> {
        return addresses.sortedWith(compareBy { if (it is Inet4Address) 0 else 1 })
    }

    private fun resolveDoh(hostname: String): List<InetAddress> {
        val result = mutableListOf<InetAddress>()

        // 1. Try Cloudflare DoH (1.1.1.1 direct IP, avoids recursive DNS resolution)
        runCatching {
            val request = Request.Builder()
                .url("https://1.1.1.1/dns-query?name=$hostname&type=A")
                .header("Accept", "application/dns-json")
                .build()
            dohClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    parseDohAnswers(body, result)
                }
            }
        }.onFailure {
            Log.w(TAG, "Cloudflare DoH failed for $hostname", it)
        }

        if (result.isNotEmpty()) return result

        // 2. Try Google DoH (dns.google / 8.8.8.8)
        runCatching {
            val request = Request.Builder()
                .url("https://dns.google/resolve?name=$hostname&type=A")
                .header("Accept", "application/json")
                .build()
            dohClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    parseDohAnswers(body, result)
                }
            }
        }.onFailure {
            Log.w(TAG, "Google DoH failed for $hostname", it)
        }

        return result
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
                    out.add(address)
                }
            }
        }
    }

    private companion object {
        const val TAG = "Ipv4FirstDns"
    }
}
