package com.jinseon.billing.payment

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PaymentTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val approvedAt = Instant.parse("2026-01-01T00:00:05Z")

    private fun newPayment() =
        Payment(
            orderId = "order-1",
            chargeKey = "SUB-1-2026-01",
            billingKey = "mock-billing-key",
            customerKey = "customer-1",
            amount = 9_900L,
            orderName = "월 구독",
            requestedAt = now,
        )

    private fun paymentIn(status: PaymentStatus): Payment {
        val payment = newPayment()
        when (status) {
            PaymentStatus.REQUESTED -> Unit
            PaymentStatus.UNKNOWN -> payment.markUnknown()
            PaymentStatus.APPROVED -> payment.approve("pk-1", approvedAt, now)
            PaymentStatus.FAILED -> payment.fail("REJECT_CARD_PAYMENT", "카드 거절", now)
        }
        return payment
    }

    @Test
    fun `새 결제는 REQUESTED 상태로 시작한다`() {
        val payment = newPayment()

        assertEquals(PaymentStatus.REQUESTED, payment.status)
        assertNull(payment.pgPaymentKey)
        assertNull(payment.resolvedAt)
    }

    @Test
    fun `REQUESTED에서 승인하면 APPROVED가 되고 승인 정보가 기록된다`() {
        val payment = newPayment()

        payment.approve("pk-1", approvedAt, now)

        assertEquals(PaymentStatus.APPROVED, payment.status)
        assertEquals("pk-1", payment.pgPaymentKey)
        assertEquals(approvedAt, payment.approvedAt)
        assertEquals(now, payment.resolvedAt)
    }

    @Test
    fun `REQUESTED에서 거절하면 FAILED가 되고 거절 사유가 기록된다`() {
        val payment = newPayment()

        payment.fail("REJECT_CARD_PAYMENT", "카드 거절", now)

        assertEquals(PaymentStatus.FAILED, payment.status)
        assertEquals("REJECT_CARD_PAYMENT", payment.failureCode)
        assertEquals("카드 거절", payment.failureMessage)
    }

    @Test
    fun `REQUESTED에서 UNKNOWN으로 바꿀 수 있다`() {
        val payment = newPayment()

        payment.markUnknown()

        assertEquals(PaymentStatus.UNKNOWN, payment.status)
    }

    @Test
    fun `UNKNOWN에서 승인하면 APPROVED가 된다`() {
        val payment = paymentIn(PaymentStatus.UNKNOWN)

        payment.approve("pk-1", approvedAt, now)

        assertEquals(PaymentStatus.APPROVED, payment.status)
    }

    @Test
    fun `UNKNOWN에서 거절하면 FAILED가 된다`() {
        val payment = paymentIn(PaymentStatus.UNKNOWN)

        payment.fail("REJECT_CARD_PAYMENT", "카드 거절", now)

        assertEquals(PaymentStatus.FAILED, payment.status)
    }

    @Test
    fun `FAILED로 기록된 결제도 결제사가 승인했다면 APPROVED로 바로잡을 수 있고 거절 이력은 남는다`() {
        val payment = paymentIn(PaymentStatus.FAILED)

        payment.approve("pk-1", approvedAt, now)

        assertEquals(PaymentStatus.APPROVED, payment.status)
        assertEquals("pk-1", payment.pgPaymentKey)
        assertEquals("REJECT_CARD_PAYMENT", payment.failureCode)
    }

    @Test
    fun `APPROVED 결제는 어떤 상태로도 바뀔 수 없다`() {
        val payment = paymentIn(PaymentStatus.APPROVED)

        assertFailsWith<IllegalStateException> { payment.approve("pk-2", approvedAt, now) }
        assertFailsWith<IllegalStateException> { payment.fail("CODE", "메시지", now) }
        assertFailsWith<IllegalStateException> { payment.markUnknown() }
        assertEquals(PaymentStatus.APPROVED, payment.status)
        assertEquals("pk-1", payment.pgPaymentKey)
    }

    @Test
    fun `FAILED에서 다시 FAILED나 UNKNOWN으로 바꿀 수 없다`() {
        val payment = paymentIn(PaymentStatus.FAILED)

        assertFailsWith<IllegalStateException> { payment.fail("CODE", "메시지", now) }
        assertFailsWith<IllegalStateException> { payment.markUnknown() }
        assertEquals(PaymentStatus.FAILED, payment.status)
    }

    @Test
    fun `UNKNOWN에서 다시 UNKNOWN으로 바꿀 수 없다`() {
        val payment = paymentIn(PaymentStatus.UNKNOWN)

        assertFailsWith<IllegalStateException> { payment.markUnknown() }
    }

    @Test
    fun `상태 전이 허용표는 설계와 정확히 일치한다`() {
        val allowed =
            setOf(
                PaymentStatus.REQUESTED to PaymentStatus.APPROVED,
                PaymentStatus.REQUESTED to PaymentStatus.FAILED,
                PaymentStatus.REQUESTED to PaymentStatus.UNKNOWN,
                PaymentStatus.UNKNOWN to PaymentStatus.APPROVED,
                PaymentStatus.UNKNOWN to PaymentStatus.FAILED,
                PaymentStatus.FAILED to PaymentStatus.APPROVED,
            )

        for (from in PaymentStatus.entries) {
            for (to in PaymentStatus.entries) {
                assertEquals((from to to) in allowed, from.canTransitionTo(to), "$from -> $to")
            }
        }
    }

    @Test
    fun `결제 요청을 문자열로 찍어도 빌링키가 나오지 않는다`() {
        val text = NewPayment("SUB-1", "BK-SECRET-123", "customer-1", 9_900L, "월 구독").toString()

        assertFalse(text.contains("BK-SECRET-123"), text)
    }

    @Test
    fun `금액이 0 이하이면 결제 요청을 만들 수 없다`() {
        assertFailsWith<IllegalArgumentException> { NewPayment("key", "bk", "ck", 0L, "이름") }
        assertFailsWith<IllegalArgumentException> { NewPayment("key", "bk", "ck", -1L, "이름") }
    }
}
