package gg.thoth.thothMcProxy.service

import java.security.SecureRandom

class AuthCodeGenerator(
    private val random: SecureRandom = SecureRandom(),
) {
    fun generate(length: Int): String {
        require(length > 0) { "Code length must be positive" }
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return buildString(length) {
            repeat(length) {
                append(alphabet[random.nextInt(alphabet.length)])
            }
        }
    }
}
