package com.jinseon.billing.subscription

import java.time.Instant

// 구독이 결제에 대해 알아야 하는 사실. 결제 쪽 타입(Payment, PgResult)을 쓰지 않아도 되게 subscription 패키지에 둔다.
// charge 패키지가 결제 상태를 읽어서 이 타입으로 바꿔 넘긴다.
sealed interface PaymentFact {
    data class Approved(
        val approvedAt: Instant,
    ) : PaymentFact

    // 결제사가 확정 거절했다.
    data object Declined : PaymentFact

    // 결제는 있지만 결과를 아직 모른다(REQUESTED, UNKNOWN).
    data object Pending : PaymentFact

    // 이 키의 결제 행이 아직 없다.
    data object NoPayment : PaymentFact
}
