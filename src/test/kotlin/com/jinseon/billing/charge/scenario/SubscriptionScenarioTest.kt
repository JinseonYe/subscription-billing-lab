package com.jinseon.billing.charge.scenario

import ch.qos.logback.classic.Level
import com.jinseon.billing.TestcontainersConfiguration
import com.jinseon.billing.charge.ChargeService
import com.jinseon.billing.charge.PaymentStatusResolver
import com.jinseon.billing.charge.RenewOutcome
import com.jinseon.billing.charge.SubscribeOutcome
import com.jinseon.billing.charge.SubscriptionChargeService
import com.jinseon.billing.mockpg.MockPgLedger
import com.jinseon.billing.mockpg.MockScenario
import com.jinseon.billing.payment.NewPayment
import com.jinseon.billing.payment.Payment
import com.jinseon.billing.payment.PaymentRepository
import com.jinseon.billing.payment.PaymentStatus
import com.jinseon.billing.payment.PaymentWriter
import com.jinseon.billing.pg.PgClient
import com.jinseon.billing.pg.PgResult
import com.jinseon.billing.subscription.CancelReason
import com.jinseon.billing.subscription.CancelResult
import com.jinseon.billing.subscription.NewSubscription
import com.jinseon.billing.subscription.PeriodEndResult
import com.jinseon.billing.subscription.ReflectResult
import com.jinseon.billing.subscription.Subscription
import com.jinseon.billing.subscription.SubscriptionChargeKey
import com.jinseon.billing.subscription.SubscriptionRepository
import com.jinseon.billing.subscription.SubscriptionStatus
import com.jinseon.billing.subscription.SubscriptionWriter
import com.jinseon.billing.support.LogCapture
import com.jinseon.billing.support.MutableClock
import com.jinseon.billing.support.MutableClockConfiguration
import com.jinseon.billing.support.warmUpMockPg
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

// 진짜 SubscriptionChargeService가 진짜 DB(MySQL)와 진짜 HTTP(Mock PG)를 쓴다.
// 각 시나리오에서 구독 상태, 결제 상태, 결제사 호출 횟수, 결제사 원장 기준 실제 청구 횟수를 본다.
// 번호(S1 등)는 설계 문서(ADR 0002, 설계 토론)의 시나리오 번호다.
@ActiveProfiles("mock-pg")
@Import(TestcontainersConfiguration::class, MutableClockConfiguration::class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["pg.toss.read-timeout=500ms", "mock-pg.delay=1500ms"],
)
class SubscriptionScenarioTest(
    @Autowired private val service: SubscriptionChargeService,
    @Autowired private val subscriptionWriter: SubscriptionWriter,
    @Autowired private val subscriptionRepository: SubscriptionRepository,
    @Autowired private val resolver: PaymentStatusResolver,
    @Autowired private val chargeService: ChargeService,
    @Autowired private val paymentWriter: PaymentWriter,
    @Autowired private val paymentRepository: PaymentRepository,
    @Autowired private val ledger: MockPgLedger,
    @Autowired private val clock: MutableClock,
    @Autowired private val transactionManager: PlatformTransactionManager,
    @Autowired private val pgClient: PgClient,
) {
    private val customerKey = "customer-1"

    // 서울 시각 2026-01-01 09:00이 앵커다. 주기 1은 2월 1일 09:00(UTC 00:00)까지, 주기 2는 3월 1일까지다.
    private val start = Instant.parse("2026-01-01T00:00:00Z")
    private val cycle1End = Instant.parse("2026-02-01T00:00:00Z")
    private val cycle2End = Instant.parse("2026-03-01T00:00:00Z")

    @BeforeEach
    fun setUp() {
        // 첫 요청이 느려서 500ms 타임아웃에 걸리지 않도록 먼저 데운다. 호출 기록은 바로 아래 reset이 지운다.
        warmUpMockPg(pgClient)
        subscriptionRepository.deleteAll()
        paymentRepository.deleteAll()
        ledger.reset()
        clock.set(start)
    }

    private fun newSubscription(customer: String = customerKey) = NewSubscription(customer, "mock-billing-key", 9_900L, "월 구독")

    private fun key(
        subscriptionId: Long,
        cycleNo: Int,
        attemptNo: Int = 1,
    ) = SubscriptionChargeKey(subscriptionId, cycleNo, attemptNo)

    private fun subscription(id: Long): Subscription = assertNotNull(subscriptionRepository.findByIdOrNull(id))

    private fun paymentOf(
        subscriptionId: Long,
        cycleNo: Int,
    ): Payment = assertNotNull(paymentRepository.findByChargeKey(key(subscriptionId, cycleNo).value), "주기 $cycleNo 의 결제가 없다")

    // 구독의 저장된 값으로 그 주기의 결제 요청을 만든다.
    private fun paymentRequestOf(
        subscriptionId: Long,
        cycleNo: Int,
    ): NewPayment {
        val saved = subscription(subscriptionId)
        return NewPayment(key(subscriptionId, cycleNo).value, saved.billingKey, saved.customerKey, saved.amount, saved.orderName)
    }

    private fun subscribeWith(
        scenario: MockScenario,
        customer: String = customerKey,
    ): Long {
        ledger.assignScenario(customer, scenario)
        return assertIs<SubscribeOutcome.Started>(service.subscribe(newSubscription(customer))).subscriptionId
    }

    private fun subscribeActive(customer: String = customerKey): Long {
        val id = subscribeWith(MockScenario.APPROVE_OK, customer)
        assertEquals(SubscriptionStatus.ACTIVE, subscription(id).status)
        return id
    }

    // 확정 잡은 5분 이상 지난 건만 본다.
    private fun runResolver(after: Duration = Duration.ofMinutes(6)) {
        clock.advance(after)
        resolver.resolvePending()
    }

    private fun assertPg(
        orderId: String,
        approveCalls: Int,
        lookupCalls: Int,
        approvedCount: Int,
    ) {
        assertEquals(approveCalls, ledger.approveCalls(orderId), "결제사 승인 요청 횟수")
        assertEquals(lookupCalls, ledger.lookupCalls(orderId), "결제사 조회 횟수")
        assertEquals(approvedCount, ledger.approvedCount(orderId), "결제사 원장 기준 실제 청구 횟수")
    }

    // 같은 시각에 시작하는 스레드들의 결과를 모은다. 예외도 결과로 모아서 테스트가 판단한다.
    private fun <T> runConcurrently(
        threads: Int,
        task: (Int) -> T,
    ): List<Result<T>> {
        val go = CountDownLatch(1)
        return Executors.newFixedThreadPool(threads).use { pool ->
            val futures =
                List(threads) { index ->
                    pool.submit(
                        Callable {
                            go.await()
                            runCatching { task(index) }
                        },
                    )
                }
            go.countDown()
            futures.map { it.get() }
        }
    }

    // 동시에 구독 행을 바꾸면 늦게 커밋하는 쪽이 @Version 충돌로 실패하는 것은 정상이다. 그 밖의 예외는 허용하지 않는다.
    private fun assertOnlyOptimisticFailures(results: List<Result<*>>) {
        for (result in results) {
            val error = result.exceptionOrNull() ?: continue
            assertIs<OptimisticLockingFailureException>(error, "예상하지 못한 예외: $error")
        }
    }

    @Nested
    inner class `첫 결제가 확정되지 않는 경우` {
        @Test
        fun `S1 첫 결제가 UNKNOWN이면 구독은 PENDING이고 확정 잡이 승인으로 확정한 뒤 sync하면 ACTIVE가 된다`() {
            val id = subscribeWith(MockScenario.TIMEOUT_AFTER_APPROVE)
            val orderId = paymentOf(id, 1).orderId

            assertEquals(PaymentStatus.UNKNOWN, paymentOf(id, 1).status)
            assertEquals(SubscriptionStatus.PENDING, subscription(id).status)
            assertNull(subscription(id).accessUntil())

            runResolver()
            assertEquals(ReflectResult.Applied, service.sync(id))

            val saved = subscription(id)
            assertEquals(SubscriptionStatus.ACTIVE, saved.status)
            assertEquals(start, saved.anchorAt)
            assertEquals(cycle1End, saved.periodEnd)
            assertNull(saved.pendingChargeKey)
            assertEquals(cycle1End, saved.accessUntil())
            assertEquals(PaymentStatus.APPROVED, paymentOf(id, 1).status)
            assertPg(orderId, approveCalls = 1, lookupCalls = 1, approvedCount = 1)
        }

        @Test
        fun `S2 첫 결제가 UNKNOWN인 동안 해지하면 요청만 기록되고 승인되면 ACTIVE이며 해지 예약이 남고 청구는 한 번이다`() {
            val id = subscribeWith(MockScenario.TIMEOUT_AFTER_APPROVE)
            val orderId = paymentOf(id, 1).orderId

            assertEquals(CancelResult.Recorded, subscriptionWriter.requestCancel(id))
            assertEquals(SubscriptionStatus.PENDING, subscription(id).status)

            runResolver()
            service.sync(id)

            val saved = subscription(id)
            assertEquals(SubscriptionStatus.ACTIVE, saved.status)
            assertTrue(saved.cancelAtPeriodEnd)
            assertPg(orderId, approveCalls = 1, lookupCalls = 1, approvedCount = 1)
        }

        @Test
        fun `S9 첫 결제가 24시간 넘게 확정되지 않아도 PENDING을 유지하고 RESOLVE_STUCK가 남으며 해지는 요청만 기록된다`() {
            val id = subscribeWith(MockScenario.IN_PROGRESS_THEN_DONE)
            val orderId = paymentOf(id, 1).orderId

            LogCapture(PaymentStatusResolver::class.java).use { logs ->
                runResolver(after = Duration.ofHours(25))

                val errors = logs.messages(Level.ERROR)
                assertEquals(1, errors.size, "ERROR 로그: $errors")
                assertTrue(errors.single().contains("RESOLVE_STUCK"), errors.single())
            }
            assertEquals(ReflectResult.NoChange, service.sync(id))
            assertEquals(SubscriptionStatus.PENDING, subscription(id).status)
            assertNotNull(subscription(id).pendingChargeKey)

            // 해지는 요청만 기록되고, 같은 고객이 다시 신청해도 새 구독이 생기지 않는다.
            assertEquals(CancelResult.Recorded, subscriptionWriter.requestCancel(id))
            assertEquals(SubscriptionStatus.PENDING, subscription(id).status)
            assertEquals(SubscribeOutcome.Existing(id), service.subscribe(newSubscription()))
            assertEquals(1, subscriptionRepository.count())

            // 결제사가 뒤늦게 끝내면 확정 잡이 승인으로 확정하고, 해지 요청은 예약으로 남는다.
            ledger.completeInProgress(orderId)
            runResolver(after = Duration.ofMinutes(1))
            service.sync(id)

            val saved = subscription(id)
            assertEquals(SubscriptionStatus.ACTIVE, saved.status)
            assertTrue(saved.cancelAtPeriodEnd)
            assertPg(orderId, approveCalls = 1, lookupCalls = 2, approvedCount = 1)
        }
    }

    @Nested
    inner class `첫 결제가 거절되는 경우` {
        @Test
        fun `첫 결제가 거절되면 CANCELED이고 사유는 FIRST_PAYMENT_DECLINED이며 청구는 없다`() {
            val id = subscribeWith(MockScenario.DECLINED)
            val payment = paymentOf(id, 1)

            val saved = subscription(id)
            assertEquals(PaymentStatus.FAILED, payment.status)
            assertEquals(SubscriptionStatus.CANCELED, saved.status)
            assertEquals(CancelReason.FIRST_PAYMENT_DECLINED, saved.canceledReason)
            assertNull(saved.pendingChargeKey)
            assertNull(saved.accessUntil())
            assertPg(payment.orderId, approveCalls = 1, lookupCalls = 0, approvedCount = 0)
        }

        @Test
        fun `S3 해지된 구독의 결제가 뒤늦게 승인되면 CHARGED_AFTER_CANCEL ERROR가 남고 상태는 그대로다`() {
            val id = subscribeWith(MockScenario.DECLINED)
            val payment = paymentOf(id, 1)
            // 결제사 Mock은 거절 뒤 승인을 재현하지 못한다. 늦게 도착한 승인 응답을 결제 쪽 반영 규칙으로 흉내 낸다.
            paymentWriter.applyResult(payment.id, PgResult.Approved("pk-late", start))
            assertEquals(PaymentStatus.APPROVED, paymentOf(id, 1).status)

            LogCapture(SubscriptionWriter::class.java).use { logs ->
                assertEquals(ReflectResult.LateApprovalAfterCancel, service.sync(id))

                val errors = logs.messages(Level.ERROR)
                assertEquals(1, errors.size, "ERROR 로그: $errors")
                assertTrue(errors.single().contains("CHARGED_AFTER_CANCEL"), errors.single())
            }
            assertEquals(SubscriptionStatus.CANCELED, subscription(id).status)
        }
    }

    @Nested
    inner class `같은 고객이 여러 번 신청하는 경우` {
        @Test
        fun `S4 같은 고객이 순서대로 두 번 신청하면 구독도 결제도 하나이고 청구는 한 번이다`() {
            val id = subscribeActive()

            val second = assertIs<SubscribeOutcome.Existing>(service.subscribe(newSubscription()))

            assertEquals(id, second.subscriptionId)
            assertEquals(1, subscriptionRepository.count())
            assertEquals(1, paymentRepository.count())
            assertEquals(1, ledger.totalApprovedCount())
        }

        @Test
        fun `S4 같은 고객이 동시에 신청해도 구독도 결제도 하나이고 청구는 한 번이다`() {
            val results = runConcurrently(6) { service.subscribe(newSubscription()) }

            assertOnlyOptimisticFailures(results)
            val outcomes = results.mapNotNull { it.getOrNull() }
            assertTrue(outcomes.isNotEmpty(), "성공한 요청이 하나도 없다")
            val saved = subscriptionRepository.findAll().single()
            assertEquals(setOf(saved.id), outcomes.map { it.subscriptionId }.toSet(), "모든 요청이 같은 구독을 가리킨다")
            assertEquals(1, paymentRepository.count())
            assertEquals(1, ledger.totalApprovedCount())

            // 충돌로 실패한 요청이 있어도 같은 호출을 다시 하면 이어서 완료되고 새로 만들어지지 않는다.
            assertEquals(SubscribeOutcome.Existing(saved.id), service.subscribe(newSubscription()))
            assertEquals(SubscriptionStatus.ACTIVE, subscription(saved.id).status)
        }

        @Test
        fun `S4 신청 내용이 다르면 Conflict이고 구독도 결제도 청구도 늘지 않는다`() {
            val id = subscribeActive()

            val outcome = service.subscribe(NewSubscription(customerKey, "mock-billing-key", 19_900L, "월 구독"))

            assertEquals(SubscribeOutcome.Conflict(id, listOf("amount")), outcome)
            assertEquals(1, subscriptionRepository.count())
            assertEquals(1, paymentRepository.count())
            assertEquals(1, ledger.totalApprovedCount())
        }

        @Test
        fun `S4 해지된 뒤에는 같은 고객이 새 구독을 신청할 수 있다`() {
            val first = subscribeWith(MockScenario.DECLINED)
            assertEquals(SubscriptionStatus.CANCELED, subscription(first).status)

            val second = subscribeActive()

            assertNotEquals(first, second)
            assertEquals(2, subscriptionRepository.count())
            assertEquals(SubscriptionStatus.CANCELED, subscription(first).status)
            assertEquals(SubscriptionStatus.ACTIVE, subscription(second).status)
        }
    }

    @Nested
    inner class `갱신일의 경합` {
        @Test
        fun `S5 해지가 먼저 커밋되면 갱신은 시작되지 않고 청구가 없다`() {
            val id = subscribeActive()
            clock.set(cycle1End)

            assertEquals(CancelResult.Recorded, subscriptionWriter.requestCancel(id))
            assertEquals(RenewOutcome.Canceled, service.renew(id, 1))

            val saved = subscription(id)
            assertEquals(SubscriptionStatus.CANCELED, saved.status)
            assertEquals(CancelReason.USER_CANCELED, saved.canceledReason)
            assertEquals(1, paymentRepository.count())
            assertEquals(1, ledger.totalApprovedCount())

            // 정상적으로 해지된 구독의 결제 상태를 다시 확인해도 해지 후 승인으로 오탐하지 않는다. 승인된 첫 주기는 해지 전에 반영됐다.
            LogCapture(SubscriptionWriter::class.java).use { logs ->
                assertEquals(ReflectResult.NoChange, service.sync(id))
                assertEquals(emptyList(), logs.messages(Level.ERROR))
            }
        }

        @Test
        fun `S5 갱신이 먼저 커밋되면 해지는 예약으로 기록되고 새 기간 끝에 CANCELED가 된다`() {
            val id = subscribeActive()
            clock.set(cycle1End)

            assertIs<RenewOutcome.Started>(service.renew(id, 1))
            assertEquals(CancelResult.Recorded, subscriptionWriter.requestCancel(id))

            val renewed = subscription(id)
            assertEquals(SubscriptionStatus.ACTIVE, renewed.status)
            assertEquals(2, renewed.cycleNo)
            assertEquals(cycle2End, renewed.periodEnd)
            assertTrue(renewed.cancelAtPeriodEnd)
            assertEquals(2, ledger.totalApprovedCount())

            clock.set(cycle2End)
            assertEquals(RenewOutcome.Canceled, service.renew(id, 2))
            assertEquals(SubscriptionStatus.CANCELED, subscription(id).status)
            assertEquals(2, ledger.totalApprovedCount())
        }

        @Test
        fun `S5 해지와 갱신 시작이 동시에 일어나도 해지된 구독에는 갱신 청구가 붙지 않는다`() {
            LogCapture(SubscriptionWriter::class.java).use { logs ->
                repeatRace(logs)
            }
        }

        // 해지와 갱신을 10번 겹쳐 본다. 정상적으로 해지된 구독을 sync해도 해지 후 승인으로 오탐하지 않아야 해서 ERROR 로그도 본다.
        private fun repeatRace(logs: LogCapture) {
            repeat(10) { round ->
                val customer = "race-$round"
                clock.set(start)
                val id = subscribeActive(customer)
                clock.set(cycle1End)

                val results = runConcurrently(2) { index -> if (index == 0) subscriptionWriter.requestCancel(id) else service.renew(id, 1) }
                assertOnlyOptimisticFailures(results)

                // 충돌로 실패한 쪽은 호출자가 다시 시도하고, 끝나지 않은 결제는 sync가 이어서 완료한다.
                if (results[0].isFailure) subscriptionWriter.requestCancel(id)
                if (results[1].isFailure) service.renew(id, 1)
                service.sync(id)

                val saved = subscription(id)
                val renewal = paymentRepository.findByChargeKey(key(id, 2).value)
                when (saved.status) {
                    SubscriptionStatus.CANCELED -> {
                        assertNull(renewal, "라운드 $round: 해지된 구독에 갱신 청구가 붙었다")
                    }

                    SubscriptionStatus.ACTIVE -> {
                        val charged = assertNotNull(renewal, "라운드 $round: 갱신이 시작됐는데 결제가 없다")
                        assertEquals(PaymentStatus.APPROVED, charged.status)
                        assertEquals(1, ledger.approvedCount(charged.orderId))
                        assertTrue(saved.cancelAtPeriodEnd, "라운드 $round: 해지 요청이 사라졌다")
                    }

                    SubscriptionStatus.PENDING, SubscriptionStatus.PAST_DUE -> {
                        fail("라운드 $round: 예상하지 못한 상태 ${saved.status}")
                    }
                }
            }
            assertEquals(emptyList(), logs.messages(Level.ERROR), "정상적인 해지와 갱신인데 ERROR 로그가 남았다")
        }

        @Test
        fun `S15 같은 구독의 갱신을 동시에 시작해도 C2-A1은 한 번이고 청구도 한 번이다`() {
            val id = subscribeActive()
            clock.set(cycle1End)

            val results = runConcurrently(6) { service.renew(id, 1) }
            assertOnlyOptimisticFailures(results)
            service.sync(id)

            assertTrue(results.count { it.getOrNull() is RenewOutcome.Started } <= 1, "갱신을 시작한 요청이 둘 이상이다: $results")
            val saved = subscription(id)
            assertEquals(SubscriptionStatus.ACTIVE, saved.status)
            assertEquals(2, saved.cycleNo)
            assertEquals(cycle2End, saved.periodEnd)
            assertNull(saved.pendingChargeKey)
            assertEquals(2, paymentRepository.count())
            assertEquals(2, ledger.totalApprovedCount())
        }

        @Test
        fun `sync가 구독을 읽은 직후 renew가 끼어들어도 정상 승인을 이중 청구 신호로 오탐하지 않는다`() {
            val id = subscribeActive()
            clock.set(cycle1End)
            // sync가 구독을 읽은 직후(아직 주기 1) 갱신이 끼어드는 순간을 만든다. 끼어든 갱신은 진짜 서비스로 끝까지 실행된다.
            val interleaved = mockk<SubscriptionRepository>()
            every { interleaved.findById(id) } answers {
                val read = subscriptionRepository.findById(id)
                service.renew(id, 1)
                read
            }
            val racing = SubscriptionChargeService(interleaved, subscriptionWriter, paymentRepository, chargeService)

            LogCapture(SubscriptionWriter::class.java).use { logs ->
                // 오래된 주기 1의 승인을 받아 반영하려 하지만, 그 승인은 이미 반영된 것이다.
                assertEquals(ReflectResult.NoChange, racing.sync(id))
                assertEquals(emptyList(), logs.messages(Level.ERROR), "정상 운영인데 ERROR 로그가 남았다")
            }
            val saved = subscription(id)
            assertEquals(SubscriptionStatus.ACTIVE, saved.status)
            assertEquals(2, saved.cycleNo)
            assertEquals(cycle2End, saved.periodEnd)
            assertEquals(2, paymentRepository.count())
            assertEquals(2, ledger.totalApprovedCount())
        }

        @Test
        fun `이미 끝난 갱신은 같은 주기 번호나 낡은 주기 번호로 다시 불려도 다음 달 청구가 나가지 않는다`() {
            val id = subscribeActive()
            clock.set(cycle1End)
            assertIs<RenewOutcome.Started>(service.renew(id, 1))

            assertEquals(RenewOutcome.AlreadyRenewed, service.renew(id, 1))
            // 주기 2의 기간도 끝난 시각에 낡은 주기 번호(1)로 불린다. 기대 주기 검사만이 막을 수 있다.
            clock.set(cycle2End.plusSeconds(1))
            assertEquals(RenewOutcome.AlreadyRenewed, service.renew(id, 1))

            assertEquals(2, subscription(id).cycleNo)
            assertEquals(2, paymentRepository.count())
            assertEquals(2, ledger.totalApprovedCount())
        }
    }

    @Nested
    inner class `서버가 중간에 죽은 경우` {
        @Test
        fun `S6 갱신 시작을 커밋하고 결제를 부르기 전에 죽었으면 sync가 같은 키로 결제를 완료한다`() {
            val id = subscribeActive()
            clock.set(cycle1End)
            assertIs<PeriodEndResult.RenewalStarted>(subscriptionWriter.closePeriod(id, 1))
            assertEquals(1, paymentRepository.count())

            assertEquals(ReflectResult.Applied, service.sync(id))

            val saved = subscription(id)
            val renewal = paymentOf(id, 2)
            assertEquals(SubscriptionStatus.ACTIVE, saved.status)
            assertEquals(cycle2End, saved.periodEnd)
            assertNull(saved.pendingChargeKey)
            // 결제 요청은 구독에 저장된 값으로 만든다. 첫 결제와 같은 금액이다(S14).
            assertEquals(saved.amount, renewal.amount)
            assertPg(renewal.orderId, approveCalls = 1, lookupCalls = 0, approvedCount = 1)
        }

        @Test
        fun `S6 갱신 결제 기록만 커밋하고 죽었으면 sync는 결제를 부르지 않고 확정 잡이 확정한 뒤 반영한다`() {
            val id = subscribeActive()
            clock.set(cycle1End)
            subscriptionWriter.closePeriod(id, 1)
            val created = paymentWriter.createRequested(paymentRequestOf(id, 2))

            assertEquals(ReflectResult.NoChange, service.sync(id))
            assertPg(created.orderId, approveCalls = 0, lookupCalls = 0, approvedCount = 0)

            runResolver()
            assertEquals(ReflectResult.Applied, service.sync(id))

            assertEquals(SubscriptionStatus.ACTIVE, subscription(id).status)
            assertEquals(PaymentStatus.APPROVED, paymentOf(id, 2).status)
            assertPg(created.orderId, approveCalls = 1, lookupCalls = 1, approvedCount = 1)
        }

        @Test
        fun `S7 결제 트랜잭션은 커밋됐는데 구독 반영 전에 죽었으면 sync가 반영하고 다시 불러도 같다`() {
            val id = subscribeActive()
            clock.set(cycle1End)
            subscriptionWriter.closePeriod(id, 1)
            chargeService.charge(paymentRequestOf(id, 2))
            assertEquals(PaymentStatus.APPROVED, paymentOf(id, 2).status)
            assertNotNull(subscription(id).pendingChargeKey)

            assertEquals(ReflectResult.Applied, service.sync(id))
            assertEquals(ReflectResult.NoChange, service.sync(id))

            val saved = subscription(id)
            assertEquals(SubscriptionStatus.ACTIVE, saved.status)
            assertEquals(cycle2End, saved.periodEnd)
            assertNull(saved.pendingChargeKey)
            assertPg(paymentOf(id, 2).orderId, approveCalls = 1, lookupCalls = 0, approvedCount = 1)
        }
    }

    @Nested
    inner class `갱신 결제가 확정되지 않거나 거절되는 경우` {
        @Test
        fun `S8 갱신 결제가 UNKNOWN이면 ACTIVE를 유지하고 기간은 연장되지 않으며 이용은 24시간 더 허용된다`() {
            val id = subscribeActive()
            clock.set(cycle1End)
            ledger.assignScenario(customerKey, MockScenario.TIMEOUT_AFTER_APPROVE)

            val outcome = assertIs<RenewOutcome.Started>(service.renew(id, 1))

            assertEquals(ReflectResult.NoChange, outcome.reflected)
            val waiting = subscription(id)
            assertEquals(SubscriptionStatus.ACTIVE, waiting.status)
            assertEquals(2, waiting.cycleNo)
            assertEquals(cycle1End, waiting.periodEnd)
            assertNotNull(waiting.pendingChargeKey)
            assertEquals(cycle1End.plus(Duration.ofHours(24)), waiting.accessUntil())

            // 확정 잡이 승인으로 확정하면 sync가 기간을 연장한다.
            runResolver()
            assertEquals(ReflectResult.Applied, service.sync(id))
            val renewed = subscription(id)
            assertEquals(cycle2End, renewed.periodEnd)
            assertNull(renewed.pendingChargeKey)
            assertEquals(cycle2End, renewed.accessUntil())
            assertPg(paymentOf(id, 2).orderId, approveCalls = 1, lookupCalls = 1, approvedCount = 1)
        }

        @Test
        fun `갱신 결제가 거절되면 PAST_DUE이고 첫 재시도 시각이 정해지며 7일 동안 이용할 수 있고 해지하면 바로 CANCELED다`() {
            val id = subscribeActive()
            clock.set(cycle1End)
            ledger.assignScenario(customerKey, MockScenario.DECLINED)

            val outcome = assertIs<RenewOutcome.Started>(service.renew(id, 1))

            assertEquals(ReflectResult.Applied, outcome.reflected)
            val saved = subscription(id)
            assertEquals(SubscriptionStatus.PAST_DUE, saved.status)
            assertEquals(0, saved.retryCount)
            assertEquals(cycle1End.plus(Duration.ofDays(1)), saved.nextRetryAt)
            assertEquals(cycle1End, saved.periodEnd)
            assertEquals(cycle1End.plus(Duration.ofDays(7)), saved.accessUntil())
            assertNull(saved.pendingChargeKey)
            val renewal = paymentOf(id, 2)
            assertEquals(PaymentStatus.FAILED, renewal.status)
            assertPg(renewal.orderId, approveCalls = 1, lookupCalls = 0, approvedCount = 0)

            assertEquals(CancelResult.Canceled, subscriptionWriter.requestCancel(id))
            assertEquals(CancelReason.USER_CANCELED, subscription(id).canceledReason)
        }

        @Test
        fun `갱신이 송신 중일 때 해지 요청이 있었다면 갱신이 거절돼도 PAST_DUE를 거치지 않고 CANCELED가 된다`() {
            val id = subscribeActive()
            clock.set(cycle1End)
            subscriptionWriter.closePeriod(id, 1)
            assertEquals(CancelResult.Recorded, subscriptionWriter.requestCancel(id))
            ledger.assignScenario(customerKey, MockScenario.DECLINED)

            assertEquals(ReflectResult.Applied, service.sync(id))

            val saved = subscription(id)
            assertEquals(SubscriptionStatus.CANCELED, saved.status)
            assertEquals(CancelReason.USER_CANCELED, saved.canceledReason)
            assertNull(saved.nextRetryAt)
            assertEquals(PaymentStatus.FAILED, paymentOf(id, 2).status)
        }

        @Test
        fun `PAST_DUE인 구독의 갱신 시도가 뒤늦게 승인되면 복구하지 않고 ERROR만 남긴다`() {
            val id = subscribeActive()
            clock.set(cycle1End)
            ledger.assignScenario(customerKey, MockScenario.DECLINED)
            service.renew(id, 1)
            // 결제사 Mock은 거절 뒤 승인을 재현하지 못한다. 늦게 도착한 승인 응답을 결제 쪽 반영 규칙으로 흉내 낸다.
            paymentWriter.applyResult(paymentOf(id, 2).id, PgResult.Approved("pk-late", cycle1End))

            LogCapture(SubscriptionWriter::class.java).use { logs ->
                assertEquals(ReflectResult.LateApprovalInPastDue, service.sync(id))

                val errors = logs.messages(Level.ERROR)
                assertEquals(1, errors.size, "ERROR 로그: $errors")
                assertTrue(errors.single().contains("SUBSCRIPTION_LATE_APPROVAL_IN_PAST_DUE"), errors.single())
            }
            assertEquals(SubscriptionStatus.PAST_DUE, subscription(id).status)
        }
    }

    @Nested
    inner class `해지와 송신이 겹치는 경우` {
        @Test
        fun `S13 송신 중인 구독은 해지해도 CANCELED가 되지 않아서 확정 잡이 재전송한 결제가 해지된 구독에 붙지 않는다`() {
            val id = subscribeWith(MockScenario.TIMEOUT_BEFORE_APPROVE)
            val orderId = paymentOf(id, 1).orderId
            assertEquals(0, ledger.approvedCount(orderId))

            assertEquals(CancelResult.Recorded, subscriptionWriter.requestCancel(id))
            assertEquals(SubscriptionStatus.PENDING, subscription(id).status)

            // 확정 잡이 결제사에 기록이 없음을 확인하고 같은 주문번호로 재전송해 승인된다.
            runResolver()
            service.sync(id)

            val saved = subscription(id)
            assertEquals(SubscriptionStatus.ACTIVE, saved.status)
            assertTrue(saved.cancelAtPeriodEnd)
            assertPg(orderId, approveCalls = 2, lookupCalls = 1, approvedCount = 1)

            // 낸 기간은 이용하고 기간이 끝나면 해지된다. 그 뒤의 청구는 없다.
            clock.set(assertNotNull(saved.periodEnd))
            assertEquals(RenewOutcome.Canceled, service.renew(id, 1))
            assertEquals(1, paymentRepository.count())
            assertEquals(1, ledger.totalApprovedCount())
        }
    }

    @Nested
    inner class `트랜잭션 안에서 호출하는 경우` {
        private val template = TransactionTemplate(transactionManager)

        @Test
        fun `트랜잭션 안에서 subscribe를 부르면 막히고 구독도 결제도 남지 않는다`() {
            assertFailsWith<IllegalStateException> { template.execute { service.subscribe(newSubscription()) } }

            assertEquals(0, subscriptionRepository.count())
            assertEquals(0, paymentRepository.count())
            assertEquals(0, ledger.totalApprovedCount())
        }

        @Test
        fun `트랜잭션 안에서 renew를 부르면 막히고 구독은 그대로다`() {
            val id = subscribeActive()
            clock.set(cycle1End)

            assertFailsWith<IllegalStateException> { template.execute { service.renew(id, 1) } }

            assertEquals(1, subscription(id).cycleNo)
            assertEquals(1, paymentRepository.count())
            assertEquals(1, ledger.totalApprovedCount())
        }

        @Test
        fun `트랜잭션 안에서 sync를 부르면 결제사를 부르기 전에 막힌다`() {
            val id = subscribeWith(MockScenario.TIMEOUT_AFTER_APPROVE)
            val orderId = paymentOf(id, 1).orderId

            assertFailsWith<IllegalStateException> { template.execute { service.sync(id) } }

            assertEquals(SubscriptionStatus.PENDING, subscription(id).status)
            assertPg(orderId, approveCalls = 1, lookupCalls = 0, approvedCount = 1)
        }
    }
}
