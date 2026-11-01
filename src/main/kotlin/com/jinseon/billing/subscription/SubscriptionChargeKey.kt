package com.jinseon.billing.subscription

// 구독 결제 시도 하나를 가리키는 chargeKey: SUB-{구독 id}-C{주기 번호}-A{시도 번호}.
// 대문자 ASCII와 숫자만 쓴다. MySQL 기본 콜레이션은 대소문자를 구분하지 않아서 소문자가 섞이면 다른 키가 같은 키로 취급된다.
// 키를 만들고 읽는 일은 이 클래스에서만 한다.
data class SubscriptionChargeKey(
    val subscriptionId: Long,
    val cycleNo: Int,
    val attemptNo: Int,
) {
    init {
        require(subscriptionId > 0) { "구독 id는 0보다 커야 합니다: $subscriptionId" }
        require(cycleNo > 0) { "주기 번호는 0보다 커야 합니다: $cycleNo" }
        require(attemptNo > 0) { "시도 번호는 0보다 커야 합니다: $attemptNo" }
    }

    val value: String = "SUB-$subscriptionId-C$cycleNo-A$attemptNo"

    override fun toString(): String = value

    companion object {
        private val pattern = Regex("SUB-([1-9][0-9]*)-C([1-9][0-9]*)-A([1-9][0-9]*)")

        // 형식이 맞지 않으면 null이다. 숫자가 Long, Int 범위를 넘어도 null이다.
        fun parse(value: String): SubscriptionChargeKey? {
            val (id, cycle, attempt) = pattern.matchEntire(value)?.destructured ?: return null
            return SubscriptionChargeKey(
                subscriptionId = id.toLongOrNull() ?: return null,
                cycleNo = cycle.toIntOrNull() ?: return null,
                attemptNo = attempt.toIntOrNull() ?: return null,
            )
        }
    }
}
