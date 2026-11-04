package com.jinseon.billing.subscription

enum class SubscriptionStatus {
    // 첫 결제가 승인으로 확정되기 전. 첫 결제를 부르는 동안에는 항상 이 상태다.
    PENDING,
    ACTIVE,
    PAST_DUE,
    CANCELED,
    ;

    // 기간 연장, 해지 예약, 재시도 횟수 증가는 상태가 바뀌지 않으므로 자기 자신으로 가는 전이는 없다.
    fun canTransitionTo(next: SubscriptionStatus): Boolean =
        when (this) {
            PENDING -> next == ACTIVE || next == CANCELED

            ACTIVE -> next == PAST_DUE || next == CANCELED

            PAST_DUE -> next == ACTIVE || next == CANCELED

            // 종결 상태. 되살리지 않는다. 고객당 live 구독 UNIQUE가 이 전제 위에 있다(ADR 0002 원칙 10).
            CANCELED -> false
        }
}
