package com.jinseon.billing.charge

import ch.qos.logback.classic.Level
import com.jinseon.billing.payment.Payment
import com.jinseon.billing.payment.PaymentRepository
import com.jinseon.billing.payment.PaymentStatus
import com.jinseon.billing.payment.PaymentWriter
import com.jinseon.billing.pg.PgApproveRequest
import com.jinseon.billing.pg.PgClient
import com.jinseon.billing.pg.PgResult
import com.jinseon.billing.support.LogCapture
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 확정 잡의 분기를 가짜 결제사와 가짜 저장소로 하나씩 확인한다.
// 실제 DB와 Mock PG를 쓰는 흐름 검증은 ChargeScenarioTest가 맡는다.
class PaymentStatusResolverTest {
    private val repository = mockk<PaymentRepository>()
    private val writer = mockk<PaymentWriter>()
    private val pgClient = mockk<PgClient>()

    private val now = Instant.parse("2026-01-02T00:00:00Z")
    private val resolver = PaymentStatusResolver(repository, writer, pgClient, Clock.fixed(now, ZoneOffset.UTC))

    private val approved = PgResult.Approved("pk-1", now)
    private val declined = PgResult.Declined("REJECT_CARD_PAYMENT", "카드 거절")

    private fun payment(
        orderId: String,
        requestedAgo: Duration = Duration.ofMinutes(10),
    ): Payment = Payment(orderId, "SUB-$orderId", "mock-billing-key", "customer-1", 9_900L, "월 구독", now.minus(requestedAgo))

    private fun request(orderId: String) = PgApproveRequest(orderId, "mock-billing-key", "customer-1", 9_900L, "월 구독")

    private fun givenTargets(vararg payments: Payment) {
        every {
            repository.findResolvable(
                listOf(PaymentStatus.REQUESTED, PaymentStatus.UNKNOWN),
                now.minus(PaymentStatusResolver.RESOLVE_DELAY),
            )
        } returns payments.toList()
        every { writer.applyResult(any(), any()) } returns payment("반영됨")
    }

    @Test
    fun `확정 대상은 REQUESTED와 UNKNOWN 중 확정 대기 시간이 지난 결제다`() {
        givenTargets()

        resolver.resolvePending()

        verify(
            exactly = 1,
        ) { repository.findResolvable(listOf(PaymentStatus.REQUESTED, PaymentStatus.UNKNOWN), now.minus(Duration.ofMinutes(5))) }
    }

    @Test
    fun `한 건의 조회가 예외로 실패해도 다음 건은 계속 확정한다`() {
        givenTargets(payment("order-1"), payment("order-2"))
        every { pgClient.lookup("order-1") } throws IllegalStateException("조회 중 오류")
        every { pgClient.lookup("order-2") } returns approved

        resolver.resolvePending()

        verify(exactly = 1) { writer.applyResult(any(), approved) }
    }

    @Test
    fun `한 건의 결과 반영이 예외로 실패해도 다음 건은 계속 확정한다`() {
        givenTargets(payment("order-1"), payment("order-2"))
        every { pgClient.lookup("order-1") } returns declined
        every { pgClient.lookup("order-2") } returns approved
        every { writer.applyResult(any(), declined) } throws RuntimeException("DB 장애")

        resolver.resolvePending()

        verify(exactly = 1) { writer.applyResult(any(), approved) }
    }

    @Test
    fun `조회 결과를 모르면 승인 요청도 결과 반영도 하지 않는다`() {
        givenTargets(payment("order-1"))
        every { pgClient.lookup("order-1") } returns PgResult.Unknown("조회 장애")

        resolver.resolvePending()

        verify(exactly = 0) { pgClient.approve(any()) }
        verify(exactly = 0) { writer.applyResult(any(), any()) }
    }

    @Test
    fun `결제사에 기록이 없으면 행에 저장된 같은 값으로 재전송한다`() {
        givenTargets(payment("order-1"))
        every { pgClient.lookup("order-1") } returns PgResult.NotFound
        every { pgClient.approve(request("order-1")) } returns approved

        resolver.resolvePending()

        verify(exactly = 1) { pgClient.approve(request("order-1")) }
        verify(exactly = 1) { writer.applyResult(any(), approved) }
    }

    @Test
    fun `재전송 결과가 거절이면 거절을 그대로 반영한다`() {
        givenTargets(payment("order-1"))
        every { pgClient.lookup("order-1") } returns PgResult.NotFound
        every { pgClient.approve(any()) } returns declined

        resolver.resolvePending()

        verify(exactly = 1) { writer.applyResult(any(), declined) }
    }

    @Test
    fun `재전송했는데 결제사가 처리 중 409라고 하면 Unknown으로 반영하고 거절로 보지 않는다`() {
        givenTargets(payment("order-1"))
        val processing = PgResult.Unknown("승인 응답 HTTP 409 code=IDEMPOTENT_REQUEST_PROCESSING")
        every { pgClient.lookup("order-1") } returns PgResult.NotFound
        every { pgClient.approve(any()) } returns processing

        resolver.resolvePending()

        verify(exactly = 1) { writer.applyResult(any(), processing) }
        verify(exactly = 0) { writer.applyResult(any(), ofType<PgResult.Declined>()) }
    }

    @Test
    fun `재전송 기한이 지나면 재전송도 결과 반영도 하지 않는다`() {
        givenTargets(payment("order-1", requestedAgo = Duration.ofHours(25)))
        every { pgClient.lookup("order-1") } returns PgResult.NotFound

        resolver.resolvePending()

        verify(exactly = 0) { pgClient.approve(any()) }
        verify(exactly = 0) { writer.applyResult(any(), any()) }
    }

    @Test
    fun `결과를 모르는 채로 재전송 기한 24시간을 넘기면 ERROR 로그를 남긴다`() {
        givenTargets(payment("order-late", requestedAgo = Duration.ofHours(25)))
        every { pgClient.lookup("order-late") } returns PgResult.Unknown("조회 응답 HTTP 401")

        LogCapture(PaymentStatusResolver::class.java).use { logs ->
            resolver.resolvePending()

            val errors = logs.messages(Level.ERROR)
            assertEquals(1, errors.size, "ERROR 로그: $errors")
            assertTrue(errors.single().contains("RESOLVE_STUCK") && errors.single().contains("order-late"), errors.single())
        }
        verify(exactly = 0) { writer.applyResult(any(), any()) }
    }

    @Test
    fun `결과를 모르는 채로 기한 안이면 ERROR가 아니라 INFO만 남기고 기한 정각에도 ERROR가 아니다`() {
        givenTargets(
            payment("order-1", requestedAgo = Duration.ofHours(2)),
            payment("order-2", requestedAgo = PaymentStatusResolver.RESEND_DEADLINE),
        )
        every { pgClient.lookup(any()) } returns PgResult.Unknown("조회 장애")

        LogCapture(PaymentStatusResolver::class.java).use { logs ->
            resolver.resolvePending()

            assertEquals(emptyList(), logs.messages(Level.ERROR))
            assertEquals(2, logs.messages(Level.INFO).count { it.contains("RESOLVE_STILL_UNKNOWN") })
        }
    }
}
