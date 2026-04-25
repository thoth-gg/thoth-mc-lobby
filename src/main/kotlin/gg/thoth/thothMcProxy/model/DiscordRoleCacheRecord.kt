package gg.thoth.thothMcProxy.model

import java.time.Instant

data class DiscordRoleCacheRecord(
    val discordUserId: String,
    val isBlacklisted: Boolean,
    val checkedAt: Instant,
)
