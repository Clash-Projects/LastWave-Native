/**
 * SecretsBridge.cpp — Native secret vault for LastWave.
 *
 * Real secrets are injected at CI / build time via `SecretsBridge_generated.h`
 * (gitignored, produced by `tools/generate_native_secrets.py` from ADDON_CLIENT_SECRET).
 *
 * Protection layers:
 *   - The secret is double-encrypted at build time using a keystream derived from
 *     the release keystore's SHA-256 certificate fingerprint + salt.
 *   - The raw secret NEVER enters Java bytecode / DEX / memory.
 *   - No JNI getter returns the secret; signing is executed entirely inside native C++.
 *   - APK signing cert gate: re-signed / patched APKs fail signature verification,
 *     and if bypassed, produce corrupted keys resulting in invalid signatures.
 *   - Anti-debugging / ptrace detection (`/proc/self/status` TracerPid).
 *   - Caller package verification (`com.lastwave.app`).
 *   - Strict volatile stack scrubbing of secret buffers immediately after HMAC calculation.
 */

#include <jni.h>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>
#include <chrono>
#include <iomanip>
#include <sstream>
#include <cstdio>
#include <cstdlib>

// Generated secrets (CI only). Public clones build without this file.
#if __has_include("SecretsBridge_generated.h")
#include "SecretsBridge_generated.h"
#else
constexpr char EXPECTED_CERT_PREFIX[] = "PLACEHOLDER_REPLACE_WITH_YOUR_CERT_SHA256";
static const uint8_t CLIENT_SECRET_SALT[16] = {0};
static constexpr size_t CLIENT_SECRET_TOTAL_LEN = 0;
static constexpr int CLIENT_SECRET_FRAGMENT_COUNT = 0;
struct Fragment { const uint8_t* data; uint8_t len; uint8_t mask; };
static const Fragment CLIENT_SECRET_FRAGMENTS[] = {};
#endif

namespace {

// Volatile sink to defeat dead code elimination / constant folding.
static volatile uint8_t g_sink = 0;

namespace crypto {

static inline uint32_t ror(uint32_t x, uint32_t n) { return (x >> n) | (x << (32 - n)); }

static void sha256_process_block(uint32_t state[8], const uint8_t block[64]) {
    static const uint32_t K[64] = {
        0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
        0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
        0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
        0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
        0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
        0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
        0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
        0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
    };

    uint32_t W[64];
    for (int i = 0; i < 16; i++) {
        W[i] = ((uint32_t)block[i * 4] << 24) |
               ((uint32_t)block[i * 4 + 1] << 16) |
               ((uint32_t)block[i * 4 + 2] << 8) |
               ((uint32_t)block[i * 4 + 3]);
    }
    for (int i = 16; i < 64; i++) {
        uint32_t s0 = ror(W[i - 15], 7) ^ ror(W[i - 15], 18) ^ (W[i - 15] >> 3);
        uint32_t s1 = ror(W[i - 2], 17) ^ ror(W[i - 2], 19) ^ (W[i - 2] >> 10);
        W[i] = W[i - 16] + s0 + W[i - 7] + s1;
    }

    uint32_t a = state[0], b = state[1], c = state[2], d = state[3];
    uint32_t e = state[4], f = state[5], g = state[6], h = state[7];

    for (int i = 0; i < 64; i++) {
        uint32_t S1 = ror(e, 6) ^ ror(e, 11) ^ ror(e, 25);
        uint32_t ch = (e & f) ^ ((~e) & g);
        uint32_t temp1 = h + S1 + ch + K[i] + W[i];
        uint32_t S0 = ror(a, 2) ^ ror(a, 13) ^ ror(a, 22);
        uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
        uint32_t temp2 = S0 + maj;

        h = g;
        g = f;
        f = e;
        e = d + temp1;
        d = c;
        c = b;
        b = a;
        a = temp1 + temp2;
    }

    state[0] += a; state[1] += b; state[2] += c; state[3] += d;
    state[4] += e; state[5] += f; state[6] += g; state[7] += h;
}

static std::vector<uint8_t> sha256(const uint8_t* data, size_t len) {
    uint32_t state[8] = {
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
        0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
    };
    uint64_t total_bits = (uint64_t)len * 8;
    size_t full_blocks = len / 64;
    for (size_t i = 0; i < full_blocks; i++) {
        sha256_process_block(state, data + i * 64);
    }
    uint8_t tail[128];
    size_t rem = len % 64;
    std::memcpy(tail, data + full_blocks * 64, rem);
    tail[rem++] = 0x80;
    size_t pad_to = (rem <= 56) ? 56 : 120;
    std::memset(tail + rem, 0, pad_to - rem);
    for (int i = 7; i >= 0; i--) {
        tail[pad_to + (7 - i)] = (uint8_t)(total_bits >> (i * 8));
    }
    for (size_t i = 0; i < (pad_to + 8) / 64; i++) {
        sha256_process_block(state, tail + i * 64);
    }
    std::vector<uint8_t> out(32);
    for (int i = 0; i < 8; i++) {
        out[i * 4]     = (uint8_t)(state[i] >> 24);
        out[i * 4 + 1] = (uint8_t)(state[i] >> 16);
        out[i * 4 + 2] = (uint8_t)(state[i] >> 8);
        out[i * 4 + 3] = (uint8_t)(state[i]);
    }
    return out;
}

static std::vector<uint8_t> hmac_sha256(const uint8_t* key, size_t key_len, const uint8_t* msg, size_t msg_len) {
    uint8_t k_block[64] = {0};
    if (key_len > 64) {
        auto h = sha256(key, key_len);
        std::memcpy(k_block, h.data(), 32);
    } else {
        std::memcpy(k_block, key, key_len);
    }
    std::vector<uint8_t> i_buf(64 + msg_len);
    uint8_t opad[64 + 32];
    for (int i = 0; i < 64; i++) {
        i_buf[i] = k_block[i] ^ 0x36;
        opad[i]  = k_block[i] ^ 0x5c;
    }
    std::memcpy(i_buf.data() + 64, msg, msg_len);
    auto inner_hash = sha256(i_buf.data(), i_buf.size());
    std::memcpy(opad + 64, inner_hash.data(), 32);
    return sha256(opad, 64 + 32);
}

} // namespace crypto

static void deriveKeystream(
    const uint8_t* key_bytes, size_t key_len,
    const uint8_t* salt, size_t salt_len,
    uint8_t* out, size_t length
) {
    size_t generated = 0;
    uint32_t counter = 0;
    while (generated < length) {
        std::vector<uint8_t> msg(salt_len + 4);
        std::memcpy(msg.data(), salt, salt_len);
        msg[salt_len]     = static_cast<uint8_t>((counter >> 24) & 0xFF);
        msg[salt_len + 1] = static_cast<uint8_t>((counter >> 16) & 0xFF);
        msg[salt_len + 2] = static_cast<uint8_t>((counter >> 8) & 0xFF);
        msg[salt_len + 3] = static_cast<uint8_t>(counter & 0xFF);
        auto block = crypto::hmac_sha256(key_bytes, key_len, msg.data(), msg.size());
        size_t to_copy = std::min(block.size(), length - generated);
        std::memcpy(out + generated, block.data(), to_copy);
        generated += to_copy;
        counter++;
    }
}

static bool isDebuggable(JNIEnv* env, jobject context) {
    jclass ctxCls = env->GetObjectClass(context);
    if (!ctxCls) return false;
    jmethodID getAppInfo = env->GetMethodID(ctxCls, "getApplicationInfo",
        "()Landroid/content/pm/ApplicationInfo;");
    if (!getAppInfo) return false;
    jobject appInfo = env->CallObjectMethod(context, getAppInfo);
    if (!appInfo || env->ExceptionCheck()) { env->ExceptionClear(); return false; }
    jclass appCls = env->GetObjectClass(appInfo);
    jfieldID flagsF = env->GetFieldID(appCls, "flags", "I");
    if (!flagsF) return false;
    jint flags = env->GetIntField(appInfo, flagsF);
    const jint FLAG_DEBUGGABLE = 0x00000002;
    return (flags & FLAG_DEBUGGABLE) != 0;
}

static bool isBeingDebugged() {
    FILE* fp = fopen("/proc/self/status", "r");
    if (!fp) return false;
    char line[128];
    int tracerPid = 0;
    while (fgets(line, sizeof(line), fp)) {
        if (strncmp(line, "TracerPid:", 10) == 0) {
            tracerPid = atoi(line + 10);
            break;
        }
    }
    fclose(fp);
    return tracerPid != 0;
}

/**
 * Verifies that the caller is genuine com.lastwave.app signed by the expected certificate.
 * Extracts the 32-byte SHA-256 certificate digest into outCertSha256.
 */
static bool verifyCallerSignature(JNIEnv* env, jobject context, uint8_t outCertSha256[32]) {
    if (!context) return false;

    jclass contextClass = env->GetObjectClass(context);
    if (!contextClass) return false;

    jmethodID getPackageName = env->GetMethodID(contextClass, "getPackageName", "()Ljava/lang/String;");
    if (!getPackageName) return false;
    auto packageName = (jstring)env->CallObjectMethod(context, getPackageName);
    if (!packageName) return false;

    // Package allowlist: only com.lastwave.app may call.
    const char* pkgChars = env->GetStringUTFChars(packageName, nullptr);
    bool pkgOk = pkgChars && strcmp(pkgChars, "com.lastwave.app") == 0;
    if (pkgChars) env->ReleaseStringUTFChars(packageName, pkgChars);
    if (!pkgOk) return false;

    bool isPlaceholder = (strncmp(EXPECTED_CERT_PREFIX, "PLACEHOLDER", 11) == 0);
    if (!isPlaceholder) {
        if (isDebuggable(env, context) || isBeingDebugged()) {
            return false;
        }
    } else {
        // Fallback key for placeholder dev builds
        static const char devFallback[] = "LASTWAVE_DEV_FALLBACK_KEY";
        auto devHash = crypto::sha256(reinterpret_cast<const uint8_t*>(devFallback), sizeof(devFallback) - 1);
        std::memcpy(outCertSha256, devHash.data(), 32);
        return true;
    }

    jmethodID getPackageManager = env->GetMethodID(contextClass, "getPackageManager", "()Landroid/content/pm/PackageManager;");
    if (!getPackageManager) return false;
    jobject pm = env->CallObjectMethod(context, getPackageManager);
    if (!pm) return false;

    jclass pmClass = env->GetObjectClass(pm);
    jmethodID getPackageInfo = env->GetMethodID(pmClass, "getPackageInfo", "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;");
    if (!getPackageInfo) return false;

    jobject pkgInfo = env->CallObjectMethod(pm, getPackageInfo, packageName, (jint)0x08000000);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        pkgInfo = env->CallObjectMethod(pm, getPackageInfo, packageName, (jint)64);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return false;
        }
    }
    if (!pkgInfo) return false;

    jclass pkgInfoClass = env->GetObjectClass(pkgInfo);
    jobjectArray signaturesArray = nullptr;
    jfieldID signingInfoField = env->GetFieldID(pkgInfoClass, "signingInfo", "Landroid/content/pm/SigningInfo;");
    if (signingInfoField && !env->ExceptionCheck()) {
        jobject signingInfo = env->GetObjectField(pkgInfo, signingInfoField);
        if (signingInfo) {
            jclass sigInfoClass = env->GetObjectClass(signingInfo);
            jmethodID getSigningCerts = env->GetMethodID(sigInfoClass, "getApkContentsSigners", "()[Landroid/content/pm/Signature;");
            if (getSigningCerts) {
                signaturesArray = (jobjectArray)env->CallObjectMethod(signingInfo, getSigningCerts);
            }
        }
    }
    if (env->ExceptionCheck()) env->ExceptionClear();

    if (!signaturesArray || env->GetArrayLength(signaturesArray) == 0) {
        jfieldID sigField = env->GetFieldID(pkgInfoClass, "signatures", "[Landroid/content/pm/Signature;");
        if (sigField) {
            signaturesArray = (jobjectArray)env->GetObjectField(pkgInfo, sigField);
        }
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (!signaturesArray || env->GetArrayLength(signaturesArray) == 0) return false;

    jobject sig = env->GetObjectArrayElement(signaturesArray, 0);
    if (!sig) return false;

    jclass sigClass = env->GetObjectClass(sig);
    jmethodID toByteArray = env->GetMethodID(sigClass, "toByteArray", "()[B");
    auto certBytes = (jbyteArray)env->CallObjectMethod(sig, toByteArray);
    if (!certBytes) return false;

    jsize certByteLen = env->GetArrayLength(certBytes);
    auto* rawCert = env->GetByteArrayElements(certBytes, nullptr);
    if (!rawCert) return false;

    auto hash = crypto::sha256(reinterpret_cast<const uint8_t*>(rawCert), static_cast<size_t>(certByteLen));
    env->ReleaseByteArrayElements(certBytes, rawCert, JNI_ABORT);

    char hexBuf[65] = {};
    for (size_t i = 0; i < 32; i++) {
        snprintf(hexBuf + i * 2, 3, "%02x", hash[i]);
    }

    size_t expLen = strlen(EXPECTED_CERT_PREFIX);
    if (expLen != 64) return false;
    volatile int diff = 0;
    for (size_t i = 0; i < 64; i++) {
        diff |= (hexBuf[i] ^ EXPECTED_CERT_PREFIX[i]);
    }
    std::memset(hexBuf, 0, sizeof(hexBuf));

    if (diff != 0) return false;

    std::memcpy(outCertSha256, hash.data(), 32);
    return true;
}

static bool parseAddonUrl(const std::string& url, std::string& path, std::string& token) {
    size_t scheme = url.find("://");
    size_t pathStart = (scheme != std::string::npos) ? url.find('/', scheme + 3) : url.find('/');
    if (pathStart == std::string::npos) {
        path = "/";
    } else {
        size_t query = url.find_first_of("?#", pathStart);
        if (query == std::string::npos) path = url.substr(pathStart);
        else path = url.substr(pathStart, query - pathStart);
    }
    size_t aPos = path.find("/a/");
    if (aPos == std::string::npos) return false;
    size_t tokStart = aPos + 3;
    size_t tokEnd = path.find('/', tokStart);
    if (tokEnd == std::string::npos) token = path.substr(tokStart);
    else token = path.substr(tokStart, tokEnd - tokStart);
    return !token.empty();
}

static size_t reconstructClientSecret(
    const uint8_t certHash[32],
    uint8_t outSecret[128]
) {
    if (CLIENT_SECRET_FRAGMENT_COUNT <= 0 || CLIENT_SECRET_TOTAL_LEN == 0 || CLIENT_SECRET_TOTAL_LEN > 128) {
        return 0;
    }

    uint8_t ciphertext[128] = {0};
    size_t offset = 0;

    volatile int n = CLIENT_SECRET_FRAGMENT_COUNT ^ 0x5A;
    for (int i = 0; i < (n ^ 0x5A); i++) {
        uint8_t len = CLIENT_SECRET_FRAGMENTS[i].len;
        uint8_t mask = CLIENT_SECRET_FRAGMENTS[i].mask;
        g_sink ^= static_cast<uint8_t>(len + mask + (i * 31));
        for (uint8_t j = 0; j < len; j++) {
            if (offset < sizeof(ciphertext)) {
                ciphertext[offset++] = static_cast<uint8_t>(CLIENT_SECRET_FRAGMENTS[i].data[j] ^ mask ^ (g_sink & 0x00));
            }
        }
    }

    uint8_t keystream[128] = {0};
    deriveKeystream(certHash, 32, CLIENT_SECRET_SALT, 16, keystream, CLIENT_SECRET_TOTAL_LEN);

    for (size_t i = 0; i < CLIENT_SECRET_TOTAL_LEN; i++) {
        outSecret[i] = ciphertext[i] ^ keystream[i];
    }

    std::memset(ciphertext, 0, sizeof(ciphertext));
    std::memset(keystream, 0, sizeof(keystream));
    return CLIENT_SECRET_TOTAL_LEN;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_lastwave_app_data_lossless_NativeSecrets_nativeSignAddonRequest(
    JNIEnv* env,
    jclass,
    jobject context,
    jstring urlStr,
    jstring methodStr) {
    uint8_t certHash[32] = {0};
    if (!verifyCallerSignature(env, context, certHash)) {
        return env->NewStringUTF("");
    }

    if (!urlStr) return env->NewStringUTF("");
    const char* rawUrl = env->GetStringUTFChars(urlStr, nullptr);
    std::string url(rawUrl ? rawUrl : "");
    if (rawUrl) env->ReleaseStringUTFChars(urlStr, rawUrl);

    std::string path, token;
    if (!parseAddonUrl(url, path, token)) {
        return env->NewStringUTF("");
    }

    std::string method = "GET";
    if (methodStr) {
        const char* rawMethod = env->GetStringUTFChars(methodStr, nullptr);
        if (rawMethod) {
            method = rawMethod;
            env->ReleaseStringUTFChars(methodStr, rawMethod);
        }
    }
    for (char& c : method) c = static_cast<char>(toupper(static_cast<unsigned char>(c)));

    auto now = std::chrono::duration_cast<std::chrono::seconds>(
        std::chrono::system_clock::now().time_since_epoch()
    ).count();
    std::string ts = std::to_string(now);

    uint8_t secretBuf[128] = {0};
    size_t secretLen = reconstructClientSecret(certHash, secretBuf);
    std::memset(certHash, 0, sizeof(certHash));

    if (secretLen == 0) {
        return env->NewStringUTF("");
    }

    std::string message = ts + "\n" + method + "\n" + path + "\n" + token;
    auto signature = crypto::hmac_sha256(
        secretBuf, secretLen,
        reinterpret_cast<const uint8_t*>(message.data()), message.size()
    );

    // Volatile scrub secret buffer immediately after use
    volatile uint8_t* p = secretBuf;
    for (size_t i = 0; i < sizeof(secretBuf); i++) p[i] = 0;

    std::stringstream ss;
    for (uint8_t b : signature) {
        ss << std::hex << std::setw(2) << std::setfill('0') << static_cast<int>(b);
    }
    std::string result = ts + "|" + ss.str();
    return env->NewStringUTF(result.c_str());
}
