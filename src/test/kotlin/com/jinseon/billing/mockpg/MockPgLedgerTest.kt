package com.jinseon.billing.mockpg

import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MockPgLedgerTest {
    private val ledger = MockPgLedger(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))

    private fun request(
        orderId: String,
        customerKey: String = "customer-1",
    ) = MockApproveRequest(customerKey, 9_900L, orderId, "월 구독")

    @Test
    fun `기본 시나리오는 승인하고 토스와 같은 모양의 DONE 응답을 돌려준다`() {
        val result = ledger.approve(request("order-1"))

        assertEquals(200, result.response.status)
        assertFalse(result.delayed)
        assertEquals("DONE", result.response.body["status"])
        assertEquals("order-1", result.response.body["orderId"])
        assertEquals(9_900L, result.response.body["totalAmount"])
        assertEquals("2026-01-01T09:00:00+09:00", result.response.body["approvedAt"])
        assertTrue((result.response.body["paymentKey"] as String).startsWith("mock_pk_"))
        assertEquals(1, ledger.approvedCount("order-1"))
    }

    @Test
    fun `같은 주문번호로 다시 요청하면 처음 응답을 그대로 돌려주고 두 번 승인하지 않는다`() {
        val first = ledger.approve(request("order-1"))
        val second = ledger.approve(request("order-1"))

        assertEquals(first.response, second.response)
        assertEquals(1, ledger.approvedCount("order-1"))
        assertEquals(2, ledger.approveCalls("order-1"))
    }

    @Test
    fun `같은 주문번호로 동시에 요청해도 승인은 한 번뿐이고 모두 같은 응답을 받는다`() {
        val threads = 16
        val ready = CountDownLatch(threads)
        val start = CountDownLatch(1)

        val responses =
            Executors.newFixedThreadPool(threads).use { pool ->
                val futures =
                    List(threads) {
                        pool.submit(
                            Callable {
                                ready.countDown()
                                start.await()
                                ledger.approve(request("order-1")).response
                            },
                        )
                    }
                ready.await()
                start.countDown()
                futures.map { it.get() }
            }

        assertEquals(1, ledger.approvedCount("order-1"))
        assertEquals(1, responses.toSet().size, "모든 요청이 같은 응답을 받아야 한다")
        assertEquals(200, responses.first().status)
    }

    @Test
    fun `DECLINED는 403을 돌려주고 승인 기록을 남기지 않으며 재요청도 같은 거절이다`() {
        ledger.assignScenario("customer-1", MockScenario.DECLINED)

        val first = ledger.approve(request("order-1"))
        val second = ledger.approve(request("order-1"))

        assertEquals(403, first.response.status)
        assertEquals("REJECT_CARD_PAYMENT", first.response.body["code"])
        assertEquals(first.response, second.response)
        assertEquals(404, ledger.lookup("order-1").status)
        assertEquals(0, ledger.approvedCount("order-1"))
    }

    @Test
    fun `TIMEOUT_AFTER_APPROVE는 승인을 기록한 뒤 응답만 늦춘다`() {
        ledger.assignScenario("customer-1", MockScenario.TIMEOUT_AFTER_APPROVE)

        val result = ledger.approve(request("order-1"))

        assertTrue(result.delayed)
        assertEquals("DONE", ledger.lookup("order-1").body["status"])
        assertEquals(1, ledger.approvedCount("order-1"))
    }

    @Test
    fun `TIMEOUT_BEFORE_APPROVE는 요청이 유실되어 아무것도 기록하지 않고 재전송은 정상 처리한다`() {
        ledger.assignScenario("customer-1", MockScenario.TIMEOUT_BEFORE_APPROVE)

        val lost = ledger.approve(request("order-1"))

        assertTrue(lost.delayed)
        assertEquals(404, ledger.lookup("order-1").status)
        assertEquals(0, ledger.approvedCount("order-1"))

        val resent = ledger.approve(request("order-1"))

        assertFalse(resent.delayed)
        assertEquals(200, resent.response.status)
        assertEquals(1, ledger.approvedCount("order-1"))
    }

    @Test
    fun `SERVER_ERROR_AFTER_APPROVE는 승인을 기록하고 500을 돌려준다`() {
        ledger.assignScenario("customer-1", MockScenario.SERVER_ERROR_AFTER_APPROVE)

        val result = ledger.approve(request("order-1"))

        assertEquals(500, result.response.status)
        assertFalse(result.delayed)
        assertEquals("DONE", ledger.lookup("order-1").body["status"])
        assertEquals(1, ledger.approvedCount("order-1"))
    }

    @Test
    fun `LOOKUP_UNAVAILABLE은 승인을 기록하고 첫 조회만 503으로 실패한다`() {
        ledger.assignScenario("customer-1", MockScenario.LOOKUP_UNAVAILABLE)

        val result = ledger.approve(request("order-1"))

        assertTrue(result.delayed)
        assertEquals(503, ledger.lookup("order-1").status)
        assertEquals("DONE", ledger.lookup("order-1").body["status"])
        assertEquals(2, ledger.lookupCalls("order-1"))
    }

    @Test
    fun `IN_PROGRESS_THEN_DONE은 처리 중에는 재요청을 409로 막고 완료시키면 승인 상태가 된다`() {
        ledger.assignScenario("customer-1", MockScenario.IN_PROGRESS_THEN_DONE)

        val first = ledger.approve(request("order-1"))
        assertTrue(first.delayed)
        assertEquals("IN_PROGRESS", ledger.lookup("order-1").body["status"])

        val resent = ledger.approve(request("order-1"))
        assertEquals(409, resent.response.status)
        assertEquals("IDEMPOTENT_REQUEST_PROCESSING", resent.response.body["code"])
        assertEquals(0, ledger.approvedCount("order-1"))

        ledger.completeInProgress("order-1")

        assertEquals("DONE", ledger.lookup("order-1").body["status"])
        assertEquals(1, ledger.approvedCount("order-1"))
        val afterComplete = ledger.approve(request("order-1"))
        assertEquals(200, afterComplete.response.status)
        assertEquals(1, ledger.approvedCount("order-1"))
    }

    @Test
    fun `시나리오는 주문번호마다 첫 요청에만 적용된다`() {
        ledger.assignScenario("customer-1", MockScenario.TIMEOUT_BEFORE_APPROVE)

        assertTrue(ledger.approve(request("order-A")).delayed)
        assertTrue(ledger.approve(request("order-B")).delayed, "다른 주문번호의 첫 요청에는 시나리오가 다시 적용된다")
        assertFalse(ledger.approve(request("order-A")).delayed, "같은 주문번호의 재전송은 정상 처리된다")
    }

    @Test
    fun `승인된 적 없는 주문번호를 조회하면 404이다`() {
        val response = ledger.lookup("order-없음")

        assertEquals(404, response.status)
        assertEquals("NOT_FOUND_PAYMENT", response.body["code"])
    }

    @Test
    fun `처리 중이 아닌 결제를 완료시키려 하면 예외가 난다`() {
        assertFailsWith<IllegalStateException> { ledger.completeInProgress("order-없음") }
    }

    @Test
    fun `reset하면 원장과 카운터와 시나리오가 모두 비워진다`() {
        ledger.assignScenario("customer-1", MockScenario.DECLINED)
        ledger.approve(request("order-1"))

        ledger.reset()

        assertEquals(404, ledger.lookup("order-1").status)
        assertEquals(0, ledger.approveCalls("order-1"))
        assertEquals(0, ledger.totalApprovedCount())
        assertEquals(200, ledger.approve(request("order-2")).response.status)
    }
}
