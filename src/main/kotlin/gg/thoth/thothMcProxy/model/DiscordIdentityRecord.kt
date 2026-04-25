package gg.thoth.thothMcProxy.model

import java.util.UUID

data class DiscordIdentityRecord(
    val discordUserId: String,
    val primaryJavaUuid: UUID?,
    val primaryBedrockUuid: UUID?,
)
