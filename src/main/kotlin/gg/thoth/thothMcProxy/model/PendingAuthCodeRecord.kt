package gg.thoth.thothMcProxy.model

import java.time.Instant
import java.util.UUID

data class PendingAuthCodeRecord(
    val id: Long,
    val accountUuid: UUID,
    val playerUuid: UUID,
    val linkedJavaUuid: UUID?,
    val platform: Platform,
    val lastUsername: String,
    val codeHash: String,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val consumedAt: Instant?,
)
