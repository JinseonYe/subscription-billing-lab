package com.jinseon.billing.payment

import com.jinseon.billing.pg.PgResult
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

private val log = LoggerFactory.getLogger(PaymentWriter::class.java)

// 결제 흐름의 두 트랜잭션(TX1, TX2)을 담당한다.
// 호출하는 쪽(ChargeService)과 다른 빈이어야 @Transactional이 적용된다.
@Component
class PaymentWriter(
    private val paymentRepository: PaymentRepository,
    private val clock: Clock,
) {
    // TX1: 결제사를 부르기 전에 REQUESTED로 기록하고 커밋한다.
    // 같은 chargeKey가 이미 있으면 DataIntegrityViolationException이 난다(UNIQUE).
    @Transactional
    fun createRequested(newPayment: NewPayment): Payment =
        paymentRepository.saveAndFlush(
            Payment(
                orderId = "order-${UUID.randomUUID()}",
                chargeKey = newPayment.chargeKey,
                billingKey = newPayment.billingKey,
                customerKey = newPayment.customerKey,
                amount = newPayment.amount,
                orderName = newPayment.orderName,
                requestedAt = clock.instant(),
            ),
        )

    // TX2: 결제사 결과를 반영한다. 상태 충돌은 예외로 던지지 않고 로그로 남긴다.
    @Transactional
    fun applyResult(
        paymentId: Long,
        result: PgResult,
    ): Payment {
        val payment = paymentRepository.findByIdOrNull(paymentId) ?: error("결제를 찾을 수 없습니다: id=$paymentId")
        val now = clock.instant()
        when (result) {
            is PgResult.Approved -> applyApproved(payment, result, now)

            is PgResult.Declined -> applyDeclined(payment, result, now)

            // 승인 경로에서 NotFound는 나오지 않지만, 나오더라도 Unknown과 똑같이 다룬다.
            PgResult.NotFound, is PgResult.Unknown -> applyUnknown(payment)
        }
        return payment
    }

    private fun applyApproved(
        payment: Payment,
        result: PgResult.Approved,
        now: Instant,
    ) {
        when (payment.status) {
            PaymentStatus.REQUESTED, PaymentStatus.UNKNOWN -> {
                payment.approve(result.paymentKey, result.approvedAt, now)
            }

            PaymentStatus.FAILED -> {
                log.error("PAYMENT_CONFLICT FAILED로 기록된 결제가 승인으로 확인됨 orderId={}", payment.orderId)
                payment.approve(result.paymentKey, result.approvedAt, now)
            }

            PaymentStatus.APPROVED -> {
                if (payment.pgPaymentKey != result.paymentKey) {
                    log.error("PAYMENT_CONFLICT 이미 승인된 결제에 다른 paymentKey가 옴 orderId={}", payment.orderId)
                }
            }
        }
    }

    private fun applyDeclined(
        payment: Payment,
        result: PgResult.Declined,
        now: Instant,
    ) {
        when (payment.status) {
            PaymentStatus.REQUESTED, PaymentStatus.UNKNOWN -> {
                payment.fail(result.code, result.message, now)
            }

            PaymentStatus.APPROVED -> {
                log.error("PAYMENT_CONFLICT 승인된 결제에 거절 결과가 옴 orderId={} code={}", payment.orderId, result.code)
            }

            PaymentStatus.FAILED -> {
                Unit
            }
        }
    }

    private fun applyUnknown(payment: Payment) {
        when (payment.status) {
            PaymentStatus.REQUESTED -> payment.markUnknown()
            PaymentStatus.UNKNOWN, PaymentStatus.APPROVED, PaymentStatus.FAILED -> Unit
        }
    }
}
