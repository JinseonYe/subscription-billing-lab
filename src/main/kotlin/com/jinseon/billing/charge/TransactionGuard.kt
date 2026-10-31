package com.jinseon.billing.charge

import org.springframework.transaction.support.TransactionSynchronizationManager

// 결제사 호출은 트랜잭션 밖에서 해야 한다. 호출한 쪽에 바깥 트랜잭션이 있으면 PaymentWriter의 트랜잭션이 거기에 합류해서:
// - TX1(결제 기록)이 커밋되기 전에 승인 요청이 나가고, DB 커넥션을 최대 65초 붙잡는다.
// - 승인된 뒤 TX2가 실패하면 바깥 트랜잭션이 롤백되어 결제 기록이 사라진다.
//   같은 chargeKey로 다시 시도하면 새 orderId로 두 번째 승인이 나간다(이중 청구).
// 그래서 승인 요청을 보내기 전에, 트랜잭션 안이면 예외로 막는다.
internal fun requireNoTransaction(caller: String) {
    check(!TransactionSynchronizationManager.isActualTransactionActive()) {
        "$caller 는 트랜잭션 안에서 호출할 수 없습니다. 결제사 호출은 트랜잭션 밖에서 해야 합니다."
    }
}
