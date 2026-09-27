package com.lastwave.app.data.lossless

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * JNI bridge to native secrets stored in compiled ARM code.
 *
 * Only live surface is in-native addon-request signing (backed by
 * ADDON_CLIENT_SECRET baked at CI time). The legacy native URL / API-key /
 * module-key getters were unconditional empty stubs with zero callers and
 * have been removed.
 */
@Singleton
class NativeSecrets @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Signs an addon request URL using HMAC-SHA256 calculated inside native C++ memory.
     * The raw secret is never held in Java heap / DEX strings, preventing Frida / memory dumps.
     * Returns Pair(timestamp, signatureHex) or null if signature/environment check failed.
     */
    fun signAddonRequest(url: String, method: String): Pair<String, String>? {
        val res = nativeSignAddonRequest(context, url, method)
        if (res.isNullOrBlank() || !res.contains("|")) return null
        val parts = res.split("|", limit = 2)
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return null
        return Pair(parts[0], parts[1])
    }

    companion object {
        init {
            System.loadLibrary("lastwave_audio")
        }

        @JvmStatic
        private external fun nativeSignAddonRequest(context: Context, urlStr: String, method: String): String
    }
}
