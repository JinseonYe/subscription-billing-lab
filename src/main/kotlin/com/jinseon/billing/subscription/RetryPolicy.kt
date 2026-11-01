package com.jinseon.billing.subscription

import java.time.Duration
import java.time.Instant

// 재시도 일정. 직전 시도가 아니라 주기 끝(period_end)이 기준이라서, 결과 확정이 늦어져도 독촉 기간이 늘어나지 않는다.
// 숫자는 사업 협의 전의 임시값이다. 이번 기능에서는 PAST_DUE에 들어갈 때의 첫 시각만 쓴다. 소진 판단과 다음 일정은 MVP 4에서 만든다.
object RetryPolicy {
    val SCHEDULE: List<Duration> = listOf(Duration.ofDays(1), Duration.ofDays(3), Duration.ofDays(5))

    fun firstRetryAt(periodEnd: Instant): Instant = periodEnd.plus(SCHEDULE.first())
}
