package com.jinseon.billing.charge

import com.jinseon.billing.payment.Payment
import com.jinseon.billing.payment.PaymentStatus
import com.jinseon.billing.pg.PgApproveRequest

// 재전송 때도 같은 요청이 만들어진다. 요청 필드가 모두 val이고 orderId는 만든 뒤 바뀌지 않기 때문이다.
internal fun Payment.toApproveRequest() = PgApproveRequest(orderId, billingKey, customerKey, amount, orderName)

internal fun Payment.toOutcome(): ChargeOutcome =
    when (status) {
        PaymentStatus.APPROVED -> ChargeOutcome.Approved(id)
        PaymentStatus.FAILED -> ChargeOutcome.Declined(id, failureCode ?: "NO_FAILURE_CODE")
        PaymentStatus.REQUESTED, PaymentStatus.UNKNOWN -> ChargeOutcome.Pending(id)
    }
