package com.jinseon.billing.charge

import com.jinseon.billing.payment.Payment
import com.jinseon.billing.payment.PaymentStatus
import com.jinseon.billing.subscription.PaymentFact
import org.junit.jupiter.api.Test
import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PaymentFactsTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val approvedAt = Instant.parse("2026-01-01T00:00:05Z")

    private fun paymentIn(status: PaymentStatus): Payment {
        val payment = Payment("order-1", "SUB-7-C1-A1", "mock-billing-key", "customer-1", 9_900L, "월 구독", now)
        when (status) {
            PaymentStatus.REQUESTED -> Unit
            PaymentStatus.UNKNOWN -> payment.markUnknown()
            PaymentStatus.APPROVED -> payment.approve("pk-1", approvedAt, now)
            PaymentStatus.FAILED -> payment.fail("REJECT_CARD_PAYMENT", "카드 거절", now)
        }
        return payment
    }

    @Test
    fun `결제 행이 없으면 NoPayment다`() {
        assertEquals(PaymentFact.NoPayment, paymentFactOf(null))
    }

    @Test
    fun `결과를 모르는 결제는 Pending이다`() {
        assertEquals(PaymentFact.Pending, paymentFactOf(paymentIn(PaymentStatus.REQUESTED)))
        assertEquals(PaymentFact.Pending, paymentFactOf(paymentIn(PaymentStatus.UNKNOWN)))
    }

    @Test
    fun `승인된 결제는 승인 시각과 함께 Approved다`() {
        assertEquals(PaymentFact.Approved(approvedAt), paymentFactOf(paymentIn(PaymentStatus.APPROVED)))
    }

    @Test
    fun `거절된 결제는 Declined다`() {
        assertEquals(PaymentFact.Declined, paymentFactOf(paymentIn(PaymentStatus.FAILED)))
    }

    @Test
    fun `승인 시각이 없는 APPROVED는 있을 수 없는 상태라서 예외다`() {
        val payment = paymentIn(PaymentStatus.APPROVED)
        ReflectionTestUtils.setField(payment, "approvedAt", null)

        assertFailsWith<IllegalStateException> { paymentFactOf(payment) }
    }
}
