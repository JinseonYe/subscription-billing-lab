package com.jinseon.billing.pg

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.RequestMatcher
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withException
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.time.Instant
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class TossPgClientTest {
    private val baseUrl = "https://api.example.test"
    private val request = PgApproveRequest("order-1", "BK-SECRET-123", "customer-1", 9_900L, "월 구독")

    private lateinit var server: MockRestServiceServer
    private lateinit var client: PgClient

    @BeforeEach
    fun setUp() {
        val builder = RestClient.builder()
        server = MockRestServiceServer.bindTo(builder).build()
        client = TossPgClient(builder.build(), "test_sk_dummy") { baseUrl }
    }

    private val doneJson = """{"status":"DONE","paymentKey":"pk_abc","orderId":"order-1","approvedAt":"2026-01-01T09:00:03+09:00"}"""

    @Test
    fun `승인 요청은 올바른 주소와 인증 헤더와 멱등키와 본문으로 나간다`() {
        server
            .expect(requestTo("$baseUrl/v1/billing/BK-SECRET-123"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString("test_sk_dummy:".toByteArray())))
            .andExpect(header("Idempotency-Key", "order-1"))
            .andExpect(
                RequestMatcher { req ->
                    val json = JsonMapper.builder().build().readTree((req as MockClientHttpRequest).bodyAsString)
                    assertEquals("customer-1", json.get("customerKey").stringValue())
                    assertEquals(9_900L, json.get("amount").asLong())
                    assertEquals("order-1", json.get("orderId").stringValue())
                    assertEquals("월 구독", json.get("orderName").stringValue())
                },
            ).andRespond(withSuccess(doneJson, MediaType.APPLICATION_JSON))

        val result = client.approve(request)

        assertEquals(PgResult.Approved("pk_abc", Instant.parse("2026-01-01T00:00:03Z")), result)
        server.verify()
    }

    @Test
    fun `조회 요청은 주문번호로 GET을 보내고 멱등키는 보내지 않는다`() {
        server
            .expect(requestTo("$baseUrl/v1/payments/orders/order-1"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(headerDoesNotExist("Idempotency-Key"))
            .andRespond(
                withStatus(
                    HttpStatus.NOT_FOUND,
                ).contentType(MediaType.APPLICATION_JSON).body("""{"code":"NOT_FOUND_PAYMENT","message":"없음"}"""),
            )

        assertEquals(PgResult.NotFound, client.lookup("order-1"))
        server.verify()
    }

    @Test
    fun `카드 거절 응답은 Declined로 바뀐다`() {
        server
            .expect(requestTo("$baseUrl/v1/billing/BK-SECRET-123"))
            .andRespond(
                withStatus(
                    HttpStatus.FORBIDDEN,
                ).contentType(MediaType.APPLICATION_JSON).body("""{"code":"REJECT_CARD_PAYMENT","message":"한도초과"}"""),
            )

        assertEquals(PgResult.Declined("REJECT_CARD_PAYMENT", "한도초과"), client.approve(request))
    }

    @Test
    fun `서버 오류는 실패가 아니라 Unknown이다`() {
        server.expect(requestTo("$baseUrl/v1/billing/BK-SECRET-123")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))

        assertIs<PgResult.Unknown>(client.approve(request))
    }

    @Test
    fun `통신 오류는 Unknown이고 사유에 빌링키가 들어가지 않는다`() {
        server.expect(requestTo("$baseUrl/v1/billing/BK-SECRET-123")).andRespond(withException(IOException("connection reset")))

        val result = assertIs<PgResult.Unknown>(client.approve(request))

        assertFalse(result.reason.contains("BK-SECRET-123"), "사유에 빌링키가 노출됨: ${result.reason}")
    }

    @Test
    fun `비밀키가 비어 있으면 요청을 보내지 않고 실패한다`() {
        val builder = RestClient.builder()
        val strictServer = MockRestServiceServer.bindTo(builder).build()
        val noKeyClient = TossPgClient(builder.build(), "") { baseUrl }

        assertFailsWith<IllegalStateException> { noKeyClient.approve(request) }
        assertFailsWith<IllegalStateException> { noKeyClient.lookup("order-1") }
        strictServer.verify()
    }

    @Test
    fun `요청과 설정 객체를 문자열로 찍어도 빌링키와 비밀키가 나오지 않는다`() {
        assertFalse(request.toString().contains("BK-SECRET-123"))
        assertFalse(PgProperties(secretKey = "test_sk_dummy").toString().contains("test_sk_dummy"))
    }
}
