package com.jinseon.billing.charge

import com.jinseon.billing.payment.NewPayment
import com.jinseon.billing.payment.PaymentRepository
import com.jinseon.billing.subscription.NewSubscription
import com.jinseon.billing.subscription.PaymentFact
import com.jinseon.billing.subscription.PeriodEndResult
import com.jinseon.billing.subscription.ReflectResult
import com.jinseon.billing.subscription.Subscription
import com.jinseon.billing.subscription.SubscriptionChargeKey
import com.jinseon.billing.subscription.SubscriptionRepository
import com.jinseon.billing.subscription.SubscriptionWriter
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service

// 구독과 결제를 엮는다. 흐름은 항상 "구독 전이 TX 커밋 → (TX 밖) charge() → (TX 밖) sync() → 구독 반영 TX"다.
// 이 클래스에는 @Transactional을 붙이지 않는다. 결제사 호출이 트랜잭션 안에 들어가면 안 되기 때문이다.
// 구독 TX는 SubscriptionWriter, 결제 TX는 PaymentWriter(ChargeService 안)가 맡는다. 둘을 한 트랜잭션에서 쓰지 않는다.
//
// 구독 전이 TX가 예외로 끝나면(@Version 충돌 포함) charge()를 부르지 않고 그대로 던진다. 결제사로 나간 것이 없기 때문이다.
// 전이가 커밋된 뒤의 예외도 그대로 던진다. 구독은 이미 바뀌었고 결제는 chargeKey로 멱등이라서, 같은 호출을 다시 하거나
// sync()를 다시 부르면 이어서 완료된다.
@Service
class SubscriptionChargeService(
    private val subscriptionRepository: SubscriptionRepository,
    private val subscriptionWriter: SubscriptionWriter,
    private val paymentRepository: PaymentRepository,
    private val chargeService: ChargeService,
) {
    fun subscribe(command: NewSubscription): SubscribeOutcome {
        requireNoTransaction("SubscriptionChargeService.subscribe")

        // 이미 live 구독이 있으면 새로 만들지 않는다.
        subscriptionRepository.findLiveByCustomerKey(command.customerKey)?.let { return outcomeOfExisting(it, command) }

        val created =
            try {
                subscriptionWriter.create(command)
            } catch (e: DataIntegrityViolationException) {
                // 동시에 들어온 같은 고객의 신청이 UNIQUE에 걸렸다. 먼저 만든 쪽의 구독을 찾는다.
                // 트랜잭션 안에서 잡으면 rollback-only가 되므로 writer 밖(여기)에서 잡는다.
                return subscriptionRepository.findLiveByCustomerKey(command.customerKey)?.let { outcomeOfExisting(it, command) } ?: throw e
            }
        sync(created.id)
        return SubscribeOutcome.Started(created.id)
    }

    // 기간이 끝난 구독을 처리한다. expectedCycleNo는 호출자가 읽은 주기 번호다. 해지 예약이 있으면 CANCELED, 없으면 다음 주기를 시작한다.
    // 갱신이 시작된 경우에만 결제를 진행한다. 그 밖의 결과에서는 결제사에 아무것도 보내지 않는다.
    fun renew(
        subscriptionId: Long,
        expectedCycleNo: Int,
    ): RenewOutcome {
        requireNoTransaction("SubscriptionChargeService.renew")

        return when (val result = subscriptionWriter.closePeriod(subscriptionId, expectedCycleNo)) {
            PeriodEndResult.Canceled -> RenewOutcome.Canceled
            PeriodEndResult.AlreadyRenewed -> RenewOutcome.AlreadyRenewed
            PeriodEndResult.AlreadyStarted -> RenewOutcome.AlreadyStarted
            is PeriodEndResult.RenewalStarted -> RenewOutcome.Started(result.chargeKey, sync(subscriptionId))
        }
    }

    // 현재 시도의 결제 상태를 읽어 구독에 반영한다. 몇 번을 다시 불러도 결과가 같다.
    // 송신 표시(pendingChargeKey)가 비어 있어도 현재 시도 키의 결제를 읽는다. 결제 TX는 커밋됐는데 구독 반영 전에 죽은 경우에도 복구된다.
    fun sync(subscriptionId: Long): ReflectResult {
        requireNoTransaction("SubscriptionChargeService.sync")

        val subscription = subscriptionRepository.findByIdOrNull(subscriptionId) ?: error("구독을 찾을 수 없습니다: id=$subscriptionId")
        val chargeKey = subscription.currentChargeKey
        val first = readFact(chargeKey)

        // 송신 표시는 있는데 결제 행이 없다. 시도를 열고 결제를 부르기 전에 죽은 것이다. 같은 키로 결제를 완료한다(멱등).
        // charge()의 결과(ChargeOutcome)는 쓰지 않는다. 호출 사이에 상태가 바뀌었을 수 있어서 항상 결제 상태를 다시 읽는다.
        val fact =
            if (first == PaymentFact.NoPayment && subscription.pendingChargeKey == chargeKey.value) {
                chargeService.charge(newPaymentOf(subscription, chargeKey))
                readFact(chargeKey)
            } else {
                first
            }
        return subscriptionWriter.reflect(subscription.id, chargeKey, fact)
    }

    // 같은 고객의 live 구독이 있을 때. 내용이 같으면 그 구독의 결제를 이어서 완료하고, 다르면 아무것도 보내지 않는다.
    // ChargeService가 같은 chargeKey에 다른 내용이 오는 것을 막는 것과 같은 모양이다. 비교하는 항목은 금액과 빌링키다.
    private fun outcomeOfExisting(
        existing: Subscription,
        command: NewSubscription,
    ): SubscribeOutcome {
        val changedFields =
            buildList {
                if (existing.amount != command.amount) add("amount")
                if (existing.billingKey != command.billingKey) add("billingKey")
            }
        if (changedFields.isNotEmpty()) return SubscribeOutcome.Conflict(existing.id, changedFields)

        sync(existing.id)
        return SubscribeOutcome.Existing(existing.id)
    }

    private fun readFact(chargeKey: SubscriptionChargeKey): PaymentFact = paymentFactOf(paymentRepository.findByChargeKey(chargeKey.value))

    // 결제 요청은 구독에 저장된 스냅샷으로 만든다. 재시도와 재전송이 첫 요청과 같아야 하기 때문이다.
    private fun newPaymentOf(
        subscription: Subscription,
        chargeKey: SubscriptionChargeKey,
    ) = NewPayment(
        chargeKey = chargeKey.value,
        billingKey = subscription.billingKey,
        customerKey = subscription.customerKey,
        amount = subscription.amount,
        orderName = subscription.orderName,
    )
}
