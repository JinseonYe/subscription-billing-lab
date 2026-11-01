package com.jinseon.billing.subscription

import java.time.Duration
import java.time.Instant

// 이용 권한은 저장하지 않고 계산한다. 상태는 청구 사실만 말하고, 미확정을 실패로 꾸미지 않는다.
// 값은 사업 협의 전의 임시값이다.
object AccessPolicy {
    // 갱신 결제가 미확정인 동안 기간이 끝나도 이용을 허용하는 시간. 재전송 기한(24시간)과 맞춘다.
    val RENEWAL_GRACE: Duration = Duration.ofHours(24)

    // 갱신이 거절된 뒤 이용을 허용하는 독촉 기간.
    val DUNNING_WINDOW: Duration = Duration.ofDays(7)

    // 이용할 수 있는 마지막 시각(미만). 이용할 수 없으면 null이다.
    // PAST_DUE 동안 행의 period_end는 지난 기간의 끝이다. period_start를 기준으로 하면 PAST_DUE에 들어가는 순간 이용이 막힌다.
    // period_end가 없는 ACTIVE와 PAST_DUE는 있을 수 없는 상태라서 이용 불가로 둔다.
    fun accessUntil(
        status: SubscriptionStatus,
        periodEnd: Instant?,
        renewalInFlight: Boolean,
    ): Instant? =
        when (status) {
            SubscriptionStatus.ACTIVE -> periodEnd?.plus(if (renewalInFlight) RENEWAL_GRACE else Duration.ZERO)
            SubscriptionStatus.PAST_DUE -> periodEnd?.plus(DUNNING_WINDOW)
            SubscriptionStatus.PENDING, SubscriptionStatus.CANCELED -> null
        }
}
