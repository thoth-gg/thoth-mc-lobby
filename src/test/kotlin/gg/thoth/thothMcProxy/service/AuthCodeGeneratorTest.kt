package gg.thoth.thothMcProxy.service

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthCodeGeneratorTest {
    @Test
    fun `generate returns uppercase alphanumeric code with configured length`() {
        val random = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(42L) }
        val generator = AuthCodeGenerator(random)

        val code = generator.generate(6)

        assertEquals(6, code.length)
        assertTrue(code.matches(Regex("[A-Z0-9]{6}")))
    }
}
