package com.jinseon.billing.charge.scenario

import ch.qos.logback.classic.Level
import com.jinseon.billing.TestcontainersConfiguration
import com.jinseon.billing.charge.ChargeOutcome
import com.jinseon.billing.charge.ChargeRequestMismatchException
import com.jinseon.billing.charge.ChargeService
import com.jinseon.billing.charge.PaymentStatusResolver
import com.jinseon.billing.mockpg.MockApproveRequest
import com.jinseon.billing.mockpg.MockPgLedger
import com.jinseon.billing.mockpg.MockScenario
import com.jinseon.billing.payment.NewPayment
import com.jinseon.billing.payment.Payment
import com.jinseon.billing.payment.PaymentRepository
import com.jinseon.billing.payment.PaymentStatus
import com.jinseon.billing.payment.PaymentWriter
import com.jinseon.billing.pg.PgClient
import com.jinseon.billing.pg.PgResult
import com.jinseon.billing.support.LogCapture
import com.jinseon.billing.support.MutableClock
import com.jinseon.billing.support.MutableClockConfiguration
import com.jinseon.billing.support.warmUpMockPg
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// 진짜 ChargeService와 PaymentStatusResolver가 진짜 DB(MySQL)와 진짜 HTTP(Mock PG)를 쓴다.
// 구독 기능이 아직 없어서 "구독 상태" 단언은 없다. 결제 상태, 결제사 호출 횟수, 결제사 원장 기준 실제 청구 횟수를 본다.
@ActiveProfiles("mock-pg")
@Import(TestcontainersConfiguration::class, MutableClockConfiguration::class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["pg.toss.read-timeout=500ms", "mock-pg.delay=1500ms"],
)
class ChargeScenarioTest(
    @Autowired private val chargeService: ChargeService,
    @Autowired private val resolver: PaymentStatusResolver,
    @Autowired private val paymentWriter: PaymentWriter,
    @Autowired private val paymentRepository: PaymentRepository,
    @Autowired private val ledger: MockPgLedger,
    @Autowired private val clock: MutableClock,
    @Autowired private val transactionManager: PlatformTransactionManager,
    @Autowired private val pgClient: PgClient,
) {
    private val customerKey = "customer-1"
    private val start = Instant.parse("2026-01-01T00:00:00Z")

    @BeforeEach
    fun setUp() {
        // 첫 요청이 느려서 500ms 타임아웃에 걸리지 않도록 먼저 데운다. 호출 기록은 바로 아래 reset이 지운다.
        warmUpMockPg(pgClient)
        paymentRepository.deleteAll()
        ledger.reset()
        clock.set(start)
    }

    private fun command(chargeKey: String = "SUB-1-2026-01") = NewPayment(chargeKey, "mock-billing-key", customerKey, 9_900L, "월 구독")

    private fun payment(paymentId: Long): Payment = assertNotNull(paymentRepository.findByIdOrNull(paymentId))

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

    @Nested
    inner class `결제사 시나리오` {
        @Test
        fun `결제사가 바로 승인하면 결제는 APPROVED이고 조회 없이 청구는 한 번이다`() {
            ledger.assignScenario(customerKey, MockScenario.APPROVE_OK)

            val outcome = assertIs<ChargeOutcome.Approved>(chargeService.charge(command()))

            val saved = payment(outcome.paymentId)
            assertEquals(PaymentStatus.APPROVED, saved.status)
            assertPg(saved.orderId, approveCalls = 1, lookupCalls = 0, approvedCount = 1)
            runResolver()
            assertPg(saved.orderId, approveCalls = 1, lookupCalls = 0, approvedCount = 1)
        }

        @Test
        fun `결제사가 카드를 거절하면 결제는 FAILED이고 청구는 없다`() {
            ledger.assignScenario(customerKey, MockScenario.DECLINED)

            val outcome = assertIs<ChargeOutcome.Declined>(chargeService.charge(command()))

            val saved = payment(outcome.paymentId)
            assertEquals("REJECT_CARD_PAYMENT", outcome.code)
            assertEquals(PaymentStatus.FAILED, saved.status)
            assertPg(saved.orderId, approveCalls = 1, lookupCalls = 0, approvedCount = 0)
        }

        @Test
        fun `타임아웃 후 결제사에 승인 기록이 있으면 재전송 없이 승인으로 확정된다`() {
            ledger.assignScenario(customerKey, MockScenario.TIMEOUT_AFTER_APPROVE)

            val outcome = assertIs<ChargeOutcome.Pending>(chargeService.charge(command()))
            val orderId = payment(outcome.paymentId).orderId
            assertEquals(PaymentStatus.UNKNOWN, payment(outcome.paymentId).status)

            runResolver()

            assertEquals(PaymentStatus.APPROVED, payment(outcome.paymentId).status)
            assertPg(orderId, approveCalls = 1, lookupCalls = 1, approvedCount = 1)
        }

        @Test
        fun `타임아웃 후 결제사에 기록이 없으면 같은 주문번호로 재전송해 승인되고 청구는 한 번이다`() {
            ledger.assignScenario(customerKey, MockScenario.TIMEOUT_BEFORE_APPROVE)

            val outcome = assertIs<ChargeOutcome.Pending>(chargeService.charge(command()))
            val orderId = payment(outcome.paymentId).orderId
            assertEquals(0, ledger.approvedCount(orderId))

            runResolver()

            assertEquals(PaymentStatus.APPROVED, payment(outcome.paymentId).status)
            assertPg(orderId, approveCalls = 2, lookupCalls = 1, approvedCount = 1)
        }

        @Test
        fun `승인 후 서버 오류 500을 받아도 조회로 승인이 확인되면 승인으로 확정된다`() {
            ledger.assignScenario(customerKey, MockScenario.SERVER_ERROR_AFTER_APPROVE)

            val outcome = assertIs<ChargeOutcome.Pending>(chargeService.charge(command()))
            val orderId = payment(outcome.paymentId).orderId
            assertEquals(PaymentStatus.UNKNOWN, payment(outcome.paymentId).status)

            runResolver()

            assertEquals(PaymentStatus.APPROVED, payment(outcome.paymentId).status)
            assertPg(orderId, approveCalls = 1, lookupCalls = 1, approvedCount = 1)
        }

        @Test
        fun `조회가 한 번 실패해도 결제는 UNKNOWN으로 남고 다음 확정 잡에서 승인으로 확정된다`() {
            ledger.assignScenario(customerKey, MockScenario.LOOKUP_UNAVAILABLE)

            val outcome = assertIs<ChargeOutcome.Pending>(chargeService.charge(command()))
            val orderId = payment(outcome.paymentId).orderId

            runResolver()

            assertEquals(PaymentStatus.UNKNOWN, payment(outcome.paymentId).status)
            assertPg(orderId, approveCalls = 1, lookupCalls = 1, approvedCount = 1)

            runResolver(after = Duration.ofMinutes(1))

            assertEquals(PaymentStatus.APPROVED, payment(outcome.paymentId).status)
            assertPg(orderId, approveCalls = 1, lookupCalls = 2, approvedCount = 1)
        }

        @Test
        fun `결제사가 처리 중이면 재전송하지 않고 기다리다가 완료되면 승인으로 확정된다`() {
            ledger.assignScenario(customerKey, MockScenario.IN_PROGRESS_THEN_DONE)

            val outcome = assertIs<ChargeOutcome.Pending>(chargeService.charge(command()))
            val orderId = payment(outcome.paymentId).orderId

            runResolver()

            assertEquals(PaymentStatus.UNKNOWN, payment(outcome.paymentId).status)
            assertPg(orderId, approveCalls = 1, lookupCalls = 1, approvedCount = 0)

            ledger.completeInProgress(orderId)
            runResolver(after = Duration.ofMinutes(1))

            assertEquals(PaymentStatus.APPROVED, payment(outcome.paymentId).status)
            assertPg(orderId, approveCalls = 1, lookupCalls = 2, approvedCount = 1)
        }
    }

    @Nested
    inner class `서버가 중간에 죽은 경우` {
        @Test
        fun `결제 기록 직후 호출 전에 죽었으면 확정 잡이 조회 후 재전송해 승인하고 같은 요청은 결제사를 부르지 않는다`() {
            val created = paymentWriter.createRequested(command())

            val retry = assertIs<ChargeOutcome.Pending>(chargeService.charge(command()))
            assertEquals(created.id, retry.paymentId)
            assertPg(created.orderId, approveCalls = 0, lookupCalls = 0, approvedCount = 0)

            runResolver()

            assertEquals(PaymentStatus.APPROVED, payment(created.id).status)
            assertPg(created.orderId, approveCalls = 1, lookupCalls = 1, approvedCount = 1)
        }

        @Test
        fun `결제사는 승인했는데 결과 반영 전에 죽었으면 확정 잡이 재전송 없이 승인으로 확정한다`() {
            val created = paymentWriter.createRequested(command())
            ledger.approve(MockApproveRequest(customerKey, created.amount, created.orderId, created.orderName))

            runResolver()

            assertEquals(PaymentStatus.APPROVED, payment(created.id).status)
            assertPg(created.orderId, approveCalls = 1, lookupCalls = 1, approvedCount = 1)
        }

        @Test
        fun `확정 잡이 재전송한 직후 결과를 반영하기 전에 죽었다가 다시 실행되면 다시 보내지 않고 승인으로 확정한다`() {
            val created = paymentWriter.createRequested(command())
            // 죽은 실행: 조회(기록 없음)와 재전송까지 했지만, 결과를 DB에 반영하기 전에 죽었다.
            ledger.lookup(created.orderId)
            ledger.approve(MockApproveRequest(customerKey, created.amount, created.orderId, created.orderName))
            assertEquals(PaymentStatus.REQUESTED, payment(created.id).status)

            runResolver()

            assertEquals(PaymentStatus.APPROVED, payment(created.id).status)
            assertPg(created.orderId, approveCalls = 1, lookupCalls = 2, approvedCount = 1)

            // 이미 확정된 건은 다음 실행에서 건드리지 않는다.
            runResolver(after = Duration.ofMinutes(10))
            assertPg(created.orderId, approveCalls = 1, lookupCalls = 2, approvedCount = 1)
        }

        @Test
        fun `결제사가 처리 중이라는 응답이 24시간을 넘게 이어지면 FAILED로 바꾸지 않고 ERROR 로그를 남긴다`() {
            ledger.assignScenario(customerKey, MockScenario.IN_PROGRESS_THEN_DONE)
            val outcome = assertIs<ChargeOutcome.Pending>(chargeService.charge(command()))
            val orderId = payment(outcome.paymentId).orderId

            LogCapture(PaymentStatusResolver::class.java).use { logs ->
                runResolver(after = Duration.ofHours(25))

                val errors = logs.messages(Level.ERROR)
                assertEquals(1, errors.size, "ERROR 로그: $errors")
                assertTrue(errors.single().contains("RESOLVE_STUCK") && errors.single().contains(orderId), errors.single())
            }
            assertEquals(PaymentStatus.UNKNOWN, payment(outcome.paymentId).status)
            assertPg(orderId, approveCalls = 1, lookupCalls = 1, approvedCount = 0)
        }

        @Test
        fun `재전송 기한 24시간이 지난 결제는 재전송도 실패 처리도 하지 않고 그대로 남는다`() {
            val created = paymentWriter.createRequested(command())

            runResolver(after = Duration.ofHours(25))

            assertEquals(PaymentStatus.REQUESTED, payment(created.id).status)
            assertPg(created.orderId, approveCalls = 0, lookupCalls = 1, approvedCount = 0)
        }

        @Test
        fun `재전송 기한 24시간 정각까지는 재전송한다`() {
            val created = paymentWriter.createRequested(command())

            runResolver(after = PaymentStatusResolver.RESEND_DEADLINE)

            assertEquals(PaymentStatus.APPROVED, payment(created.id).status)
            assertPg(created.orderId, approveCalls = 1, lookupCalls = 1, approvedCount = 1)
        }

        @Test
        fun `확정 잡이 승인으로 확정한 뒤 원래 요청의 늦은 Unknown 결과가 와도 승인이 유지된다`() {
            ledger.assignScenario(customerKey, MockScenario.TIMEOUT_AFTER_APPROVE)
            val outcome = assertIs<ChargeOutcome.Pending>(chargeService.charge(command()))
            runResolver()

            paymentWriter.applyResult(outcome.paymentId, PgResult.Unknown("늦게 도착한 타임아웃"))

            assertEquals(PaymentStatus.APPROVED, payment(outcome.paymentId).status)
        }

        @Test
        fun `기록 직후 죽은 결제도 확정 대기 5분이 지나기 전에는 확정 잡이 건드리지 않는다`() {
            val created = paymentWriter.createRequested(command())

            runResolver(after = Duration.ofMinutes(4))

            assertEquals(PaymentStatus.REQUESTED, payment(created.id).status)
            assertPg(created.orderId, approveCalls = 0, lookupCalls = 0, approvedCount = 0)
        }
    }

    @Nested
    inner class `트랜잭션 안에서 호출하는 경우` {
        private val template = TransactionTemplate(transactionManager)

        @Test
        fun `트랜잭션 안에서 charge를 부르면 결제사를 부르기 전에 막히고 결제 기록도 남지 않는다`() {
            assertFailsWith<IllegalStateException> { template.execute { chargeService.charge(command()) } }

            assertEquals(0, paymentRepository.count())
            assertEquals(0, ledger.totalApprovedCount())
        }

        @Test
        fun `트랜잭션 안에서 확정 잡을 부르면 결제사를 조회하기 전에 막힌다`() {
            val created = paymentWriter.createRequested(command())
            clock.advance(Duration.ofMinutes(6))

            assertFailsWith<IllegalStateException> { template.execute { resolver.resolvePending() } }

            assertEquals(PaymentStatus.REQUESTED, payment(created.id).status)
            assertPg(created.orderId, approveCalls = 0, lookupCalls = 0, approvedCount = 0)
        }
    }

    @Nested
    inner class `같은 chargeKey로 다른 내용이 오는 경우` {
        @Test
        fun `금액이 다른 요청은 예외로 막히고 기존 결제와 결제사 호출 횟수는 그대로다`() {
            val first = assertIs<ChargeOutcome.Approved>(chargeService.charge(command()))
            val orderId = payment(first.paymentId).orderId

            val error =
                assertFailsWith<ChargeRequestMismatchException> {
                    chargeService.charge(NewPayment(command().chargeKey, "mock-billing-key", customerKey, 19_900L, "월 구독"))
                }

            assertEquals(listOf("amount"), error.changedFields)
            assertEquals(9_900L, payment(first.paymentId).amount)
            assertEquals(1, paymentRepository.count())
            assertPg(orderId, approveCalls = 1, lookupCalls = 0, approvedCount = 1)
        }
    }

    @Nested
    inner class `같은 청구를 여러 번 요청하는 경우` {
        @Test
        fun `같은 chargeKey로 순서대로 두 번 요청하면 결제는 하나이고 결제사 호출도 한 번뿐이다`() {
            val first = assertIs<ChargeOutcome.Approved>(chargeService.charge(command()))
            val second = assertIs<ChargeOutcome.Approved>(chargeService.charge(command()))

            assertEquals(first.paymentId, second.paymentId)
            assertEquals(1, paymentRepository.count())
            assertPg(payment(first.paymentId).orderId, approveCalls = 1, lookupCalls = 0, approvedCount = 1)
        }

        @Test
        fun `결과를 모르는 결제를 같은 chargeKey로 다시 요청해도 새 승인 요청을 보내지 않는다`() {
            ledger.assignScenario(customerKey, MockScenario.TIMEOUT_AFTER_APPROVE)
            val first = assertIs<ChargeOutcome.Pending>(chargeService.charge(command()))

            val second = assertIs<ChargeOutcome.Pending>(chargeService.charge(command()))

            assertEquals(first.paymentId, second.paymentId)
            assertPg(payment(first.paymentId).orderId, approveCalls = 1, lookupCalls = 0, approvedCount = 1)
        }

        @Test
        fun `같은 chargeKey로 동시에 요청해도 결제는 하나이고 결제사 승인 요청은 한 번뿐이다`() {
            val threads = 6
            val go = CountDownLatch(1)

            val outcomes =
                Executors.newFixedThreadPool(threads).use { pool ->
                    val futures =
                        List(threads) {
                            pool.submit(
                                Callable {
                                    go.await()
                                    chargeService.charge(command())
                                },
                            )
                        }
                    go.countDown()
                    futures.map { it.get() }
                }

            val saved = assertNotNull(paymentRepository.findByChargeKey(command().chargeKey))
            assertEquals(1, paymentRepository.count())
            assertEquals(1, outcomes.map { it.paymentId }.toSet().size, "모든 요청이 같은 결제를 가리킨다")
            assertPg(saved.orderId, approveCalls = 1, lookupCalls = 0, approvedCount = 1)
        }
    }
}
