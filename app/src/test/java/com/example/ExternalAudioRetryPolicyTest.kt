package com.example

import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalAudioRetryPolicyTest {
    @Test fun retryDelayMatchesSprtxxxBackoffSchedule() {
        assertEquals(1000L, externalAudioRetryDelayMs(1))
        assertEquals(2000L, externalAudioRetryDelayMs(2))
        assertEquals(4000L, externalAudioRetryDelayMs(3))
        assertEquals(8000L, externalAudioRetryDelayMs(4))
        assertEquals(16000L, externalAudioRetryDelayMs(5))
        assertEquals(30000L, externalAudioRetryDelayMs(6))
        assertEquals(30000L, externalAudioRetryDelayMs(20))
    }

    @Test fun firstRetryDelayIsUsedForZeroOrNegativeAttempt() {
        assertEquals(1000L, externalAudioRetryDelayMs(0))
        assertEquals(1000L, externalAudioRetryDelayMs(-3))
    }

    @Test fun positiveOffsetDelaysAudioFromVideoReferenceWithoutAccumulation() {
        assertEquals(9500L, ExternalAudioSyncPolicy.targetPositionMs(10000L, 500L, 30000L))
        assertEquals(8000L, ExternalAudioSyncPolicy.targetPositionMs(10000L, 2000L, 30000L))
        assertEquals(9500L, ExternalAudioSyncPolicy.targetPositionMs(10000L, 500L, 30000L))
    }

    @Test fun negativeOffsetAdvancesAudioAndBoundsTarget() {
        assertEquals(10500L, ExternalAudioSyncPolicy.targetPositionMs(10000L, -500L, 30000L))
        assertEquals(0L, ExternalAudioSyncPolicy.targetPositionMs(100L, 500L, 30000L))
        assertEquals(12000L, ExternalAudioSyncPolicy.targetPositionMs(20000L, -500L, 12000L))
    }

    @Test fun liveDriftCorrectionUsesToleranceAndDirection() {
        assertEquals(1.02f, ExternalAudioSyncPolicy.livePlaybackSpeed(1000L, 1400L, 0L), 0.001f)
        assertEquals(0.98f, ExternalAudioSyncPolicy.livePlaybackSpeed(1000L, 500L, 0L), 0.001f)
        assertEquals(1.0f, ExternalAudioSyncPolicy.livePlaybackSpeed(1000L, 1100L, 0L), 0.001f)
        assertEquals(1.02f, ExternalAudioSyncPolicy.livePlaybackSpeed(1000L, 1800L, 500L), 0.001f)
    }
}
