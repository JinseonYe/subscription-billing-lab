package com.jinseon.billing.charge

import com.jinseon.billing.payment.Payment
import com.jinseon.billing.payment.PaymentRepository
import com.jinseon.billing.payment.PaymentStatus
import com.jinseon.billing.payment.PaymentWriter
import com.jinseon.billing.pg.PgClient
import com.jinseon.billing.pg.PgResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val log = LoggerFactory.getLogger(PaymentStatusResolver::class.java)

// 결과를 모르는 결제(REQUESTED, UNKNOWN)를 결제사에 조회해서 확정한다. 스케줄러는 다음 기능이라 지금은 직접 호출한다.
// 이 클래스에도 @Transactional을 붙이지 않는다. 결제사 호출은 트랜잭션 밖이어야 한다.
@Service
class PaymentStatusResolver(
    private val paymentRepository: PaymentRepository,
    private val paymentWriter: PaymentWriter,
    private val pgClient: PgClient,
    private val clock: Clock,
) {
    companion object {
        // 원래 요청이 아직 응답을 기다리는 중일 수 있다. 호출 최대 소요(read-timeout 65초)보다 충분히 길어야 한다.
        val RESOLVE_DELAY: Duration = Duration.ofMinutes(5)

        // 이 시간이 지나면 재전송하지 않는다. 토스 멱등키 유효기간(15일)보다 훨씬 짧아야 한다.
        val RESEND_DEADLINE: Duration = Duration.ofHours(24)
    }

    fun resolvePending() {
        requireNoTransaction("PaymentStatusResolver.resolvePending")

        val now = clock.instant()
        val targets = paymentRepository.findResolvable(listOf(PaymentStatus.REQUESTED, PaymentStatus.UNKNOWN), now.minus(RESOLVE_DELAY))
        for (payment in targets) {
            // 한 건이 실패해도 나머지는 계속 처리한다.
            try {
                resolve(payment, now)
            } catch (e: Exception) {
                log.error("RESOLVE_FAILED 확정하지 못함 orderId={}", payment.orderId, e)
            }
        }
    }

    private fun resolve(
        payment: Payment,
        now: Instant,
    ) {
        when (val observed = pgClient.lookup(payment.orderId)) {
            is PgResult.Approved, is PgResult.Declined -> paymentWriter.applyResult(payment.id, observed)

            // 모르면 그대로 둔다. 시간이 지났다고 FAILED로 바꾸지 않는다.
            is PgResult.Unknown -> logStillUnknown(payment, observed.reason, now)

            PgResult.NotFound -> resendIfInTime(payment, now)
        }
    }

    // 상태는 바꾸지 않는다. 다만 기한(24시간)이 지났는데도 모르면 사람이 봐야 하므로 ERROR로 남긴다.
    private fun logStillUnknown(
        payment: Payment,
        reason: String,
        now: Instant,
    ) {
        if (Duration.between(payment.requestedAt, now) > RESEND_DEADLINE) {
            log.error("RESOLVE_STUCK 기한이 지났는데도 결과를 알 수 없음 orderId={} reason={}", payment.orderId, reason)
        } else {
            log.info("RESOLVE_STILL_UNKNOWN orderId={} reason={}", payment.orderId, reason)
        }
    }

    // 결제사에 기록이 없을 때만 재전송한다. 같은 orderId와 같은 멱등키라서 결제사가 이중으로 승인하지 않는다.
    private fun resendIfInTime(
        payment: Payment,
        now: Instant,
    ) {
        if (Duration.between(payment.requestedAt, now) > RESEND_DEADLINE) {
            log.error("RESEND_DEADLINE_PASSED 재전송 기한이 지나 확정하지 않음 orderId={}", payment.orderId)
            return
        }
        val result = pgClient.approve(payment.toApproveRequest())
        paymentWriter.applyResult(payment.id, result)
    }
}
