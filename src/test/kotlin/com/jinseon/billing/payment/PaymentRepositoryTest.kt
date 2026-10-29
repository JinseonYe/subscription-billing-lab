package com.jinseon.billing.payment

import com.jinseon.billing.TestcontainersConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@Import(TestcontainersConfiguration::class)
@SpringBootTest
class PaymentRepositoryTest(
    @Autowired private val paymentRepository: PaymentRepository,
) {
    private val base = Instant.parse("2026-01-01T00:00:00Z")

    @BeforeEach
    fun setUp() {
        paymentRepository.deleteAll()
    }

    private fun save(
        chargeKey: String,
        requestedAt: Instant,
        status: PaymentStatus = PaymentStatus.REQUESTED,
    ): Payment {
        val payment =
            Payment(
                orderId = "order-$chargeKey",
                chargeKey = chargeKey,
                billingKey = "mock-billing-key",
                customerKey = "customer-1",
                amount = 9_900L,
                orderName = "월 구독",
                requestedAt = requestedAt,
            )
        when (status) {
            PaymentStatus.REQUESTED -> Unit
            PaymentStatus.UNKNOWN -> payment.markUnknown()
            PaymentStatus.APPROVED -> payment.approve("pk-$chargeKey", requestedAt, requestedAt)
            PaymentStatus.FAILED -> payment.fail("REJECT_CARD_PAYMENT", "카드 거절", requestedAt)
        }
        return paymentRepository.save(payment)
    }

    @Test
    fun `chargeKey로 결제를 찾고 없으면 null이다`() {
        save("SUB-1", base)

        assertNotNull(paymentRepository.findByChargeKey("SUB-1"))
        assertNull(paymentRepository.findByChargeKey("SUB-없음"))
    }

    @Test
    fun `확정 대상은 REQUESTED와 UNKNOWN 중 기준 시각보다 오래된 건만 오래된 순서로 나온다`() {
        save("old-unknown", base.plusSeconds(10), PaymentStatus.UNKNOWN)
        save("oldest-requested", base)
        save("old-approved", base.plusSeconds(5), PaymentStatus.APPROVED)
        save("old-failed", base.plusSeconds(6), PaymentStatus.FAILED)
        save("recent-requested", base.plusSeconds(600))

        val found =
            paymentRepository.findResolvable(
                listOf(PaymentStatus.REQUESTED, PaymentStatus.UNKNOWN),
                base.plusSeconds(300),
            )

        assertEquals(listOf("oldest-requested", "old-unknown"), found.map { it.chargeKey })
    }
}
