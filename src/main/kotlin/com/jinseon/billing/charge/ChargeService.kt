package com.jinseon.billing.charge

import com.jinseon.billing.payment.NewPayment
import com.jinseon.billing.payment.Payment
import com.jinseon.billing.payment.PaymentRepository
import com.jinseon.billing.payment.PaymentWriter
import com.jinseon.billing.pg.PgClient
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service

private val log = LoggerFactory.getLogger(ChargeService::class.java)

// 결제 1건 흐름: 기록(TX1) -> 결제사 호출 -> 결과 반영(TX2).
// 이 클래스에는 @Transactional을 붙이지 않는다. 결제사 호출이 트랜잭션 안에 들어가면 안 되기 때문이다.
// TX1과 TX2는 PaymentWriter(다른 빈)가 맡는다.
@Service
class ChargeService(
    private val paymentRepository: PaymentRepository,
    private val paymentWriter: PaymentWriter,
    private val pgClient: PgClient,
) {
    fun charge(command: NewPayment): ChargeOutcome {
        requireNoTransaction("ChargeService.charge")

        // 같은 chargeKey로 이미 만든 결제가 있으면 결제사를 부르지 않고 지금 상태를 알려준다.
        paymentRepository.findByChargeKey(command.chargeKey)?.let { return outcomeOfExisting(it, command) }

        val payment =
            try {
                paymentWriter.createRequested(command)
            } catch (e: DataIntegrityViolationException) {
                // 동시에 들어온 같은 chargeKey가 UNIQUE에 걸렸다. 먼저 만든 쪽의 결제를 알려준다.
                return paymentRepository.findByChargeKey(command.chargeKey)?.let { outcomeOfExisting(it, command) } ?: throw e
            }
        return sendAndApply(payment)
    }

    // 같은 chargeKey인데 금액이나 결제 수단이 다르면 기존 결과를 돌려주지 않고 예외를 던진다.
    // 돌려주면 호출자는 "내가 요청한 내용이 결제됐다"고 오해한다. 결제사는 아직 부르지 않았으므로 던져도 안전하다.
    private fun outcomeOfExisting(
        existing: Payment,
        command: NewPayment,
    ): ChargeOutcome {
        val changedFields =
            buildList {
                if (existing.amount != command.amount) add("amount")
                if (existing.customerKey != command.customerKey) add("customerKey")
                if (existing.billingKey != command.billingKey) add("billingKey")
            }
        if (changedFields.isNotEmpty()) {
            log.error("PAYMENT_CONFLICT 같은 chargeKey에 다른 내용의 요청 chargeKey={} 달라진 항목={}", command.chargeKey, changedFields)
            throw ChargeRequestMismatchException(command.chargeKey, changedFields)
        }
        return existing.toOutcome()
    }

    // TX1이 커밋된 뒤의 모든 예외는 Pending이다. 결제사가 이미 승인했을 수 있어서 "실패"라고 알리면 안 된다.
    private fun sendAndApply(payment: Payment): ChargeOutcome =
        try {
            val result = pgClient.approve(payment.toApproveRequest())
            paymentWriter.applyResult(payment.id, result).toOutcome()
        } catch (e: Exception) {
            log.error("CHARGE_PENDING 결과를 반영하지 못함 orderId={}", payment.orderId, e)
            ChargeOutcome.Pending(payment.id)
        }
}
