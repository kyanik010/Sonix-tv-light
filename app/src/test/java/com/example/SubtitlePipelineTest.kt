package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class SubtitlePipelineTest {

    private fun decompressGzip(bytes: ByteArray): ByteArray {
        if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            return GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        }
        return bytes
    }

    private fun decodeSubtitle(bytes: ByteArray): String {
        val decompressed = decompressGzip(bytes)
        if (decompressed.isEmpty()) return ""

        // Check BOM
        if (decompressed.size >= 3 && decompressed[0] == 0xEF.toByte() && decompressed[1] == 0xBB.toByte() && decompressed[2] == 0xBF.toByte()) {
            return String(decompressed, 3, decompressed.size - 3, Charsets.UTF_8)
        }
        if (decompressed.size >= 2 && decompressed[0] == 0xFF.toByte() && decompressed[1] == 0xFE.toByte()) {
            return String(decompressed, 2, decompressed.size - 2, Charsets.UTF_16LE)
        }
        if (decompressed.size >= 2 && decompressed[0] == 0xFE.toByte() && decompressed[1] == 0xFF.toByte()) {
            return String(decompressed, 2, decompressed.size - 2, Charsets.UTF_16BE)
        }

        try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val charBuffer = decoder.decode(ByteBuffer.wrap(decompressed))
            return charBuffer.toString()
        } catch (_: Exception) {}

        try {
            val cp1256 = Charset.forName("windows-1256")
            val decoded1256 = String(decompressed, cp1256)
            if (decoded1256.any { it in '\u0600'..'\u06FF' }) {
                return decoded1256
            }
        } catch (_: Exception) {}

        return String(decompressed, Charsets.UTF_8)
    }

    private fun normalizeExt(raw: String): String {
        var clean = raw.substringBefore('?').substringBefore('#').trim().lowercase()
        if (clean.endsWith(".gz")) clean = clean.substringBeforeLast(".gz")
        val ext = clean.substringAfterLast('.', "")
        return when (ext) {
            "srt", "vtt", "ssa", "ass", "ttml", "xml", "sub", "txt" -> ext
            else -> "srt"
        }
    }

    @Test
    fun decompressGzip_decompressesValidGzipStream() {
        val originalText = "1\n00:00:01,000 --> 00:00:03,000\nمرحبا بك في SONIX TV\n"
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(originalText.toByteArray(Charsets.UTF_8)) }
        val compressed = bos.toByteArray()

        assertTrue(compressed[0] == 0x1f.toByte())
        assertTrue(compressed[1] == 0x8b.toByte())

        val decompressed = decompressGzip(compressed)
        val decoded = String(decompressed, Charsets.UTF_8)
        assertEquals(originalText, decoded)
    }

    @Test
    fun decodeSubtitle_decodesWindows1256ArabicCorrectly() {
        val arabicText = "أهلاً وسهلاً بك في البث المباشر"
        val cp1256 = Charset.forName("windows-1256")
        val cp1256Bytes = arabicText.toByteArray(cp1256)

        val decoded = decodeSubtitle(cp1256Bytes)
        assertEquals(arabicText, decoded)
    }

    @Test
    fun decodeSubtitle_handlesGzippedWindows1256Arabic() {
        val arabicSrt = "1\n00:01:00,000 --> 00:01:05,000\nهذه ترجمة عربية مضغوطة\n"
        val cp1256 = Charset.forName("windows-1256")
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(arabicSrt.toByteArray(cp1256)) }
        val compressed = bos.toByteArray()

        val decoded = decodeSubtitle(compressed)
        assertTrue(decoded.contains("هذه ترجمة عربية مضغوطة"))
    }

    @Test
    fun decodeSubtitle_stripsUtf8Bom() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\nHello"
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8)

        val decoded = decodeSubtitle(bytes)
        assertFalse(decoded.startsWith("\uFEFF"))
        assertEquals(text, decoded)
    }

    @Test
    fun normalizeExt_stripsGzAndQueryParameters() {
        assertEquals("srt", normalizeExt("https://opensubtitles.org/sub/12345.srt.gz?token=abc"))
        assertEquals("vtt", normalizeExt("subtitles.vtt.gz"))
        assertEquals("ass", normalizeExt("subtitles.ass"))
        assertEquals("srt", normalizeExt("archive.unknown"))
    }

    @Test
    fun srtCrlfReplacement_normalizesLineBreaks() {
        val crlfText = "1\r\n00:00:01,000 --> 00:00:02,000\r\nLine 1\r\nLine 2\r\n\r\n2\r\n"
        val normalized = crlfText.replace("\r\n", "\n").replace('\r', '\n')
        assertFalse(normalized.contains("\r"))
        assertTrue(normalized.contains("\nLine 1\nLine 2\n\n2\n"))
    }
}
