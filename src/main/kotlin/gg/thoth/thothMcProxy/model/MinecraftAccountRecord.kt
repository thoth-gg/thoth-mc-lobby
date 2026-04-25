package gg.thoth.thothMcProxy.model

import java.util.UUID

data class MinecraftAccountRecord(
    val accountUuid: UUID,
    val playerUuid: UUID,
    val platform: Platform,
    val lastUsername: String,
    val ownerDiscordId: String,
    val authState: String = "AUTHORIZED",
)
