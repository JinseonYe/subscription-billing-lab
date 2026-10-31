package com.jinseon.billing.charge

// charge()의 결과. 호출자는 Pending을 실패로 해석하면 안 된다.
// 결제사가 이미 승인했을 수 있으므로, 최종 판단은 결제 상태를 다시 조회해서만 한다.
sealed interface ChargeOutcome {
    val paymentId: Long

    data class Approved(
        override val paymentId: Long,
    ) : ChargeOutcome

    // 결제사가 확정 거절했다.
    data class Declined(
        override val paymentId: Long,
        val code: String,
    ) : ChargeOutcome

    // 결과를 아직 모른다(REQUESTED, UNKNOWN). 확정 잡이 나중에 확정한다.
    data class Pending(
        override val paymentId: Long,
    ) : ChargeOutcome
}
