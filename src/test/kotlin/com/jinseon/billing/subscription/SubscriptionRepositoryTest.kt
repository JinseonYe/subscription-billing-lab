package com.jinseon.billing.subscription

import com.jinseon.billing.TestcontainersConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 이 테스트는 스키마(V2)와 엔티티 매핑을 확인한다. 제약은 엔티티 로직과 상관없이 SQL을 직접 실행해서 확인한다.
// DB 제약은 애플리케이션 가드가 뚫렸을 때의 마지막 방어선이기 때문이다.
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class SubscriptionRepositoryTest(
    @Autowired private val subscriptionRepository: SubscriptionRepository,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val transactionManager: PlatformTransactionManager,
) {
    private val createdAt = Instant.parse("2026-01-01T00:00:00.123456Z")

    @BeforeEach
    fun setUp() {
        jdbc.update("DELETE FROM subscription")
    }

    private fun insertRow(
        customerKey: String = "customer-1",
        status: String = "ACTIVE",
        pendingChargeKey: String? = null,
        canceledReason: String? = null,
    ) {
        jdbc.update(
            """
            INSERT INTO subscription
                (customer_key, billing_key, amount, order_name, status, cycle_no, attempt_no, pending_charge_key,
                 anchor_at, period_start, period_end, cancel_at_period_end, canceled_reason, retry_count, next_retry_at,
                 created_at, version)
            VALUES (?, 'mock-billing-key', 9900, '월 구독', ?, 1, 1, ?,
                    NULL, NULL, NULL, FALSE, ?, 0, NULL,
                    '2026-01-01 00:00:00.000000', 0)
            """.trimIndent(),
            customerKey,
            status,
            pendingChargeKey,
            canceledReason,
        )
    }

    // 다른 제약에 걸려서 우연히 통과하지 않도록, 걸린 제약의 이름까지 확인한다.
    // 예외 타입은 제약마다 다르다. UNIQUE 위반은 DataIntegrityViolationException이지만,
    // Spring이 MySQL CHECK 위반(에러 3819)은 번역하지 못해 UncategorizedSQLException으로 나온다.
    private fun assertViolates(
        constraint: String,
        block: () -> Unit,
    ) {
        val e = assertFailsWith<DataAccessException> { block() }
        assertTrue(e.message?.contains(constraint) == true, "기대한 제약 $constraint 이 아닌 다른 이유로 실패했다: ${e.message}")
    }

    @Test
    fun `새 구독을 저장하면 PENDING으로 시작하고 첫 시도를 열면 pendingChargeKey가 채워진다`() {
        val id =
            assertNotNull(
                TransactionTemplate(transactionManager).execute {
                    val saved =
                        subscriptionRepository.saveAndFlush(
                            Subscription("customer-1", "mock-billing-key", 9_900L, "월 구독", createdAt),
                        )
                    saved.beginFirstAttempt()
                    saved.id
                },
            )

        val loaded = assertNotNull(subscriptionRepository.findByIdOrNull(id))
        assertEquals(SubscriptionStatus.PENDING, loaded.status)
        assertEquals("SUB-$id-C1-A1", loaded.pendingChargeKey)
        assertEquals(1, loaded.cycleNo)
        assertEquals(1, loaded.attemptNo)
        assertEquals("customer-1", loaded.customerKey)
        assertEquals("mock-billing-key", loaded.billingKey)
        assertEquals(9_900L, loaded.amount)
        assertEquals("월 구독", loaded.orderName)
        assertEquals(createdAt, loaded.createdAt)
        assertNull(loaded.anchorAt)
        assertNull(loaded.periodStart)
        assertNull(loaded.periodEnd)
        assertFalse(loaded.cancelAtPeriodEnd)
        assertNull(loaded.canceledReason)
        assertEquals(0, loaded.retryCount)
        assertNull(loaded.nextRetryAt)
    }

    @Test
    fun `모든 컬럼이 채워진 행도 엔티티로 읽힌다`() {
        jdbc.update(
            """
            INSERT INTO subscription
                (customer_key, billing_key, amount, order_name, status, cycle_no, attempt_no, pending_charge_key,
                 anchor_at, period_start, period_end, cancel_at_period_end, canceled_reason, retry_count, next_retry_at,
                 created_at, version)
            VALUES ('customer-past-due', 'bk', 19900, '연 구독', 'PAST_DUE', 3, 1, NULL,
                    '2026-01-31 03:00:00.123456', '2026-02-28 03:00:00.000000', '2026-03-31 03:00:00.000000', TRUE, NULL, 2,
                    '2026-04-01 03:00:00.000000', '2026-01-31 03:00:00.123456', 5),
                   ('customer-canceled', 'bk', 9900, '월 구독', 'CANCELED', 2, 1, NULL,
                    NULL, NULL, NULL, FALSE, 'RETRY_EXHAUSTED', 3,
                    NULL, '2026-01-01 00:00:00.000000', 1)
            """.trimIndent(),
        )

        val pastDue = assertNotNull(subscriptionRepository.findLiveByCustomerKey("customer-past-due"))
        assertEquals(SubscriptionStatus.PAST_DUE, pastDue.status)
        assertEquals(3, pastDue.cycleNo)
        assertEquals(Instant.parse("2026-01-31T03:00:00.123456Z"), pastDue.anchorAt)
        assertEquals(Instant.parse("2026-02-28T03:00:00Z"), pastDue.periodStart)
        assertEquals(Instant.parse("2026-03-31T03:00:00Z"), pastDue.periodEnd)
        assertTrue(pastDue.cancelAtPeriodEnd)
        assertEquals(2, pastDue.retryCount)
        assertEquals(Instant.parse("2026-04-01T03:00:00Z"), pastDue.nextRetryAt)
        assertEquals(5L, pastDue.version)

        val canceled = subscriptionRepository.findAll().single { it.customerKey == "customer-canceled" }
        assertEquals(SubscriptionStatus.CANCELED, canceled.status)
        assertEquals(CancelReason.RETRY_EXHAUSTED, canceled.canceledReason)
        assertNull(canceled.pendingChargeKey)
    }

    @Test
    fun `CANCELED 구독에 송신 중인 키가 있는 행은 INSERT가 거부된다`() {
        assertViolates("chk_subscription_canceled_no_pending") {
            insertRow(status = "CANCELED", pendingChargeKey = "SUB-1-C1-A1", canceledReason = "USER_CANCELED")
        }
    }

    @Test
    fun `송신 중인 구독의 status만 CANCELED로 바꾸는 UPDATE는 거부된다`() {
        insertRow(status = "ACTIVE", pendingChargeKey = "SUB-1-C2-A1")

        assertViolates("chk_subscription_canceled_no_pending") {
            jdbc.update("UPDATE subscription SET status = 'CANCELED', canceled_reason = 'USER_CANCELED'")
        }
    }

    @Test
    fun `status와 pendingChargeKey와 해지 사유를 한 UPDATE에서 함께 바꾸면 통과한다`() {
        // ADR 0002 알려진 한계 4번의 운영 절차: 결제사에 기록이 없음을 확인한 건을 운영자가 수동으로 해지한다.
        insertRow(status = "PENDING", pendingChargeKey = "SUB-1-C1-A1")

        jdbc.update("UPDATE subscription SET status = 'CANCELED', canceled_reason = 'FIRST_PAYMENT_DECLINED', pending_charge_key = NULL")

        val row = jdbc.queryForMap("SELECT status, canceled_reason, pending_charge_key FROM subscription")
        assertEquals("CANCELED", row["status"])
        assertEquals("FIRST_PAYMENT_DECLINED", row["canceled_reason"])
        assertNull(row["pending_charge_key"])
    }

    @Test
    fun `해지 사유는 CANCELED일 때만 있어야 한다`() {
        assertViolates("chk_subscription_canceled_reason") { insertRow(status = "CANCELED", canceledReason = null) }
        assertViolates("chk_subscription_canceled_reason") { insertRow(status = "ACTIVE", canceledReason = "USER_CANCELED") }
    }

    @Test
    fun `같은 고객의 live 구독은 하나만 만들 수 있다`() {
        insertRow(customerKey = "customer-1", status = "ACTIVE")

        assertViolates("uk_subscription_live_customer") { insertRow(customerKey = "customer-1", status = "PAST_DUE") }
    }

    @Test
    fun `PENDING도 live라서 같은 고객이 다시 신청할 수 없다`() {
        insertRow(customerKey = "customer-1", status = "PENDING", pendingChargeKey = "SUB-1-C1-A1")

        assertViolates("uk_subscription_live_customer") {
            insertRow(customerKey = "customer-1", status = "PENDING", pendingChargeKey = "SUB-2-C1-A1")
        }
    }

    @Test
    fun `해지된 뒤에는 같은 고객이 새로 신청할 수 있다`() {
        insertRow(customerKey = "customer-1", status = "ACTIVE")
        jdbc.update("UPDATE subscription SET status = 'CANCELED', canceled_reason = 'USER_CANCELED' WHERE customer_key = 'customer-1'")

        insertRow(customerKey = "customer-1", status = "PENDING", pendingChargeKey = "SUB-2-C1-A1")

        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM subscription WHERE customer_key = 'customer-1'", Int::class.java))
    }

    @Test
    fun `해지된 구독은 같은 고객에게 여러 개 쌓일 수 있다`() {
        insertRow(customerKey = "customer-1", status = "CANCELED", canceledReason = "USER_CANCELED")
        insertRow(customerKey = "customer-1", status = "CANCELED", canceledReason = "RETRY_EXHAUSTED")
        insertRow(customerKey = "customer-1", status = "CANCELED", canceledReason = "FIRST_PAYMENT_DECLINED")

        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM subscription", Int::class.java))
    }

    @Test
    fun `서로 다른 고객은 각자 live 구독을 가질 수 있다`() {
        insertRow(customerKey = "customer-1", status = "ACTIVE")
        insertRow(customerKey = "customer-2", status = "ACTIVE")

        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM subscription", Int::class.java))
    }

    @Test
    fun `대소문자만 다른 고객 키도 같은 고객으로 막는다`() {
        // MySQL 기본 콜레이션은 대소문자를 구분하지 않는다. 가입을 거절하는 쪽이라 안전하다(ADR 0002 가정 3).
        insertRow(customerKey = "Customer-1", status = "ACTIVE")

        assertViolates("uk_subscription_live_customer") { insertRow(customerKey = "customer-1", status = "ACTIVE") }
    }

    @Test
    fun `live 구독 조회는 CANCELED를 돌려주지 않는다`() {
        insertRow(customerKey = "customer-1", status = "CANCELED", canceledReason = "USER_CANCELED")
        insertRow(customerKey = "customer-1", status = "PAST_DUE")
        insertRow(customerKey = "customer-2", status = "CANCELED", canceledReason = "USER_CANCELED")

        val live = assertNotNull(subscriptionRepository.findLiveByCustomerKey("customer-1"))

        assertEquals(SubscriptionStatus.PAST_DUE, live.status)
        assertNull(subscriptionRepository.findLiveByCustomerKey("customer-2"))
        assertNull(subscriptionRepository.findLiveByCustomerKey("customer-없음"))
    }

    @Test
    fun `live 구독 조회도 UNIQUE와 같은 기준으로 대소문자를 구분하지 않는다`() {
        insertRow(customerKey = "Customer-1", status = "ACTIVE")

        assertNotNull(subscriptionRepository.findLiveByCustomerKey("customer-1"))
    }
}
