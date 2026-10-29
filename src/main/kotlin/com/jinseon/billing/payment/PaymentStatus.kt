package com.jinseon.billing.payment

enum class PaymentStatus {
    REQUESTED,
    UNKNOWN,
    APPROVED,
    FAILED,
    ;

    fun canTransitionTo(next: PaymentStatus): Boolean =
        when (this) {
            REQUESTED -> next == APPROVED || next == FAILED || next == UNKNOWN

            UNKNOWN -> next == APPROVED || next == FAILED

            // 결제사 승인이 우리 기록보다 우선한다. 잘못 FAILED로 남은 건도 승인으로 바로잡을 수 있어야 한다.
            FAILED -> next == APPROVED

            APPROVED -> false
        }
}
