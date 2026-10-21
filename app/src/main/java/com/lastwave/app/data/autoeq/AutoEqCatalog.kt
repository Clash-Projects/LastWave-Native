package com.lastwave.app.data.autoeq

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

enum class FormFactor { OVER_EAR, IN_EAR }

/** One headphone result of the AutoEQ database (results/INDEX.md). */
@Serializable
data class AutoEqEntry(
    val name: String,
    val source: String,
    val rig: String? = null,
    /** Percent-encoded path below results/, as written in INDEX.md. */
    val encodedPath: String,
) {
    val formFactor: FormFactor
        get() {
            val folder = URLDecoder.decode(encodedPath.replace("+", "%2B"), "UTF-8").lowercase()
            return if ("in-ear" in folder || "earbud" in folder) FormFactor.IN_EAR else FormFactor.OVER_EAR
        }

    /** File name stem: the last path segment (decoded). */
    val folderName: String
        get() = URLDecoder.decode(encodedPath.substringAfterLast('/').replace("+", "%2B"), "UTF-8")

    val subtitle: String get() = if (rig.isNullOrBlank()) source else "$source · $rig"
}

/** Raw measurement + the lab's target (AutoEQ result CSV), normalised for display. */
data class AutoEqMeasurement(
    val frequencies: DoubleArray,
    val raw: DoubleArray,
    val target: DoubleArray,
)

/**
 * Pure parsing helpers (unit-tested without Android).
 */
object AutoEqIndexParser {
    private val LINE = Regex("""^- \[(.+)]\(\./(.+)\) by (.+?)(?: on (.+))?\s*$""")

    fun parse(markdown: String): List<AutoEqEntry> = markdown.lineSequence().mapNotNull { line ->
        val m = LINE.find(line.trim()) ?: return@mapNotNull null
        AutoEqEntry(
            name = m.groupValues[1].trim(),
            source = m.groupValues[3].trim(),
            rig = m.groupValues[4].trim().ifBlank { null },
            encodedPath = m.groupValues[2].trim(),
        )
    }.toList()

    /** Every query word must appear in name, source or rig (case-insensitive). */
    fun search(entries: List<AutoEqEntry>, query: String, limit: Int = 200): List<AutoEqEntry> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return entries.asSequence()
            .filter { e ->
                val hay = "${e.name} ${e.source} ${e.rig.orEmpty()}".lowercase()
                words.all { it in hay }
            }
            .sortedWith(
                compareBy<AutoEqEntry> { !it.name.lowercase().startsWith(words.first()) }
                    .thenBy { it.name.length }
                    .thenBy { it.name.lowercase() },
            )
            .take(limit)
            .toList()
    }

    fun parseMeasurementCsv(csv: String): AutoEqMeasurement? {
        val lines = csv.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.size < 3) return null
        val header = lines.first().split(',').map { it.trim().lowercase() }
        val iF = header.indexOf("frequency")
        val iRaw = header.indexOf("raw")
        val iTarget = header.indexOf("target")
        if (iF < 0 || iRaw < 0) return null
        val f = ArrayList<Double>(lines.size)
        val raw = ArrayList<Double>(lines.size)
        val target = ArrayList<Double>(lines.size)
        for (line in lines.drop(1)) {
            val cols = line.split(',')
            val freq = cols.getOrNull(iF)?.toDoubleOrNull() ?: continue
            val r = cols.getOrNull(iRaw)?.toDoubleOrNull() ?: continue
            f += freq
            raw += r
            target += if (iTarget >= 0) cols.getOrNull(iTarget)?.toDoubleOrNull() ?: Double.NaN else Double.NaN
        }
        if (f.size < 3) return null
        return AutoEqMeasurement(f.toDoubleArray(), raw.toDoubleArray(), target.toDoubleArray())
    }

    fun encodeSegment(segment: String): String =
        URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
}

/**
 * Downloads the AutoEQ index and individual profiles from the public
 * jaakkopasanen/AutoEq repository (MIT), on demand, and keeps them on disk
 * so a chosen profile keeps working offline.
 */
@Singleton
class AutoEqCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
) {
    private val root get() = File(context.filesDir, "autoeq").apply { mkdirs() }
    private val indexFile get() = File(root, "INDEX.md")
    private val profileDir get() = File(root, "profiles").apply { mkdirs() }
    private val mutex = Mutex()
    @Volatile private var cachedEntries: List<AutoEqEntry>? = null

    /** Cached index immediately when present; refreshed from GitHub when stale or [forceRefresh]. */
    suspend fun entries(forceRefresh: Boolean = false): List<AutoEqEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = indexFile
            val stale = !file.exists() || System.currentTimeMillis() - file.lastModified() > INDEX_MAX_AGE_MS
            if (forceRefresh || stale) {
                runCatching { download("$BASE_URL/INDEX.md") }
                    .onSuccess { body ->
                        if (AutoEqIndexParser.parse(body).size > 100) {
                            file.writeText(body)
                            cachedEntries = null
                        }
                    }
                    .onFailure { if (!file.exists()) throw it else Log.w(TAG, "Index refresh failed, using cache", it) }
            }
            cachedEntries ?: AutoEqIndexParser.parse(file.readText()).also { cachedEntries = it }
        }
    }

    suspend fun search(query: String): List<AutoEqEntry> = AutoEqIndexParser.search(entries(), query)

    /** The lab's ParametricEQ.txt for [entry] (cached on disk after the first download). */
    suspend fun profile(entry: AutoEqEntry): PeqSettings = withContext(Dispatchers.IO) {
        val cache = File(profileDir, key(entry) + ".txt")
        val text = if (cache.exists() && cache.length() > 0) {
            cache.readText()
        } else {
            download(fileUrl(entry, "${entry.folderName} ParametricEQ.txt")).also { cache.writeText(it) }
        }
        ParametricEqText.parse(text) ?: throw IOException("Profile has no filters")
    }

    /** Raw response + target for the chart; null when the lab published no CSV. */
    suspend fun measurement(entry: AutoEqEntry): AutoEqMeasurement? = withContext(Dispatchers.IO) {
        val cache = File(profileDir, key(entry) + ".csv")
        val missing = File(profileDir, key(entry) + ".nocsv")
        if (missing.exists()) return@withContext null
        val text = if (cache.exists() && cache.length() > 0) {
            cache.readText()
        } else {
            runCatching { download(fileUrl(entry, "${entry.folderName}.csv")) }
                .onSuccess { cache.writeText(it) }
                .getOrElse { error ->
                    if (error is NotFoundException) missing.writeText("")
                    return@withContext null
                }
        }
        AutoEqIndexParser.parseMeasurementCsv(text)
    }

    private fun fileUrl(entry: AutoEqEntry, fileName: String): String =
        "$BASE_URL/${entry.encodedPath}/${AutoEqIndexParser.encodeSegment(fileName)}"

    private fun download(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", "LastWave-AutoEQ").build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) throw NotFoundException(url)
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            return response.body?.string() ?: throw IOException("Empty body for $url")
        }
    }

    private fun key(entry: AutoEqEntry): String =
        MessageDigest.getInstance("SHA-1").digest(entry.encodedPath.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(24)

    class NotFoundException(url: String) : IOException("Not found: $url")

    companion object {
        private const val TAG = "AutoEqCatalog"
        const val BASE_URL = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results"
        private const val INDEX_MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000
    }
}

/** Bundled target deltas (generated from AutoEQ's targets/ folder, see assets/autoeq_targets.json). */
@Serializable
data class AutoEqTargetFile(
    val frequencies: List<Double>,
    val targets: List<AutoEqTarget>,
)

@Serializable
data class AutoEqTarget(
    val id: String,
    val name: String,
    val family: String,
    val deltaOverEar: List<Double>,
    val deltaInEar: List<Double>,
) {
    fun delta(formFactor: FormFactor): List<Double> =
        if (formFactor == FormFactor.IN_EAR) deltaInEar else deltaOverEar
}

@Singleton
class AutoEqTargets @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val file: AutoEqTargetFile by lazy {
        runCatching {
            context.assets.open(ASSET).bufferedReader().use { json.decodeFromString(AutoEqTargetFile.serializer(), it.readText()) }
        }.getOrElse {
            Log.w("AutoEqTargets", "Target asset unreadable", it)
            AutoEqTargetFile(emptyList(), emptyList())
        }
    }

    fun byId(id: String): AutoEqTarget? = file.targets.firstOrNull { it.id == id }

    companion object {
        const val ASSET = "autoeq_targets.json"
        /** The lab's own target: the published profile is used as-is. */
        const val DEFAULT_TARGET_ID = "default"
    }
}
