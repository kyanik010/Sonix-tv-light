package com.example

import kotlin.math.abs

/**
 * Small, deterministic policy helpers for external-audio synchronization.
 * Positive offset means the external audio should trail the video.
 */
internal object ExternalAudioSyncPolicy {
    fun targetPositionMs(
        videoPositionMs: Long,
        offsetMs: Long,
        audioDurationMs: Long
    ): Long {
        val target = (videoPositionMs - offsetMs).coerceAtLeast(0L)
        return if (audioDurationMs > 0L) target.coerceAtMost(audioDurationMs) else target
    }

    /**
     * Live offset is the distance behind the live edge. If audio is further
     * behind than requested, play slightly faster; if it is ahead, play slower.
     */
    fun livePlaybackSpeed(
        videoLiveOffsetMs: Long,
        audioLiveOffsetMs: Long,
        desiredAudioDelayMs: Long,
        toleranceMs: Long = 150L
    ): Float {
        val drift = (audioLiveOffsetMs - videoLiveOffsetMs) - desiredAudioDelayMs
        return when {
            drift > toleranceMs -> 1.02f
            drift < -toleranceMs -> 0.98f
            else -> 1.0f
        }
    }
}
