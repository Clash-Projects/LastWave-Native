package com.lastwave.app.data.plugin

import android.util.Base64
import com.lastwave.app.BuildConfig
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decrypts provider-module code files (LWP1 envelope).
 *
 * Envelope: magic "LWP1"(4) + nonce(12) + ciphertext + GCM tag(16),
 * AES-256-GCM. The key lives in the app build (PROVIDER_MODULE_KEY, same
 * obfuscated-BuildConfig pattern as the other secrets) — never in the repo
 * in plaintext and never inside the .lwp. keyId (sha256(key)[:16]) must match
 * the manifest's enc.keyId or the package is rejected before any execution.
 */
@Singleton
class ModuleCrypto @Inject constructor() {

    /** Null when the build wasn't provisioned with a module key. */
    fun appKey(): ByteArray? {
        val rawString = decodeSecretBytes(
            BuildConfig.PROVIDER_MODULE_KEY_BYTES,
            BuildConfig.SECRET_MASK_BYTES,
        )
        if (rawString.isBlank()) return null
        val decoded = try {
            Base64.decode(rawString.trim(), Base64.DEFAULT)
        } catch (_: Throwable) {
            rawString.toByteArray(Charsets.ISO_8859_1)
        }
        if (decoded.size == 32) return decoded
        val rawBytes = rawString.toByteArray(Charsets.ISO_8859_1)
        if (rawBytes.size == 32) return rawBytes
        return null
    }

    fun appKeyId(): String? {
        val key = appKey() ?: return null
        return MessageDigest.getInstance("SHA-256").digest(key)
            .joinToString("") { "%02x".format(it) }.take(16)
    }

    fun isKeyProvisioned(): Boolean = appKey() != null

    /** Throws on wrong key / tampered bytes (GCM auth). */
    fun decrypt(envelope: ByteArray, key: ByteArray): ByteArray {
        require(key.size == 32) { "Module key must be 32 bytes" }
        require(envelope.size > 4 + 12 + 16) { "Truncated module envelope" }
        require(envelope[0] == 'L'.code.toByte() && envelope[1] == 'W'.code.toByte() &&
            envelope[2] == 'P'.code.toByte() && envelope[3] == '1'.code.toByte()) {
            "Not an LWP1 envelope"
        }
        val nonce = envelope.copyOfRange(4, 16)
        val ct = envelope.copyOfRange(16, envelope.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        }
        return cipher.doFinal(ct)
    }

    fun decryptBase64(envelopeB64: String, key: ByteArray): ByteArray =
        decrypt(Base64.decode(envelopeB64, Base64.DEFAULT), key)

    private fun decodeSecretBytes(data: ByteArray, mask: ByteArray): String {
        if (data.isEmpty() || mask.isEmpty()) return ""
        val decoded = ByteArray(data.size) { i ->
            (data[i].toInt() xor mask[i % mask.size].toInt()).toByte()
        }
        return String(decoded, Charsets.ISO_8859_1)
    }
}
