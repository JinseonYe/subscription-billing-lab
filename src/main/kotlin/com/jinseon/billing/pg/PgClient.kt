package com.jinseon.billing.pg

// 결제사 호출 창구. pg 밖에서는 이 인터페이스와 PgResult만 안다.
interface PgClient {
    // 승인 요청. 같은 orderId로 다시 보내면 결제사가 같은 결과를 돌려준다(Idempotency-Key = orderId).
    fun approve(request: PgApproveRequest): PgResult

    // orderId로 승인 여부를 조회한다.
    fun lookup(orderId: String): PgResult
}

data class PgApproveRequest(
    val orderId: String,
    val billingKey: String,
    val customerKey: String,
    val amount: Long,
    val orderName: String,
) {
    // 데이터 클래스의 기본 toString은 모든 필드를 찍는다. 빌링키가 로그에 남지 않게 막는다.
    override fun toString(): String = "PgApproveRequest(orderId=$orderId, amount=$amount)"
}
