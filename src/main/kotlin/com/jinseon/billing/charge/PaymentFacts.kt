package com.jinseon.billing.charge

import com.jinseon.billing.payment.Payment
import com.jinseon.billing.payment.PaymentStatus
import com.jinseon.billing.subscription.PaymentFact

// 결제 행을 구독이 이해하는 사실로 바꾼다. subscription은 payment를 모르므로 이 변환은 둘을 아는 charge가 맡는다.
// 결제 행이 없으면 NoPayment다.
internal fun paymentFactOf(payment: Payment?): PaymentFact {
    if (payment == null) return PaymentFact.NoPayment
    return when (payment.status) {
        PaymentStatus.APPROVED -> PaymentFact.Approved(payment.approvedAt ?: error("승인 시각이 없는 APPROVED 결제입니다: orderId=${payment.orderId}"))
        PaymentStatus.FAILED -> PaymentFact.Declined
        PaymentStatus.REQUESTED, PaymentStatus.UNKNOWN -> PaymentFact.Pending
    }
}
