package com.jinseon.billing.subscription

import ch.qos.logback.classic.Level
import com.jinseon.billing.TestcontainersConfiguration
import com.jinseon.billing.support.LogCapture
import com.jinseon.billing.support.MutableClock
import com.jinseon.billing.support.MutableClockConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.DefaultTransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 실제 MySQL 위에서 구독 트랜잭션의 경계를 확인한다. 상태 전이 규칙 자체는 SubscriptionTest가 확인한다.
@Import(TestcontainersConfiguration::class, MutableClockConfiguration::class)
@SpringBootTest
class SubscriptionWriterTest(
    @Autowired private val subscriptionWriter: SubscriptionWriter,
    @Autowired private val subscriptionRepository: SubscriptionRepository,
    @Autowired private val clock: MutableClock,
    @Autowired private val transactionManager: PlatformTransactionManager,
) {
    // 서울 시각 2026-01-01 09:00이 앵커다. 주기 1은 2월 1일 09:00(UTC 00:00)까지다.
    private val start = Instant.parse("2026-01-01T00:00:00Z")
    private val cycle1End = Instant.parse("2026-02-01T00:00:00Z")

    @BeforeEach
    fun setUp() {
        subscriptionRepository.deleteAll()
        clock.set(start)
    }

    private fun newSubscription(customerKey: String = "customer-1") = NewSubscription(customerKey, "mock-billing-key", 9_900L, "월 구독")

    private fun key(
        subscriptionId: Long,
        cycleNo: Int,
        attemptNo: Int = 1,
    ) = SubscriptionChargeKey(subscriptionId, cycleNo, attemptNo)

    private fun reload(id: Long): Subscription = assertNotNull(subscriptionRepository.findByIdOrNull(id))

    private fun createPending(customerKey: String = "customer-1"): Long = subscriptionWriter.create(newSubscription(customerKey)).id

    private fun createActive(): Long {
        val id = createPending()
        subscriptionWriter.reflect(id, key(id, 1), PaymentFact.Approved(start))
        return id
    }

    @Test
    fun `신청하면 PENDING 행과 첫 시도 키가 한 트랜잭션으로 함께 커밋된다`() {
        clock.set(Instant.parse("2026-01-01T00:00:00.5Z"))

        val created = subscriptionWriter.create(newSubscription())

        val saved = reload(created.id)
        assertEquals(SubscriptionStatus.PENDING, saved.status)
        assertEquals("SUB-${created.id}-C1-A1", saved.pendingChargeKey)
        assertEquals(Instant.parse("2026-01-01T00:00:00.5Z"), saved.createdAt)
        assertEquals(9_900L, saved.amount)
        assertEquals("customer-1", saved.customerKey)
    }

    @Test
    fun `같은 고객의 live 구독을 다시 만들면 UNIQUE 예외가 나고 기존 구독은 그대로다`() {
        val firstId = createPending()

        assertFailsWith<DataIntegrityViolationException> { subscriptionWriter.create(newSubscription()) }

        assertEquals(1, subscriptionRepository.count())
        assertEquals("SUB-$firstId-C1-A1", reload(firstId).pendingChargeKey)
    }

    @Test
    fun `해지된 뒤에는 같은 고객이 새로 신청할 수 있다`() {
        val firstId = createPending()
        subscriptionWriter.reflect(firstId, key(firstId, 1), PaymentFact.Declined)

        val secondId = createPending()

        assertEquals(SubscriptionStatus.CANCELED, reload(firstId).status)
        assertEquals(SubscriptionStatus.PENDING, reload(secondId).status)
        assertEquals(2, subscriptionRepository.count())
    }

    @Test
    fun `첫 결제 승인을 반영하면 ACTIVE와 앵커와 첫 주기 기간이 저장된다`() {
        val id = createPending()

        val result = subscriptionWriter.reflect(id, key(id, 1), PaymentFact.Approved(start))

        assertEquals(ReflectResult.Applied, result)
        val saved = reload(id)
        assertEquals(SubscriptionStatus.ACTIVE, saved.status)
        assertEquals(start, saved.anchorAt)
        assertEquals(start, saved.periodStart)
        assertEquals(cycle1End, saved.periodEnd)
        assertNull(saved.pendingChargeKey)
    }

    @Test
    fun `해지 요청은 예약으로 저장된다`() {
        val id = createActive()

        assertEquals(CancelResult.Recorded, subscriptionWriter.requestCancel(id))

        assertTrue(reload(id).cancelAtPeriodEnd)
        assertEquals(SubscriptionStatus.ACTIVE, reload(id).status)
    }

    @Test
    fun `기간이 끝나면 갱신이 시작되어 새 키가 송신 중으로 저장된다`() {
        val id = createActive()
        clock.set(cycle1End)

        val result = subscriptionWriter.closePeriod(id, 1)

        assertEquals(PeriodEndResult.RenewalStarted(key(id, 2)), result)
        val saved = reload(id)
        assertEquals(2, saved.cycleNo)
        assertEquals("SUB-$id-C2-A1", saved.pendingChargeKey)
    }

    @Test
    fun `갱신 시작의 응답을 잃고 같은 호출을 다시 해도 갱신이 다시 시작되지 않는다`() {
        val id = createActive()
        clock.set(cycle1End)
        subscriptionWriter.closePeriod(id, 1)

        // 응답을 못 받은 호출자가 읽어 둔 주기 번호로 다시 부른다. 키는 돌아오지 않는다.
        // 송신이 끝나지 않은 시도는 pendingChargeKey에 남아 있고, 호출자가 아니라 sync가 완료한다.
        val result = subscriptionWriter.closePeriod(id, 1)

        assertEquals(PeriodEndResult.AlreadyRenewed, result)
        assertEquals(2, reload(id).cycleNo)
        assertEquals("SUB-$id-C2-A1", reload(id).pendingChargeKey)
    }

    @Test
    fun `없는 구독이면 어느 메서드든 예외다`() {
        assertFailsWith<IllegalStateException> { subscriptionWriter.requestCancel(999L) }
        assertFailsWith<IllegalStateException> { subscriptionWriter.closePeriod(999L, 1) }
        assertFailsWith<IllegalStateException> { subscriptionWriter.reflect(999L, key(999L, 1), PaymentFact.Declined) }
    }

    @Test
    fun `해지가 먼저 커밋되면 오래된 정보로 갱신을 시작하려던 쪽이 실패한다`() {
        val id = createActive()
        clock.set(cycle1End)
        val outer = TransactionTemplate(transactionManager)
        val independent =
            TransactionTemplate(transactionManager, DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW))

        assertFailsWith<ObjectOptimisticLockingFailureException> {
            outer.execute {
                // 스케줄러가 구독을 읽어 둔다. 이 시점에는 해지 예약이 없다.
                val stale = assertNotNull(subscriptionRepository.findByIdOrNull(id))
                // 그 사이 사용자의 해지가 먼저 커밋된다.
                independent.execute { subscriptionWriter.requestCancel(id) }
                // 스케줄러는 오래된 값을 보고 갱신을 시작하려 한다. version이 달라져 있어서 실패해야 한다.
                stale.closePeriod(1, clock.instant())
                subscriptionRepository.flush()
            }
        }

        // 갱신은 시작되지 않았고 해지 예약만 남았다. 다시 시도하면 예약대로 해지된다.
        val saved = reload(id)
        assertTrue(saved.cancelAtPeriodEnd)
        assertEquals(1, saved.cycleNo)
        assertNull(saved.pendingChargeKey)
        assertEquals(PeriodEndResult.Canceled, subscriptionWriter.closePeriod(id, 1))
    }

    @Test
    fun `갱신이 먼저 커밋되면 오래된 정보로 해지하려던 쪽이 실패하고 다시 해지하면 예약으로 기록된다`() {
        val id = createActive()
        clock.set(cycle1End)
        val outer = TransactionTemplate(transactionManager)
        val independent =
            TransactionTemplate(transactionManager, DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW))

        assertFailsWith<ObjectOptimisticLockingFailureException> {
            outer.execute {
                val stale = assertNotNull(subscriptionRepository.findByIdOrNull(id))
                independent.execute { subscriptionWriter.closePeriod(id, 1) }
                stale.requestCancel()
                subscriptionRepository.flush()
            }
        }

        // 해지가 실패했으므로 갱신은 송신 중이고 해지 예약은 아직 없다.
        assertFalse(reload(id).cancelAtPeriodEnd)
        // 다시 해지하면 송신 중인 구독은 CANCELED가 되지 않고 예약만 기록된다.
        assertEquals(CancelResult.Recorded, subscriptionWriter.requestCancel(id))
        val saved = reload(id)
        assertEquals(SubscriptionStatus.ACTIVE, saved.status)
        assertTrue(saved.cancelAtPeriodEnd)
        assertEquals("SUB-$id-C2-A1", saved.pendingChargeKey)
    }

    @Test
    fun `flush를 부르지 않아도 충돌은 커밋 시점에 예외로 나오고 writer 호출 자체는 정상으로 돌아온다`() {
        val id = createActive()
        clock.set(cycle1End)
        val outer = TransactionTemplate(transactionManager)
        val independent =
            TransactionTemplate(transactionManager, DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRES_NEW))
        var returned = false

        assertFailsWith<ObjectOptimisticLockingFailureException> {
            outer.execute {
                subscriptionRepository.findByIdOrNull(id)
                independent.execute { subscriptionWriter.requestCancel(id) }
                // 바깥 트랜잭션에 참여해서 같은 영속성 컨텍스트의 오래된 엔티티를 바꾼다. 반환까지는 아무 일도 없다.
                subscriptionWriter.closePeriod(id, 1)
                returned = true
            }
        }

        assertTrue(returned, "충돌은 writer 호출이 아니라 커밋에서 나온다")
        assertEquals(1, reload(id).cycleNo)
    }

    @Test
    fun `해지된 구독에 승인이 도착하면 CHARGED_AFTER_CANCEL ERROR가 한 번 남고 상태는 그대로다`() {
        val id = createPending()
        subscriptionWriter.reflect(id, key(id, 1), PaymentFact.Declined)

        LogCapture(SubscriptionWriter::class.java).use { logs ->
            val result = subscriptionWriter.reflect(id, key(id, 1), PaymentFact.Approved(start))

            assertEquals(ReflectResult.LateApprovalAfterCancel, result)
            val errors = logs.messages(Level.ERROR)
            assertEquals(1, errors.size, errors.toString())
            assertTrue(errors.single().contains("CHARGED_AFTER_CANCEL"), errors.single())
        }
        assertEquals(SubscriptionStatus.CANCELED, reload(id).status)
        assertEquals(CancelReason.FIRST_PAYMENT_DECLINED, reload(id).canceledReason)
    }

    @Test
    fun `승인된 기간이 끝나 예약대로 해지된 구독의 승인을 다시 확인해도 ERROR가 남지 않는다`() {
        val id = createActive()
        subscriptionWriter.requestCancel(id)
        clock.set(cycle1End)
        assertEquals(PeriodEndResult.Canceled, subscriptionWriter.closePeriod(id, 1))

        LogCapture(SubscriptionWriter::class.java).use { logs ->
            val result = subscriptionWriter.reflect(id, key(id, 1), PaymentFact.Approved(start))

            assertEquals(ReflectResult.NoChange, result)
            assertEquals(emptyList(), logs.messages(Level.ERROR))
        }
    }

    @Test
    fun `현재 시도가 아닌 키의 승인은 SUBSCRIPTION_STALE_APPROVAL ERROR를 남기고 아무것도 바꾸지 않는다`() {
        val id = createActive()
        val before = reload(id).version

        LogCapture(SubscriptionWriter::class.java).use { logs ->
            val result = subscriptionWriter.reflect(id, key(id, 9), PaymentFact.Approved(start))

            assertEquals(ReflectResult.StaleApproval, result)
            val errors = logs.messages(Level.ERROR)
            assertEquals(1, errors.size, errors.toString())
            assertTrue(errors.single().contains("SUBSCRIPTION_STALE_APPROVAL"), errors.single())
        }
        assertEquals(before, reload(id).version)
    }

    @Test
    fun `현재 시도가 아닌 키의 거절은 WARN만 남긴다`() {
        val id = createActive()

        LogCapture(SubscriptionWriter::class.java).use { logs ->
            val result = subscriptionWriter.reflect(id, key(id, 9), PaymentFact.Declined)

            assertEquals(ReflectResult.StaleKey, result)
            assertEquals(emptyList(), logs.messages(Level.ERROR))
            assertEquals(1, logs.messages(Level.WARN).size)
        }
    }

    @Test
    fun `PAST_DUE 구독의 갱신 시도가 뒤늦게 승인되면 ERROR만 남기고 ACTIVE로 복구하지 않는다`() {
        val id = createActive()
        clock.set(cycle1End)
        subscriptionWriter.closePeriod(id, 1)
        subscriptionWriter.reflect(id, key(id, 2), PaymentFact.Declined)
        assertEquals(SubscriptionStatus.PAST_DUE, reload(id).status)

        LogCapture(SubscriptionWriter::class.java).use { logs ->
            val result = subscriptionWriter.reflect(id, key(id, 2), PaymentFact.Approved(cycle1End))

            assertEquals(ReflectResult.LateApprovalInPastDue, result)
            val errors = logs.messages(Level.ERROR)
            assertEquals(1, errors.size, errors.toString())
            assertTrue(errors.single().contains("SUBSCRIPTION_LATE_APPROVAL_IN_PAST_DUE"), errors.single())
        }
        assertEquals(SubscriptionStatus.PAST_DUE, reload(id).status)
        assertEquals(cycle1End, reload(id).periodEnd)
    }
}
