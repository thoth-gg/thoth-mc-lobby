package gg.thoth.thothMcProxy.discord

import gg.thoth.thothMcProxy.config.PluginConfig
import gg.thoth.thothMcProxy.model.RoleStatusSnapshot
import gg.thoth.thothMcProxy.model.RoleStatusSource
import gg.thoth.thothMcProxy.repository.AuthRepository
import gg.thoth.thothMcProxy.service.RoleStatusService
import java.time.Clock
import java.time.Instant
import java.util.concurrent.TimeUnit
import net.dv8tion.jda.api.JDA
import org.slf4j.Logger

class DiscordRoleService(
    private val config: PluginConfig,
    private val repository: AuthRepository,
    private val logger: Logger,
    private val clock: Clock,
) : RoleStatusService {
    private companion object {
        const val MEMBER_LOOKUP_TIMEOUT_SECONDS = 3L
    }

    @Volatile
    private var jda: JDA? = null

    fun bind(jda: JDA) {
        this.jda = jda
    }

    fun unbind() {
        this.jda = null
    }

    override fun refreshForLogin(discordUserId: String): RoleStatusSnapshot {
        val activeJda = jda
        if (activeJda != null) {
            runCatching {
                val guild = requireNotNull(activeJda.getGuildById(config.discord.guildId)) {
                    "Discord guild ${config.discord.guildId} was not found"
                }
                val member = guild.retrieveMemberById(discordUserId)
                    .submit()
                    .get(MEMBER_LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                val roleIds = member.roles.map { it.id }
                val blacklisted = isBlacklisted(roleIds)
                repository.upsertRoleCache(discordUserId, blacklisted, now())
                return RoleStatusSnapshot(
                    isBlacklisted = blacklisted,
                    source = RoleStatusSource.LIVE,
                )
            }.onFailure { throwable ->
                logger.warn("Falling back to cached Discord role status for {}", discordUserId, throwable)
            }
        }

        val cached = repository.getRoleCache(discordUserId)
        return if (cached != null) {
            RoleStatusSnapshot(
                isBlacklisted = cached.isBlacklisted,
                source = RoleStatusSource.CACHE,
            )
        } else {
            RoleStatusSnapshot(
                isBlacklisted = null,
                source = RoleStatusSource.UNAVAILABLE,
            )
        }
    }

    override fun updateFromObservedRoles(
        discordUserId: String,
        roleIds: Collection<String>?,
    ): RoleStatusSnapshot {
        if (roleIds == null) {
            return RoleStatusSnapshot(
                isBlacklisted = null,
                source = RoleStatusSource.UNAVAILABLE,
            )
        }
        val blacklisted = isBlacklisted(roleIds)
        repository.upsertRoleCache(discordUserId, blacklisted, now())
        return RoleStatusSnapshot(
            isBlacklisted = blacklisted,
            source = RoleStatusSource.MESSAGE,
        )
    }

    private fun isBlacklisted(roleIds: Collection<String>): Boolean {
        return roleIds.any(config.discord.blacklistedRoleIds::contains)
    }

    private fun now(): Instant = Instant.now(clock)
}
