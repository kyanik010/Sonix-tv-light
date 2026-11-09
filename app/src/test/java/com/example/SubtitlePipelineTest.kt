package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SubtitlePipelineTest {
    @Test
    fun parsesSrtWithCueNumbersAndMultilineText() {
        val srt = "1\r\n00:00:01,000 --> 00:00:03,500\r\nمرحبا <i>بك</i>\r\nالسطر الثاني\r\n\r\n2\r\n00:00:04,000 --> 00:00:05,000\r\nNext"
        val cues = SubtitlePipeline.parse(srt, "srt")
        assertEquals(2, cues.size)
        assertEquals(1000L, cues[0].startMs)
        assertEquals(3500L, cues[0].endMs)
        assertEquals("مرحبا بك\nالسطر الثاني", cues[0].text)
        assertEquals("Next", cues[1].text)
    }

    @Test
    fun parsesWebVttWithShortTimestampsCueIdsAndSettings() {
        val vtt = "WEBVTT\n\nintro\n00:01.500 --> 00:03.000 align:start\nHello <c.yellow>world</c>\n\n00:00:04.000 --> 00:00:05.000\nSecond"
        val cues = SubtitlePipeline.parse(vtt, "vtt")
        assertEquals(2, cues.size)
        assertEquals(1500L, cues[0].startMs)
        assertEquals(3000L, cues[0].endMs)
        assertEquals("Hello world", cues[0].text)
    }

    @Test
    fun parsesAssDialogueWithCommaInSubtitleText() {
        val ass = "[Script Info]\nTitle: Test\n[V4+ Styles]\n[Events]\nFormat: Start, End, Layer, Text\nDialogue: 0:00:01.00,0:00:03.50,0,Hello, world\\Nsecond line"
        val cues = SubtitlePipeline.parse(ass, "ass")
        assertEquals(1, cues.size)
        assertEquals(1000L, cues[0].startMs)
        assertEquals("Hello, world\nsecond line", cues[0].text)
    }

    @Test
    fun parsesTtmlTimedParagraphs() {
        val xml = """<tt><body><div><p begin="00:00:01.000" end="00:00:02.500">Hello</p></div></body></tt>"""
        val cues = SubtitlePipeline.parse(xml, "ttml")
        assertEquals(1, cues.size)
        assertEquals(1000L, cues[0].startMs)
        assertEquals(2500L, cues[0].endMs)
    }

    @Test
    fun decodesGzipAndDetectsVttFromPayload() {
        val vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nArabic: مرحباً"
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(vtt.toByteArray(Charsets.UTF_8)) }
        val decoded = SubtitlePipeline.decodePayload(
            output.toByteArray(), null, "https://cdn.example/subtitle.gz"
        )
        assertEquals("vtt", decoded.extension)
        assertTrue(decoded.text.contains("مرحباً"))
        assertEquals(1, SubtitlePipeline.parse(decoded.text, decoded.extension).size)
    }

    @Test
    fun decodesZipAndSelectsSubtitleEntryInsteadOfReadme() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write("not a subtitle".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("Arabic/movie.vtt"))
            zip.write("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nمرحبا".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        val decoded = SubtitlePipeline.decodePayload(
            output.toByteArray(), null, "https://cdn.example/download?id=42"
        )
        assertEquals("vtt", decoded.extension)
        assertTrue(decoded.fileName.endsWith("movie.vtt"))
        assertEquals(1, SubtitlePipeline.parse(decoded.text, decoded.extension).size)
    }

    @Test
    fun decodesWindows1256Arabic() {
        val original = "هذه ترجمة عربية سليمة"
        val bytes = original.toByteArray(Charset.forName("windows-1256"))
        val decoded = SubtitlePipeline.decodePayload(bytes, null, "https://cdn.example/file.srt")
        assertEquals(original, decoded.text)
    }

    @Test
    fun honorsHttpGzipContentEncoding() {
        val original = "1\n00:00:01,000 --> 00:00:02,000\nCompressed"
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(original.toByteArray(Charsets.UTF_8)) }
        val decoded = SubtitlePipeline.decodePayload(
            output.toByteArray(), "gzip", "https://cdn.example/no-extension"
        )
        assertEquals("srt", decoded.extension)
        assertEquals(1, SubtitlePipeline.parse(decoded.text, decoded.extension).size)
        assertEquals(1500L, SubtitlePipeline.timeToMs("00:01.500"))
        assertEquals(1500L, SubtitlePipeline.timeToMs("1.5s"))
    }

    @Test
    fun rejectsHtmlErrorPagesInsteadOfReportingSuccessfulDownload() {
        val html = "<!doctype html><html><body>Not found</body></html>"
        assertThrows(IllegalArgumentException::class.java) {
            SubtitlePipeline.decodePayload(
                html.toByteArray(Charsets.UTF_8), null, "https://cdn.example/subtitle"
            )
        }
    }

    @Test
    fun supportsUtf8BomAndExtensionNormalization() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\nHello"
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8)
        val decoded = SubtitlePipeline.decodePayload(bytes, null, "https://cdn.example/file.srt?token=1")
        assertEquals(text, decoded.text)
        assertEquals("srt", decoded.extension)
        assertEquals("vtt", SubtitlePipeline.normalizeExtension("file.vtt.gz?token=abc"))
    }

    @Test
    fun applyDelayShiftsSrtPositiveAndNegativeTimestamps() {
        val srt = "1\r\n00:00:02,000 --> 00:00:05,000\r\nArabic subtitle"
        val delayed = SubtitlePipeline.applyDelay(srt, "srt", 1500L)
        assertTrue(delayed.contains("00:00:03,500 --> 00:00:06,500"))

        val advanced = SubtitlePipeline.applyDelay(srt, "srt", -1000L)
        assertTrue(advanced.contains("00:00:01,000 --> 00:00:04,000"))

        // Clamping at zero
        val clamped = SubtitlePipeline.applyDelay(srt, "srt", -5000L)
        assertTrue(clamped.contains("00:00:00,000 --> 00:00:00,000"))
    }

    @Test
    fun applyDelaySupportsWebVttWithShortTimestamps() {
        val vtt = "WEBVTT\n\n00:01.500 --> 00:03.000\nHello"
        val delayed = SubtitlePipeline.applyDelay(vtt, "vtt", 1000L)
        assertTrue(delayed.contains("00:02.500 --> 00:04.000"))
    }

    @Test
    fun detectsMimeTypesForSupportedExtensions() {
        assertEquals("application/x-subrip", SubtitlePipeline.getMimeTypeForExtension("srt"))
        assertEquals("application/x-subrip", SubtitlePipeline.getMimeTypeForExtension(".srt"))
        assertEquals("text/vtt", SubtitlePipeline.getMimeTypeForExtension("vtt"))
        assertEquals("text/vtt", SubtitlePipeline.getMimeTypeForExtension(".vtt"))
        assertEquals("text/x-ssa", SubtitlePipeline.getMimeTypeForExtension("ass"))
        assertEquals("text/x-ssa", SubtitlePipeline.getMimeTypeForExtension("ssa"))
        assertEquals("application/ttml+xml", SubtitlePipeline.getMimeTypeForExtension("ttml"))
        assertEquals("application/ttml+xml", SubtitlePipeline.getMimeTypeForExtension("xml"))
    }

    @Test
    fun detectsLanguageFromLabelOrName() {
        assertEquals("ar", SubtitlePipeline.detectLanguage("Arabic (Default)"))
        assertEquals("ar", SubtitlePipeline.detectLanguage("العربية - رسمي"))
        assertEquals("en", SubtitlePipeline.detectLanguage("English (SDH)"))
        assertEquals("fr", SubtitlePipeline.detectLanguage("French / Français"))
        assertEquals("es", SubtitlePipeline.detectLanguage("Spanish (Latin America)"))
        assertEquals("de", SubtitlePipeline.detectLanguage("German (Deutsch)"))
        assertEquals("ru", SubtitlePipeline.detectLanguage("Russian (Русский)"))
        assertEquals("tr", SubtitlePipeline.detectLanguage("Turkish (Türkçe)"))
        assertEquals("fa", SubtitlePipeline.detectLanguage("Persian (فارسی)"))
        assertEquals("und", SubtitlePipeline.detectLanguage("Unknown Label"))
    }

    @Test
    fun removesAssOverrideTagsWithoutBreakingSrtParsing() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\n{\\an8}Hello"
        val cues = SubtitlePipeline.parse(srt, "srt")
        assertEquals(1, cues.size)
        assertEquals("Hello", cues[0].text)
    }
}
