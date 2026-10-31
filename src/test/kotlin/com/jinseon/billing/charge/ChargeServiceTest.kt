package com.jinseon.billing.charge

import com.jinseon.billing.payment.NewPayment
import com.jinseon.billing.payment.Payment
import com.jinseon.billing.payment.PaymentRepository
import com.jinseon.billing.payment.PaymentStatus
import com.jinseon.billing.payment.PaymentWriter
import com.jinseon.billing.pg.PgApproveRequest
import com.jinseon.billing.pg.PgClient
import com.jinseon.billing.pg.PgResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ChargeServiceTest {
    private val repository = mockk<PaymentRepository>()
    private val writer = mockk<PaymentWriter>()
    private val pgClient = mockk<PgClient>()
    private val service = ChargeService(repository, writer, pgClient)

    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val command = NewPayment("SUB-1-2026-01", "mock-billing-key", "customer-1", 9_900L, "월 구독")
    private val approved = PgResult.Approved("pk-1", now)

    private fun paymentIn(status: PaymentStatus): Payment {
        val payment = Payment("order-1", command.chargeKey, command.billingKey, command.customerKey, command.amount, command.orderName, now)
        when (status) {
            PaymentStatus.REQUESTED -> Unit
            PaymentStatus.UNKNOWN -> payment.markUnknown()
            PaymentStatus.APPROVED -> payment.approve("pk-1", now, now)
            PaymentStatus.FAILED -> payment.fail("REJECT_CARD_PAYMENT", "카드 거절", now)
        }
        return payment
    }

    private val approveRequest = PgApproveRequest("order-1", "mock-billing-key", "customer-1", 9_900L, "월 구독")

    @Test
    fun `정상이면 결제를 만들고 결제사에 승인 요청한 뒤 결과를 반영해 Approved를 돌려준다`() {
        every { repository.findByChargeKey(command.chargeKey) } returns null
        every { writer.createRequested(command) } returns paymentIn(PaymentStatus.REQUESTED)
        every { pgClient.approve(approveRequest) } returns approved
        every { writer.applyResult(any(), approved) } returns paymentIn(PaymentStatus.APPROVED)

        val outcome = service.charge(command)

        assertEquals(ChargeOutcome.Approved(0L), outcome)
        verify(exactly = 1) { pgClient.approve(approveRequest) }
    }

    @Test
    fun `결제사가 거절하면 Declined를 돌려준다`() {
        every { repository.findByChargeKey(command.chargeKey) } returns null
        every { writer.createRequested(command) } returns paymentIn(PaymentStatus.REQUESTED)
        every { pgClient.approve(any()) } returns PgResult.Declined("REJECT_CARD_PAYMENT", "카드 거절")
        every { writer.applyResult(any(), any()) } returns paymentIn(PaymentStatus.FAILED)

        assertEquals(ChargeOutcome.Declined(0L, "REJECT_CARD_PAYMENT"), service.charge(command))
    }

    @Test
    fun `결제사 결과를 모르면 Pending이다`() {
        every { repository.findByChargeKey(command.chargeKey) } returns null
        every { writer.createRequested(command) } returns paymentIn(PaymentStatus.REQUESTED)
        every { pgClient.approve(any()) } returns PgResult.Unknown("타임아웃")
        every { writer.applyResult(any(), any()) } returns paymentIn(PaymentStatus.UNKNOWN)

        assertEquals(ChargeOutcome.Pending(0L), service.charge(command))
    }

    @Test
    fun `결제사 호출에서 예외가 나도 밖으로 던지지 않고 Pending이며 결과 반영을 시도하지 않는다`() {
        every { repository.findByChargeKey(command.chargeKey) } returns null
        every { writer.createRequested(command) } returns paymentIn(PaymentStatus.REQUESTED)
        every { pgClient.approve(any()) } throws IllegalStateException("예상하지 못한 오류")

        assertEquals(ChargeOutcome.Pending(0L), service.charge(command))
        verify(exactly = 0) { writer.applyResult(any(), any()) }
    }

    @Test
    fun `결과를 DB에 반영하다 예외가 나도 밖으로 던지지 않고 Pending이다`() {
        every { repository.findByChargeKey(command.chargeKey) } returns null
        every { writer.createRequested(command) } returns paymentIn(PaymentStatus.REQUESTED)
        every { pgClient.approve(any()) } returns approved
        every { writer.applyResult(any(), any()) } throws RuntimeException("DB 장애")

        assertEquals(ChargeOutcome.Pending(0L), service.charge(command))
    }

    @Test
    fun `같은 chargeKey의 결제가 이미 있으면 결제사를 부르지 않고 상태에 맞는 결과를 돌려준다`() {
        val expected =
            mapOf(
                PaymentStatus.APPROVED to ChargeOutcome.Approved(0L),
                PaymentStatus.FAILED to ChargeOutcome.Declined(0L, "REJECT_CARD_PAYMENT"),
                PaymentStatus.REQUESTED to ChargeOutcome.Pending(0L),
                PaymentStatus.UNKNOWN to ChargeOutcome.Pending(0L),
            )

        for ((status, outcome) in expected) {
            every { repository.findByChargeKey(command.chargeKey) } returns paymentIn(status)

            assertEquals(outcome, service.charge(command), "기존 결제 상태 $status")
        }
        verify(exactly = 0) { writer.createRequested(any()) }
        verify(exactly = 0) { pgClient.approve(any()) }
    }

    @Test
    fun `동시에 같은 chargeKey가 UNIQUE에 걸리면 먼저 만든 결제의 결과를 돌려주고 결제사를 부르지 않는다`() {
        every { repository.findByChargeKey(command.chargeKey) } returnsMany listOf(null, paymentIn(PaymentStatus.REQUESTED))
        every { writer.createRequested(command) } throws DataIntegrityViolationException("Duplicate entry")

        assertEquals(ChargeOutcome.Pending(0L), service.charge(command))
        verify(exactly = 0) { pgClient.approve(any()) }
    }

    @Test
    fun `UNIQUE 위반인데 먼저 만든 결제도 찾을 수 없으면 원래 예외를 던진다`() {
        every { repository.findByChargeKey(command.chargeKey) } returns null
        every { writer.createRequested(command) } throws DataIntegrityViolationException("다른 제약 위반")

        assertFailsWith<DataIntegrityViolationException> { service.charge(command) }
        verify(exactly = 0) { pgClient.approve(any()) }
    }

    @Test
    fun `결제를 기록하지 못하면 결제사를 부르지 않고 예외를 그대로 던진다`() {
        every { repository.findByChargeKey(command.chargeKey) } returns null
        every { writer.createRequested(command) } throws IllegalStateException("DB 연결 실패")

        assertFailsWith<IllegalStateException> { service.charge(command) }
        verify(exactly = 0) { pgClient.approve(any()) }
    }

    @Test
    fun `같은 chargeKey인데 금액이나 고객이나 결제 수단이 다른 요청이 오면 예외를 던지고 결제사를 부르지 않는다`() {
        val changedCommands =
            mapOf(
                "amount" to command.copy(amount = 19_900L),
                "customerKey" to command.copy(customerKey = "customer-OTHER"),
                "billingKey" to command.copy(billingKey = "other-billing-key"),
            )
        every { repository.findByChargeKey(command.chargeKey) } returns paymentIn(PaymentStatus.APPROVED)

        for ((field, changedCommand) in changedCommands) {
            val error = assertFailsWith<ChargeRequestMismatchException>(field) { service.charge(changedCommand) }

            assertEquals(listOf(field), error.changedFields)
            val message = error.message.orEmpty()
            assertFalse(message.contains("mock-billing-key") || message.contains("other-billing-key"), "예외 메시지에 빌링키가 노출됨: $message")
        }
        verify(exactly = 0) { writer.createRequested(any()) }
        verify(exactly = 0) { pgClient.approve(any()) }
    }

    @Test
    fun `동시 요청 경합으로 먼저 만든 결제를 찾았는데 내용이 다르면 그 결과를 돌려주지 않고 예외를 던진다`() {
        every { repository.findByChargeKey(command.chargeKey) } returnsMany listOf(null, paymentIn(PaymentStatus.APPROVED))
        every { writer.createRequested(any()) } throws DataIntegrityViolationException("Duplicate entry")

        assertFailsWith<ChargeRequestMismatchException> { service.charge(command.copy(amount = 19_900L)) }
        verify(exactly = 0) { pgClient.approve(any()) }
    }
}
