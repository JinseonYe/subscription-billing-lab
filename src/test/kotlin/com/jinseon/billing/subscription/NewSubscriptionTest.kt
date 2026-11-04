package com.jinseon.billing.subscription

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class NewSubscriptionTest {
    @Test
    fun `신청을 문자열로 찍어도 빌링키가 나오지 않는다`() {
        val text = NewSubscription("customer-1", "BK-SECRET-123", 9_900L, "월 구독").toString()

        assertFalse(text.contains("BK-SECRET-123"), text)
        assertEquals("NewSubscription(customerKey=customer-1, amount=9900)", text)
    }

    @Test
    fun `금액이 0 이하이면 신청을 만들 수 없다`() {
        assertFailsWith<IllegalArgumentException> { NewSubscription("customer-1", "bk", 0L, "월 구독") }
        assertFailsWith<IllegalArgumentException> { NewSubscription("customer-1", "bk", -1L, "월 구독") }
    }
}
