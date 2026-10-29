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
}
