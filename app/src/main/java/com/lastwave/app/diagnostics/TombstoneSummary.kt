package com.lastwave.app.diagnostics

/**
 * Readable summary of a native-crash tombstone.
 *
 * For a native crash, ApplicationExitInfo.traceInputStream is a binary
 * protobuf (AOSP system/core/debuggerd/proto/tombstone.proto), not text.
 * Reading it as text mangled every multi-byte value and the size cap then cut
 * it off before the crashing thread, so reports showed a random idle thread.
 * This decodes just the fields that identify the crash; field numbers follow
 * tombstone.proto.
 */
internal object TombstoneSummary {
    private const val MAX_FRAMES = 48

    private class Frame(val relPc: Long, val function: String, val offset: Long, val file: String)
    private class ThreadInfo(val id: Int, val name: String, val frames: List<Frame>)

    /** Null when [bytes] is not a tombstone protobuf this decoder understands. */
    fun format(bytes: ByteArray): String? = runCatching {
        var tid = -1
        var signal = ""
        var signalCode = ""
        var faultAddress: Long? = null
        var abortMessage = ""
        var pageSize = 0L
        var compat16Kb = false
        val causes = mutableListOf<String>()
        val threads = mutableMapOf<Int, ThreadInfo>()

        Reader(bytes).forEachField { field, reader ->
            when (field) {
                6 -> tid = reader.varint().toInt()
                10 -> reader.message().forEachField { f, r ->
                    when (f) {
                        2 -> signal = r.string()
                        4 -> signalCode = r.string()
                        9 -> faultAddress = r.varint()
                        else -> r.skip()
                    }
                }
                14 -> abortMessage = reader.string()
                15 -> reader.message().forEachField { f, r -> if (f == 1) causes += r.string() else r.skip() }
                16 -> reader.message().forEachField { f, r ->
                    if (f == 2) parseThread(r.message())?.let { threads[it.id] = it } else r.skip()
                }
                22 -> pageSize = reader.varint()
                23 -> compat16Kb = reader.varint() != 0L
                else -> reader.skip()
            }
        }
        if (signal.isBlank() && threads.isEmpty()) return@runCatching null

        buildString {
            appendLine("Signal      : $signal ($signalCode)" + (faultAddress?.let { "  fault addr 0x${it.toString(16)}" } ?: ""))
            if (abortMessage.isNotBlank()) appendLine("Abort       : $abortMessage")
            causes.forEach { appendLine("Cause       : $it") }
            if (pageSize > 0) appendLine("Page size   : $pageSize" + if (compat16Kb) "  (ran in 16 KB compatibility mode)" else "")
            val crashing = threads[tid]
            appendLine("Thread      : ${crashing?.name ?: "?"} (tid=$tid)")
            crashing?.frames?.take(MAX_FRAMES)?.forEachIndexed { index, frame ->
                val symbol = if (frame.function.isNotBlank()) " (${frame.function}+${frame.offset})" else ""
                appendLine("  #%02d pc %016x  %s%s".format(index, frame.relPc, frame.file, symbol))
            }
        }.trimEnd()
    }.getOrNull()

    private fun parseThread(reader: Reader): ThreadInfo? {
        var id = -1
        var name = ""
        val frames = mutableListOf<Frame>()
        reader.forEachField { field, r ->
            when (field) {
                1 -> id = r.varint().toInt()
                2 -> name = r.string()
                4 -> {
                    var relPc = 0L
                    var function = ""
                    var offset = 0L
                    var file = ""
                    r.message().forEachField { f, fr ->
                        when (f) {
                            1 -> relPc = fr.varint()
                            4 -> function = fr.string()
                            5 -> offset = fr.varint()
                            6 -> file = fr.string()
                            else -> fr.skip()
                        }
                    }
                    frames += Frame(relPc, function, offset, file)
                }
                else -> r.skip()
            }
        }
        return if (id >= 0) ThreadInfo(id, name, frames) else null
    }

    /** Minimal protobuf wire-format reader over [bytes] in [start, end). */
    private class Reader(private val bytes: ByteArray, private var pos: Int = 0, private val end: Int = bytes.size) {
        private var wireType = 0

        fun forEachField(block: (field: Int, reader: Reader) -> Unit) {
            while (pos < end) {
                val key = varint()
                wireType = (key and 7).toInt()
                block((key ushr 3).toInt(), this)
            }
        }

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                require(pos < end && shift < 64) { "truncated varint" }
                val b = bytes[pos++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        private fun lengthDelimited(): IntRange {
            val length = varint().toInt()
            require(length >= 0 && pos + length <= end) { "bad length" }
            val range = pos until pos + length
            pos += length
            return range
        }

        fun string(): String = lengthDelimited().let { String(bytes, it.first, it.count(), Charsets.UTF_8) }

        fun message(): Reader = lengthDelimited().let { Reader(bytes, it.first, it.last + 1) }

        fun skip() {
            when (wireType) {
                0 -> varint()
                1 -> pos += 8
                2 -> lengthDelimited()
                5 -> pos += 4
                else -> error("unsupported wire type $wireType")
            }
            require(pos <= end) { "truncated field" }
        }
    }
}
