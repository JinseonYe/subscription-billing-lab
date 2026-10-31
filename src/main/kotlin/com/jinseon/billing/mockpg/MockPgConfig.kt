package com.jinseon.billing.mockpg

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Configuration
@Profile("mock-pg")
@EnableConfigurationProperties(MockPgProperties::class)
class MockPgConfig
