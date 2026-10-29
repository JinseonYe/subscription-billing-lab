package com.jinseon.billing.pg

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TossResponseMapperTest {
    private fun payment(
        status: String,
        extra: String = ""","paymentKey":"pk_abc","approvedAt":"2026-01-01T09:00:03+09:00"""",
    ) = """{"status":"$status","orderId":"order-1","totalAmount":9900$extra}"""

    private fun error(
        code: String,
        message: String = "메시지",
    ) = """{"code":"$code","message":"$message"}"""

    // ---- 승인 응답 ----

    @Test
    fun `승인 200 DONE이면 Approved이고 승인 시각은 UTC 기준 Instant로 바뀐다`() {
        val result = TossResponseMapper.mapApprove(200, payment("DONE"))

        assertEquals(PgResult.Approved("pk_abc", Instant.parse("2026-01-01T00:00:03Z")), result)
    }

    @Test
    fun `승인 200이어도 status가 DONE이 아니면 Unknown이다`() {
        assertIs<PgResult.Unknown>(TossResponseMapper.mapApprove(200, payment("IN_PROGRESS")))
        assertIs<PgResult.Unknown>(TossResponseMapper.mapApprove(200, payment("ABORTED")))
    }

    @Test
    fun `승인 200 DONE이어도 paymentKey나 승인 시각이 없거나 형식이 틀리면 Unknown이다`() {
        assertIs<PgResult.Unknown>(TossResponseMapper.mapApprove(200, payment("DONE", extra = "")))
        assertIs<PgResult.Unknown>(TossResponseMapper.mapApprove(200, payment("DONE", extra = ""","paymentKey":"pk_abc"""")))
        assertIs<PgResult.Unknown>(
            TossResponseMapper.mapApprove(200, payment("DONE", extra = ""","paymentKey":"pk_abc","approvedAt":"어제"""")),
        )
    }

    @Test
    fun `승인 200인데 본문이 JSON이 아니면 Unknown이다`() {
        assertIs<PgResult.Unknown>(TossResponseMapper.mapApprove(200, "<html>bad gateway</html>"))
        assertIs<PgResult.Unknown>(TossResponseMapper.mapApprove(200, ""))
    }

    @ParameterizedTest(name = "{0}은 카드사 거절이라 Declined이다")
    @CsvSource("REJECT_CARD_PAYMENT", "REJECT_CARD_COMPANY", "INVALID_REJECT_CARD")
    fun `허용 목록의 거절 코드는 Declined이다`(code: String) {
        val result = TossResponseMapper.mapApprove(403, error(code, "카드 거절"))

        assertEquals(PgResult.Declined(code, "카드 거절"), result)
    }

    @ParameterizedTest(name = "HTTP {0} {1}은 거절로 단정할 수 없어 Unknown이다")
    @CsvSource(
        "400,DUPLICATED_ORDER_ID",
        "409,IDEMPOTENT_REQUEST_PROCESSING",
        "400,ALREADY_PROCESSED_PAYMENT",
        "400,PROVIDER_ERROR",
        "401,UNAUTHORIZED_KEY",
        "403,INCORRECT_BASIC_AUTH_FORMAT",
        "400,INVALID_STOPPED_CARD",
        "400,INVALID_CARD_NUMBER",
        "429,TOO_MANY_REQUESTS",
        "400,처음_보는_코드",
    )
    fun `허용 목록에 없는 4xx는 Unknown이다`(
        status: Int,
        code: String,
    ) {
        assertIs<PgResult.Unknown>(TossResponseMapper.mapApprove(status, error(code)))
    }

    @ParameterizedTest(name = "HTTP {0}은 Unknown이다")
    @CsvSource("500", "502", "503", "504", "302")
    fun `5xx와 그 밖의 상태 코드는 Unknown이다`(status: Int) {
        assertIs<PgResult.Unknown>(TossResponseMapper.mapApprove(status, error("FAILED_INTERNAL_SYSTEM_PROCESSING")))
    }

    @Test
    fun `4xx인데 본문이 토스 에러 형식이 아니면 Unknown이다`() {
        assertIs<PgResult.Unknown>(TossResponseMapper.mapApprove(403, "<html>forbidden</html>"))
        assertIs<PgResult.Unknown>(TossResponseMapper.mapApprove(400, "{}"))
    }

    // ---- 조회 응답 ----

    @Test
    fun `조회 200 DONE이면 Approved이다`() {
        val result = TossResponseMapper.mapLookup(200, payment("DONE"))

        assertEquals(PgResult.Approved("pk_abc", Instant.parse("2026-01-01T00:00:03Z")), result)
    }

    @ParameterizedTest(name = "조회 status {0}은 승인된 뒤 취소된 것이라 Approved이다")
    @CsvSource("CANCELED", "PARTIAL_CANCELED")
    fun `취소된 결제는 승인으로 본다`(status: String) {
        assertIs<PgResult.Approved>(TossResponseMapper.mapLookup(200, payment(status)))
    }

    @ParameterizedTest(name = "조회 status {0}은 Declined이다")
    @CsvSource("ABORTED", "EXPIRED")
    fun `승인 실패 상태는 Declined이다`(status: String) {
        val result = TossResponseMapper.mapLookup(200, payment(status, extra = ""))

        assertEquals(PgResult.Declined(status, "결제 상태: $status"), result)
    }

    @Test
    fun `조회 결과에 failure 메시지가 있으면 Declined 사유로 쓴다`() {
        val body = payment("ABORTED", extra = ""","failure":{"code":"REJECT_CARD_PAYMENT","message":"한도초과"}""")

        assertEquals(PgResult.Declined("ABORTED", "한도초과"), TossResponseMapper.mapLookup(200, body))
    }

    @ParameterizedTest(name = "조회 status {0}은 아직 모르니 Unknown이다")
    @CsvSource("READY", "IN_PROGRESS", "WAITING_FOR_DEPOSIT", "처음_보는_상태")
    fun `진행 중이거나 처음 보는 상태는 Unknown이다`(status: String) {
        assertIs<PgResult.Unknown>(TossResponseMapper.mapLookup(200, payment(status)))
    }

    @Test
    fun `조회 200 DONE이어도 필수 값이 없거나 본문이 깨졌으면 Unknown이다`() {
        assertIs<PgResult.Unknown>(TossResponseMapper.mapLookup(200, payment("DONE", extra = "")))
        assertIs<PgResult.Unknown>(TossResponseMapper.mapLookup(200, "not json"))
    }

    @ParameterizedTest(name = "조회 404 {0}은 NotFound이다")
    @CsvSource("NOT_FOUND_PAYMENT", "NOT_FOUND")
    fun `조회 404 결제 없음 코드는 NotFound이다`(code: String) {
        assertEquals(PgResult.NotFound, TossResponseMapper.mapLookup(404, error(code)))
    }

    @Test
    fun `조회 404라도 토스 에러 형식이 아니거나 다른 코드면 NotFound로 단정하지 않는다`() {
        assertIs<PgResult.Unknown>(TossResponseMapper.mapLookup(404, "<html>not found</html>"))
        assertIs<PgResult.Unknown>(TossResponseMapper.mapLookup(404, error("NOT_FOUND_PAYMENT_SESSION")))
    }

    @ParameterizedTest(name = "조회 HTTP {0}은 Unknown이다")
    @CsvSource("401", "403", "429", "500", "503")
    fun `조회가 다른 상태 코드로 실패하면 Unknown이다`(status: Int) {
        assertIs<PgResult.Unknown>(TossResponseMapper.mapLookup(status, error("FORBIDDEN_CONSECUTIVE_REQUEST")))
    }
}
