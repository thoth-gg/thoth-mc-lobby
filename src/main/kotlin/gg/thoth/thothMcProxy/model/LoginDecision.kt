package gg.thoth.thothMcProxy.model

data class LoginDecision(
    val allowed: Boolean,
    val message: String? = null,
    val denialSeverity: LoginDenialSeverity = LoginDenialSeverity.ERROR,
    val highlightedText: String? = null,
) {
    companion object {
        fun allow(): LoginDecision = LoginDecision(allowed = true)

        fun deny(
            message: String,
            denialSeverity: LoginDenialSeverity = LoginDenialSeverity.ERROR,
            highlightedText: String? = null,
        ): LoginDecision = LoginDecision(
            allowed = false,
            message = message,
            denialSeverity = denialSeverity,
            highlightedText = highlightedText,
        )
    }
}

enum class LoginDenialSeverity {
    ACTION_REQUIRED,
    ERROR,
}
