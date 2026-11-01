package com.jinseon.billing.subscription

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BillingPeriodTest {
    // 서울 시각으로 읽기 쉽게 +09:00 문자열로 만든다.
    private fun kst(value: String): Instant = OffsetDateTime.parse("$value+09:00").toInstant()

    @Test
    fun `첫 주기는 앵커부터 한 달 뒤까지다`() {
        val anchor = kst("2026-01-10T12:00:00")

        val period = BillingPeriod.of(anchor, 1)

        assertEquals(anchor, period.start)
        assertEquals(kst("2026-02-10T12:00:00"), period.end)
    }

    @Test
    fun `다음 주기의 시작은 이전 주기의 끝과 같다`() {
        val anchor = kst("2026-01-31T12:00:00")

        for (cycle in 1..11) {
            assertEquals(BillingPeriod.of(anchor, cycle).end, BillingPeriod.of(anchor, cycle + 1).start, "주기 $cycle -> ${cycle + 1}")
        }
    }

    @Test
    fun `1월 31일 앵커는 말일을 보정하되 결제일이 28일로 밀리지 않는다`() {
        val anchor = kst("2026-01-31T12:00:00")

        assertEquals(kst("2026-02-28T12:00:00"), BillingPeriod.of(anchor, 1).end)
        // 이전 기간 끝(2월 28일)에 한 달을 더하는 구현이면 3월 28일이 된다.
        assertEquals(kst("2026-03-31T12:00:00"), BillingPeriod.of(anchor, 2).end)
        assertEquals(kst("2026-04-30T12:00:00"), BillingPeriod.of(anchor, 3).end)
        assertEquals(kst("2026-05-31T12:00:00"), BillingPeriod.of(anchor, 4).end)
    }

    @Test
    fun `윤년 2월은 29일로 보정한다`() {
        val anchor = kst("2028-01-31T12:00:00")

        assertEquals(kst("2028-02-29T12:00:00"), BillingPeriod.of(anchor, 1).end)
        assertEquals(kst("2028-03-31T12:00:00"), BillingPeriod.of(anchor, 2).end)
    }

    @Test
    fun `해가 바뀌어도 앵커의 날짜와 시각을 유지한다`() {
        val anchor = kst("2026-11-15T09:30:00")

        assertEquals(kst("2026-12-15T09:30:00"), BillingPeriod.of(anchor, 1).end)
        assertEquals(kst("2027-01-15T09:30:00"), BillingPeriod.of(anchor, 2).end)
        assertEquals(kst("2027-11-15T09:30:00"), BillingPeriod.of(anchor, 13).start)
    }

    @Test
    fun `달력은 UTC가 아니라 서울 시간 기준이다`() {
        // UTC로는 2월 28일 15:30이지만 서울로는 3월 1일 00:30이다. 서울 기준이면 4월 1일 00:30이 한 달 뒤다.
        val anchor = Instant.parse("2026-02-28T15:30:00Z")

        assertEquals(Instant.parse("2026-03-31T15:30:00Z"), BillingPeriod.of(anchor, 1).end)
    }

    @Test
    fun `주기 번호가 0 이하이면 기간을 만들 수 없다`() {
        assertFailsWith<IllegalArgumentException> { BillingPeriod.of(kst("2026-01-01T00:00:00"), 0) }
        assertFailsWith<IllegalArgumentException> { BillingPeriod.of(kst("2026-01-01T00:00:00"), -1) }
    }
}
