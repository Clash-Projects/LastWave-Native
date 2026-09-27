package com.lastwave.app.data.plugin

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decrypts provider-module code files (LWP1/LWP2 envelope).
 *
 * LWP1 (legacy): magic "LWP1"(4) + nonce(12) + ct+tag, AES-256-GCM no AAD.
 * LWP2 (current): magic "LWP2"(4) + nonce(12) + ct+tag, AES-256-GCM with
 *   AAD = entryName UTF-8 (binds ciphertext to file path, blocks swap).
 *
 * The native provider-module key provisioning (NativeModuleKey /
 * PROVIDER_MODULE_KEY) was removed as dead code: its JNI getters were
 * unconditional empty stubs, so no build could ever provision a key.
 * Encrypted configs are therefore rejected exactly as before (loadKey
 * returns null); plaintext module handling is untouched. keyId
 * (sha256(key)[:16 hex]) must constant-time match manifest's enc.keyId
 * or package is rejected before execution.
 */
@Singleton
class ModuleCrypto @Inject constructor() {
    /** No provisioned provider key: encrypted module configs are rejected. */
    fun appKey(): ByteArray? = null
    companion object {
        private val MAGIC_LWP1 = byteArrayOf('L'.code.toByte(), 'W'.code.toByte(), 'P'.code.toByte(), '1'.code.toByte())
        private val MAGIC_LWP2 = byteArrayOf('L'.code.toByte(), 'W'.code.toByte(), 'P'.code.toByte(), '2'.code.toByte())
        private const val NONCE_LEN = 12
        private const val TAG_BITS = 128
        private const val MAX_PLAINTEXT = 8 * 1024 * 1024 // 8MB cap: reject zip-bombs
    }

    /** Returns key only when its id constant-time equals [expectedKeyId]. */
    fun loadKey(expectedKeyId: String?): ByteArray? {
        if (expectedKeyId.isNullOrBlank()) return null
        val key = appKey() ?: return null
        val actual = keyIdOf(key)
        return if (constantTimeEquals(actual, expectedKeyId.trim().lowercase())) {
            key
        } else {
            key.fill(0)
            null
        }
    }

    fun keyIdOf(key: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(key)
            .joinToString("") { "%02x".format(it) }.take(16)

    /** LWP2 encrypt with AAD binding (entryName). Null AAD = LWP1 legacy. */
    fun encrypt(plaintext: ByteArray, key: ByteArray, aadName: String? = null): ByteArray {
        require(key.size == 32) { "Module key must be 32 bytes" }
        require(plaintext.isNotEmpty() && plaintext.size <= MAX_PLAINTEXT) { "Bad plaintext size" }
        val nonce = ByteArray(NONCE_LEN).apply { SecureRandom().nextBytes(this) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            if (!aadName.isNullOrEmpty()) updateAAD(aadName.toByteArray(Charsets.UTF_8))
        }
        val ct = cipher.doFinal(plaintext)
        val magic = if (aadName.isNullOrEmpty()) MAGIC_LWP1 else MAGIC_LWP2
        return magic + nonce + ct
    }

    /** Auto-detects LWP1/LWP2. For LWP2, [aadName] must equal encrypt-time entry name. */
    fun decrypt(envelope: ByteArray, key: ByteArray, aadName: String? = null): ByteArray {
        require(key.size == 32) { "Module key must be 32 bytes" }
        require(envelope.size > 4 + NONCE_LEN + 16) { "Truncated module envelope" }
        require(envelope.size <= MAX_PLAINTEXT + 4 + NONCE_LEN + 16 + 64) { "Envelope too large" }
        val isLwp1 = envelope[0] == MAGIC_LWP1[0] && envelope[1] == MAGIC_LWP1[1] &&
            envelope[2] == MAGIC_LWP1[2] && envelope[3] == MAGIC_LWP1[3]
        val isLwp2 = envelope[0] == MAGIC_LWP2[0] && envelope[1] == MAGIC_LWP2[1] &&
            envelope[2] == MAGIC_LWP2[2] && envelope[3] == MAGIC_LWP2[3]
        require(isLwp1 || isLwp2) { "Not an LWP envelope" }
        if (isLwp2 && aadName.isNullOrEmpty()) error("LWP2 requires entry name AAD")
        val nonce = envelope.copyOfRange(4, 4 + NONCE_LEN)
        val ct = envelope.copyOfRange(4 + NONCE_LEN, envelope.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            if (isLwp2) updateAAD(aadName!!.toByteArray(Charsets.UTF_8))
        }
        val pt = cipher.doFinal(ct) // throws on wrong key / tamper / AAD mismatch
        require(pt.isNotEmpty() && pt.size <= MAX_PLAINTEXT) { "Bad decrypted size" }
        return pt
    }

    /** Tries LWP2 with AAD first, falls back to LWP1 for legacy packages. */
    fun decryptEntry(envelope: ByteArray, key: ByteArray, entryName: String): ByteArray {
        return try {
            decrypt(envelope, key, entryName)
        } catch (_: Throwable) {
            // Legacy LWP1 has no AAD; try without.
            decrypt(envelope, key, null)
        }
    }

    fun decryptBase64(envelopeB64: String, key: ByteArray): ByteArray =
        decrypt(Base64.decode(envelopeB64, Base64.DEFAULT), key)

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}
