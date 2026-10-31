package com.jinseon.billing.support

import com.jinseon.billing.pg.PgClient
import com.jinseon.billing.pg.PgResult

private const val MAX_ATTEMPTS = 20
private const val WARM_UP_ORDER_ID = "warm-up-order"

// Mock PG의 첫 요청은 서블릿 초기화 때문에 느려서(이 환경에서 500ms 안팎), 짧은 read-timeout(500ms)에 걸려 Unknown이 될 수 있다.
// 그러면 시나리오와 상관없는 이유로 테스트가 실패한다. 시나리오를 시작하기 전에 정상 응답이 올 때까지 한 번 데워 둔다.
// 데우는 요청은 존재하지 않는 주문번호를 조회할 뿐이라 결제 상태에 영향이 없다. 호출 기록은 이어서 ledger.reset()이 지운다.
fun warmUpMockPg(pgClient: PgClient) {
    repeat(MAX_ATTEMPTS) {
        if (pgClient.lookup(WARM_UP_ORDER_ID) == PgResult.NotFound) return
    }
    error("Mock PG가 ${MAX_ATTEMPTS}번 시도해도 정상 응답하지 않습니다.")
}
