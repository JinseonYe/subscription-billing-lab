package com.jinseon.billing.subscription

import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

private val log = LoggerFactory.getLogger(SubscriptionWriter::class.java)

// 구독 행을 바꾸는 짧은 트랜잭션들. 결제사 호출은 여기에 없다.
// 호출하는 쪽(charge 패키지의 서비스)과 다른 빈이어야 @Transactional이 적용된다.
// 이 클래스는 payment 테이블을 건드리지 않는다. 구독과 결제를 한 트랜잭션에서 함께 쓰지 않기 위해서다(ADR 0002 원칙 6).
//
// @Version 충돌(ObjectOptimisticLockingFailureException)은 잡지 않고 그대로 던진다.
// 호출하는 쪽은 이 메서드들이 예외 없이 돌아온 결과로만 다음 일(결제 호출)을 정한다.
@Component
class SubscriptionWriter(
    private val subscriptionRepository: SubscriptionRepository,
    private val clock: Clock,
) {
    // 신청과 첫 시도 열기를 한 트랜잭션으로 한다. 따로 하면 그 사이에 죽었을 때 송신 표시가 없는 PENDING이 남고,
    // 아무도 이 구독의 첫 결제를 완료하지 않아서 고객이 다시 신청할 수도 없다.
    // 같은 고객의 live 구독이 이미 있으면 DataIntegrityViolationException이 난다(UNIQUE). 잡는 일은 호출하는 쪽이 한다.
    @Transactional
    fun create(newSubscription: NewSubscription): Subscription {
        val subscription =
            subscriptionRepository.saveAndFlush(
                Subscription(
                    customerKey = newSubscription.customerKey,
                    billingKey = newSubscription.billingKey,
                    amount = newSubscription.amount,
                    orderName = newSubscription.orderName,
                    createdAt = clock.instant(),
                ),
            )
        subscription.beginFirstAttempt()
        return subscription
    }

    @Transactional
    fun requestCancel(subscriptionId: Long): CancelResult = find(subscriptionId).requestCancel()

    @Transactional
    fun closePeriod(
        subscriptionId: Long,
        expectedCycleNo: Int,
    ): PeriodEndResult = find(subscriptionId).closePeriod(expectedCycleNo, clock.instant())

    // 결제 사실을 반영한다. 충돌은 예외로 롤백하지 않고 로그로 남긴다(ADR 0001 원칙 2와 같다).
    // 예외로 하면 다음 실행에서 같은 예외가 영원히 반복된다.
    @Transactional
    fun reflect(
        subscriptionId: Long,
        chargeKey: SubscriptionChargeKey,
        fact: PaymentFact,
    ): ReflectResult {
        val subscription = find(subscriptionId)
        val result = subscription.reflect(chargeKey, fact)
        logIfConflict(subscription, chargeKey, result)
        return result
    }

    private fun find(subscriptionId: Long): Subscription =
        subscriptionRepository.findByIdOrNull(subscriptionId) ?: error("구독을 찾을 수 없습니다: id=$subscriptionId")

    private fun logIfConflict(
        subscription: Subscription,
        chargeKey: SubscriptionChargeKey,
        result: ReflectResult,
    ) {
        when (result) {
            ReflectResult.LateApprovalAfterCancel -> {
                log.error("CHARGED_AFTER_CANCEL 해지된 구독의 결제가 승인으로 확인됨 subscriptionId={} chargeKey={}", subscription.id, chargeKey)
            }

            ReflectResult.LateApprovalInPastDue -> {
                log.error(
                    "SUBSCRIPTION_LATE_APPROVAL_IN_PAST_DUE PAST_DUE 구독의 갱신 시도가 뒤늦게 승인됨. 이번에는 복구하지 않음 subscriptionId={} chargeKey={}",
                    subscription.id,
                    chargeKey,
                )
            }

            ReflectResult.StaleApproval -> {
                log.error(
                    "SUBSCRIPTION_STALE_APPROVAL 현재 시도가 아닌 결제가 승인됨 subscriptionId={} chargeKey={} currentChargeKey={}",
                    subscription.id,
                    chargeKey,
                    subscription.currentChargeKey,
                )
            }

            ReflectResult.StaleKey -> {
                log.warn(
                    "SUBSCRIPTION_STALE_KEY 현재 시도가 아닌 결제 결과를 무시함 subscriptionId={} chargeKey={} currentChargeKey={}",
                    subscription.id,
                    chargeKey,
                    subscription.currentChargeKey,
                )
            }

            ReflectResult.Applied, ReflectResult.NoChange -> {
                Unit
            }
        }
    }
}
