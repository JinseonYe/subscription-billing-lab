package com.jinseon.billing.subscription

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SubscriptionStatusTest {
    @Test
    fun `상태 전이 허용표는 설계와 정확히 일치한다`() {
        val allowed =
            setOf(
                SubscriptionStatus.PENDING to SubscriptionStatus.ACTIVE,
                SubscriptionStatus.PENDING to SubscriptionStatus.CANCELED,
                SubscriptionStatus.ACTIVE to SubscriptionStatus.PAST_DUE,
                SubscriptionStatus.ACTIVE to SubscriptionStatus.CANCELED,
                SubscriptionStatus.PAST_DUE to SubscriptionStatus.ACTIVE,
                SubscriptionStatus.PAST_DUE to SubscriptionStatus.CANCELED,
            )

        for (from in SubscriptionStatus.entries) {
            for (to in SubscriptionStatus.entries) {
                assertEquals((from to to) in allowed, from.canTransitionTo(to), "$from -> $to")
            }
        }
    }

    @Test
    fun `CANCELED는 종결 상태라서 어디로도 갈 수 없다`() {
        for (to in SubscriptionStatus.entries) {
            assertFalse(SubscriptionStatus.CANCELED.canTransitionTo(to), "CANCELED -> $to")
        }
    }

    @Test
    fun `자기 자신으로 가는 전이는 없다`() {
        for (status in SubscriptionStatus.entries) {
            assertFalse(status.canTransitionTo(status), "$status -> $status")
        }
    }
}
