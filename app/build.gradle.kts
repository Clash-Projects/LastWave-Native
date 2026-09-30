import java.io.File
import java.security.KeyStore
import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.lastwave.app"
    compileSdk = 37

    val localProps = Properties().apply {
        val localPropsFile = rootProject.file("local.properties")
        if (localPropsFile.exists()) {
            localPropsFile.inputStream().use { load(it) }
        }
        val envFile = rootProject.file(".env")
        if (envFile.exists()) {
            envFile.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
                    val parts = trimmed.split("=", limit = 2)
                    setProperty(parts[0].trim(), parts[1].trim())
                }
            }
        }
    }

    fun resolveSecret(vararg keys: String): String {
        for (key in keys) {
            val fromEnv = System.getenv(key)
            if (!fromEnv.isNullOrBlank()) return fromEnv.trim().replace("\r", "").replace("\n", "").replace("\"", "").replace("\\", "")
            val fromGradle = project.findProperty(key) as? String
            if (!fromGradle.isNullOrBlank()) return fromGradle.trim().replace("\r", "").replace("\n", "").replace("\"", "").replace("\\", "")
            val fromLocal = localProps.getProperty(key)
            if (!fromLocal.isNullOrBlank()) return fromLocal.trim().replace("\r", "").replace("\n", "").replace("\"", "").replace("\\", "")
            val dotEnv = rootProject.file(".env")
            if (dotEnv.isFile) {
                dotEnv.useLines { lines ->
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.startsWith("$key=")) {
                            val v = trimmed.substringAfter("=").trim().replace("\"", "").replace("\\", "")
                            if (v.isNotBlank()) return v
                        }
                    }
                }
            }
        }
        return ""
    }

    defaultConfig {
        applicationId = "com.lastwave.app"
        minSdk = (project.findProperty("minSdk") as? String)?.toIntOrNull() ?: 29
        targetSdk = 35
        versionCode = 22
        versionName = "4.2.2"

        // Native secrets (addon client lock) live strictly in native .so via
        // SecretsBridge_generated.h (tools/generate_native_secrets.py).
        // No secret fields are exposed in DEX / BuildConfig.

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    // Android 15+ can boot with 16 KB memory pages; all native
                    // libraries must be built/aligned accordingly. Ignored
                    // harmlessly by NDK toolchains that predate the flag.
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DCMAKE_POLICY_VERSION_MINIMUM=3.5",
                )
                cFlags += "-Wl,-z,max-page-size=16384"
                cppFlags += "-Wl,-z,max-page-size=16384"
            }
        }
    }

    fun ensureKeystoreFile(file: File): Boolean {
        if (file.exists() && file.length() > 0) return true
        return try {
            file.parentFile?.mkdirs()
            val javaHome = System.getProperty("java.home")
            val isWindows = org.gradle.internal.os.OperatingSystem.current().isWindows
            val keytoolFile = File(javaHome, if (isWindows) "bin/keytool.exe" else "bin/keytool")
            val keytoolCmd = if (keytoolFile.exists()) keytoolFile.absolutePath else "keytool"
            val cmd = listOf(
                keytoolCmd,
                "-genkeypair",
                "-v",
                "-keystore", file.absolutePath,
                "-storepass", "android",
                "-alias", "androiddebugkey",
                "-keypass", "android",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "10000",
                "-dname", "CN=Android Debug,O=Android,C=US"
            )
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            proc.waitFor() == 0 && file.exists()
        } catch (_: Exception) {
            false
        }
    }

    val base64Key = resolveSecret("SIGNING_KEY")
    val storeFilePath = resolveSecret("RELEASE_STORE_FILE")
    val rawStorePassword = resolveSecret("RELEASE_STORE_PASSWORD", "KEY_STORE_PASSWORD")
    val rawKeyAlias = resolveSecret("RELEASE_KEY_ALIAS", "ALIAS").ifBlank { "release_key" }
    val rawKeyPassword = resolveSecret("RELEASE_KEY_PASSWORD", "KEY_PASSWORD")

    val resolvedReleaseFile: File? = when {
        base64Key.isNotBlank() -> {
            try {
                val cleanedB64 = base64Key.trim().replace("\r", "").replace("\n", "").replace(" ", "")
                val decodedBytes = try {
                    Base64.getMimeDecoder().decode(cleanedB64)
                } catch (_: Exception) {
                    Base64.getDecoder().decode(cleanedB64)
                }
                val keyDir = rootProject.file(".gradle/signing")
                keyDir.mkdirs()
                val targetFile = File(keyDir, "release.keystore")
                targetFile.writeBytes(decodedBytes)
                targetFile
            } catch (_: Exception) {
                null
            }
        }
        storeFilePath.isNotBlank() -> {
            val candidate = file(storeFilePath)
            if (candidate.exists()) candidate
            else {
                val rootCandidate = rootProject.file(storeFilePath)
                if (rootCandidate.exists()) rootCandidate else null
            }
        }
        else -> null
    }

    var effectiveStorePassword = rawStorePassword
    var effectiveKeyAlias = rawKeyAlias
    var effectiveKeyPassword = rawKeyPassword.ifBlank { rawStorePassword }
    var isReleaseKeystoreValid = false

    if (resolvedReleaseFile != null && resolvedReleaseFile.exists() && resolvedReleaseFile.length() > 0) {
        val candidatePasswords = listOf(
            rawStorePassword,
            "3w6gLAaDj0oTcTxHNkRk",
            "android",
            ""
        ).filter { it.isNotBlank() || rawStorePassword.isEmpty() }.distinct()

        for (cand in candidatePasswords) {
            for (type in listOf("PKCS12", "JKS")) {
                try {
                    val ks = KeyStore.getInstance(type)
                    resolvedReleaseFile.inputStream().use { stream ->
                        ks.load(stream, cand.toCharArray())
                    }
                    effectiveStorePassword = cand
                    val aliases = ks.aliases().toList()
                    if (aliases.isNotEmpty() && !aliases.contains(effectiveKeyAlias)) {
                        effectiveKeyAlias = aliases.first()
                    }
                    if (effectiveKeyPassword.isBlank()) {
                        effectiveKeyPassword = cand
                    }
                    isReleaseKeystoreValid = true
                    break
                } catch (_: Exception) {}
            }
            if (isReleaseKeystoreValid) break
        }
        if (!isReleaseKeystoreValid && rawStorePassword.isNotBlank()) {
            isReleaseKeystoreValid = true
        }
    }

    signingConfigs {
        getByName("debug").apply {
            val defaultDebugStore = storeFile ?: File(System.getProperty("user.home"), ".android/debug.keystore")
            if (!ensureKeystoreFile(defaultDebugStore)) {
                val fallbackDebug = rootProject.file(".gradle/signing/fallback-debug.keystore")
                ensureKeystoreFile(fallbackDebug)
                storeFile = fallbackDebug
            } else {
                storeFile = defaultDebugStore
            }
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }

        if (isReleaseKeystoreValid && resolvedReleaseFile != null) {
            create("release_config") {
                storeFile = resolvedReleaseFile
                storePassword = effectiveStorePassword
                keyAlias = effectiveKeyAlias
                keyPassword = effectiveKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    val releaseSigning = if (isReleaseKeystoreValid && signingConfigs.findByName("release_config") != null) {
        signingConfigs.getByName("release_config")
    } else {
        signingConfigs.getByName("debug")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = releaseSigning
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("rawRelease") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            // Raw variant — no code/resource shrinking, no ProGuard/R8
            signingConfig = releaseSigning
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Required by org.jellyfin.media3:media3-ffmpeg-decoder AAR metadata.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
        prefab = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
        ignoreWarnings = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-cast-framework:22.3.1")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.palette)

    // Home-screen "Now Playing" widget (Glance — Compose-style APIs over
    // RemoteViews), driven by the same MediaController access the local
    // scrobbler (MediaScrobbleListenerService) already holds.
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")

    // Required even in a Compose-only app: Theme.Material3.DayNight.NoActionBar
    // (used as the AndroidManifest/splash theme parent in themes.xml) is an XML
    // style resource shipped by this artifact. androidx.compose.material3 is
    // Compose-only Kotlin and contributes no AAPT-resolvable style/ resources,
    // so without this dependency that parent can never be found by the linker.
    implementation(libs.material)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.kyant.backdrop)
    implementation(libs.kyant.shapes)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.androidx.palette)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.coil.compose)
    implementation(libs.lyrics.ui)
    implementation(libs.lyrics.core)
    // Installs the baseline profiles bundled inside Compose (and other
    // androidx) AARs so hot UI paths are AOT-compiled on device instead of
    // running through JIT on first use — a large, zero-code smoothness win
    // for scrolling and animations in release builds.
    implementation(libs.androidx.profileinstaller)

    // Native in-app audio playback, background service, system media
    // controls, Bluetooth/headset controls and a MediaController-backed UI.
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.2.1")
    // Segmented provider-module path: DASH chunk source + CDM decryption.
    // Pinned to the same 1.2.1 line as exoplayer/hls to avoid binary mismatch.
    implementation("androidx.media3:media3-exoplayer-dash:1.2.1")
    // MediaBrowserServiceCompat/MediaSessionCompat bridge used by Android
    // Auto to browse the LastWave library and control the same player.
    implementation("androidx.media:media:1.7.0")

    // GPLv3 Media3-matched FFmpeg software decoder (distribution must comply).
    // The renderer factory prefers FFmpeg for every codec it supports so all
    // devices decode through one deterministic, OEM-bug-free path; platform
    // decoders remain as automatic fallbacks.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.2.1+1")

    // Core library desugaring required by the FFmpeg decoder AAR metadata.
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // Low-latency native output. Version 1.10 remains API-compatible with the
    // requested Oboe 1.8+ baseline and exposes its CMake target through Prefab.
    implementation("com.google.oboe:oboe:1.10.0")

    // Metadata/search remains local InnerTube/NewPipe functionality; playback
    // resolves through InnerTubeX first and retains NewPipe as a fallback.
    implementation(libs.newpipe.extractor)

    // InnerTubeX is invoked through the compatibility adapter because its
    // current release is built with a newer Kotlin metadata version than the
    // app. Runtime-only keeps the app compiler on its existing Kotlin line.
    runtimeOnly(libs.innertubex)
    runtimeOnly("io.ktor:ktor-client-cio:3.5.2")

    // Unit Testing dependencies
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.truth:truth:1.4.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("io.mockk:mockk:1.13.10")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    // Bit-perfect USB exclusive output: audio_engine UsbAudioDriver.
    implementation(project(":audio:decent-usb-audio-driver"))
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.add("androidx.compose.foundation.ExperimentalFoundationApi")
    }
}

configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") {
            useVersion(libs.versions.kotlin.get())
        }
    }
}

tasks.withType<Test> {
    maxHeapSize = "2048m"
}

// Generate native secrets header before CMake configures.
// CI provides ADDON_CLIENT_SECRET / RELEASE_CERT_SHA256 via env/secrets.
val generateNativeSecrets by tasks.registering(Exec::class) {
    workingDir = rootProject.projectDir
    val py = org.gradle.internal.os.OperatingSystem.current().let {
        if (it.isWindows) "python" else "python3"
    }
    commandLine(py, "tools/generate_native_secrets.py")
    // Never fail public builds when secrets absent; script emits empty header.
    isIgnoreExitValue = true
}
tasks.matching { it.name.startsWith("preBuild") || it.name.startsWith("configureCMake") }.configureEach {
    dependsOn(generateNativeSecrets)
}

