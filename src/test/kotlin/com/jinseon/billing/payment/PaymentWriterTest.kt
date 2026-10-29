package com.jinseon.billing.payment

import com.jinseon.billing.TestcontainersConfiguration
import com.jinseon.billing.pg.PgResult
import com.jinseon.billing.support.MutableClock
import com.jinseon.billing.support.MutableClockConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.repository.findByIdOrNull
import java.time.Instant
import java.util.stream.Stream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class, MutableClockConfiguration::class)
@SpringBootTest
class PaymentWriterTest(
    @Autowired private val paymentWriter: PaymentWriter,
    @Autowired private val paymentRepository: PaymentRepository,
    @Autowired private val clock: MutableClock,
) {
    @BeforeEach
    fun setUp() {
        paymentRepository.deleteAll()
        clock.set(Instant.parse("2026-01-01T00:00:00Z"))
    }

    private fun newPayment(chargeKey: String = "SUB-1-2026-01") = NewPayment(chargeKey, "mock-billing-key", "customer-1", 9_900L, "월 구독")

    // 실제 흐름과 같은 방법(writer를 거침)으로 원하는 상태의 결제를 만든다.
    private fun paymentIn(status: PaymentStatus): Payment {
        val created = paymentWriter.createRequested(newPayment())
        when (status) {
            PaymentStatus.REQUESTED -> Unit
            PaymentStatus.UNKNOWN -> paymentWriter.applyResult(created.id, unknown)
            PaymentStatus.APPROVED -> paymentWriter.applyResult(created.id, approved)
            PaymentStatus.FAILED -> paymentWriter.applyResult(created.id, declined)
        }
        return reload(created.id)
    }

    private fun reload(id: Long): Payment = assertNotNull(paymentRepository.findByIdOrNull(id))

    @Test
    fun `결제를 만들면 REQUESTED로 저장되고 orderId와 요청 시각이 채워진다`() {
        val created = paymentWriter.createRequested(newPayment())

        val saved = reload(created.id)
        assertEquals(PaymentStatus.REQUESTED, saved.status)
        assertTrue(Regex("[A-Za-z0-9_=-]{6,64}").matches(saved.orderId), "토스 orderId 규칙: ${saved.orderId}")
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), saved.requestedAt)
        assertEquals(9_900L, saved.amount)
    }

    @Test
    fun `같은 chargeKey로 두 번 만들면 두 번째는 막히고 행은 하나만 남는다`() {
        paymentWriter.createRequested(newPayment("SUB-1-2026-01"))

        assertFailsWith<DataIntegrityViolationException> { paymentWriter.createRequested(newPayment("SUB-1-2026-01")) }

        assertEquals(1, paymentRepository.count())
    }

    @Test
    fun `결제마다 orderId가 다르다`() {
        val first = paymentWriter.createRequested(newPayment("SUB-1-2026-01"))
        val second = paymentWriter.createRequested(newPayment("SUB-2-2026-01"))

        assertTrue(first.orderId != second.orderId)
    }

    @ParameterizedTest(name = "{0} 상태에서 {1}를 받으면 {2}가 된다")
    @MethodSource("reflectionRules")
    fun `결과 반영 규칙표대로 상태가 정해진다`(
        start: PaymentStatus,
        result: PgResult,
        expected: PaymentStatus,
    ) {
        val payment = paymentIn(start)

        paymentWriter.applyResult(payment.id, result)

        assertEquals(expected, reload(payment.id).status)
    }

    @Test
    fun `승인되면 paymentKey와 승인 시각과 확정 시각이 기록된다`() {
        val payment = paymentIn(PaymentStatus.REQUESTED)
        clock.set(Instant.parse("2026-01-01T00:05:00Z"))

        paymentWriter.applyResult(payment.id, approved)

        val saved = reload(payment.id)
        assertEquals("pk-1", saved.pgPaymentKey)
        assertEquals(approved.approvedAt, saved.approvedAt)
        assertEquals(Instant.parse("2026-01-01T00:05:00Z"), saved.resolvedAt)
    }

    @Test
    fun `FAILED로 기록된 결제에 승인이 오면 APPROVED로 바로잡히고 거절 이력은 남는다`() {
        val payment = paymentIn(PaymentStatus.FAILED)

        paymentWriter.applyResult(payment.id, approved)

        val saved = reload(payment.id)
        assertEquals(PaymentStatus.APPROVED, saved.status)
        assertEquals("pk-1", saved.pgPaymentKey)
        assertEquals("REJECT_CARD_PAYMENT", saved.failureCode)
    }

    @Test
    fun `이미 승인된 결제에 다른 paymentKey의 승인이 와도 기존 승인 정보를 덮어쓰지 않는다`() {
        val payment = paymentIn(PaymentStatus.APPROVED)

        paymentWriter.applyResult(payment.id, PgResult.Approved("pk-OTHER", Instant.parse("2026-01-02T00:00:00Z")))

        val saved = reload(payment.id)
        assertEquals("pk-1", saved.pgPaymentKey)
        assertEquals(approved.approvedAt, saved.approvedAt)
    }

    @Test
    fun `이미 승인된 결제에 거절이 와도 승인 상태와 정보가 그대로다`() {
        val payment = paymentIn(PaymentStatus.APPROVED)

        paymentWriter.applyResult(payment.id, declined)

        val saved = reload(payment.id)
        assertEquals(PaymentStatus.APPROVED, saved.status)
        assertNull(saved.failureCode)
    }

    @Test
    fun `존재하지 않는 결제에 결과를 반영하면 예외가 난다`() {
        assertFailsWith<IllegalStateException> { paymentWriter.applyResult(9_999L, approved) }
    }

    companion object {
        private val approved = PgResult.Approved("pk-1", Instant.parse("2026-01-01T00:00:03Z"))
        private val declined = PgResult.Declined("REJECT_CARD_PAYMENT", "카드 거절")
        private val unknown = PgResult.Unknown("타임아웃")

        // 설계 문서의 반영 규칙표 (현재 상태 x 결과) 전체 16칸. NotFound는 Unknown과 같게 다룬다.
        @JvmStatic
        fun reflectionRules(): Stream<Arguments> =
            Stream.of(
                Arguments.of(PaymentStatus.REQUESTED, approved, PaymentStatus.APPROVED),
                Arguments.of(PaymentStatus.REQUESTED, declined, PaymentStatus.FAILED),
                Arguments.of(PaymentStatus.REQUESTED, unknown, PaymentStatus.UNKNOWN),
                Arguments.of(PaymentStatus.REQUESTED, PgResult.NotFound, PaymentStatus.UNKNOWN),
                Arguments.of(PaymentStatus.UNKNOWN, approved, PaymentStatus.APPROVED),
                Arguments.of(PaymentStatus.UNKNOWN, declined, PaymentStatus.FAILED),
                Arguments.of(PaymentStatus.UNKNOWN, unknown, PaymentStatus.UNKNOWN),
                Arguments.of(PaymentStatus.UNKNOWN, PgResult.NotFound, PaymentStatus.UNKNOWN),
                Arguments.of(PaymentStatus.APPROVED, approved, PaymentStatus.APPROVED),
                Arguments.of(PaymentStatus.APPROVED, declined, PaymentStatus.APPROVED),
                Arguments.of(PaymentStatus.APPROVED, unknown, PaymentStatus.APPROVED),
                Arguments.of(PaymentStatus.APPROVED, PgResult.NotFound, PaymentStatus.APPROVED),
                Arguments.of(PaymentStatus.FAILED, approved, PaymentStatus.APPROVED),
                Arguments.of(PaymentStatus.FAILED, declined, PaymentStatus.FAILED),
                Arguments.of(PaymentStatus.FAILED, unknown, PaymentStatus.FAILED),
                Arguments.of(PaymentStatus.FAILED, PgResult.NotFound, PaymentStatus.FAILED),
            )
    }
}
