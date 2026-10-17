package com.lastwave.app.data.lyrics

import com.lastwave.app.data.artwork.awaitSuccessfulBodyOrNull
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Lightweight, on-demand lyric translation and romanization.
 *
 * Translation models are deliberately not installed on the device. A model for
 * every language would cost tens of megabytes each; translated lyric text is
 * normally only a few kilobytes. Requests are batched and the compact result is
 * kept in a bounded cache under the app's cache directory, so Android may
 * reclaim it and the feature can never grow without limit.
 *
 * Results are returned as *sub-lines*: one entry per input line, aligned by
 * index, with `null` wherever there is nothing worth drawing (blank lines, and
 * lines whose transformed text is identical to the original). The player draws
 * each non-null entry underneath its line instead of replacing anything, so
 * timing, karaoke sweeps and word highlights keep working untouched.
 */
object LyricsTranslation {
    sealed interface Result {
        data class Translated(
            val subLines: List<String?>,
            val sourceLanguage: String,
            val fromCache: Boolean,
        ) : Result

        data class SameLanguage(val language: String) : Result
        data object Unavailable : Result
    }

    sealed interface RomanizationResult {
        data class Romanized(
            val subLines: List<String?>,
            val sourceLanguage: String,
            val fromCache: Boolean,
        ) : RomanizationResult

        data object AlreadyRomanized : RomanizationResult
        data object Unavailable : RomanizationResult
    }

    private const val ENDPOINT = "https://translate.googleapis.com/translate_a/single"
    private const val INPUT_TOOLS_ENDPOINT = "https://inputtools.google.com/request"
    private const val CACHE_VERSION = 1
    private const val CACHE_DIRECTORY = "lyrics_translation_v1"
    private const val MAX_CACHE_BYTES = 2L * 1024L * 1024L
    private const val MAX_BATCH_CHARS = 3_500
    private const val MAX_PARALLEL_REQUESTS = 2
    private const val USER_AGENT = "LastWave/1.0"
    // Input Tools changes ASCII digits to the destination script, so match the
    // private-use wrapper rather than assuming the counter remains ASCII.
    private val markerRegex = Regex("\\uE000[^\\uE001]*\\uE001")
    private val json = Json { ignoreUnknownKeys = true }
    private val diskMutex = Mutex()
    private val memory = object {
        private val entries = object : LinkedHashMap<String, CachedTranslation>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, CachedTranslation>) = size > 12
        }

        @Synchronized fun get(key: String): CachedTranslation? = entries[key]

        @Synchronized fun put(key: String, value: CachedTranslation) {
            entries[key] = value
        }
    }

    /**
     * Where translated lyrics are kept between runs — the app's cache
     * directory. Null keeps them in memory only.
     */
    @Volatile
    var cacheDir: File? = null

    private val client by lazy {
        OkHttpClient.Builder()
            .callTimeout(12, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    @Serializable
    internal data class CachedTranslation(
        val version: Int = CACHE_VERSION,
        val sourceLanguage: String,
        val targetLanguage: String,
        val texts: List<String>,
    )

    internal data class TextSlot(
        val lineIndex: Int,
        val text: String,
        val sectionHeader: Boolean,
    )

    internal data class Batch(
        val slots: List<TextSlot>,
        val payload: String,
    )

    internal data class BatchAnswer(
        val translations: List<String>,
        val sourceLanguage: String,
        val sourceWeight: Int,
    )

    suspend fun translate(
        trackId: String,
        lines: List<LyricLine>,
        targetLanguageTag: String,
    ): Result {
        // Sent as given rather than reduced to a base language: zh-CN and
        // zh-TW are the same language in two scripts, and canonicalising either
        // to "zh" hands back Simplified whichever one was asked for. The
        // narrowing is still done, but only where it belongs — in
        // [sameLanguage], which is asking a different question.
        val target = targetLanguageTag.trim()
        if (target.isBlank() || lines.isEmpty()) return Result.Unavailable

        val slots = flatten(lines)
        if (slots.isEmpty()) return Result.Unavailable
        val cacheKey = cacheKey(trackId, target, slots)
        val cached = memory.get(cacheKey) ?: readCache(cacheKey)?.also {
            memory.put(cacheKey, it)
        }
        if (cached != null && cached.version == CACHE_VERSION && cached.texts.size == slots.size) {
            return if (sameLanguage(cached.sourceLanguage, target)) {
                Result.SameLanguage(cached.sourceLanguage)
            } else {
                Result.Translated(
                    subLines = rebuild(lines, slots, cached.texts),
                    sourceLanguage = cached.sourceLanguage,
                    fromCache = true,
                )
            }
        }

        val batches = batches(slots)
        val answers = coroutineScope {
            // Two short requests at a time keeps a long lyric fast without
            // competing with playback for every connection in the pool.
            batches.chunked(MAX_PARALLEL_REQUESTS).flatMap { group ->
                group.map { batch -> async { requestBatch(batch, target) } }.awaitAll()
            }
        }
        if (answers.any { it == null }) return Result.Unavailable
        val complete = answers.filterNotNull()
        val source = complete
            .groupBy { canonicalLanguage(it.sourceLanguage) }
            .maxByOrNull { (_, values) -> values.sumOf { it.sourceWeight } }
            ?.key
            .orEmpty()
        if (source.isBlank()) return Result.Unavailable

        var translated = complete.flatMap { it.translations }
        if (translated.size != slots.size) return Result.Unavailable

        // The endpoint's auto-detection understands many Latin-script
        // Hindi/Urdu/Punjabi lyrics, but its NMT occasionally returns whole
        // phrases unchanged. If a sizeable part of a Latin-script source
        // survived untouched, use Input Tools to restore the detected
        // language's native script and translate that instead. Keep the first
        // answer unless the retry actually transforms more of the song, so
        // names and genuinely bilingual lyrics do not get worse merely
        // because they contain Latin text.
        if (
            !sameLanguage(source, target) &&
            predominantlyLatin(slots) &&
            unchangedWeight(slots, translated) * 3 >= slots.sumOf { it.text.length }
        ) {
            val retried = retryRomanizedTranslation(batches, source, target)
            if (
                retried != null &&
                retried.size == slots.size &&
                unchangedWeight(slots, retried) < unchangedWeight(slots, translated)
            ) {
                translated = retried
            }
        }
        val entry = CachedTranslation(
            sourceLanguage = source,
            targetLanguage = target,
            texts = translated,
        )
        memory.put(cacheKey, entry)
        writeCache(cacheKey, entry)

        return if (sameLanguage(source, target)) {
            Result.SameLanguage(source)
        } else {
            Result.Translated(
                subLines = rebuild(lines, slots, translated),
                sourceLanguage = source,
                fromCache = false,
            )
        }
    }

    suspend fun romanize(trackId: String, lines: List<LyricLine>): RomanizationResult {
        if (lines.isEmpty()) return RomanizationResult.Unavailable
        val slots = flatten(lines)
        if (slots.isEmpty()) return RomanizationResult.Unavailable
        if (!hasNonLatinLetters(slots)) return RomanizationResult.AlreadyRomanized

        // Romanization always ends in Latin script. The endpoint still wants a
        // non-blank `tl` but nothing about the output depends on its value, so
        // "en" is sent and the target is deliberately excluded from the cache
        // key: changing "translate to" cannot change how a language is
        // pronounced.
        val cacheKey = cacheKey(trackId, "romanize", slots)
        val cached = memory.get(cacheKey) ?: readCache(cacheKey)?.also {
            memory.put(cacheKey, it)
        }
        if (cached != null && cached.version == CACHE_VERSION && cached.texts.size == slots.size) {
            return RomanizationResult.Romanized(
                subLines = rebuild(lines, slots, cached.texts),
                sourceLanguage = cached.sourceLanguage,
                fromCache = true,
            )
        }

        val batches = batches(slots)
        val answers = coroutineScope {
            batches.chunked(MAX_PARALLEL_REQUESTS).flatMap { group ->
                group.map { batch -> async { requestRomanizationBatch(batch) } }.awaitAll()
            }
        }
        if (answers.any { it == null }) return RomanizationResult.Unavailable
        val complete = answers.filterNotNull()
        val source = complete
            .groupBy { canonicalLanguage(it.sourceLanguage) }
            .maxByOrNull { (_, values) -> values.sumOf { it.sourceWeight } }
            ?.key
            .orEmpty()
        val romanized = complete.flatMap { it.translations }
        if (source.isBlank() || romanized.size != slots.size) return RomanizationResult.Unavailable
        if (unchangedWeight(slots, romanized) == slots.sumOf { it.text.length }) {
            // Non-Latin input reached this point, so an unchanged response is
            // an unsupported/failed romanization rather than an already-Latin
            // lyric. The fast all-Latin check above handles the real no-op case.
            return RomanizationResult.Unavailable
        }

        val entry = CachedTranslation(
            sourceLanguage = source,
            targetLanguage = "Latn",
            texts = romanized,
        )
        memory.put(cacheKey, entry)
        writeCache(cacheKey, entry)
        return RomanizationResult.Romanized(
            subLines = rebuild(lines, slots, romanized),
            sourceLanguage = source,
            fromCache = false,
        )
    }

    internal fun flatten(lines: List<LyricLine>): List<TextSlot> = buildList {
        lines.forEachIndexed { index, line ->
            val header = isSectionHeader(line.text)
            val text = if (header) line.text.trim().removePrefix("[").removeSuffix("]").trim() else line.text
            if (text.isNotBlank()) {
                add(TextSlot(lineIndex = index, text = text, sectionHeader = header))
            }
        }
    }

    /** A whole line wrapped in brackets is a structure label ("[Chorus]"), not
     *  lyric text; the wrapper is stripped for the request and restored on the
     *  way back so the label survives translation as structure. */
    private fun isSectionHeader(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.length >= 3 && trimmed.startsWith("[") && trimmed.endsWith("]")
    }

    internal fun batches(slots: List<TextSlot>): List<Batch> {
        val result = mutableListOf<Batch>()
        var current = mutableListOf<TextSlot>()
        var length = 0

        fun flush() {
            if (current.isEmpty()) return
            result += Batch(current.toList(), payload(current))
            current = mutableListOf()
            length = 0
        }

        slots.forEach { slot ->
            val added = slot.text.length + if (current.isEmpty()) 0 else 8
            if (current.isNotEmpty() && length + added > MAX_BATCH_CHARS) flush()
            current += slot
            length += added
        }
        flush()
        return result
    }

    private fun payload(slots: List<TextSlot>): String = buildString {
        slots.forEachIndexed { index, slot ->
            if (index > 0) append('\n').append(marker(index)).append('\n')
            append(slot.text)
        }
    }

    private fun marker(index: Int): String = "\uE000${index.toString().padStart(4, '0')}\uE001"

    private suspend fun requestBatch(
        batch: Batch,
        target: String,
        sourceLanguage: String = "auto",
    ): BatchAnswer? {
        val body = FormBody.Builder()
            .add("client", "dict-chrome-ex")
            .add("sl", sourceLanguage)
            .add("tl", target)
            .add("dt", "t")
            .add("q", batch.payload)
            .build()
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .post(body)
            .build()
        val response = client.newCall(request).awaitSuccessfulBodyOrNull() ?: return null
        return parseTranslatedAnswer(response, batch.slots.size, batch.payload.length)
    }

    private suspend fun requestRomanizationBatch(batch: Batch): BatchAnswer? {
        val body = FormBody.Builder()
            .add("client", "dict-chrome-ex")
            .add("sl", "auto")
            .add("tl", "en")
            .add("dt", "rm")
            .add("q", batch.payload)
            .build()
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .post(body)
            .build()
        val response = client.newCall(request).awaitSuccessfulBodyOrNull() ?: return null
        return parseRomanizedAnswer(response, batch.slots.size, batch.payload.length)
    }

    internal fun parseTranslatedAnswer(
        response: String,
        slotCount: Int,
        payloadLength: Int,
    ): BatchAnswer? = runCatching {
        val root = json.parseToJsonElement(response).jsonArray
        val translatedBody = root[0].jsonArray.joinToString(separator = "") { segment ->
            segment.jsonArray.getOrNull(0)?.jsonPrimitive?.contentOrNull.orEmpty()
        }
        val source = root.getOrNull(2)?.jsonPrimitive?.contentOrNull.orEmpty()
        val parts = translatedBody.split(markerRegex).map(String::trim)
        if (parts.size != slotCount || parts.any { it.isBlank() }) return@runCatching null
        BatchAnswer(parts, source, payloadLength)
    }.getOrNull()

    internal fun parseRomanizedAnswer(
        response: String,
        slotCount: Int,
        payloadLength: Int,
    ): BatchAnswer? = runCatching {
        val root = json.parseToJsonElement(response).jsonArray
        val romanizedBody = root[0].jsonArray.joinToString(separator = "") { segment ->
            segment.jsonArray.getOrNull(3)?.jsonPrimitive?.contentOrNull.orEmpty()
        }
        val source = root.getOrNull(2)?.jsonPrimitive?.contentOrNull.orEmpty()
        val parts = romanizedBody.split(markerRegex).map(String::trim)
        if (source.isBlank() || parts.size != slotCount || parts.any { it.isBlank() }) {
            return@runCatching null
        }
        BatchAnswer(parts, source, payloadLength)
    }.getOrNull()

    private suspend fun retryRomanizedTranslation(
        batches: List<Batch>,
        sourceLanguage: String,
        target: String,
    ): List<String>? = coroutineScope {
        val source = canonicalLanguage(sourceLanguage)
        if (source.isBlank() || source == "en") return@coroutineScope null
        val answers = batches.chunked(MAX_PARALLEL_REQUESTS).flatMap { group ->
            group.map { batch ->
                async {
                    val nativePayload = requestNativeScript(batch.payload, source) ?: return@async null
                    requestBatch(batch.copy(payload = nativePayload), target, source)
                }
            }.awaitAll()
        }
        if (answers.any { it == null }) null else answers.filterNotNull().flatMap { it.translations }
    }

    private suspend fun requestNativeScript(text: String, sourceLanguage: String): String? {
        val body = FormBody.Builder()
            .add("text", text)
            .add("itc", "$sourceLanguage-t-i0-und")
            .add("num", "1")
            .add("cp", "0")
            .add("cs", "1")
            .add("ie", "utf-8")
            .add("oe", "utf-8")
            .build()
        val request = Request.Builder()
            .url(INPUT_TOOLS_ENDPOINT)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .post(body)
            .build()
        val response = client.newCall(request).awaitSuccessfulBodyOrNull() ?: return null
        return runCatching {
            val root = json.parseToJsonElement(response).jsonArray
            if (root.getOrNull(0)?.jsonPrimitive?.contentOrNull != "SUCCESS") return@runCatching null
            root[1].jsonArray[0].jsonArray[1].jsonArray[0].jsonPrimitive.contentOrNull
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun predominantlyLatin(slots: List<TextSlot>): Boolean {
        var latin = 0
        var other = 0
        slots.forEach { slot ->
            slot.text.codePoints().forEach { codePoint ->
                if (Character.isLetter(codePoint)) {
                    if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN) latin++
                    else other++
                }
            }
        }
        return latin > 0 && latin >= other * 4
    }

    private fun hasNonLatinLetters(slots: List<TextSlot>): Boolean = slots.any { slot ->
        slot.text.codePoints().anyMatch { codePoint ->
            Character.isLetter(codePoint) &&
                Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.LATIN
        }
    }

    internal fun unchangedWeight(slots: List<TextSlot>, transformed: List<String>): Int =
        slots.zip(transformed).sumOf { (slot, text) ->
            if (comparable(slot.text) == comparable(text)) slot.text.length else 0
        }

    private fun comparable(text: String): String = text
        .trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")

    /**
     * One entry per original line, aligned by index: the visible sub-line text
     * where there is one, `null` where there is not. A transformed line that
     * merely repeats its source (names, shared phrases, already-Latin lines in
     * a romanization) is not worth a second copy on screen, so it maps to
     * `null` too.
     */
    internal fun rebuild(
        lines: List<LyricLine>,
        slots: List<TextSlot>,
        transformed: List<String>,
    ): List<String?> {
        val byIndex = HashMap<Int, String>(slots.size)
        slots.zip(transformed).forEach { (slot, text) ->
            byIndex[slot.lineIndex] = if (slot.sectionHeader) "[$text]" else text
        }
        return lines.mapIndexed { index, line ->
            val subLine = byIndex[index] ?: return@mapIndexed null
            subLine.takeIf { comparable(it) != comparable(line.text) }
        }
    }

    internal fun canonicalLanguage(tag: String): String = when (
        Locale.forLanguageTag(tag.replace('_', '-')).language.lowercase(Locale.ROOT)
    ) {
        "iw" -> "he"
        "in" -> "id"
        "ji" -> "yi"
        else -> Locale.forLanguageTag(tag.replace('_', '-')).language.lowercase(Locale.ROOT)
    }

    private fun sameLanguage(first: String, second: String): Boolean =
        canonicalLanguage(first) == canonicalLanguage(second)

    internal fun cacheKey(trackId: String, target: String, slots: List<TextSlot>): String {
        val source = buildString {
            append(trackId).append('\u0000').append(target)
            slots.forEach { append('\u0000').append(it.text) }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    internal suspend fun readCache(key: String): CachedTranslation? =
        withContext(Dispatchers.IO) {
            diskMutex.withLock {
                val root = cacheDir ?: return@withLock null
                val file = File(File(root, CACHE_DIRECTORY), "$key.json.gz")
                if (!file.isFile) return@withLock null
                runCatching {
                    val value = GZIPInputStream(FileInputStream(file)).bufferedReader().use {
                        json.decodeFromString<CachedTranslation>(it.readText())
                    }
                    file.setLastModified(System.currentTimeMillis())
                    value.takeIf { it.version == CACHE_VERSION }
                }.getOrNull()
            }
        }

    internal suspend fun writeCache(key: String, value: CachedTranslation) =
        withContext(Dispatchers.IO) {
            diskMutex.withLock {
                val root = cacheDir ?: return@withLock
                val directory = File(root, CACHE_DIRECTORY)
                if (!directory.exists() && !directory.mkdirs()) return@withLock
                val destination = File(directory, "$key.json.gz")
                val temporary = File(directory, "$key.tmp")
                runCatching {
                    GZIPOutputStream(FileOutputStream(temporary)).bufferedWriter().use {
                        it.write(json.encodeToString(value))
                    }
                    if (!temporary.renameTo(destination)) {
                        temporary.copyTo(destination, overwrite = true)
                        temporary.delete()
                    }
                    trimCache(directory)
                }.onFailure { temporary.delete() }
            }
        }

    private fun trimCache(directory: File) {
        val files = directory.listFiles { file -> file.extension == "gz" }
            ?.sortedByDescending(File::lastModified)
            .orEmpty()
        var kept = 0L
        files.forEach { file ->
            kept += file.length()
            if (kept > MAX_CACHE_BYTES) file.delete()
        }
    }
}
