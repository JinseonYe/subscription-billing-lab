package com.jinseon.billing.mockpg

// 토스 자동결제 승인 요청 본문과 같은 모양이다.
data class MockApproveRequest(
    val customerKey: String,
    val amount: Long,
    val orderId: String,
    val orderName: String,
)

data class MockPgResponse(
    val status: Int,
    val body: Map<String, Any?>,
)

// delayed가 true이면 호출한 쪽이 설정된 시간만큼 기다렸다가 응답을 돌려준다(호출자 타임아웃 재현).
data class MockApproveResult(
    val response: MockPgResponse,
    val delayed: Boolean,
)
