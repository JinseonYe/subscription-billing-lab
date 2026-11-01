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
// 상태는 이 클래스의 메서드로만 바꾼다. 로그는 남기지 않고 결과 타입으로 알린다(로그는 SubscriptionWriter가 남긴다).
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

    // 지금 시도의 chargeKey. 행을 저장해 id가 생긴 뒤에만 만들 수 있다.
    val currentChargeKey: SubscriptionChargeKey
        get() = SubscriptionChargeKey(id, cycleNo, attemptNo)

    // 지금 주기의 승인이 이미 반영됐는지. 승인이 반영되면 기간이 이 주기의 기간으로 옮겨 간다.
    // 해지된 구독에서 현재 키의 결제가 APPROVED여도, 이 주기를 이미 반영했다면 해지 뒤에 도착한 승인이 아니다.
    private val currentCyclePaid: Boolean
        get() {
            val anchor = anchorAt ?: return false
            return periodStart == BillingPeriod.of(anchor, cycleNo).start
        }

    // 첫 결제 시도를 연다. chargeKey에 구독 id가 들어가므로 행을 저장해 id가 생긴 뒤, 같은 트랜잭션에서 불러야 한다.
    fun beginFirstAttempt() {
        check(status == SubscriptionStatus.PENDING && pendingChargeKey == null) {
            "첫 결제 시도를 열 수 없는 구독입니다: status=$status, pendingChargeKey=$pendingChargeKey (id=$id)"
        }
        pendingChargeKey = currentChargeKey.value
    }

    // 사용자가 해지한다. PENDING과 ACTIVE는 예약만 기록하고, ACTIVE는 기간이 끝날 때 CANCELED가 된다(closePeriod).
    fun requestCancel(): CancelResult =
        when (status) {
            SubscriptionStatus.PENDING, SubscriptionStatus.ACTIVE -> {
                cancelAtPeriodEnd = true
                CancelResult.Recorded
            }

            // 기간이 이미 끝났고 송신 중이 아니라서 바로 해지한다. 송신 중이면 cancel()이 막는다(재시도 송신은 MVP 4).
            SubscriptionStatus.PAST_DUE -> {
                cancel(CancelReason.USER_CANCELED)
                CancelResult.Canceled
            }

            SubscriptionStatus.CANCELED -> {
                CancelResult.AlreadyCanceled
            }
        }

    // 기간이 끝났을 때 부른다. 해지 예약이 있으면 CANCELED, 없으면 다음 주기를 시작한다.
    // expectedCycleNo는 호출자가 읽은 주기 번호다. 다른 호출자가 이미 갱신을 끝냈다면 지금 시각이 새 기간의 끝을 넘겼어도 다시 시작하지 않는다.
    // @Version은 동시에 실행되는 호출만 막는다. 순차로 다시 오는 호출은 이 기대값과 기간 검사가 막는다.
    fun closePeriod(
        expectedCycleNo: Int,
        now: Instant,
    ): PeriodEndResult {
        check(status == SubscriptionStatus.ACTIVE) { "ACTIVE가 아닌 구독은 기간을 마감할 수 없습니다: $status (id=$id)" }
        val end = periodEnd ?: error("ACTIVE 구독에 period_end가 없습니다 (id=$id)")
        return when {
            expectedCycleNo != cycleNo -> {
                PeriodEndResult.AlreadyRenewed
            }

            pendingChargeKey != null -> {
                PeriodEndResult.AlreadyStarted
            }

            now < end -> {
                PeriodEndResult.AlreadyRenewed
            }

            cancelAtPeriodEnd -> {
                cancel(CancelReason.USER_CANCELED)
                PeriodEndResult.Canceled
            }

            else -> {
                cycleNo += 1
                attemptNo = 1
                pendingChargeKey = currentChargeKey.value
                PeriodEndResult.RenewalStarted(currentChargeKey)
            }
        }
    }

    // 결제 사실을 반영한다. chargeKey가 현재 시도의 키가 아니면 아무것도 바꾸지 않는다.
    // 멈춰 있던 호출이 이미 끝난 시도의 결과를 늦게 넘기면, 진행 중인 다음 시도의 송신 표시가 지워지기 때문이다.
    fun reflect(
        chargeKey: SubscriptionChargeKey,
        fact: PaymentFact,
    ): ReflectResult {
        if (chargeKey != currentChargeKey) {
            return if (fact is PaymentFact.Approved) ReflectResult.StaleApproval else ReflectResult.StaleKey
        }
        return when (status) {
            SubscriptionStatus.PENDING -> reflectFirstPayment(fact)
            SubscriptionStatus.ACTIVE -> reflectRenewal(fact)
            SubscriptionStatus.PAST_DUE -> reflectInPastDue(fact)
            SubscriptionStatus.CANCELED -> reflectInCanceled(fact)
        }
    }

    // 이용할 수 있는 마지막 시각(미만). 이용할 수 없으면 null이다.
    fun accessUntil(): Instant? = AccessPolicy.accessUntil(status, periodEnd, renewalInFlight = pendingChargeKey != null)

    private fun reflectFirstPayment(fact: PaymentFact): ReflectResult =
        when (fact) {
            is PaymentFact.Approved -> {
                transitionTo(SubscriptionStatus.ACTIVE)
                anchorAt = fact.approvedAt
                pendingChargeKey = null
                moveToCurrentCyclePeriod(fact.approvedAt)
                ReflectResult.Applied
            }

            // 해지 요청이 있었어도 사유는 거절이다. 일어난 사실이 우선한다.
            PaymentFact.Declined -> {
                pendingChargeKey = null
                cancel(CancelReason.FIRST_PAYMENT_DECLINED)
                ReflectResult.Applied
            }

            PaymentFact.Pending, PaymentFact.NoPayment -> {
                ReflectResult.NoChange
            }
        }

    private fun reflectRenewal(fact: PaymentFact): ReflectResult {
        // 갱신이 송신 중이 아니면 이 주기의 결과는 이미 반영된 것이다.
        if (pendingChargeKey == null) return ReflectResult.NoChange
        return when (fact) {
            is PaymentFact.Approved -> {
                val anchor = anchorAt ?: error("ACTIVE 구독에 anchor_at이 없습니다 (id=$id)")
                pendingChargeKey = null
                moveToCurrentCyclePeriod(anchor)
                ReflectResult.Applied
            }

            PaymentFact.Declined -> {
                val end = periodEnd ?: error("ACTIVE 구독에 period_end가 없습니다 (id=$id)")
                pendingChargeKey = null
                if (cancelAtPeriodEnd) {
                    cancel(CancelReason.USER_CANCELED)
                } else {
                    transitionTo(SubscriptionStatus.PAST_DUE)
                    retryCount = 0
                    nextRetryAt = RetryPolicy.firstRetryAt(end)
                }
                ReflectResult.Applied
            }

            PaymentFact.Pending, PaymentFact.NoPayment -> {
                ReflectResult.NoChange
            }
        }
    }

    private fun reflectInPastDue(fact: PaymentFact): ReflectResult =
        when (fact) {
            is PaymentFact.Approved -> ReflectResult.LateApprovalInPastDue
            PaymentFact.Declined, PaymentFact.Pending, PaymentFact.NoPayment -> ReflectResult.NoChange
        }

    private fun reflectInCanceled(fact: PaymentFact): ReflectResult =
        when (fact) {
            is PaymentFact.Approved -> if (currentCyclePaid) ReflectResult.NoChange else ReflectResult.LateApprovalAfterCancel
            PaymentFact.Declined, PaymentFact.Pending, PaymentFact.NoPayment -> ReflectResult.NoChange
        }

    private fun moveToCurrentCyclePeriod(anchor: Instant) {
        val period = BillingPeriod.of(anchor, cycleNo)
        periodStart = period.start
        periodEnd = period.end
    }

    // 송신 중인 구독은 CANCELED가 될 수 없다. 호출하는 쪽이 먼저 pendingChargeKey를 비우거나, 송신 중이 아님을 확인해야 한다.
    private fun cancel(reason: CancelReason) {
        check(pendingChargeKey == null) { "송신 중인 구독은 CANCELED가 될 수 없습니다: pendingChargeKey=$pendingChargeKey (id=$id)" }
        transitionTo(SubscriptionStatus.CANCELED)
        canceledReason = reason
    }

    private fun transitionTo(next: SubscriptionStatus) {
        check(status.canTransitionTo(next)) { "허용되지 않은 상태 전이입니다: $status -> $next (id=$id)" }
        status = next
    }
}
