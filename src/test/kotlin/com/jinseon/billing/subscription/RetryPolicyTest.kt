package com.jinseon.billing.subscription

import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals

class RetryPolicyTest {
    @Test
    fun `첫 재시도는 주기 끝에서 하루 뒤다`() {
        val periodEnd = Instant.parse("2026-02-28T03:00:00Z")

        assertEquals(Instant.parse("2026-03-01T03:00:00Z"), RetryPolicy.firstRetryAt(periodEnd))
    }

    @Test
    fun `재시도 일정은 주기 끝에서 1일 3일 5일 뒤다`() {
        assertEquals(listOf(Duration.ofDays(1), Duration.ofDays(3), Duration.ofDays(5)), RetryPolicy.SCHEDULE)
    }
}
