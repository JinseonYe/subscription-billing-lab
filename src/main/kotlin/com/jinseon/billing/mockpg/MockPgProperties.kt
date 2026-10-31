package com.jinseon.billing.mockpg

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "mock-pg")
data class MockPgProperties(
    // 타임아웃 시나리오에서 응답을 늦추는 시간. 호출자의 read-timeout(65초)보다 길어야 한다.
    val delay: Duration = Duration.ofSeconds(70),
)
