package com.example

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import java.util.zip.ZipInputStream

internal data class DecodedSubtitle(
    val text: String,
    val extension: String,
    val fileName: String
)

/**
 * Pure subtitle transport/decoding/parsing helpers shared by the Android pipeline and JVM tests.
 * Network I/O stays in MainActivity; this object validates and normalizes the bytes it receives.
 */
internal object SubtitlePipeline {
    private const val MAX_SUBTITLE_BYTES = 20 * 1024 * 1024
    private val supportedExtensions = setOf("srt", "vtt", "ass", "ssa", "ttml", "xml", "txt")

    fun decodePayload(
        rawBytes: ByteArray,
        contentEncoding: String?,
        sourceUrl: String,
        contentDisposition: String? = null
    ): DecodedSubtitle {
        require(rawBytes.isNotEmpty()) { "ملف الترجمة فارغ" }
        require(rawBytes.size <= MAX_SUBTITLE_BYTES) { "ملف الترجمة أكبر من 20MB" }

        val dispositionName = fileNameFromDisposition(contentDisposition)
        val urlName = sourceUrl.substringBefore('#').substringBefore('?').substringAfterLast('/')
        var fileName = dispositionName?.takeIf { it.isNotBlank() } ?: urlName
        var bytes = rawBytes
        val encoding = contentEncoding.orEmpty().lowercase(Locale.ROOT).trim()

        if (encoding.split(',').any { it.trim() == "gzip" } || isGzip(bytes)) {
            bytes = readBounded(GZIPInputStream(ByteArrayInputStream(bytes)))
            if (fileName.endsWith(".gz", ignoreCase = true)) fileName = fileName.dropLast(3)
        } else if (encoding.split(',').any { it.trim() == "deflate" }) {
            bytes = readBounded(InflaterInputStream(ByteArrayInputStream(bytes)))
        }

        if (isZip(bytes)) {
            val entry = extractSubtitleFromZip(bytes)
            bytes = entry.second
            fileName = entry.first
        }

        require(bytes.isNotEmpty()) { "ملف الترجمة بعد فك الضغط فارغ" }
        require(bytes.size <= MAX_SUBTITLE_BYTES) { "ملف الترجمة بعد فك الضغط أكبر من 20MB" }

        val text = decodeText(bytes)
            .removePrefix("\uFEFF")
            .replace("\u0000", "")
            .trimStart()
        require(text.isNotBlank()) { "تعذر فك ترميز ملف الترجمة" }

        val first = text.trimStart().take(256).lowercase(Locale.ROOT)
        require(!first.startsWith("<!doctype html") && !first.startsWith("<html") &&
            !first.startsWith("{\"") && !first.startsWith("{\"error\"")) {
            "مصدر الترجمة أعاد صفحة ويب أو استجابة JSON بدلاً من ملف ترجمة"
        }

        val extension = normalizeExtension(fileName, text)
        return DecodedSubtitle(text, extension, fileName.ifBlank { "subtitle.$extension" })
    }

    fun normalizeExtension(rawNameOrUrl: String, text: String = ""): String {
        val clean = rawNameOrUrl.substringBefore('?').substringBefore('#').trim().lowercase(Locale.ROOT)
        val withoutCompression = clean.removeSuffix(".gz").removeSuffix(".zip")
        val ext = withoutCompression.substringAfterLast('.', "")
        if (ext in supportedExtensions) return ext
        val sample = text.trimStart()
        return when {
            sample.startsWith("WEBVTT", ignoreCase = true) -> "vtt"
            sample.contains("[Script Info]", ignoreCase = true) -> "ass"
            sample.contains("<tt", ignoreCase = true) -> "ttml"
            else -> "srt"
        }
    }

    fun parse(text: String, extension: String): List<TimedSubtitleCue> {
        val source = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val result = mutableListOf<TimedSubtitleCue>()

        fun add(start: String, end: String, rawText: String) {
            val startMs = timeToMs(start) ?: return
            val endMs = timeToMs(end) ?: return
            val clean = cleanCueText(rawText)
            if (clean.isNotBlank() && endMs > startMs) {
                result += TimedSubtitleCue(startMs, endMs, clean)
            }
        }

        when (extension.lowercase(Locale.ROOT)) {
            "ass", "ssa" -> {
                source.lineSequence().forEach { line ->
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("Dialogue:", ignoreCase = true)) return@forEach
                    val fields = trimmed.substringAfter(':').split(',', limit = 10)
                    if (fields.size == 10) add(fields[1], fields[2], fields[9])
                }
            }
            "ttml", "xml" -> {
                val paragraph = Regex("(?is)<p\\b([^>]*)>(.*?)</p>")
                val attribute = Regex("""([\\w:-]+)\\s*=\\s*["']([^"']+)["']""")
                paragraph.findAll(source).forEach { match ->
                    val attrs = attribute.findAll(match.groupValues[1])
                        .associate { it.groupValues[1].lowercase(Locale.ROOT) to it.groupValues[2] }
                    val begin = attrs["begin"] ?: return@forEach
                    val end = attrs["end"] ?: return@forEach
                    add(begin, end, match.groupValues[2])
                }
            }
            else -> {
                // SRT and WebVTT: split into cue blocks, allowing cue identifiers,
                // VTT settings, CRLF, and both HH:MM:SS.mmm and MM:SS.mmm timestamps.
                val timestamp = Regex(
                    """((?:\\d{1,2}:)?\\d{2}:\\d{2}[,.]\\d{1,3})\\s*-->\\s*((?:\\d{1,2}:)?\\d{2}:\\d{2}[,.]\\d{1,3})"""
                )
                source.split(Regex("\\n\\s*\\n")).forEach { block ->
                    val lines = block.lines()
                    val index = lines.indexOfFirst { timestamp.containsMatchIn(it) }
                    if (index < 0 || index + 1 >= lines.size) return@forEach
                    val match = timestamp.find(lines[index]) ?: return@forEach
                    add(match.groupValues[1], match.groupValues[2], lines.drop(index + 1).joinToString("\n"))
                }
            }
        }
        return result.sortedBy { it.startMs }
    }

    fun timeToMs(raw: String): Long? {
        val parts = raw.trim().replace(',', '.').split(':')
        return try {
            val seconds = when (parts.size) {
                3 -> parts[0].toLong() * 3600.0 + parts[1].toLong() * 60.0 + parts[2].toDouble()
                2 -> parts[0].toLong() * 60.0 + parts[1].toDouble()
                1 -> parts[0].toDouble()
                else -> return null
            }
            (seconds * 1000.0).toLong().takeIf { it >= 0L }
        } catch (_: Exception) { null }
    }

    private fun cleanCueText(raw: String): String =
        raw.replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ").replace("&amp;", "&")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
            .replace("\\N", "\n")
            .replace(Regex("\\{[^}]*}"), "")
            .trim()

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        try {
            return Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) { }

        for (charsetName in listOf("windows-1256", "ISO-8859-6")) {
            try {
                val decoded = String(bytes, Charset.forName(charsetName))
                if (decoded.any { it in '\u0600'..'\u06FF' }) return decoded
            } catch (_: Exception) { }
        }
        return String(bytes, Charset.forName("windows-1252"))
    }

    private fun fileNameFromDisposition(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val extended = Regex("""filename\\*\\s*=\\s*UTF-8''([^;]+)""", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.getOrNull(1)
        val plain = Regex("""filename\\s*=\\s*["']?([^;"']+)""", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.getOrNull(1)
        val raw = extended ?: plain ?: return null
        return try { URLDecoder.decode(raw.trim(), "UTF-8") } catch (_: Exception) { raw.trim() }
    }

    private fun isGzip(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()

    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte() &&
            (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte())

    private fun extractSubtitleFromZip(bytes: ByteArray): Pair<String, ByteArray> {
        var fallback: Pair<String, ByteArray>? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.substringAfterLast('/')
                val data = readBounded(zip)
                zip.closeEntry()
                if (data.isEmpty()) continue
                val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (ext in supportedExtensions) return name to data
                if (fallback == null) fallback = name to data
            }
        }
        return fallback ?: throw IllegalArgumentException("أرشيف ZIP لا يحتوي على ملف ترجمة")
    }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_SUBTITLE_BYTES) { "ملف الترجمة بعد فك الضغط أكبر من 20MB" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
