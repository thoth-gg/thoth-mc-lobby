package gg.thoth.thothMcProxy.model

data class AuthCompletionResult(
    val status: AuthCompletionStatus,
    val account: MinecraftAccountRecord? = null,
    val identity: DiscordIdentityRecord? = null,
)

enum class AuthCompletionStatus {
    SUCCESS,
    CODE_NOT_FOUND,
    ACCOUNT_ALREADY_AUTHENTICATED,
    PRIMARY_JAVA_ALREADY_EXISTS,
    PRIMARY_BEDROCK_ALREADY_EXISTS,
    LINKED_JAVA_MISMATCH,
}
