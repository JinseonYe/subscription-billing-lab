package com.jinseon.billing.support

import com.jinseon.billing.subscription.Subscription
import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant

// 저장 전의 엔티티는 id가 0이라 chargeKey를 만들 수 없다. DB 없이 단위 테스트를 하려고 저장된 것처럼 id를 정한다.
fun savedSubscription(
    id: Long = 7L,
    customerKey: String = "customer-1",
    billingKey: String = "mock-billing-key",
    amount: Long = 9_900L,
): Subscription {
    val subscription = Subscription(customerKey, billingKey, amount, "월 구독", Instant.parse("2026-01-01T00:00:00Z"))
    ReflectionTestUtils.setField(subscription, "id", id)
    return subscription
}
