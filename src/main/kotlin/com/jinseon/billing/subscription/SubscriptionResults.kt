package com.jinseon.billing.subscription

// 허용되지 않은 전이만 예외로 던진다. 이미 처리된 이벤트가 다시 오는 것은 예외가 아니라 아래 결과로 돌려준다.
// 예외로 하면 같은 이벤트를 다시 실행하는 스케줄러가 매번 ERROR를 낸다.

sealed interface CancelResult {
    // 해지 요청(PENDING)이나 해지 예약(ACTIVE)을 기록했다. 이미 기록돼 있어도 같다.
    data object Recorded : CancelResult

    // 바로 CANCELED가 됐다.
    data object Canceled : CancelResult

    data object AlreadyCanceled : CancelResult
}

sealed interface PeriodEndResult {
    // 해지 예약대로 CANCELED가 됐다.
    data object Canceled : PeriodEndResult

    // 다음 주기를 시작했다. 호출자는 트랜잭션이 커밋된 뒤에 이 키로 결제를 부른다.
    data class RenewalStarted(
        val chargeKey: SubscriptionChargeKey,
    ) : PeriodEndResult

    // 호출자가 읽은 주기가 이미 지났거나 아직 기간이 끝나지 않았다.
    data object AlreadyRenewed : PeriodEndResult

    // 갱신 결제가 이미 송신 중이다.
    data object AlreadyStarted : PeriodEndResult
}

sealed interface ReflectResult {
    // 상태나 기간이 바뀌었다.
    data object Applied : ReflectResult

    // 바뀐 것이 없다. 결과가 아직 없거나 이미 반영된 결과다.
    data object NoChange : ReflectResult

    // 현재 시도의 키가 아닌 결제의 결과다. 반영하지 않는다.
    data object StaleKey : ReflectResult

    // 현재 시도의 키가 아닌 결제가 승인됐다. 돈이 나갔을 수 있어서 StaleKey와 따로 둔다.
    data object StaleApproval : ReflectResult

    // 해지된 구독의 이 주기 결제가 해지 뒤에 승인으로 확인됐다.
    data object LateApprovalAfterCancel : ReflectResult

    // PAST_DUE인 구독의 갱신 시도가 뒤늦게 승인으로 확인됐다. 이번에는 ACTIVE로 복구하지 않는다(ADR 0002 알려진 한계 8번).
    data object LateApprovalInPastDue : ReflectResult
}
