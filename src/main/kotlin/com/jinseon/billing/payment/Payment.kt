package com.jinseon.billing.payment

import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Version
import java.time.Instant

// 요청 필드는 모두 val이다. 재전송 요청이 첫 송신과 같다는 것을 언어 수준에서 보장한다.
// toString을 만들지 않는 이유: billingKey가 로그에 찍히면 안 된다.
@Entity
class Payment(
    val orderId: String,
    val chargeKey: String,
    val billingKey: String,
    val customerKey: String,
    val amount: Long,
    val orderName: String,
    val requestedAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0L

    @Enumerated(EnumType.STRING)
    var status: PaymentStatus = PaymentStatus.REQUESTED
        protected set

    var pgPaymentKey: String? = null
        protected set

    var failureCode: String? = null
        protected set

    var failureMessage: String? = null
        protected set

    var approvedAt: Instant? = null
        protected set

    var resolvedAt: Instant? = null
        protected set

    @Version
    var version: Long = 0L
        protected set

    fun approve(
        pgPaymentKey: String,
        approvedAt: Instant,
        now: Instant,
    ) {
        transitionTo(PaymentStatus.APPROVED)
        this.pgPaymentKey = pgPaymentKey
        this.approvedAt = approvedAt
        this.resolvedAt = now
    }

    fun fail(
        code: String,
        message: String,
        now: Instant,
    ) {
        transitionTo(PaymentStatus.FAILED)
        this.failureCode = code
        this.failureMessage = message
        this.resolvedAt = now
    }

    fun markUnknown() {
        transitionTo(PaymentStatus.UNKNOWN)
    }

    private fun transitionTo(next: PaymentStatus) {
        check(status.canTransitionTo(next)) { "허용되지 않은 상태 전이입니다: $status -> $next (orderId=$orderId)" }
        status = next
    }
}
