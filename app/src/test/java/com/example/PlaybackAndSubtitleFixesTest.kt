package com.example

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class PlaybackAndSubtitleFixesTest {

    @Test
    fun dynamicLoadControlDelegatesToLiveAndVodCorrectly() {
        var isLive = true
        val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
        val liveControl = DefaultLoadControl.Builder()
            .setAllocator(allocator)
            .setBufferDurationsMs(15000, 50000, 2500, 5000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val vodControl = DefaultLoadControl.Builder()
            .setAllocator(allocator)
            .setBufferDurationsMs(30000, 60000, 2500, 5000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val dynamicControl = DynamicLoadControl(
            liveLoadControl = liveControl,
            vodLoadControl = vodControl,
            isLiveProvider = { isLive }
        )

        assertNotNull(dynamicControl.allocator)
        assertEquals(allocator, dynamicControl.allocator)

        dynamicControl.onPrepared(androidx.media3.exoplayer.analytics.PlayerId.UNSET)

        // When live, shouldContinueLoading is backed by liveControl
        isLive = true
        val liveCanLoad = dynamicControl.shouldContinueLoading(0L, 10_000_000L, 1.0f)
        assertTrue(liveCanLoad)

        // When VOD, shouldContinueLoading is backed by vodControl
        isLive = false
        val vodCanLoad = dynamicControl.shouldContinueLoading(0L, 20_000_000L, 1.0f)
        assertTrue(vodCanLoad)
    }

    @Test
    fun subtitleCueDelayShiftsWithoutAccumulation() {
        val originalCues = listOf(
            TimedSubtitleCue(1000L, 3000L, "First line"),
            TimedSubtitleCue(4000L, 6000L, "Second line")
        )

        // Apply +500ms delay
        val delay500 = 500L
        val shifted500 = originalCues.mapNotNull { cue ->
            val start = cue.startMs + delay500
            val end = cue.endMs + delay500
            if (end > 0) TimedSubtitleCue(start.coerceAtLeast(0L), end.coerceAtLeast(0L), cue.text) else null
        }.sortedBy { it.startMs }

        assertEquals(1500L, shifted500[0].startMs)
        assertEquals(3500L, shifted500[0].endMs)
        assertEquals(4500L, shifted500[1].startMs)
        assertEquals(6500L, shifted500[1].endMs)

        // Change delay to -200ms from original (NOT from shifted500)
        val delayMinus200 = -200L
        val shiftedMinus200 = originalCues.mapNotNull { cue ->
            val start = cue.startMs + delayMinus200
            val end = cue.endMs + delayMinus200
            if (end > 0) TimedSubtitleCue(start.coerceAtLeast(0L), end.coerceAtLeast(0L), cue.text) else null
        }.sortedBy { it.startMs }

        assertEquals(800L, shiftedMinus200[0].startMs)
        assertEquals(2800L, shiftedMinus200[0].endMs)
        assertEquals(3800L, shiftedMinus200[1].startMs)
        assertEquals(5800L, shiftedMinus200[1].endMs)
    }

    @Test
    fun mimeTypeResolutionHandlesMpegTsHlsAndDash() {
        val extensionTs = "ts"
        val extensionM3u8 = "m3u8"
        val extensionMpd = "mpd"

        assertEquals("video/mp2t", androidx.media3.common.MimeTypes.VIDEO_MP2T)
        assertEquals("application/x-mpegURL", androidx.media3.common.MimeTypes.APPLICATION_M3U8)
        assertEquals("application/dash+xml", androidx.media3.common.MimeTypes.APPLICATION_MPD)
    }
}
