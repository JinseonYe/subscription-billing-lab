package com.jinseon.billing.pg

import java.time.Instant

// 결제사 호출 결과. pg 밖에서는 토스의 타입, 에러 코드, JSON 구조 대신 이 타입만 쓴다.
sealed interface PgResult {
    data class Approved(
        val paymentKey: String,
        val approvedAt: Instant,
    ) : PgResult

    data class Declined(
        val code: String,
        val message: String,
    ) : PgResult

    data object NotFound : PgResult

    data class Unknown(
        val reason: String,
    ) : PgResult
}
