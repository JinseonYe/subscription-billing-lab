package com.jinseon.billing.common

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class ClockConfig {
    // 현재 시각은 이 Clock을 주입받아서만 얻는다. 테스트에서는 고정 Clock으로 바꿔 끼운다.
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
