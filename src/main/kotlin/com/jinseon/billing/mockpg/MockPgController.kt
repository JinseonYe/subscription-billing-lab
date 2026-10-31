package com.jinseon.billing.mockpg

import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// 토스페이먼츠 API의 경로와 응답 모양을 흉내 낸다. mock-pg 프로필에서만 뜬다.
@RestController
@Profile("mock-pg")
@RequestMapping("/mock-pg")
class MockPgController(
    private val ledger: MockPgLedger,
    private val properties: MockPgProperties,
) {
    // 자동결제 승인. billingKey는 쓰지 않고 로그에도 남기지 않는다.
    @PostMapping("/v1/billing/{billingKey}")
    fun approve(
        @PathVariable billingKey: String,
        @RequestBody request: MockApproveRequest,
    ): ResponseEntity<Map<String, Any?>> {
        val result = ledger.approve(request)
        if (result.delayed) {
            sleep()
        }
        return result.response.toEntity()
    }

    // orderId로 결제 조회
    @GetMapping("/v1/payments/orders/{orderId}")
    fun lookup(
        @PathVariable orderId: String,
    ): ResponseEntity<Map<String, Any?>> = ledger.lookup(orderId).toEntity()

    // 아래는 수동 확인(curl)용이다. 테스트는 MockPgLedger를 직접 부른다.
    @PutMapping("/admin/scenarios/{customerKey}/{scenario}")
    fun assignScenario(
        @PathVariable customerKey: String,
        @PathVariable scenario: MockScenario,
    ) {
        ledger.assignScenario(customerKey, scenario)
    }

    @PostMapping("/admin/orders/{orderId}/complete")
    fun completeInProgress(
        @PathVariable orderId: String,
    ) {
        ledger.completeInProgress(orderId)
    }

    @PostMapping("/admin/reset")
    fun reset() {
        ledger.reset()
    }

    private fun sleep() {
        try {
            Thread.sleep(properties.delay)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun MockPgResponse.toEntity(): ResponseEntity<Map<String, Any?>> = ResponseEntity.status(status).body(body)
}
