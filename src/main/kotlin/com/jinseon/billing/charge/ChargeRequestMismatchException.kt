package com.jinseon.billing.charge

// 같은 chargeKey로 이미 만든 결제와 내용이 다른 요청이 왔다. 결제사를 부르기 전에 던지므로 청구는 일어나지 않았다.
// 메시지에는 달라진 항목의 이름만 넣는다. 빌링키 같은 값은 넣지 않는다.
class ChargeRequestMismatchException(
    val chargeKey: String,
    val changedFields: List<String>,
) : RuntimeException("같은 chargeKey에 다른 내용의 결제 요청이 들어왔습니다: chargeKey=$chargeKey, 달라진 항목=$changedFields")
