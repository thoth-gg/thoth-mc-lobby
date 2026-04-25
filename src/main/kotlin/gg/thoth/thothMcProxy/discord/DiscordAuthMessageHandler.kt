package gg.thoth.thothMcProxy.discord

import gg.thoth.thothMcProxy.config.PluginConfig
import gg.thoth.thothMcProxy.model.ReactionDecision
import gg.thoth.thothMcProxy.service.AuthService
import gg.thoth.thothMcProxy.service.RoleStatusService
import org.slf4j.Logger

class DiscordAuthMessageHandler(
    private val config: PluginConfig,
    private val roleStatusService: RoleStatusService,
    private val authService: AuthService,
    private val logger: Logger,
) {
    private val codePattern = Regex("\\b[A-Za-z0-9]{${config.auth.codeLength}}\\b")

    fun handle(
        discordUserId: String,
        channelId: String,
        content: String,
        roleIds: Collection<String>?,
        isBot: Boolean,
    ): ReactionDecision? {
        if (isBot || channelId != config.discord.authChannelId) {
            return null
        }

        val code = extractCode(content) ?: return null
        val roleStatus = roleStatusService.updateFromObservedRoles(discordUserId, roleIds)
        if (roleStatus.isBlacklisted == true) {
            logger.info("Rejected auth code for blacklisted Discord user {}", discordUserId)
            return ReactionDecision.FAILURE
        }
        if (roleStatus.isBlacklisted == null) {
            logger.warn("Rejected auth code because Discord member roles were unavailable for {}", discordUserId)
            return ReactionDecision.FAILURE
        }
        return authService.completeAuthentication(discordUserId, code)
    }

    private fun extractCode(content: String): String? {
        return codePattern.find(content)?.value?.uppercase()
    }
}
