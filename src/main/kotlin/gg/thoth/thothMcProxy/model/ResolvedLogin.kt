package gg.thoth.thothMcProxy.model

import java.util.UUID

data class ResolvedLogin(
    val platform: Platform,
    val accountUuid: UUID,
    val playerUuid: UUID,
    val username: String,
    val linkedJavaUuid: UUID? = null,
    val linkedJavaLookupFailed: Boolean = false,
)
