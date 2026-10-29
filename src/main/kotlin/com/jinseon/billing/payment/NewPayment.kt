package com.jinseon.billing.payment

data class NewPayment(
    val chargeKey: String,
    val billingKey: String,
    val customerKey: String,
    val amount: Long,
    val orderName: String,
) {
    init {
        require(amount > 0) { "금액은 0보다 커야 합니다: $amount" }
    }

    // 데이터 클래스의 기본 toString은 모든 필드를 찍는다. 빌링키가 로그에 남지 않게 막는다.
    override fun toString(): String = "NewPayment(chargeKey=$chargeKey, amount=$amount)"
}
