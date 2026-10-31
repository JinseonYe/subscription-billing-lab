package com.jinseon.billing.mockpg

import com.jinseon.billing.TestcontainersConfiguration
import com.jinseon.billing.pg.PgApproveRequest
import com.jinseon.billing.pg.PgClient
import com.jinseon.billing.pg.PgResult
import com.jinseon.billing.support.MutableClock
import com.jinseon.billing.support.MutableClockConfiguration
import com.jinseon.billing.support.warmUpMockPg
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// 진짜 TossPgClient가 진짜 HTTP로 Mock PG를 호출한다. 클라이언트 쪽 타임아웃(500ms)이
// 실제로 걸리는지, 토스 응답 모양을 매퍼가 알아보는지 한 번에 확인한다.
@ActiveProfiles("mock-pg")
@Import(TestcontainersConfiguration::class, MutableClockConfiguration::class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["pg.toss.read-timeout=500ms", "mock-pg.delay=1500ms"],
)
class MockPgControllerTest(
    @Autowired private val pgClient: PgClient,
    @Autowired private val ledger: MockPgLedger,
    @Autowired private val clock: MutableClock,
) {
    private val customerKey = "customer-1"

    @BeforeEach
    fun setUp() {
        // 첫 요청이 느려서 500ms 타임아웃에 걸리지 않도록 먼저 데운다. 호출 기록은 바로 아래 reset이 지운다.
        warmUpMockPg(pgClient)
        ledger.reset()
        // 같은 컨텍스트를 쓰는 다른 테스트가 시계를 돌려놓았을 수 있다.
        clock.set(Instant.parse("2026-01-01T00:00:00Z"))
    }

    private fun newRequest() = PgApproveRequest("order-${UUID.randomUUID()}", "mock-billing-key", customerKey, 9_900L, "월 구독")

    @Test
    fun `정상 승인은 Approved이고 조회해도 같은 결과다`() {
        val request = newRequest()

        val approved = assertIs<PgResult.Approved>(pgClient.approve(request))

        assertTrue(approved.paymentKey.startsWith("mock_pk_"))
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), approved.approvedAt)
        assertEquals(approved, pgClient.lookup(request.orderId))
        assertEquals(1, ledger.approvedCount(request.orderId))
    }

    @Test
    fun `같은 주문번호로 다시 승인 요청해도 같은 결과이고 승인은 한 번뿐이다`() {
        val request = newRequest()

        val first = pgClient.approve(request)
        val second = pgClient.approve(request)

        assertEquals(first, second)
        assertEquals(1, ledger.approvedCount(request.orderId))
    }

    @Test
    fun `카드 거절은 Declined이고 조회하면 결제 없음이다`() {
        ledger.assignScenario(customerKey, MockScenario.DECLINED)
        val request = newRequest()

        val declined = assertIs<PgResult.Declined>(pgClient.approve(request))

        assertEquals("REJECT_CARD_PAYMENT", declined.code)
        assertEquals(PgResult.NotFound, pgClient.lookup(request.orderId))
    }

    @Test
    fun `승인 뒤 응답이 늦으면 호출자는 타임아웃으로 Unknown을 받지만 조회하면 승인되어 있다`() {
        ledger.assignScenario(customerKey, MockScenario.TIMEOUT_AFTER_APPROVE)
        val request = newRequest()

        val unknown = assertIs<PgResult.Unknown>(pgClient.approve(request))

        assertTrue(unknown.reason.contains("통신 오류"), unknown.reason)
        assertIs<PgResult.Approved>(pgClient.lookup(request.orderId))
        assertEquals(1, ledger.approvedCount(request.orderId))
    }

    @Test
    fun `요청이 유실되면 조회는 결제 없음이고 같은 주문번호로 재전송하면 승인은 한 번뿐이다`() {
        ledger.assignScenario(customerKey, MockScenario.TIMEOUT_BEFORE_APPROVE)
        val request = newRequest()

        assertIs<PgResult.Unknown>(pgClient.approve(request))
        assertEquals(PgResult.NotFound, pgClient.lookup(request.orderId))
        assertIs<PgResult.Approved>(pgClient.approve(request))

        assertEquals(1, ledger.approvedCount(request.orderId))
    }

    @Test
    fun `승인 뒤 서버 오류 500은 Unknown이고 조회하면 승인되어 있다`() {
        ledger.assignScenario(customerKey, MockScenario.SERVER_ERROR_AFTER_APPROVE)
        val request = newRequest()

        assertIs<PgResult.Unknown>(pgClient.approve(request))
        assertIs<PgResult.Approved>(pgClient.lookup(request.orderId))
    }

    @Test
    fun `조회 장애 503은 Unknown이고 다음 조회에서 승인이 확인된다`() {
        ledger.assignScenario(customerKey, MockScenario.LOOKUP_UNAVAILABLE)
        val request = newRequest()

        assertIs<PgResult.Unknown>(pgClient.approve(request))
        assertIs<PgResult.Unknown>(pgClient.lookup(request.orderId))
        assertIs<PgResult.Approved>(pgClient.lookup(request.orderId))
    }

    @Test
    fun `처리 중에는 조회도 재전송도 Unknown이고 승인이 늘지 않으며 완료되면 승인이 확인된다`() {
        ledger.assignScenario(customerKey, MockScenario.IN_PROGRESS_THEN_DONE)
        val request = newRequest()

        assertIs<PgResult.Unknown>(pgClient.approve(request))
        assertIs<PgResult.Unknown>(pgClient.lookup(request.orderId))
        assertIs<PgResult.Unknown>(pgClient.approve(request))
        assertEquals(0, ledger.approvedCount(request.orderId))

        ledger.completeInProgress(request.orderId)

        val approved = assertIs<PgResult.Approved>(pgClient.lookup(request.orderId))
        assertEquals(approved, pgClient.approve(request))
        assertEquals(1, ledger.approvedCount(request.orderId))
    }
}
