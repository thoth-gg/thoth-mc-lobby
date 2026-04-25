package gg.thoth.thothMcProxy.model

data class LoginDecision(
    val allowed: Boolean,
    val message: String? = null,
) {
    companion object {
        fun allow(): LoginDecision = LoginDecision(allowed = true)

        fun deny(message: String): LoginDecision = LoginDecision(
            allowed = false,
            message = message,
        )
    }
}
