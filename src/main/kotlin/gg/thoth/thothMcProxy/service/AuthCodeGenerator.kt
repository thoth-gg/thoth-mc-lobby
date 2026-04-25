package gg.thoth.thothMcProxy.service

import java.security.SecureRandom

class AuthCodeGenerator(
    private val random: SecureRandom = SecureRandom(),
) {
    fun generate(length: Int): String {
        require(length > 0) { "Code length must be positive" }
        return buildString(length) {
            repeat(length) {
                append(ALPHABET[random.nextInt(ALPHABET.length)])
            }
        }
    }

    private companion object {
        private const val ALPHABET = "ABCDEFGHJKMNPQRSTWXYZ23456789"
    }
}
