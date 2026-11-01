package com.jinseon.billing.subscription

import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccessPolicyTest {
    private val periodEnd = Instant.parse("2026-02-28T03:00:00Z")

    @Test
    fun `ACTIVE는 기간이 끝나는 시각까지 이용할 수 있다`() {
        assertEquals(periodEnd, AccessPolicy.accessUntil(SubscriptionStatus.ACTIVE, periodEnd, renewalInFlight = false))
    }

    @Test
    fun `갱신 결제가 진행 중인 ACTIVE는 24시간 더 이용할 수 있다`() {
        assertEquals(
            periodEnd.plus(Duration.ofHours(24)),
            AccessPolicy.accessUntil(SubscriptionStatus.ACTIVE, periodEnd, renewalInFlight = true),
        )
    }

    @Test
    fun `PAST_DUE는 기간이 끝난 시각부터 7일까지 이용할 수 있다`() {
        assertEquals(
            periodEnd.plus(Duration.ofDays(7)),
            AccessPolicy.accessUntil(SubscriptionStatus.PAST_DUE, periodEnd, renewalInFlight = false),
        )
    }

    @Test
    fun `PAST_DUE의 이용 기한은 지난 기간의 끝보다 뒤라서 PAST_DUE가 되는 순간 이용이 막히지 않는다`() {
        val until = AccessPolicy.accessUntil(SubscriptionStatus.PAST_DUE, periodEnd, renewalInFlight = false)

        // 갱신 거절을 반영하는 시각은 기간 끝 이후다. 그 시각에도 이용할 수 있어야 한다.
        assertNotNull(until)
        assertTrue(until > periodEnd)
    }

    @Test
    fun `PENDING과 CANCELED는 이용할 수 없다`() {
        assertNull(AccessPolicy.accessUntil(SubscriptionStatus.PENDING, null, renewalInFlight = false))
        assertNull(AccessPolicy.accessUntil(SubscriptionStatus.PENDING, periodEnd, renewalInFlight = true))
        assertNull(AccessPolicy.accessUntil(SubscriptionStatus.CANCELED, periodEnd, renewalInFlight = false))
        assertNull(AccessPolicy.accessUntil(SubscriptionStatus.CANCELED, periodEnd, renewalInFlight = true))
    }

    @Test
    fun `기간 끝이 없는 ACTIVE와 PAST_DUE는 이용 불가로 본다`() {
        assertNull(AccessPolicy.accessUntil(SubscriptionStatus.ACTIVE, null, renewalInFlight = false))
        assertNull(AccessPolicy.accessUntil(SubscriptionStatus.PAST_DUE, null, renewalInFlight = false))
    }
}
