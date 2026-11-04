package com.jinseon.billing.subscription

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface SubscriptionRepository : JpaRepository<Subscription, Long> {
    // CANCELED가 아닌 구독. 고객당 하나만 있을 수 있다(live_customer_key UNIQUE).
    @Query(
        "select s from Subscription s where s.customerKey = :customerKey and s.status <> com.jinseon.billing.subscription.SubscriptionStatus.CANCELED",
    )
    fun findLiveByCustomerKey(customerKey: String): Subscription?
}
