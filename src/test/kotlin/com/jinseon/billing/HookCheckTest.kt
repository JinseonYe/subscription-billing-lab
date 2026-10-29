package com.jinseon.billing

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class HookCheckTest {
    @Test
    fun `Stop 훅이 실패한 테스트를 잡는지 확인한다`() {
        assertEquals(1, 2)
    }
}
