package com.jinseon.billing.subscription

import java.time.Instant
import java.time.ZoneId

// 청구 주기 하나의 이용 기간. start 이상 end 미만이다.
data class BillingPeriod(
    val start: Instant,
    val end: Instant,
) {
    companion object {
        val ZONE: ZoneId = ZoneId.of("Asia/Seoul")

        // 주기 n의 기간은 항상 앵커에서 계산한다: [앵커 + (n-1)개월, 앵커 + n개월).
        // 직전 기간 끝에 한 달씩 이어 붙이면 1월 31일 앵커가 2월 28일을 거치며 28일로 밀린다.
        fun of(
            anchor: Instant,
            cycleNo: Int,
        ): BillingPeriod {
            require(cycleNo > 0) { "주기 번호는 0보다 커야 합니다: $cycleNo" }
            val zonedAnchor = anchor.atZone(ZONE)
            return BillingPeriod(
                start = zonedAnchor.plusMonths(cycleNo - 1L).toInstant(),
                end = zonedAnchor.plusMonths(cycleNo.toLong()).toInstant(),
            )
        }
    }
}
