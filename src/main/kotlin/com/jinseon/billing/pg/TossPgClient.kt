package com.jinseon.billing.pg

import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

private val log = LoggerFactory.getLogger(TossPgClient::class.java)

private data class RawResponse(
    val httpStatus: Int,
    val body: String,
)

// 토스페이먼츠 호출. 요청을 보내고 응답을 TossResponseMapper에 넘기는 일만 한다.
// baseUrl은 호출할 때마다 읽는다. mock-pg 프로필에서는 서버가 뜬 뒤에야 포트를 알 수 있기 때문이다.
internal class TossPgClient(
    private val restClient: RestClient,
    private val secretKey: String,
    private val baseUrl: () -> String,
) : PgClient {
    override fun approve(request: PgApproveRequest): PgResult {
        checkSecretKey()
        val body =
            mapOf(
                "customerKey" to request.customerKey,
                "amount" to request.amount,
                "orderId" to request.orderId,
                "orderName" to request.orderName,
            )
        return call("approve", request.orderId) {
            val raw =
                restClient
                    .post()
                    .uri("${baseUrl()}/v1/billing/{billingKey}", request.billingKey)
                    .headers { headers ->
                        headers.setBasicAuth(secretKey, "")
                        // 재전송 때도 같은 값을 보낸다. 결제사가 같은 요청으로 알아본다.
                        headers.set("Idempotency-Key", request.orderId)
                    }.contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange { _, response -> RawResponse(response.statusCode.value(), response.body.readAllBytes().decodeToString()) }
            raw.httpStatus to TossResponseMapper.mapApprove(raw.httpStatus, raw.body)
        }
    }

    override fun lookup(orderId: String): PgResult {
        checkSecretKey()
        return call("lookup", orderId) {
            val raw =
                restClient
                    .get()
                    .uri("${baseUrl()}/v1/payments/orders/{orderId}", orderId)
                    .headers { headers -> headers.setBasicAuth(secretKey, "") }
                    .exchange { _, response -> RawResponse(response.statusCode.value(), response.body.readAllBytes().decodeToString()) }
            raw.httpStatus to TossResponseMapper.mapLookup(raw.httpStatus, raw.body)
        }
    }

    private fun call(
        operation: String,
        orderId: String,
        send: () -> Pair<Int, PgResult>,
    ): PgResult =
        try {
            val (httpStatus, result) = send()
            log.info("PG_CALL operation={} orderId={} http={} result={}", operation, orderId, httpStatus, result.javaClass.simpleName)
            result
        } catch (e: RestClientException) {
            // 타임아웃과 연결 오류는 실패가 아니라 Unknown이다. 결제사가 요청을 처리했을 수도 있다.
            // 예외 메시지에는 요청 URL(빌링키 포함)이 들어 있어서, 클래스 이름만 남긴다.
            val cause = e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName
            log.warn("PG_CALL operation={} orderId={} transportError={}", operation, orderId, cause)
            PgResult.Unknown("통신 오류: $cause")
        }

    private fun checkSecretKey() {
        check(secretKey.isNotBlank()) { "pg.toss.secret-key가 설정되지 않았습니다. TOSS_SECRET_KEY 환경변수를 확인하세요." }
    }
}
