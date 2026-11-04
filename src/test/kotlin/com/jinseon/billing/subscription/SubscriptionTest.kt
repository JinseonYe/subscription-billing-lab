package com.jinseon.billing.subscription

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.test.util.ReflectionTestUtils
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 엔티티의 전이 규칙을 DB 없이 확인한다. 상태는 실제 흐름과 같은 방법(엔티티 메서드)으로 만든다.
// 서울 시각 2026-01-31 12:00이 앵커다. 주기 1은 2월 28일 12:00까지, 주기 2는 3월 31일 12:00까지다.
private const val ID = 7L
private val anchor = Instant.parse("2026-01-31T03:00:00Z")
private val cycle1End = Instant.parse("2026-02-28T03:00:00Z")
private val cycle2End = Instant.parse("2026-03-31T03:00:00Z")

private fun key(
    cycleNo: Int,
    attemptNo: Int = 1,
) = SubscriptionChargeKey(ID, cycleNo, attemptNo)

// 저장 전의 엔티티는 id가 0이라 chargeKey를 만들 수 없다. 저장된 것처럼 id를 정한다.
private fun unsavedSubscription() = Subscription("customer-1", "mock-billing-key", 9_900L, "월 구독", anchor.minusSeconds(60))

private fun newSubscription(): Subscription {
    val subscription = unsavedSubscription()
    ReflectionTestUtils.setField(subscription, "id", ID)
    return subscription
}

private fun pending(): Subscription {
    val subscription = newSubscription()
    subscription.beginFirstAttempt()
    return subscription
}

private fun pendingWithCancelRequest(): Subscription {
    val subscription = pending()
    subscription.requestCancel()
    return subscription
}

private fun active(): Subscription {
    val subscription = pending()
    subscription.reflect(key(1), PaymentFact.Approved(anchor))
    return subscription
}

private fun renewing(): Subscription {
    val subscription = active()
    subscription.closePeriod(1, cycle1End)
    return subscription
}

private fun renewingWithCancelReservation(): Subscription {
    val subscription = renewing()
    subscription.requestCancel()
    return subscription
}

private fun pastDue(): Subscription {
    val subscription = renewing()
    subscription.reflect(key(2), PaymentFact.Declined)
    return subscription
}

private fun canceledByFirstPaymentDecline(): Subscription {
    val subscription = pending()
    subscription.reflect(key(1), PaymentFact.Declined)
    return subscription
}

private fun canceledAtPeriodEnd(): Subscription {
    val subscription = active()
    subscription.requestCancel()
    subscription.closePeriod(1, cycle1End)
    return subscription
}

private fun canceledByRenewalDecline(): Subscription {
    val subscription = renewingWithCancelReservation()
    subscription.reflect(key(2), PaymentFact.Declined)
    return subscription
}

private fun canceledFromPastDue(): Subscription {
    val subscription = pastDue()
    subscription.requestCancel()
    return subscription
}

private val allStates: List<Pair<String, () -> Subscription>> =
    listOf(
        "PENDING" to ::pending,
        "PENDING 해지 요청" to ::pendingWithCancelRequest,
        "ACTIVE" to ::active,
        "ACTIVE 갱신 중" to ::renewing,
        "ACTIVE 갱신 중 해지 예약" to ::renewingWithCancelReservation,
        "PAST_DUE" to ::pastDue,
        "CANCELED 첫 결제 거절" to ::canceledByFirstPaymentDecline,
        "CANCELED 기간 끝 해지" to ::canceledAtPeriodEnd,
        "CANCELED 갱신 거절 해지" to ::canceledByRenewalDecline,
        "CANCELED PAST_DUE 해지" to ::canceledFromPastDue,
    )

private data class Snapshot(
    val status: SubscriptionStatus,
    val cycleNo: Int,
    val attemptNo: Int,
    val pendingChargeKey: String?,
    val anchorAt: Instant?,
    val periodStart: Instant?,
    val periodEnd: Instant?,
    val cancelAtPeriodEnd: Boolean,
    val canceledReason: CancelReason?,
    val retryCount: Int,
    val nextRetryAt: Instant?,
)

private fun Subscription.snapshot() =
    Snapshot(
        status,
        cycleNo,
        attemptNo,
        pendingChargeKey,
        anchorAt,
        periodStart,
        periodEnd,
        cancelAtPeriodEnd,
        canceledReason,
        retryCount,
        nextRetryAt,
    )

private typealias Expected = Pair<ReflectResult, SubscriptionStatus>

// 한 상태에서 네 가지 결제 사실이 왔을 때의 기대값. 미확정(Pending, NoPayment)은 같은 기대값을 쓴다.
private fun reflectRows(
    label: String,
    build: () -> Subscription,
    onApproved: Expected,
    onDeclined: Expected,
    onUnconfirmed: Expected,
): List<Arguments> =
    listOf(
        Arguments.of(label, build, PaymentFact.Approved(anchor), onApproved.first, onApproved.second),
        Arguments.of(label, build, PaymentFact.Declined, onDeclined.first, onDeclined.second),
        Arguments.of(label, build, PaymentFact.Pending, onUnconfirmed.first, onUnconfirmed.second),
        Arguments.of(label, build, PaymentFact.NoPayment, onUnconfirmed.first, onUnconfirmed.second),
    )

class SubscriptionTest {
    companion object {
        private val applied = ReflectResult.Applied
        private val noChange = ReflectResult.NoChange
        private val lateAfterCancel = ReflectResult.LateApprovalAfterCancel

        @JvmStatic
        fun reflectRules(): List<Arguments> =
            listOf(
                reflectRows(
                    "PENDING",
                    ::pending,
                    applied to SubscriptionStatus.ACTIVE,
                    applied to SubscriptionStatus.CANCELED,
                    noChange to SubscriptionStatus.PENDING,
                ),
                reflectRows(
                    "PENDING 해지 요청",
                    ::pendingWithCancelRequest,
                    applied to SubscriptionStatus.ACTIVE,
                    applied to SubscriptionStatus.CANCELED,
                    noChange to SubscriptionStatus.PENDING,
                ),
                reflectRows(
                    "ACTIVE",
                    ::active,
                    noChange to SubscriptionStatus.ACTIVE,
                    noChange to SubscriptionStatus.ACTIVE,
                    noChange to SubscriptionStatus.ACTIVE,
                ),
                reflectRows(
                    "ACTIVE 갱신 중",
                    ::renewing,
                    applied to SubscriptionStatus.ACTIVE,
                    applied to SubscriptionStatus.PAST_DUE,
                    noChange to SubscriptionStatus.ACTIVE,
                ),
                reflectRows(
                    "ACTIVE 갱신 중 해지 예약",
                    ::renewingWithCancelReservation,
                    applied to SubscriptionStatus.ACTIVE,
                    applied to SubscriptionStatus.CANCELED,
                    noChange to SubscriptionStatus.ACTIVE,
                ),
                reflectRows(
                    "PAST_DUE",
                    ::pastDue,
                    ReflectResult.LateApprovalInPastDue to SubscriptionStatus.PAST_DUE,
                    noChange to SubscriptionStatus.PAST_DUE,
                    noChange to SubscriptionStatus.PAST_DUE,
                ),
                reflectRows(
                    "CANCELED 첫 결제 거절",
                    ::canceledByFirstPaymentDecline,
                    lateAfterCancel to SubscriptionStatus.CANCELED,
                    noChange to SubscriptionStatus.CANCELED,
                    noChange to SubscriptionStatus.CANCELED,
                ),
                // 승인된 기간이 끝나 예약대로 해지된 구독이다. 현재 키의 결제가 APPROVED인 것은 정상이다.
                reflectRows(
                    "CANCELED 기간 끝 해지",
                    ::canceledAtPeriodEnd,
                    noChange to SubscriptionStatus.CANCELED,
                    noChange to SubscriptionStatus.CANCELED,
                    noChange to SubscriptionStatus.CANCELED,
                ),
                reflectRows(
                    "CANCELED 갱신 거절 해지",
                    ::canceledByRenewalDecline,
                    lateAfterCancel to SubscriptionStatus.CANCELED,
                    noChange to SubscriptionStatus.CANCELED,
                    noChange to SubscriptionStatus.CANCELED,
                ),
                reflectRows(
                    "CANCELED PAST_DUE 해지",
                    ::canceledFromPastDue,
                    lateAfterCancel to SubscriptionStatus.CANCELED,
                    noChange to SubscriptionStatus.CANCELED,
                    noChange to SubscriptionStatus.CANCELED,
                ),
            ).flatten()

        @JvmStatic
        fun states(): List<Arguments> = allStates.map { (label, build) -> Arguments.of(label, build) }
    }

    @ParameterizedTest(name = "{0} + {2} → {3}, {4}")
    @MethodSource("reflectRules")
    fun `현재 시도 키의 결제 사실을 반영하는 규칙 표`(
        label: String,
        build: () -> Subscription,
        fact: PaymentFact,
        expectedResult: ReflectResult,
        expectedStatus: SubscriptionStatus,
    ) {
        val subscription = build()
        val before = subscription.status

        val result = subscription.reflect(subscription.currentChargeKey, fact)

        assertEquals(expectedResult, result)
        assertEquals(expectedStatus, subscription.status)
        assertTrue(before == subscription.status || before.canTransitionTo(subscription.status), "$before -> ${subscription.status}")
        // 어떤 경로로 반영해도 불변식이 지켜진다.
        assertEquals(subscription.status == SubscriptionStatus.CANCELED, subscription.canceledReason != null)
        if (subscription.status == SubscriptionStatus.CANCELED) assertNull(subscription.pendingChargeKey)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("states")
    fun `현재 시도의 키가 아닌 결제 결과는 어느 상태에서도 아무것도 바꾸지 않는다`(
        label: String,
        build: () -> Subscription,
    ) {
        val subscription = build()
        val current = subscription.currentChargeKey
        val staleKeys =
            listOf(
                SubscriptionChargeKey(ID, current.cycleNo + 1, 1),
                SubscriptionChargeKey(ID, current.cycleNo, current.attemptNo + 1),
                SubscriptionChargeKey(ID + 1, current.cycleNo, current.attemptNo),
            )
        val facts = listOf(PaymentFact.Approved(anchor), PaymentFact.Declined, PaymentFact.Pending, PaymentFact.NoPayment)
        val before = subscription.snapshot()

        for (staleKey in staleKeys) {
            for (fact in facts) {
                val expected = if (fact is PaymentFact.Approved) ReflectResult.StaleApproval else ReflectResult.StaleKey
                assertEquals(expected, subscription.reflect(staleKey, fact), "$staleKey + $fact")
                assertEquals(before, subscription.snapshot(), "$staleKey + $fact")
            }
        }
    }

    @Test
    fun `지난 주기의 승인은 이미 반영된 것이라 어느 상태에서도 아무것도 바꾸지 않고 NoChange다`() {
        // 주기 2 이상인 상태들. 주기가 넘어가려면 이전 주기의 승인이 먼저 반영되어야 하므로 주기 1의 승인은 이미 반영된 것이다.
        val pastCycleStates =
            listOf(
                renewing(),
                renewingWithCancelReservation(),
                pastDue(),
                canceledByRenewalDecline(),
                canceledFromPastDue(),
            )

        for (subscription in pastCycleStates) {
            val before = subscription.snapshot()

            val result = subscription.reflect(key(1), PaymentFact.Approved(anchor))

            assertEquals(ReflectResult.NoChange, result, "${subscription.status}")
            assertEquals(before, subscription.snapshot(), "${subscription.status}")
        }
    }

    @Test
    fun `지난 주기의 키라도 승인이 아니면 StaleKey이고 다른 구독의 키는 승인이어도 StaleApproval이다`() {
        val subscription = renewing()
        val before = subscription.snapshot()

        assertEquals(ReflectResult.StaleKey, subscription.reflect(key(1), PaymentFact.Declined))
        assertEquals(ReflectResult.StaleKey, subscription.reflect(key(1), PaymentFact.Pending))
        assertEquals(ReflectResult.StaleKey, subscription.reflect(key(1), PaymentFact.NoPayment))
        assertEquals(ReflectResult.StaleApproval, subscription.reflect(SubscriptionChargeKey(ID + 1, 1, 1), PaymentFact.Approved(anchor)))
        assertEquals(before, subscription.snapshot())
    }

    @Test
    fun `첫 시도를 열면 C1-A1 키가 송신 중이고 상태는 PENDING이다`() {
        val subscription = pending()

        assertEquals(SubscriptionStatus.PENDING, subscription.status)
        assertEquals("SUB-7-C1-A1", subscription.pendingChargeKey)
        assertEquals(key(1), subscription.currentChargeKey)
    }

    @Test
    fun `첫 시도를 두 번 열 수 없다`() {
        val subscription = pending()

        assertFailsWith<IllegalStateException> { subscription.beginFirstAttempt() }
    }

    @Test
    fun `저장되기 전에는 id가 없어서 첫 시도를 열 수 없다`() {
        assertFailsWith<IllegalArgumentException> { unsavedSubscription().beginFirstAttempt() }
    }

    @Test
    fun `첫 결제가 승인되면 ACTIVE가 되고 앵커와 첫 주기 기간이 정해지고 송신 표시가 지워진다`() {
        val subscription = pending()

        subscription.reflect(key(1), PaymentFact.Approved(anchor))

        assertEquals(SubscriptionStatus.ACTIVE, subscription.status)
        assertEquals(anchor, subscription.anchorAt)
        assertEquals(anchor, subscription.periodStart)
        assertEquals(cycle1End, subscription.periodEnd)
        assertNull(subscription.pendingChargeKey)
        assertEquals(cycle1End, subscription.accessUntil())
    }

    @Test
    fun `첫 결제 승인 전에 해지를 요청했다면 승인된 뒤 ACTIVE이고 해지 예약이 남는다`() {
        val subscription = pendingWithCancelRequest()

        subscription.reflect(key(1), PaymentFact.Approved(anchor))

        assertEquals(SubscriptionStatus.ACTIVE, subscription.status)
        assertTrue(subscription.cancelAtPeriodEnd)
        assertEquals(PeriodEndResult.Canceled, subscription.closePeriod(1, cycle1End))
        assertEquals(CancelReason.USER_CANCELED, subscription.canceledReason)
    }

    @Test
    fun `첫 결제가 거절되면 CANCELED이고 사유는 FIRST_PAYMENT_DECLINED이며 송신 표시가 지워진다`() {
        val subscription = pending()

        subscription.reflect(key(1), PaymentFact.Declined)

        assertEquals(SubscriptionStatus.CANCELED, subscription.status)
        assertEquals(CancelReason.FIRST_PAYMENT_DECLINED, subscription.canceledReason)
        assertNull(subscription.pendingChargeKey)
    }

    @Test
    fun `해지를 요청했어도 첫 결제가 거절되면 사유는 FIRST_PAYMENT_DECLINED다`() {
        val subscription = pendingWithCancelRequest()

        subscription.reflect(key(1), PaymentFact.Declined)

        assertEquals(CancelReason.FIRST_PAYMENT_DECLINED, subscription.canceledReason)
    }

    @Test
    fun `ACTIVE를 해지하면 예약만 기록하고 몇 번을 불러도 같다`() {
        val subscription = active()

        assertEquals(CancelResult.Recorded, subscription.requestCancel())
        assertEquals(CancelResult.Recorded, subscription.requestCancel())

        assertEquals(SubscriptionStatus.ACTIVE, subscription.status)
        assertTrue(subscription.cancelAtPeriodEnd)
        assertNull(subscription.canceledReason)
        assertEquals(cycle1End, subscription.periodEnd)
    }

    @Test
    fun `PENDING을 해지하면 요청만 기록하고 송신 중인 첫 결제는 그대로다`() {
        val subscription = pending()

        assertEquals(CancelResult.Recorded, subscription.requestCancel())

        assertEquals(SubscriptionStatus.PENDING, subscription.status)
        assertTrue(subscription.cancelAtPeriodEnd)
        assertEquals("SUB-7-C1-A1", subscription.pendingChargeKey)
    }

    @Test
    fun `갱신 결제가 송신 중일 때 해지하면 예약만 기록하고 CANCELED가 되지 않는다`() {
        val subscription = renewing()

        assertEquals(CancelResult.Recorded, subscription.requestCancel())

        assertEquals(SubscriptionStatus.ACTIVE, subscription.status)
        assertEquals("SUB-7-C2-A1", subscription.pendingChargeKey)
    }

    @Test
    fun `PAST_DUE를 해지하면 바로 CANCELED가 된다`() {
        val subscription = pastDue()

        assertEquals(CancelResult.Canceled, subscription.requestCancel())

        assertEquals(SubscriptionStatus.CANCELED, subscription.status)
        assertEquals(CancelReason.USER_CANCELED, subscription.canceledReason)
    }

    @Test
    fun `이미 해지된 구독을 다시 해지해도 예외 없이 사유가 바뀌지 않는다`() {
        val subscription = canceledByFirstPaymentDecline()

        assertEquals(CancelResult.AlreadyCanceled, subscription.requestCancel())

        assertEquals(CancelReason.FIRST_PAYMENT_DECLINED, subscription.canceledReason)
    }

    @Test
    fun `송신 중인 구독은 해지 경로가 무엇이든 CANCELED가 되지 않는다`() {
        // 재시도 송신이 없는 지금은 PAST_DUE가 송신 중일 수 없다. 있을 수 없는 상태를 억지로 만들어 가드를 확인한다.
        val subscription = pastDue()
        ReflectionTestUtils.setField(subscription, "pendingChargeKey", "SUB-7-C2-A2")

        assertFailsWith<IllegalStateException> { subscription.requestCancel() }

        assertEquals(SubscriptionStatus.PAST_DUE, subscription.status)
        assertNull(subscription.canceledReason)
    }

    @Test
    fun `기간이 끝났고 해지 예약이 없으면 다음 주기를 시작한다`() {
        val subscription = active()

        val result = subscription.closePeriod(1, cycle1End.plusSeconds(1))

        assertEquals(PeriodEndResult.RenewalStarted(key(2)), result)
        assertEquals(SubscriptionStatus.ACTIVE, subscription.status)
        assertEquals(2, subscription.cycleNo)
        assertEquals(1, subscription.attemptNo)
        assertEquals("SUB-7-C2-A1", subscription.pendingChargeKey)
        // 기간은 갱신 결제가 승인된 뒤에 옮겨 간다.
        assertEquals(cycle1End, subscription.periodEnd)
    }

    @Test
    fun `기간이 끝나는 정각에는 갱신을 시작하고 1초 전에는 시작하지 않는다`() {
        val early = active()
        assertEquals(PeriodEndResult.AlreadyRenewed, early.closePeriod(1, cycle1End.minusSeconds(1)))
        assertEquals(1, early.cycleNo)
        assertNull(early.pendingChargeKey)

        val onTime = active()
        assertIs<PeriodEndResult.RenewalStarted>(onTime.closePeriod(1, cycle1End))
    }

    @Test
    fun `이미 끝난 갱신은 새 기간도 지난 뒤에 낡은 주기 번호로 불려도 다시 시작하지 않는다`() {
        val subscription = renewing()
        subscription.reflect(key(2), PaymentFact.Approved(cycle1End))

        // 기간(주기 2)도 이미 끝난 시각이다. 기대 주기가 낡았다는 검사만이 막을 수 있다.
        val result = subscription.closePeriod(1, cycle2End.plusSeconds(1))

        assertEquals(PeriodEndResult.AlreadyRenewed, result)
        assertEquals(2, subscription.cycleNo)
        assertNull(subscription.pendingChargeKey)
    }

    @Test
    fun `갱신 결제가 송신 중이면 같은 주기로 다시 불려도 시작하지 않는다`() {
        val subscription = renewing()

        assertEquals(PeriodEndResult.AlreadyStarted, subscription.closePeriod(2, cycle2End))
        assertEquals(PeriodEndResult.AlreadyRenewed, subscription.closePeriod(1, cycle2End))

        assertEquals(2, subscription.cycleNo)
        assertEquals("SUB-7-C2-A1", subscription.pendingChargeKey)
    }

    @Test
    fun `해지 예약이 있고 기간이 끝나면 CANCELED가 되고 새 주기를 시작하지 않는다`() {
        val subscription = active()
        subscription.requestCancel()

        val result = subscription.closePeriod(1, cycle1End)

        assertEquals(PeriodEndResult.Canceled, result)
        assertEquals(SubscriptionStatus.CANCELED, subscription.status)
        assertEquals(CancelReason.USER_CANCELED, subscription.canceledReason)
        assertEquals(1, subscription.cycleNo)
        assertNull(subscription.pendingChargeKey)
    }

    @Test
    fun `해지 예약이 있어도 기간이 끝나기 전에는 ACTIVE로 남는다`() {
        val subscription = active()
        subscription.requestCancel()

        assertEquals(PeriodEndResult.AlreadyRenewed, subscription.closePeriod(1, cycle1End.minusSeconds(1)))

        assertEquals(SubscriptionStatus.ACTIVE, subscription.status)
    }

    @Test
    fun `갱신 결제가 송신 중이면 해지 예약이 있어도 기간 종료가 CANCELED로 만들지 않는다`() {
        val subscription = renewingWithCancelReservation()

        val result = subscription.closePeriod(2, cycle2End)

        assertEquals(PeriodEndResult.AlreadyStarted, result)
        assertEquals(SubscriptionStatus.ACTIVE, subscription.status)
        assertEquals("SUB-7-C2-A1", subscription.pendingChargeKey)
    }

    @Test
    fun `ACTIVE가 아닌 구독은 기간을 마감할 수 없다`() {
        for (subscription in listOf(pending(), pastDue(), canceledAtPeriodEnd())) {
            val status = subscription.status

            assertFailsWith<IllegalStateException>("$status") { subscription.closePeriod(subscription.cycleNo, cycle2End.plusSeconds(1)) }

            assertEquals(status, subscription.status)
        }
    }

    @Test
    fun `갱신이 승인되면 기간이 앵커 기준 다음 주기로 옮겨 가고 송신 표시가 지워진다`() {
        val subscription = renewing()

        subscription.reflect(key(2), PaymentFact.Approved(cycle1End))

        assertEquals(SubscriptionStatus.ACTIVE, subscription.status)
        assertEquals(cycle1End, subscription.periodStart)
        assertEquals(cycle2End, subscription.periodEnd)
        assertEquals(anchor, subscription.anchorAt)
        assertNull(subscription.pendingChargeKey)
    }

    @Test
    fun `갱신이 승인돼도 해지 예약은 남아서 새 기간 끝에 CANCELED가 된다`() {
        val subscription = renewingWithCancelReservation()

        subscription.reflect(key(2), PaymentFact.Approved(cycle1End))

        assertTrue(subscription.cancelAtPeriodEnd)
        assertEquals(PeriodEndResult.Canceled, subscription.closePeriod(2, cycle2End))
    }

    @Test
    fun `갱신이 거절되면 PAST_DUE가 되고 첫 재시도 시각이 정해지고 기간은 그대로다`() {
        val subscription = renewing()

        subscription.reflect(key(2), PaymentFact.Declined)

        assertEquals(SubscriptionStatus.PAST_DUE, subscription.status)
        assertEquals(0, subscription.retryCount)
        assertEquals(cycle1End.plus(Duration.ofDays(1)), subscription.nextRetryAt)
        assertEquals(cycle1End, subscription.periodEnd)
        assertNull(subscription.pendingChargeKey)
    }

    @Test
    fun `해지 예약이 있는 구독의 갱신이 거절되면 PAST_DUE를 거치지 않고 CANCELED가 된다`() {
        val subscription = renewingWithCancelReservation()

        subscription.reflect(key(2), PaymentFact.Declined)

        assertEquals(SubscriptionStatus.CANCELED, subscription.status)
        assertEquals(CancelReason.USER_CANCELED, subscription.canceledReason)
        assertNull(subscription.nextRetryAt)
        assertNull(subscription.pendingChargeKey)
    }

    @Test
    fun `이용 기한은 상태에 따라 계산된다`() {
        assertNull(pending().accessUntil())
        assertEquals(cycle1End, active().accessUntil())
        assertEquals(cycle1End.plus(Duration.ofHours(24)), renewing().accessUntil())
        assertEquals(cycle1End.plus(Duration.ofDays(7)), pastDue().accessUntil())
        assertNull(canceledAtPeriodEnd().accessUntil())
    }
}
