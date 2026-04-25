package gg.thoth.thothMcProxy.model

data class LoginDecision(
    val allowed: Boolean,
    val message: String? = null,
    val denialSeverity: LoginDenialSeverity = LoginDenialSeverity.ERROR,
) {
    companion object {
        fun allow(): LoginDecision = LoginDecision(allowed = true)

        fun deny(
            message: String,
            denialSeverity: LoginDenialSeverity = LoginDenialSeverity.ERROR,
        ): LoginDecision = LoginDecision(
            allowed = false,
            message = message,
            denialSeverity = denialSeverity,
        )
    }
}

enum class LoginDenialSeverity {
    ACTION_REQUIRED,
    ERROR,
}
