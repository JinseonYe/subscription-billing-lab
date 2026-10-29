package com.jinseon.billing.payment

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface PaymentRepository : JpaRepository<Payment, Long> {
    fun findByChargeKey(chargeKey: String): Payment?

    @Query("select p from Payment p where p.status in :statuses and p.requestedAt < :before order by p.requestedAt asc")
    fun findResolvable(
        statuses: Collection<PaymentStatus>,
        before: Instant,
    ): List<Payment>
}
