package com.jinseon.billing.mockpg

import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// 결제사 쪽 원장을 메모리에 흉내 낸다. DB 테이블로 만들지 않는 이유:
// JPA 엔티티는 @Profile이 적용되지 않아 운영 스키마에도 테이블이 생기기 때문이다.
//
// 지키는 규칙:
// - 주문번호별 상태 결정은 ConcurrentHashMap.compute 안에서 원자적으로 한다. (get 후 put이면 동시 요청이 둘 다 승인된다)
// - 상태 기록은 항상 응답 지연보다 먼저 한다. 지연은 응답에만 걸리므로 늦게 깨어난 스레드가 원장을 바꾸지 않는다.
@Component
@Profile("mock-pg")
class MockPgLedger(
    private val clock: Clock,
) {
    private sealed interface Entry {
        val request: MockApproveRequest

        data class InProgress(
            override val request: MockApproveRequest,
        ) : Entry

        data class Done(
            override val request: MockApproveRequest,
            val paymentKey: String,
            val approvedAt: String,
        ) : Entry

        data class Declined(
            override val request: MockApproveRequest,
        ) : Entry
    }

    // 상태 결정과, 호출자에게 돌려줄 응답을 한 덩어리로 다룬다.
    private class Decision(
        val entry: Entry?,
        val result: MockApproveResult,
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val seenOrderIds = ConcurrentHashMap.newKeySet<String>()
    private val scenarios = ConcurrentHashMap<String, MockScenario>()
    private val lookupFailures = ConcurrentHashMap<String, AtomicInteger>()
    private val approveCalls = ConcurrentHashMap<String, AtomicInteger>()
    private val lookupCalls = ConcurrentHashMap<String, AtomicInteger>()
    private val approvedCounts = ConcurrentHashMap<String, AtomicInteger>()

    fun assignScenario(
        customerKey: String,
        scenario: MockScenario,
    ) {
        scenarios[customerKey] = scenario
    }

    fun approve(request: MockApproveRequest): MockApproveResult {
        count(approveCalls, request.orderId)
        val isFirstRequest = seenOrderIds.add(request.orderId)
        val scenario = if (isFirstRequest) scenarios[request.customerKey] ?: MockScenario.APPROVE_OK else MockScenario.APPROVE_OK

        var result: MockApproveResult? = null
        entries.compute(request.orderId) { _, existing ->
            if (existing == null) {
                val decision = decideFirstApproval(request, scenario)
                result = decision.result
                decision.entry
            } else {
                // 이미 처리한 주문번호다. 같은 멱등키로 다시 온 요청이므로 처음 응답을 그대로 재생한다.
                result = replay(existing)
                existing
            }
        }
        return checkNotNull(result)
    }

    fun lookup(orderId: String): MockPgResponse {
        count(lookupCalls, orderId)
        val remainingFailures = lookupFailures[orderId]
        if (remainingFailures != null && remainingFailures.getAndDecrement() > 0) {
            return error(503, "FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING", "결제가 완료되지 않았어요. 다시 시도해주세요.")
        }
        return when (val entry = entries[orderId]) {
            // 거절된 결제는 승인된 결제가 아니라서 조회되지 않는다.
            null, is Entry.Declined -> error(404, "NOT_FOUND_PAYMENT", "존재하지 않는 결제 정보 입니다.")

            is Entry.InProgress -> MockPgResponse(200, inProgressBody(entry))

            is Entry.Done -> MockPgResponse(200, doneBody(entry))
        }
    }

    // IN_PROGRESS_THEN_DONE 시나리오에서 "결제사가 뒤늦게 승인을 끝냈다"를 재현한다.
    fun completeInProgress(orderId: String) {
        entries.compute(orderId) { _, existing ->
            check(existing is Entry.InProgress) { "처리 중인 결제가 아닙니다: orderId=$orderId" }
            count(approvedCounts, orderId)
            newDone(existing.request)
        }
    }

    fun approveCalls(orderId: String): Int = approveCalls[orderId]?.get() ?: 0

    fun lookupCalls(orderId: String): Int = lookupCalls[orderId]?.get() ?: 0

    // 이 주문번호로 실제 승인된 횟수. 1을 넘으면 이중 청구다.
    fun approvedCount(orderId: String): Int = approvedCounts[orderId]?.get() ?: 0

    fun totalApprovedCount(): Int = approvedCounts.values.sumOf { it.get() }

    fun reset() {
        entries.clear()
        seenOrderIds.clear()
        scenarios.clear()
        lookupFailures.clear()
        approveCalls.clear()
        lookupCalls.clear()
        approvedCounts.clear()
    }

    private fun decideFirstApproval(
        request: MockApproveRequest,
        scenario: MockScenario,
    ): Decision =
        when (scenario) {
            MockScenario.APPROVE_OK -> {
                val done = approveNow(request)
                Decision(done, MockApproveResult(MockPgResponse(200, doneBody(done)), delayed = false))
            }

            MockScenario.DECLINED -> {
                val declined = Entry.Declined(request)
                Decision(declined, MockApproveResult(declinedResponse(), delayed = false))
            }

            MockScenario.TIMEOUT_AFTER_APPROVE -> {
                val done = approveNow(request)
                Decision(done, MockApproveResult(MockPgResponse(200, doneBody(done)), delayed = true))
            }

            // 요청이 유실됐다. 원장에 아무것도 남기지 않는다.
            MockScenario.TIMEOUT_BEFORE_APPROVE -> {
                Decision(null, MockApproveResult(error(504, "GATEWAY_TIMEOUT", "요청이 유실되었습니다."), delayed = true))
            }

            // 승인은 끝났는데 호출자는 500을 받는다. 호출자가 실패로 오해하기 쉬운 경우다.
            MockScenario.SERVER_ERROR_AFTER_APPROVE -> {
                val done = approveNow(request)
                val serverError = error(500, "FAILED_INTERNAL_SYSTEM_PROCESSING", "내부 시스템 처리 작업이 실패했습니다.")
                Decision(done, MockApproveResult(serverError, delayed = false))
            }

            MockScenario.LOOKUP_UNAVAILABLE -> {
                val done = approveNow(request)
                lookupFailures[request.orderId] = AtomicInteger(1)
                Decision(done, MockApproveResult(MockPgResponse(200, doneBody(done)), delayed = true))
            }

            MockScenario.IN_PROGRESS_THEN_DONE -> {
                Decision(Entry.InProgress(request), MockApproveResult(processingResponse(), delayed = true))
            }
        }

    private fun replay(existing: Entry): MockApproveResult =
        when (existing) {
            is Entry.InProgress -> MockApproveResult(processingResponse(), delayed = false)
            is Entry.Done -> MockApproveResult(MockPgResponse(200, doneBody(existing)), delayed = false)
            is Entry.Declined -> MockApproveResult(declinedResponse(), delayed = false)
        }

    private fun approveNow(request: MockApproveRequest): Entry.Done {
        count(approvedCounts, request.orderId)
        return newDone(request)
    }

    private fun newDone(request: MockApproveRequest): Entry.Done {
        // 토스 형식: 2022-01-01T00:00:00+09:00
        val approvedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS).atOffset(ZoneOffset.ofHours(9))
        return Entry.Done(request, "mock_pk_${UUID.randomUUID()}", approvedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
    }

    private fun doneBody(entry: Entry.Done): Map<String, Any?> =
        mapOf(
            "status" to "DONE",
            "type" to "BILLING",
            "paymentKey" to entry.paymentKey,
            "orderId" to entry.request.orderId,
            "orderName" to entry.request.orderName,
            "totalAmount" to entry.request.amount,
            "approvedAt" to entry.approvedAt,
        )

    private fun inProgressBody(entry: Entry.InProgress): Map<String, Any?> =
        mapOf(
            "status" to "IN_PROGRESS",
            "type" to "BILLING",
            "orderId" to entry.request.orderId,
            "orderName" to entry.request.orderName,
            "totalAmount" to entry.request.amount,
        )

    private fun declinedResponse() = error(403, "REJECT_CARD_PAYMENT", "한도초과 혹은 잔액부족으로 결제에 실패했습니다.")

    private fun processingResponse() = error(409, "IDEMPOTENT_REQUEST_PROCESSING", "이전 멱등 요청이 처리중입니다.")

    private fun error(
        status: Int,
        code: String,
        message: String,
    ) = MockPgResponse(status, mapOf("code" to code, "message" to message))

    private fun count(
        counters: ConcurrentHashMap<String, AtomicInteger>,
        orderId: String,
    ) {
        counters.computeIfAbsent(orderId) { AtomicInteger() }.incrementAndGet()
    }
}
