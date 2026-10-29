package com.jinseon.billing.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

// 테스트가 시각을 직접 정하고 앞으로 돌릴 수 있는 Clock.
class MutableClock(
    start: Instant,
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    @Volatile
    private var current: Instant = start

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)

    override fun instant(): Instant = current

    fun set(instant: Instant) {
        current = instant
    }

    fun advance(duration: Duration) {
        current = current.plus(duration)
    }
}

@TestConfiguration(proxyBeanMethods = false)
class MutableClockConfiguration {
    // 운영용 Clock 빈이 있어도 @Primary라서 이 Clock이 주입된다.
    @Bean
    @Primary
    fun mutableClock(): MutableClock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
}
