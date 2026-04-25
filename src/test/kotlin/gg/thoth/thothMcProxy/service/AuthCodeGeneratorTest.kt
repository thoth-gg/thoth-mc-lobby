package gg.thoth.thothMcProxy.service

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthCodeGeneratorTest {
    @Test
    fun `generate returns code with configured length from unambiguous characters`() {
        val random = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(42L) }
        val generator = AuthCodeGenerator(random)

        val code = generator.generate(6)

        assertEquals(6, code.length)
        assertTrue(code.matches(Regex("[ABCDEFGHJKMNPQRSTWXYZ23456789]{6}")))
        assertTrue(code.none { it in "01ILOUV" })
    }

    @Test
    fun `generate uses only unambiguous alphabet`() {
        val random = SequentialSecureRandom()
        val generator = AuthCodeGenerator(random)

        val code = generator.generate(29)

        assertEquals("ABCDEFGHJKMNPQRSTWXYZ23456789", code)
    }

    private class SequentialSecureRandom : SecureRandom() {
        private var next = 0

        override fun nextInt(bound: Int): Int {
            return next++ % bound
        }
    }
}
