package com.jinseon.billing.subscription

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubscriptionChargeKeyTest {
    @Test
    fun `구독 id, 주기, 시도 번호로 SUB-id-Cn-Am 형식의 키를 만든다`() {
        assertEquals("SUB-7-C1-A1", SubscriptionChargeKey(7L, 1, 1).value)
        assertEquals("SUB-7-C12-A3", SubscriptionChargeKey(7L, 12, 3).value)
    }

    @Test
    fun `키를 만들고 읽으면 원래 값으로 돌아온다`() {
        val key = SubscriptionChargeKey(123L, 4, 2)

        assertEquals(key, SubscriptionChargeKey.parse(key.value))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "SUB-7-C1-A1 ",
            " SUB-7-C1-A1",
            "sub-7-c1-a1",
            "SUB-7-c1-A1",
            "SUB-7-C1",
            "SUB--C1-A1",
            "SUB-7-C0-A1",
            "SUB-7-C1-A0",
            "SUB-0-C1-A1",
            "SUB-07-C1-A1",
            "SUB-7-C-1-A1",
            "SUB-7-C1-A1-X",
            "SUB-1-2026-01",
        ],
    )
    fun `형식이 다른 문자열은 읽으면 null이다`(value: String) {
        assertNull(SubscriptionChargeKey.parse(value), value)
    }

    @Test
    fun `숫자가 Long이나 Int 범위를 넘으면 읽으면 null이다`() {
        assertNull(SubscriptionChargeKey.parse("SUB-9223372036854775808-C1-A1"))
        assertNull(SubscriptionChargeKey.parse("SUB-1-C2147483648-A1"))
        assertNull(SubscriptionChargeKey.parse("SUB-1-C1-A2147483648"))
    }

    @Test
    fun `0 이하의 번호로는 키를 만들 수 없다`() {
        assertFailsWith<IllegalArgumentException> { SubscriptionChargeKey(0L, 1, 1) }
        assertFailsWith<IllegalArgumentException> { SubscriptionChargeKey(1L, 0, 1) }
        assertFailsWith<IllegalArgumentException> { SubscriptionChargeKey(1L, 1, 0) }
        assertFailsWith<IllegalArgumentException> { SubscriptionChargeKey(-1L, 1, 1) }
    }

    @Test
    fun `가장 큰 번호로 만들어도 charge_key 컬럼 길이 100을 넘지 않는다`() {
        val key = SubscriptionChargeKey(Long.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)

        assertTrue(key.value.length <= 100, "길이 ${key.value.length}: ${key.value}")
        assertEquals(key, SubscriptionChargeKey.parse(key.value))
    }

    @Test
    fun `키는 대문자 ASCII와 숫자와 하이픈만 쓴다`() {
        assertTrue(Regex("[A-Z0-9-]+").matches(SubscriptionChargeKey(42L, 3, 1).value))
    }
}
