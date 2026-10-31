package com.jinseon.billing.pg

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient

@Configuration
@EnableConfigurationProperties(PgProperties::class)
class PgConfig {
    @Bean
    fun pgClient(
        properties: PgProperties,
        environment: Environment,
    ): PgClient {
        val httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout).build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient)
        requestFactory.setReadTimeout(properties.readTimeout)
        val restClient = RestClient.builder().requestFactory(requestFactory).build()
        // base-url을 호출 시점에 읽어서, ${local.server.port}처럼 서버가 뜬 뒤에야 정해지는 값도 쓸 수 있다.
        return TossPgClient(restClient, properties.secretKey) { environment.getRequiredProperty("pg.toss.base-url") }
    }
}
