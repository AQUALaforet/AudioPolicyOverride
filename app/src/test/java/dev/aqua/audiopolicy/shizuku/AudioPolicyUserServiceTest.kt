package dev.aqua.audiopolicy.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AudioPolicyUserServiceTest {
    @Test fun unsupportedConfigIsRejectedBeforeReflection() {
        for (config in listOf(-1, 1, 4, 10, 12, Int.MAX_VALUE)) {
            val exception = assertThrows(IllegalArgumentException::class.java) { AudioPolicyUserService.validateConfig(config) }
            assertEquals("Only FORCE_NONE and FORCE_SYSTEM_ENFORCED are allowed", exception.message)
        }
        AudioPolicyUserService.validateConfig(0)
        AudioPolicyUserService.validateConfig(11)
    }
}
