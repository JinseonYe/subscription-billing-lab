package com.jinseon.billing.charge

import com.jinseon.billing.subscription.ReflectResult
import com.jinseon.billing.subscription.SubscriptionChargeKey

// subscribe()의 결과. 호출자는 구독의 최종 상태를 이 값이 아니라 구독 행을 다시 읽어서 안다.
sealed interface SubscribeOutcome {
    val subscriptionId: Long

    // 새 구독을 만들고 첫 결제를 진행했다.
    data class Started(
        override val subscriptionId: Long,
    ) : SubscribeOutcome

    // 같은 고객의 live 구독이 이미 있고 신청 내용이 같다. 그 구독의 결제를 이어서 완료했다.
    data class Existing(
        override val subscriptionId: Long,
    ) : SubscribeOutcome

    // 같은 고객의 live 구독이 이미 있는데 신청 내용이 다르다. 달라진 항목의 이름만 담는다(빌링키 같은 값은 넣지 않는다).
    data class Conflict(
        override val subscriptionId: Long,
        val changedFields: List<String>,
    ) : SubscribeOutcome
}

// renew()의 결과.
sealed interface RenewOutcome {
    // 다음 주기를 시작하고 결제를 진행해서 그 결과를 반영했다. reflected가 NoChange면 결제 결과가 아직 확정되지 않은 것이다.
    data class Started(
        val chargeKey: SubscriptionChargeKey,
        val reflected: ReflectResult,
    ) : RenewOutcome

    // 해지 예약대로 CANCELED가 됐다.
    data object Canceled : RenewOutcome

    // 호출자가 읽은 주기가 이미 지났거나 아직 기간이 끝나지 않았다.
    data object AlreadyRenewed : RenewOutcome

    // 갱신 결제가 이미 송신 중이다. 완료는 sync()가 한다.
    data object AlreadyStarted : RenewOutcome
}
