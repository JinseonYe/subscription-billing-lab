package com.jinseon.billing.pg

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

// base-url은 여기에 없다. TossPgClient가 호출할 때마다 Environment에서 직접 읽는다(PgConfig 참고).
@ConfigurationProperties(prefix = "pg.toss")
data class PgProperties(
    val secretKey: String = "",
    val connectTimeout: Duration = Duration.ofSeconds(3),
    // 토스 문서: "자동결제 승인은 최대 60초가 소요됩니다. 타임아웃 값을 최소 60초로 설정하세요."
    val readTimeout: Duration = Duration.ofSeconds(65),
) {
    // 비밀키가 로그에 남지 않게 한다.
    override fun toString(): String = "PgProperties(connectTimeout=$connectTimeout, readTimeout=$readTimeout)"
}
