package com.jinseon.billing.subscription

import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Version
import java.time.Instant

// 결제 요청에 쓰는 값(고객 키, 빌링키, 금액, 주문명)은 신청 때의 스냅샷이라 바뀌지 않는다. 재시도와 재전송이 첫 요청과 같아야 하기 때문이다.
// toString을 만들지 않는 이유: billingKey가 로그에 찍히면 안 된다.
// 상태를 바꾸는 메서드(해지, 기간 종료, 결제 결과 반영)는 3단계에서 추가한다.
@Entity
class Subscription(
    val customerKey: String,
    val billingKey: String,
    val amount: Long,
    val orderName: String,
    val createdAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0L

    @Enumerated(EnumType.STRING)
    var status: SubscriptionStatus = SubscriptionStatus.PENDING
        protected set

    // 마지막으로 시작한 청구 주기와 그 주기의 시도 번호. 현재 시도의 chargeKey를 만든다.
    var cycleNo: Int = 1
        protected set

    var attemptNo: Int = 1
        protected set

    // 결제사로 보낼 수 있는 시도가 열려 있으면 그 chargeKey다. 최종 결과를 반영할 때만 비운다.
    var pendingChargeKey: String? = null
        protected set

    var anchorAt: Instant? = null
        protected set

    var periodStart: Instant? = null
        protected set

    var periodEnd: Instant? = null
        protected set

    var cancelAtPeriodEnd: Boolean = false
        protected set

    @Enumerated(EnumType.STRING)
    var canceledReason: CancelReason? = null
        protected set

    var retryCount: Int = 0
        protected set

    var nextRetryAt: Instant? = null
        protected set

    @Version
    var version: Long = 0L
        protected set

    // 첫 결제 시도를 연다. chargeKey에 구독 id가 들어가므로 행을 저장해 id가 생긴 뒤, 같은 트랜잭션에서 불러야 한다.
    fun beginFirstAttempt() {
        check(status == SubscriptionStatus.PENDING && pendingChargeKey == null) {
            "첫 결제 시도를 열 수 없는 구독입니다: status=$status, pendingChargeKey=$pendingChargeKey (id=$id)"
        }
        pendingChargeKey = SubscriptionChargeKey(id, cycleNo, attemptNo).value
    }
}
