package com.lastwave.app.data.lyrics

import com.lastwave.app.data.artwork.awaitSuccessfulBodyOrNull
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
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

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

    @Serializable
    internal data class CachedTranslation(
        val version: Int = CACHE_FORMAT_VERSION,
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

    private const val TRANSLATE_URL = "https://translate.googleapis.com/translate_a/single"
    private const val INPUT_TOOLS_URL = "https://inputtools.google.com/request"
    private const val CACHE_FORMAT_VERSION = 1
    private const val DISK_CACHE_DIR = "lyrics_translation_v1"
    private const val DISK_CACHE_LIMIT_BYTES = 2L * 1024L * 1024L
    private const val BATCH_CHAR_LIMIT = 3_500
    private const val CONCURRENT_BATCHES = 2
    private const val CLIENT_HEADER = "LastWave/1.0"

    private val slotDividerPattern = Regex("\\uE000[^\\uE001]*\\uE001")
    private val multiSpacePattern = Regex("\\s+")
    private val jsonParser = Json { ignoreUnknownKeys = true }
    private val fileLock = Mutex()
    private val lruLock = Any()
    private val recentTranslations = object : LinkedHashMap<String, CachedTranslation>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedTranslation>): Boolean =
            size > 12
    }

    @Volatile
    var cacheDir: File? = null

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    suspend fun translate(
        trackId: String,
        lines: List<LyricLine>,
        targetLanguageTag: String,
    ): Result {
        val targetTag = targetLanguageTag.trim()
        if (targetTag.isEmpty() || lines.isEmpty()) return Result.Unavailable

        val slots = flatten(lines)
        if (slots.isEmpty()) return Result.Unavailable

        val key = cacheKey(trackId, targetTag, slots)
        val cached = loadFromCache(key)
        if (cached != null && cached.version == CACHE_FORMAT_VERSION && cached.texts.size == slots.size) {
            return if (languagesMatch(cached.sourceLanguage, targetTag)) {
                Result.SameLanguage(cached.sourceLanguage)
            } else {
                Result.Translated(
                    rebuild(lines, slots, cached.texts),
                    cached.sourceLanguage,
                    true,
                )
            }
        }

        val chunked = batches(slots)
        val answers = executeInGroups(chunked) { chunk -> fetchTranslation(chunk, targetTag) }
            ?: return Result.Unavailable

        val detectedSource = dominantSource(answers)
        if (detectedSource.isEmpty()) return Result.Unavailable

        var output = answers.flatMap(BatchAnswer::translations)
        if (output.size != slots.size) return Result.Unavailable

        val totalChars = slots.sumOf { slot -> slot.text.length }
        if (
            !languagesMatch(detectedSource, targetTag) &&
            isMostlyLatin(slots) &&
            unchangedWeight(slots, output) * 3 >= totalChars
        ) {
            val nativeRetry = translateViaNativeScript(chunked, detectedSource, targetTag)
            if (
                nativeRetry != null &&
                nativeRetry.size == slots.size &&
                unchangedWeight(slots, nativeRetry) < unchangedWeight(slots, output)
            ) {
                output = nativeRetry
            }
        }

        val record = CachedTranslation(
            sourceLanguage = detectedSource,
            targetLanguage = targetTag,
            texts = output,
        )
        saveToMemory(key, record)
        writeCache(key, record)

        return if (languagesMatch(detectedSource, targetTag)) {
            Result.SameLanguage(detectedSource)
        } else {
            Result.Translated(
                rebuild(lines, slots, output),
                detectedSource,
                false,
            )
        }
    }

    suspend fun romanize(trackId: String, lines: List<LyricLine>): RomanizationResult {
        if (lines.isEmpty()) return RomanizationResult.Unavailable

        val slots = flatten(lines)
        if (slots.isEmpty()) return RomanizationResult.Unavailable
        if (!containsNonLatinText(slots)) return RomanizationResult.AlreadyRomanized

        val key = cacheKey(trackId, "romanize", slots)
        val cached = loadFromCache(key)
        if (cached != null && cached.version == CACHE_FORMAT_VERSION && cached.texts.size == slots.size) {
            return RomanizationResult.Romanized(
                rebuild(lines, slots, cached.texts),
                cached.sourceLanguage,
                true,
            )
        }

        val chunked = batches(slots)
        val answers = executeInGroups(chunked) { chunk -> fetchRomanization(chunk) }
            ?: return RomanizationResult.Unavailable

        val detectedSource = dominantSource(answers)
        val romanized = answers.flatMap(BatchAnswer::translations)
        if (detectedSource.isEmpty() || romanized.size != slots.size) {
            return RomanizationResult.Unavailable
        }
        if (unchangedWeight(slots, romanized) == slots.sumOf { it.text.length }) {
            return RomanizationResult.Unavailable
        }

        val record = CachedTranslation(
            sourceLanguage = detectedSource,
            targetLanguage = "Latn",
            texts = romanized,
        )
        saveToMemory(key, record)
        writeCache(key, record)

        return RomanizationResult.Romanized(
            rebuild(lines, slots, romanized),
            detectedSource,
            false,
        )
    }

    internal fun flatten(lines: List<LyricLine>): List<TextSlot> {
        val slots = ArrayList<TextSlot>(lines.size)
        for (idx in lines.indices) {
            val raw = lines[idx].text
            val trimmed = raw.trim()
            val isHeader = trimmed.length >= 3 && trimmed.first() == '[' && trimmed.last() == ']'
            val cleanText = if (isHeader) trimmed.substring(1, trimmed.length - 1).trim() else raw
            if (cleanText.isNotBlank()) {
                slots.add(TextSlot(idx, cleanText, isHeader))
            }
        }
        return slots
    }

    internal fun batches(slots: List<TextSlot>): List<Batch> {
        val output = mutableListOf<Batch>()
        val pending = mutableListOf<TextSlot>()
        var charCount = 0

        for (slot in slots) {
            val delta = slot.text.length + if (pending.isEmpty()) 0 else 8
            if (pending.isNotEmpty() && charCount + delta > BATCH_CHAR_LIMIT) {
                output.add(Batch(pending.toList(), assemblePayload(pending)))
                pending.clear()
                charCount = 0
            }
            pending.add(slot)
            charCount += delta
        }
        if (pending.isNotEmpty()) {
            output.add(Batch(pending.toList(), assemblePayload(pending)))
        }
        return output
    }

    internal fun parseTranslatedAnswer(
        response: String,
        slotCount: Int,
        payloadLength: Int,
    ): BatchAnswer? = decodeResponse(response, slotCount, payloadLength, cellIndex = 0, requireDetectedSource = false)

    internal fun parseRomanizedAnswer(
        response: String,
        slotCount: Int,
        payloadLength: Int,
    ): BatchAnswer? = decodeResponse(response, slotCount, payloadLength, cellIndex = 3, requireDetectedSource = true)

    internal fun unchangedWeight(slots: List<TextSlot>, transformed: List<String>): Int {
        var total = 0
        val count = minOf(slots.size, transformed.size)
        for (i in 0 until count) {
            if (normalizeComparable(slots[i].text) == normalizeComparable(transformed[i])) {
                total += slots[i].text.length
            }
        }
        return total
    }

    internal fun rebuild(
        lines: List<LyricLine>,
        slots: List<TextSlot>,
        transformed: List<String>,
    ): List<String?> {
        val mapped = HashMap<Int, String>(slots.size)
        for (i in 0 until minOf(slots.size, transformed.size)) {
            val slot = slots[i]
            val value = transformed[i]
            mapped[slot.lineIndex] = if (slot.sectionHeader) "[$value]" else value
        }
        return lines.mapIndexed { idx, original ->
            val candidate = mapped[idx] ?: return@mapIndexed null
            if (normalizeComparable(candidate) != normalizeComparable(original.text)) candidate else null
        }
    }

    internal fun canonicalLanguage(tag: String): String {
        val normalized = Locale.forLanguageTag(tag.replace('_', '-')).language.lowercase(Locale.ROOT)
        return when (normalized) {
            "iw" -> "he"
            "in" -> "id"
            "ji" -> "yi"
            else -> normalized
        }
    }

    internal fun cacheKey(trackId: String, target: String, slots: List<TextSlot>): String {
        val sb = StringBuilder(trackId.length + target.length + slots.size * 16)
        sb.append(trackId).append('\u0000').append(target)
        for (slot in slots) {
            sb.append('\u0000').append(slot.text)
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sb.toString().toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            for (b in digest) {
                append("%02x".format(b))
            }
        }
    }

    internal suspend fun readCache(key: String): CachedTranslation? = withContext(Dispatchers.IO) {
        fileLock.withLock {
            val base = cacheDir ?: return@withLock null
            val targetFile = File(File(base, DISK_CACHE_DIR), "$key.json.gz")
            if (!targetFile.isFile) return@withLock null
            try {
                val decoded = GZIPInputStream(FileInputStream(targetFile)).bufferedReader().use { reader ->
                    jsonParser.decodeFromString<CachedTranslation>(reader.readText())
                }
                targetFile.setLastModified(System.currentTimeMillis())
                if (decoded.version == CACHE_FORMAT_VERSION) decoded else null
            } catch (_: Exception) {
                null
            }
        }
    }

    internal suspend fun writeCache(key: String, value: CachedTranslation) {
        withContext(Dispatchers.IO) {
            fileLock.withLock {
                val base = cacheDir ?: return@withLock
                val cacheFolder = File(base, DISK_CACHE_DIR)
                if (!cacheFolder.exists() && !cacheFolder.mkdirs()) return@withLock

                val finalFile = File(cacheFolder, "$key.json.gz")
                val stagingFile = File(cacheFolder, "$key.tmp")
                try {
                    GZIPOutputStream(FileOutputStream(stagingFile)).bufferedWriter().use { writer ->
                        writer.write(jsonParser.encodeToString(value))
                    }
                    if (!stagingFile.renameTo(finalFile)) {
                        stagingFile.copyTo(finalFile, overwrite = true)
                        stagingFile.delete()
                    }
                    pruneDiskCache(cacheFolder)
                } catch (_: Exception) {
                    stagingFile.delete()
                }
            }
        }
    }

    private fun assemblePayload(slots: List<TextSlot>): String {
        val sb = StringBuilder()
        for (i in slots.indices) {
            if (i > 0) {
                sb.append('\n')
                    .append('\uE000')
                    .append(i.toString().padStart(4, '0'))
                    .append('\uE001')
                    .append('\n')
            }
            sb.append(slots[i].text)
        }
        return sb.toString()
    }

    private suspend fun <T> executeInGroups(
        items: List<Batch>,
        task: suspend (Batch) -> T?,
    ): List<T>? = coroutineScope {
        val collected = ArrayList<T>(items.size)
        for (group in items.chunked(CONCURRENT_BATCHES)) {
            val groupResults = group.map { item -> async { task(item) } }.awaitAll()
            for (res in groupResults) {
                if (res == null) return@coroutineScope null
                collected.add(res)
            }
        }
        collected
    }

    private fun dominantSource(answers: List<BatchAnswer>): String {
        val weights = HashMap<String, Int>()
        for (answer in answers) {
            val lang = canonicalLanguage(answer.sourceLanguage)
            weights[lang] = (weights[lang] ?: 0) + answer.sourceWeight
        }
        return weights.maxByOrNull { it.value }?.key.orEmpty()
    }

    private suspend fun fetchTranslation(
        batch: Batch,
        target: String,
        sourceLang: String = "auto",
    ): BatchAnswer? {
        val body = submitTranslateForm(batch.payload, sourceLang, target, "t") ?: return null
        return parseTranslatedAnswer(body, batch.slots.size, batch.payload.length)
    }

    private suspend fun fetchRomanization(batch: Batch): BatchAnswer? {
        val body = submitTranslateForm(batch.payload, "auto", "en", "rm") ?: return null
        return parseRomanizedAnswer(body, batch.slots.size, batch.payload.length)
    }

    private suspend fun submitTranslateForm(
        text: String,
        sourceLang: String,
        targetLang: String,
        detailType: String,
    ): String? {
        val form = FormBody.Builder()
            .add("client", "dict-chrome-ex")
            .add("sl", sourceLang)
            .add("tl", targetLang)
            .add("dt", detailType)
            .add("q", text)
            .build()
        val req = Request.Builder()
            .url(TRANSLATE_URL)
            .header("User-Agent", CLIENT_HEADER)
            .header("Accept", "application/json")
            .post(form)
            .build()
        return httpClient.newCall(req).awaitSuccessfulBodyOrNull()
    }

    private fun decodeResponse(
        rawJson: String,
        expectedCount: Int,
        payloadLength: Int,
        cellIndex: Int,
        requireDetectedSource: Boolean,
    ): BatchAnswer? = try {
        val rootArray = jsonParser.parseToJsonElement(rawJson).jsonArray
        val joinedText = buildString {
            for (segment in rootArray[0].jsonArray) {
                append(segment.jsonArray.getOrNull(cellIndex)?.jsonPrimitive?.contentOrNull.orEmpty())
            }
        }
        val sourceLang = rootArray.getOrNull(2)?.jsonPrimitive?.contentOrNull.orEmpty()
        val chunks = joinedText.split(slotDividerPattern).map { it.trim() }
        if ((requireDetectedSource && sourceLang.isBlank()) ||
            chunks.size != expectedCount ||
            chunks.any { it.isEmpty() }
        ) {
            null
        } else {
            BatchAnswer(chunks, sourceLang, payloadLength)
        }
    } catch (_: Exception) {
        null
    }

    private suspend fun translateViaNativeScript(
        batches: List<Batch>,
        sourceLang: String,
        targetLang: String,
    ): List<String>? {
        val canonical = canonicalLanguage(sourceLang)
        if (canonical.isEmpty() || canonical == "en") return null

        val answers = executeInGroups(batches) { batch ->
            val converted = transliterateToNative(batch.payload, canonical) ?: return@executeInGroups null
            fetchTranslation(batch.copy(payload = converted), targetLang, canonical)
        } ?: return null

        return answers.flatMap(BatchAnswer::translations)
    }

    private suspend fun transliterateToNative(text: String, sourceLang: String): String? {
        val form = FormBody.Builder()
            .add("text", text)
            .add("itc", "$sourceLang-t-i0-und")
            .add("num", "1")
            .add("cp", "0")
            .add("cs", "1")
            .add("ie", "utf-8")
            .add("oe", "utf-8")
            .build()
        val req = Request.Builder()
            .url(INPUT_TOOLS_URL)
            .header("User-Agent", CLIENT_HEADER)
            .header("Accept", "application/json")
            .post(form)
            .build()
        val body = httpClient.newCall(req).awaitSuccessfulBodyOrNull() ?: return null
        return try {
            val root = jsonParser.parseToJsonElement(body).jsonArray
            if (root.getOrNull(0)?.jsonPrimitive?.contentOrNull != "SUCCESS") return null
            root[1].jsonArray[0].jsonArray[1].jsonArray[0].jsonPrimitive.contentOrNull
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun isMostlyLatin(slots: List<TextSlot>): Boolean {
        var latinLetters = 0
        var otherLetters = 0
        for (slot in slots) {
            val stream = slot.text.codePoints().iterator()
            while (stream.hasNext()) {
                val cp = stream.nextInt()
                if (!Character.isLetter(cp)) continue
                if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN) {
                    latinLetters++
                } else {
                    otherLetters++
                }
            }
        }
        return latinLetters > 0 && latinLetters >= otherLetters * 4
    }

    private fun containsNonLatinText(slots: List<TextSlot>): Boolean {
        for (slot in slots) {
            val stream = slot.text.codePoints().iterator()
            while (stream.hasNext()) {
                val cp = stream.nextInt()
                if (Character.isLetter(cp) && Character.UnicodeScript.of(cp) != Character.UnicodeScript.LATIN) {
                    return true
                }
            }
        }
        return false
    }

    private fun normalizeComparable(input: String): String =
        input.trim().lowercase(Locale.ROOT).replace(multiSpacePattern, " ")

    private fun languagesMatch(a: String, b: String): Boolean =
        canonicalLanguage(a) == canonicalLanguage(b)

    private suspend fun loadFromCache(key: String): CachedTranslation? {
        synchronized(lruLock) { recentTranslations[key] }?.let { return it }
        return readCache(key)?.also { entry -> saveToMemory(key, entry) }
    }

    private fun saveToMemory(key: String, value: CachedTranslation) {
        synchronized(lruLock) {
            recentTranslations[key] = value
        }
    }

    private fun pruneDiskCache(folder: File) {
        val entries = folder.listFiles { file -> file.extension == "gz" }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        var bytesKept = 0L
        for (entry in entries) {
            bytesKept += entry.length()
            if (bytesKept > DISK_CACHE_LIMIT_BYTES) {
                entry.delete()
            }
        }
    }
}
