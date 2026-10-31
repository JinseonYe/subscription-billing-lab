package com.jinseon.billing.pg

import org.slf4j.LoggerFactory
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

private val log = LoggerFactory.getLogger(TossResponseMapper::class.java)

// 토스 HTTP 응답(상태 코드 + 본문)을 PgResult로 바꾸는 순수 함수 모음.
// 원칙: 확실히 알 수 있는 경우만 Approved, Declined, NotFound로 보내고, 나머지는 모두 Unknown이다.
internal object TossResponseMapper {
    // 카드사가 결제를 거절했다고 메시지에 직접 나오는 코드만 허용한다(허용 목록).
    // 출처: https://docs.tosspayments.com/reference/error-codes#카드-자동결제-승인 (2026-10-29 확인)
    //  - REJECT_CARD_PAYMENT: "한도초과 혹은 잔액부족으로 결제에 실패했습니다."
    //  - REJECT_CARD_COMPANY: "결제 승인이 거절되었습니다."
    //  - INVALID_REJECT_CARD: "카드 사용이 거절되었습니다. 카드사 문의가 필요합니다."
    // 문서가 "청구되지 않았다"고 보장하는 코드는 없다. 위 코드는 메시지 문구로 판단한 것이다.
    // 목록에 없는 코드(정지된 카드, 카드 정보 오류 등)는 Unknown으로 남는다. 늘릴 때는 출처를 함께 적는다.
    private val declineCodes = setOf("REJECT_CARD_PAYMENT", "REJECT_CARD_COMPANY", "INVALID_REJECT_CARD")

    // 출처: https://docs.tosspayments.com/reference/error-codes#결제-조회
    private val notFoundCodes = setOf("NOT_FOUND_PAYMENT", "NOT_FOUND")

    private val jsonMapper = JsonMapper.builder().build()

    fun mapApprove(
        httpStatus: Int,
        body: String,
    ): PgResult =
        when (httpStatus) {
            200 -> mapApproveSuccess(body)
            in 400..499 -> mapApproveClientError(httpStatus, body)
            else -> PgResult.Unknown("승인 응답 HTTP $httpStatus")
        }

    fun mapLookup(
        httpStatus: Int,
        body: String,
    ): PgResult =
        when (httpStatus) {
            200 -> mapLookupSuccess(body)
            404 -> mapLookupNotFound(body)
            else -> PgResult.Unknown("조회 응답 HTTP $httpStatus")
        }

    private fun mapApproveSuccess(body: String): PgResult {
        val node = parse(body) ?: return PgResult.Unknown("승인 응답 본문을 해석할 수 없음")
        val status = node.text("status")
        return if (status == "DONE") {
            approvedOrUnknown(node)
        } else {
            PgResult.Unknown("승인 응답의 status가 DONE이 아님: $status")
        }
    }

    private fun mapApproveClientError(
        httpStatus: Int,
        body: String,
    ): PgResult {
        val error = parse(body) ?: return PgResult.Unknown("승인 응답 HTTP $httpStatus, 본문을 해석할 수 없음")
        val code = error.text("code")
        return if (code != null && code in declineCodes) {
            PgResult.Declined(code, error.text("message") ?: code)
        } else {
            PgResult.Unknown("승인 응답 HTTP $httpStatus code=$code")
        }
    }

    private fun mapLookupSuccess(body: String): PgResult {
        val node = parse(body) ?: return PgResult.Unknown("조회 응답 본문을 해석할 수 없음")
        return when (val status = node.text("status")) {
            "DONE" -> {
                approvedOrUnknown(node)
            }

            // 승인된 뒤 취소된 결제다. 돈이 오갔다는 사실은 그대로이므로 승인으로 기록하고, 정산 대사 대상으로 남긴다.
            "CANCELED", "PARTIAL_CANCELED" -> {
                val result = approvedOrUnknown(node)
                if (result is PgResult.Approved) {
                    log.error("PAYMENT_CONFLICT 결제사에서 취소된 결제 orderId={} status={}", node.text("orderId"), status)
                }
                result
            }

            "ABORTED", "EXPIRED" -> {
                PgResult.Declined(status, node.get("failure")?.text("message") ?: "결제 상태: $status")
            }

            "READY", "IN_PROGRESS" -> {
                PgResult.Unknown("조회 결과 아직 처리 중: $status")
            }

            // 자동결제에서는 나오지 않아야 하는 값이거나 처음 보는 값이다. 단정하지 않고 확인이 필요하다고 남긴다.
            else -> {
                log.error("PG_UNEXPECTED_STATUS 조회 응답의 status를 알 수 없음 orderId={} status={}", node.text("orderId"), status)
                PgResult.Unknown("조회 응답의 status를 알 수 없음: $status")
            }
        }
    }

    private fun mapLookupNotFound(body: String): PgResult {
        // 게이트웨이의 HTML 404 같은 것을 "결제 없음"으로 오해하지 않도록, 토스 에러 코드가 확인될 때만 NotFound다.
        val error = parse(body) ?: return PgResult.Unknown("조회 응답 404, 본문을 해석할 수 없음")
        val code = error.text("code")
        return if (code != null && code in notFoundCodes) {
            PgResult.NotFound
        } else {
            PgResult.Unknown("조회 응답 404 code=$code")
        }
    }

    private fun approvedOrUnknown(node: JsonNode): PgResult {
        val paymentKey = node.text("paymentKey")
        val approvedAt = node.text("approvedAt")?.let { parseInstant(it) }
        return if (paymentKey != null && approvedAt != null) {
            PgResult.Approved(paymentKey, approvedAt)
        } else {
            PgResult.Unknown("응답에 paymentKey 또는 approvedAt이 없음")
        }
    }

    private fun parse(body: String): JsonNode? =
        try {
            jsonMapper.readTree(body)
        } catch (e: JacksonException) {
            null
        }

    // 토스 approvedAt 형식: 2022-01-01T00:00:00+09:00
    private fun parseInstant(value: String): Instant? =
        try {
            OffsetDateTime.parse(value).toInstant()
        } catch (e: DateTimeParseException) {
            null
        }

    private fun JsonNode.text(name: String): String? = get(name)?.takeIf { it.isString }?.stringValue()
}
