package com.jinseon.billing.mockpg

// 주문번호(orderId)마다 "첫 승인 요청"에만 적용된다. 재전송부터는 정상(APPROVE_OK)으로 처리한다.
enum class MockScenario {
    // 승인 기록, 200 DONE
    APPROVE_OK,

    // 기록 없음, 403 REJECT_CARD_PAYMENT
    DECLINED,

    // 승인 기록 후 응답이 늦어 호출자가 타임아웃을 맞는다
    TIMEOUT_AFTER_APPROVE,

    // 요청이 유실된 경우. 아무것도 기록하지 않고 응답만 늦는다
    TIMEOUT_BEFORE_APPROVE,

    // 승인 기록 후 500을 돌려준다
    SERVER_ERROR_AFTER_APPROVE,

    // 승인 기록 후 응답이 늦고, 첫 조회는 503으로 실패한다
    LOOKUP_UNAVAILABLE,

    // 처리 중(IN_PROGRESS)으로 기록하고 응답이 늦다. completeInProgress를 부르기 전까지 승인되지 않는다
    IN_PROGRESS_THEN_DONE,
}
