package gg.thoth.thothMcProxy.service

import gg.thoth.thothMcProxy.config.PluginConfig
import gg.thoth.thothMcProxy.model.AuthCompletionStatus
import gg.thoth.thothMcProxy.model.LoginDenialSeverity
import gg.thoth.thothMcProxy.model.LoginDecision
import gg.thoth.thothMcProxy.model.Platform
import gg.thoth.thothMcProxy.model.ReactionDecision
import gg.thoth.thothMcProxy.model.ResolvedLogin
import gg.thoth.thothMcProxy.repository.AuthRepository
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant

class AuthService(
    private val repository: AuthRepository,
    private val roleStatusService: RoleStatusService,
    private val codeGenerator: AuthCodeGenerator,
    private val clock: Clock,
    private val config: PluginConfig,
) {
    private companion object {
        const val CODE_ISSUE_ATTEMPTS = 5
    }

    fun evaluateLogin(login: ResolvedLogin): LoginDecision {
        val account = repository.findAccount(login.accountUuid)
        if (account == null) {
            return issuePendingCode(login)
        }
        val currentTime = now()

        val roleStatus = roleStatusService.refreshForLogin(account.ownerDiscordId)
        when (roleStatus.isBlacklisted) {
            true -> return LoginDecision.deny(config.messages.blacklisted)
            false -> Unit
            null -> return LoginDecision.deny(config.messages.discordUnavailable)
        }

        if (login.platform == Platform.BEDROCK && login.linkedJavaLookupFailed) {
            return LoginDecision.deny(config.messages.discordUnavailable)
        }

        if (login.platform == Platform.BEDROCK && login.linkedJavaUuid != null) {
            val identity = repository.findIdentity(account.ownerDiscordId)
            if (identity?.primaryJavaUuid == null || identity.primaryJavaUuid != login.linkedJavaUuid) {
                return LoginDecision.deny(config.messages.linkMismatch)
            }
        }

        repository.touchAccount(login.accountUuid, login.playerUuid, login.username, currentTime)
        return LoginDecision.allow()
    }

    fun completeAuthentication(discordUserId: String, code: String): ReactionDecision {
        val normalizedCode = normalizeCode(code)
        if (normalizedCode.length != config.auth.codeLength) {
            return ReactionDecision.FAILURE
        }

        val result = repository.completeAuthentication(
            discordUserId = discordUserId,
            codeHash = hashCode(normalizedCode),
            now = now(),
        )

        return when (result.status) {
            AuthCompletionStatus.SUCCESS -> ReactionDecision.SUCCESS
            AuthCompletionStatus.CODE_NOT_FOUND,
            AuthCompletionStatus.ACCOUNT_ALREADY_AUTHENTICATED,
            AuthCompletionStatus.PRIMARY_JAVA_ALREADY_EXISTS,
            AuthCompletionStatus.PRIMARY_BEDROCK_ALREADY_EXISTS,
            AuthCompletionStatus.LINKED_JAVA_MISMATCH,
            -> ReactionDecision.FAILURE
        }
    }

    private fun issuePendingCode(login: ResolvedLogin): LoginDecision {
        if (login.platform == Platform.BEDROCK && login.linkedJavaLookupFailed) {
            return LoginDecision.deny(config.messages.discordUnavailable)
        }

        val now = now()
        repeat(CODE_ISSUE_ATTEMPTS) {
            val code = codeGenerator.generate(config.auth.codeLength)
            val stored = repository.replacePendingCode(
                accountUuid = login.accountUuid,
                playerUuid = login.playerUuid,
                platform = login.platform,
                lastUsername = login.username,
                codeHash = hashCode(code),
                issuedAt = now,
                expiresAt = now.plusSeconds(config.auth.codeTtlSeconds),
                linkedJavaUuid = login.linkedJavaUuid,
            )
            if (stored) {
                return LoginDecision.deny(
                    config.messages.pendingAuth.replace("{code}", code),
                    LoginDenialSeverity.ACTION_REQUIRED,
                    highlightedText = code,
                )
            }
        }
        return LoginDecision.deny(config.messages.discordUnavailable)
    }

    private fun hashCode(code: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(code.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun normalizeCode(code: String): String = code.trim().uppercase()

    private fun now(): Instant = Instant.now(clock)
}

interface RoleStatusService {
    fun refreshForLogin(discordUserId: String): gg.thoth.thothMcProxy.model.RoleStatusSnapshot

    fun updateFromObservedRoles(
        discordUserId: String,
        roleIds: Collection<String>?,
    ): gg.thoth.thothMcProxy.model.RoleStatusSnapshot
}
