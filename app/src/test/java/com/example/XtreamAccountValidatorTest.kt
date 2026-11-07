package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class XtreamAccountValidatorTest {
    // Synthetic response fields live only in this test class, never in application assets.
    private data class ApiFixture(val auth: Int, val status: String, val expiration: String)

    private fun fixture(name: String): ApiFixture = when (name) {
        "active" -> ApiFixture(1, "Active", "1893456000")
        "expired-status" -> ApiFixture(1, "Expired", "1609459200")
        "expired-timestamp" -> ApiFixture(1, "Active", "1609459200")
        "unauthorized" -> ApiFixture(0, "Disabled", "1893456000")
        else -> error("Unknown fixture: $name")
    }

    @Test
    fun acceptsActiveSubscriptionResponse() {
        val response = fixture("active")
        XtreamAccountValidator.validateFields(response.auth, response.status, response.expiration, 1_700_000_000_000L)
        assertEquals("Active", response.status)
    }

    @Test
    fun rejectsExpiredStatus() {
        val response = fixture("expired-status")
        val error = assertThrows(IllegalArgumentException::class.java) {
            XtreamAccountValidator.validateFields(response.auth, response.status, response.expiration, 1_700_000_000_000L)
        }
        assertEquals("الاشتراك منتهي أو موقوف: Expired", error.message)
    }

    @Test
    fun rejectsPastExpirationTimestampEvenWhenStatusSaysActive() {
        val response = fixture("expired-timestamp")
        assertThrows(IllegalArgumentException::class.java) {
            XtreamAccountValidator.validateFields(response.auth, response.status, response.expiration, 1_700_000_000_000L)
        }
    }

    @Test
    fun rejectsMalformedExpirationTimestamp() {
        assertThrows(IllegalArgumentException::class.java) {
            XtreamAccountValidator.validateFields(1, "Active", "not-a-timestamp", 1_700_000_000_000L)
        }
    }

    @Test
    fun rejectsNegativeExpirationTimestamp() {
        assertThrows(IllegalArgumentException::class.java) {
            XtreamAccountValidator.validateFields(1, "Active", "-1", 1_700_000_000_000L)
        }
    }

    @Test
    fun acceptsUnlimitedSubscriptionWithNullOrZeroExpiration() {
        XtreamAccountValidator.validateFields(1, "Active", "0", 1_700_000_000_000L)
        XtreamAccountValidator.validateFields(1, "Active", "null", 1_700_000_000_000L)
    }

    @Test
    fun rejectsMissingSubscriptionStatus() {
        assertThrows(IllegalArgumentException::class.java) {
            XtreamAccountValidator.validateFields(1, "", "1893456000", 1_700_000_000_000L)
        }
    }

    @Test
    fun rejectsBlockedSubscriptionStatus() {
        assertThrows(IllegalArgumentException::class.java) {
            XtreamAccountValidator.validateFields(1, "Blocked", "1893456000", 1_700_000_000_000L)
        }
    }

    @Test
    fun rejectsUnauthorizedResponse() {
        val response = fixture("unauthorized")
        assertThrows(IllegalArgumentException::class.java) {
            XtreamAccountValidator.validateFields(response.auth, response.status, response.expiration, 1_700_000_000_000L)
        }
    }
}
