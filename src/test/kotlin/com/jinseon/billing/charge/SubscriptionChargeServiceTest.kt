package com.jinseon.billing.charge

import com.jinseon.billing.payment.NewPayment
import com.jinseon.billing.payment.Payment
import com.jinseon.billing.payment.PaymentRepository
import com.jinseon.billing.payment.PaymentStatus
import com.jinseon.billing.subscription.NewSubscription
import com.jinseon.billing.subscription.PaymentFact
import com.jinseon.billing.subscription.PeriodEndResult
import com.jinseon.billing.subscription.ReflectResult
import com.jinseon.billing.subscription.Subscription
import com.jinseon.billing.subscription.SubscriptionChargeKey
import com.jinseon.billing.subscription.SubscriptionRepository
import com.jinseon.billing.subscription.SubscriptionWriter
import com.jinseon.billing.support.savedSubscription
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

// 구독 TX → charge() → sync() 순서와 "트랜잭션 밖" 규칙을 호출 순서로 확인한다.
// 실제 DB와 결제사를 쓰는 확인은 6단계 시나리오 테스트가 한다.
class SubscriptionChargeServiceTest {
    private val subscriptionRepository = mockk<SubscriptionRepository>()
    private val subscriptionWriter = mockk<SubscriptionWriter>()
    private val paymentRepository = mockk<PaymentRepository>()
    private val chargeService = mockk<ChargeService>()
    private val service = SubscriptionChargeService(subscriptionRepository, subscriptionWriter, paymentRepository, chargeService)

    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val cycle1End = Instant.parse("2026-02-01T00:00:00Z")
    private val command = NewSubscription("customer-1", "mock-billing-key", 9_900L, "월 구독")

    private fun key(cycleNo: Int) = SubscriptionChargeKey(7L, cycleNo, 1)

    private fun newPayment(cycleNo: Int) = NewPayment(key(cycleNo).value, "mock-billing-key", "customer-1", 9_900L, "월 구독")

    private fun pendingSubscription(): Subscription {
        val subscription = savedSubscription()
        subscription.beginFirstAttempt()
        return subscription
    }

    private fun activeSubscription(): Subscription {
        val subscription = pendingSubscription()
        subscription.reflect(key(1), PaymentFact.Approved(now))
        return subscription
    }

    private fun renewingSubscription(): Subscription {
        val subscription = activeSubscription()
        subscription.closePeriod(1, cycle1End)
        return subscription
    }

    private fun pastDueSubscription(): Subscription {
        val subscription = renewingSubscription()
        subscription.reflect(key(2), PaymentFact.Declined)
        return subscription
    }

    private fun paymentIn(
        cycleNo: Int,
        status: PaymentStatus,
    ): Payment {
        val payment = Payment("order-$cycleNo", key(cycleNo).value, "mock-billing-key", "customer-1", 9_900L, "월 구독", now)
        when (status) {
            PaymentStatus.REQUESTED -> Unit
            PaymentStatus.UNKNOWN -> payment.markUnknown()
            PaymentStatus.APPROVED -> payment.approve("pk-$cycleNo", now, now)
            PaymentStatus.FAILED -> payment.fail("REJECT_CARD_PAYMENT", "카드 거절", now)
        }
        return payment
    }

    private fun givenSubscription(subscription: Subscription) {
        every { subscriptionRepository.findById(subscription.id) } returns Optional.of(subscription)
    }

    private fun <T> inTransaction(block: () -> T): T {
        TransactionSynchronizationManager.setActualTransactionActive(true)
        try {
            return block()
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false)
        }
    }

    @Test
    fun `트랜잭션 안에서 부르면 어떤 협력자도 부르기 전에 예외가 난다`() {
        inTransaction {
            assertFailsWith<IllegalStateException> { service.subscribe(command) }
            assertFailsWith<IllegalStateException> { service.renew(7L, 1) }
            assertFailsWith<IllegalStateException> { service.sync(7L) }
        }

        verify {
            subscriptionRepository wasNot Called
            subscriptionWriter wasNot Called
            paymentRepository wasNot Called
            chargeService wasNot Called
        }
    }

    @Test
    fun `새 고객이면 구독을 만들고 같은 키로 결제를 완료한 뒤 결제 상태를 다시 읽어 반영한다`() {
        every { subscriptionRepository.findLiveByCustomerKey("customer-1") } returns null
        every { subscriptionWriter.create(command) } returns pendingSubscription()
        givenSubscription(pendingSubscription())
        every { paymentRepository.findByChargeKey("SUB-7-C1-A1") } returnsMany listOf(null, paymentIn(1, PaymentStatus.APPROVED))
        every { chargeService.charge(newPayment(1)) } returns ChargeOutcome.Approved(1L)
        every { subscriptionWriter.reflect(7L, key(1), PaymentFact.Approved(now)) } returns ReflectResult.Applied

        val outcome = service.subscribe(command)

        assertEquals(SubscribeOutcome.Started(7L), outcome)
        verifyOrder {
            subscriptionWriter.create(command)
            chargeService.charge(newPayment(1))
            subscriptionWriter.reflect(7L, key(1), PaymentFact.Approved(now))
        }
    }

    @Test
    fun `같은 고객의 live 구독이 있고 내용이 같으면 새로 만들지 않고 그 구독의 결제를 이어서 반영한다`() {
        every { subscriptionRepository.findLiveByCustomerKey("customer-1") } returns activeSubscription()
        givenSubscription(activeSubscription())
        every { paymentRepository.findByChargeKey("SUB-7-C1-A1") } returns paymentIn(1, PaymentStatus.APPROVED)
        every { subscriptionWriter.reflect(7L, key(1), PaymentFact.Approved(now)) } returns ReflectResult.NoChange

        val outcome = service.subscribe(command)

        assertEquals(SubscribeOutcome.Existing(7L), outcome)
        verify(exactly = 0) { subscriptionWriter.create(any()) }
        verify { chargeService wasNot Called }
    }

    @Test
    fun `같은 고객의 live 구독이 있는데 금액이나 빌링키가 다르면 Conflict이고 아무것도 보내지 않는다`() {
        every { subscriptionRepository.findLiveByCustomerKey("customer-1") } returns activeSubscription()

        val amountChanged = service.subscribe(NewSubscription("customer-1", "mock-billing-key", 19_900L, "월 구독"))
        val keyChanged = service.subscribe(NewSubscription("customer-1", "other-billing-key", 9_900L, "월 구독"))
        val bothChanged = service.subscribe(NewSubscription("customer-1", "other-billing-key", 19_900L, "월 구독"))

        assertEquals(SubscribeOutcome.Conflict(7L, listOf("amount")), amountChanged)
        assertEquals(SubscribeOutcome.Conflict(7L, listOf("billingKey")), keyChanged)
        assertEquals(SubscribeOutcome.Conflict(7L, listOf("amount", "billingKey")), bothChanged)
        verify {
            subscriptionWriter wasNot Called
            paymentRepository wasNot Called
            chargeService wasNot Called
        }
    }

    @Test
    fun `동시에 들어온 신청이 UNIQUE에 걸리면 먼저 만든 구독을 찾아 그 구독의 결제를 이어서 반영한다`() {
        every { subscriptionRepository.findLiveByCustomerKey("customer-1") } returnsMany listOf(null, pendingSubscription())
        every { subscriptionWriter.create(command) } throws DataIntegrityViolationException("Duplicate entry")
        givenSubscription(pendingSubscription())
        every { paymentRepository.findByChargeKey("SUB-7-C1-A1") } returns paymentIn(1, PaymentStatus.UNKNOWN)
        every { subscriptionWriter.reflect(7L, key(1), PaymentFact.Pending) } returns ReflectResult.NoChange

        val outcome = service.subscribe(command)

        assertEquals(SubscribeOutcome.Existing(7L), outcome)
        verify(exactly = 1) { subscriptionWriter.create(command) }
        verify { chargeService wasNot Called }
    }

    @Test
    fun `UNIQUE에 걸렸는데 먼저 만든 구독을 찾지 못하면 원래 예외를 그대로 던진다`() {
        val duplicate = DataIntegrityViolationException("Duplicate entry")
        every { subscriptionRepository.findLiveByCustomerKey("customer-1") } returns null
        every { subscriptionWriter.create(command) } throws duplicate

        val thrown = assertFailsWith<DataIntegrityViolationException> { service.subscribe(command) }

        assertSame(duplicate, thrown)
        verify { chargeService wasNot Called }
    }

    @Test
    fun `구독을 만들지 못하면 결제를 부르지 않고 예외를 그대로 던진다`() {
        every { subscriptionRepository.findLiveByCustomerKey("customer-1") } returns null
        every { subscriptionWriter.create(command) } throws IllegalStateException("DB 장애")

        assertFailsWith<IllegalStateException> { service.subscribe(command) }

        verify { chargeService wasNot Called }
    }

    @Test
    fun `갱신이 시작되면 같은 키로 결제를 완료하고 반영한 결과를 돌려준다`() {
        every { subscriptionWriter.closePeriod(7L, 1) } returns PeriodEndResult.RenewalStarted(key(2))
        givenSubscription(renewingSubscription())
        every { paymentRepository.findByChargeKey("SUB-7-C2-A1") } returnsMany listOf(null, paymentIn(2, PaymentStatus.APPROVED))
        every { chargeService.charge(newPayment(2)) } returns ChargeOutcome.Approved(2L)
        every { subscriptionWriter.reflect(7L, key(2), PaymentFact.Approved(now)) } returns ReflectResult.Applied

        val outcome = service.renew(7L, 1)

        assertEquals(RenewOutcome.Started(key(2), ReflectResult.Applied), outcome)
        verifyOrder {
            subscriptionWriter.closePeriod(7L, 1)
            chargeService.charge(newPayment(2))
            subscriptionWriter.reflect(7L, key(2), PaymentFact.Approved(now))
        }
    }

    @Test
    fun `갱신이 시작되지 않은 결과에서는 결제사에 아무것도 보내지 않고 결제 상태도 읽지 않는다`() {
        val results =
            mapOf(
                PeriodEndResult.Canceled to RenewOutcome.Canceled,
                PeriodEndResult.AlreadyRenewed to RenewOutcome.AlreadyRenewed,
                PeriodEndResult.AlreadyStarted to RenewOutcome.AlreadyStarted,
            )

        for ((periodEnd, expected) in results) {
            every { subscriptionWriter.closePeriod(7L, 1) } returns periodEnd

            assertEquals(expected, service.renew(7L, 1), "$periodEnd")
        }

        verify {
            subscriptionRepository wasNot Called
            paymentRepository wasNot Called
            chargeService wasNot Called
        }
    }

    @Test
    fun `갱신 시작 트랜잭션이 충돌로 끝나면 결제를 부르지 않고 예외를 그대로 던진다`() {
        every { subscriptionWriter.closePeriod(7L, 1) } throws ObjectOptimisticLockingFailureException(Subscription::class.java, 7L)

        assertFailsWith<ObjectOptimisticLockingFailureException> { service.renew(7L, 1) }

        verify {
            paymentRepository wasNot Called
            chargeService wasNot Called
        }
    }

    @Test
    fun `결제 행이 없고 송신 표시가 현재 키면 같은 키로 결제를 완료한 뒤 다시 읽어 반영한다`() {
        givenSubscription(pendingSubscription())
        every { paymentRepository.findByChargeKey("SUB-7-C1-A1") } returnsMany listOf(null, paymentIn(1, PaymentStatus.FAILED))
        every { chargeService.charge(newPayment(1)) } returns ChargeOutcome.Declined(1L, "REJECT_CARD_PAYMENT")
        every { subscriptionWriter.reflect(7L, key(1), PaymentFact.Declined) } returns ReflectResult.Applied

        assertEquals(ReflectResult.Applied, service.sync(7L))

        verify(exactly = 1) { chargeService.charge(newPayment(1)) }
    }

    @Test
    fun `charge가 승인을 돌려줘도 결제 상태가 아직 UNKNOWN이면 Pending으로 반영한다`() {
        givenSubscription(pendingSubscription())
        every { paymentRepository.findByChargeKey("SUB-7-C1-A1") } returnsMany listOf(null, paymentIn(1, PaymentStatus.UNKNOWN))
        every { chargeService.charge(newPayment(1)) } returns ChargeOutcome.Approved(1L)
        every { subscriptionWriter.reflect(7L, key(1), PaymentFact.Pending) } returns ReflectResult.NoChange

        assertEquals(ReflectResult.NoChange, service.sync(7L))

        verify(exactly = 0) { subscriptionWriter.reflect(any(), any(), ofType<PaymentFact.Approved>()) }
    }

    @Test
    fun `결제 행이 이미 있으면 결제를 다시 부르지 않고 그 상태를 반영한다`() {
        givenSubscription(pendingSubscription())
        every { paymentRepository.findByChargeKey("SUB-7-C1-A1") } returns paymentIn(1, PaymentStatus.UNKNOWN)
        every { subscriptionWriter.reflect(7L, key(1), PaymentFact.Pending) } returns ReflectResult.NoChange

        service.sync(7L)

        verify { chargeService wasNot Called }
    }

    @Test
    fun `송신 표시가 없는 구독은 결제 행이 없어도 결제를 부르지 않고 NoPayment로 반영한다`() {
        givenSubscription(activeSubscription())
        every { paymentRepository.findByChargeKey("SUB-7-C1-A1") } returns null
        every { subscriptionWriter.reflect(7L, key(1), PaymentFact.NoPayment) } returns ReflectResult.NoChange

        service.sync(7L)

        verify { chargeService wasNot Called }
    }

    @Test
    fun `송신 표시가 비어 있어도 현재 시도 키의 결제를 읽어서 반영한다`() {
        // 갱신 거절이 반영된 뒤 같은 키의 결제가 뒤늦게 승인으로 바뀐 경우다. 송신 표시는 이미 지워져 있다.
        givenSubscription(pastDueSubscription())
        every { paymentRepository.findByChargeKey("SUB-7-C2-A1") } returns paymentIn(2, PaymentStatus.APPROVED)
        every { subscriptionWriter.reflect(7L, key(2), PaymentFact.Approved(now)) } returns ReflectResult.LateApprovalInPastDue

        assertEquals(ReflectResult.LateApprovalInPastDue, service.sync(7L))

        verify { chargeService wasNot Called }
    }

    @Test
    fun `없는 구독이면 결제 상태를 읽기 전에 예외가 난다`() {
        every { subscriptionRepository.findById(7L) } returns Optional.empty()

        assertFailsWith<IllegalStateException> { service.sync(7L) }

        verify {
            paymentRepository wasNot Called
            chargeService wasNot Called
            subscriptionWriter wasNot Called
        }
    }
}
