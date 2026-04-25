package gg.thoth.thothMcProxy.repository

import gg.thoth.thothMcProxy.model.AuthCompletionResult
import gg.thoth.thothMcProxy.model.AuthCompletionStatus
import gg.thoth.thothMcProxy.model.DiscordIdentityRecord
import gg.thoth.thothMcProxy.model.DiscordRoleCacheRecord
import gg.thoth.thothMcProxy.model.MinecraftAccountRecord
import gg.thoth.thothMcProxy.model.PendingAuthCodeRecord
import gg.thoth.thothMcProxy.model.Platform
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException

class AuthRepository(
    databasePath: Path,
) {
    private companion object {
        const val SQLITE_BUSY_TIMEOUT_MILLIS = 10_000
    }

    private val databaseUrl = "jdbc:sqlite:${databasePath.toAbsolutePath()}"
    private val connectionProperties = SQLiteConfig().apply {
        enforceForeignKeys(true)
        setBusyTimeout(SQLITE_BUSY_TIMEOUT_MILLIS)
        setJournalMode(SQLiteConfig.JournalMode.WAL)
        setSynchronous(SQLiteConfig.SynchronousMode.NORMAL)
    }.toProperties()

    init {
        Files.createDirectories(databasePath.toAbsolutePath().parent)
        Class.forName("org.sqlite.JDBC")
    }

    fun migrate() {
        withConnection { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS discord_identities (
                        discord_user_id TEXT PRIMARY KEY,
                        primary_java_uuid TEXT,
                        primary_bedrock_uuid TEXT
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS minecraft_accounts (
                        account_uuid TEXT PRIMARY KEY,
                        player_uuid TEXT,
                        platform TEXT NOT NULL,
                        last_username TEXT NOT NULL,
                        owner_discord_id TEXT NOT NULL,
                        auth_state TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        FOREIGN KEY(owner_discord_id) REFERENCES discord_identities(discord_user_id)
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    CREATE INDEX IF NOT EXISTS idx_minecraft_accounts_owner
                    ON minecraft_accounts(owner_discord_id)
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_discord_identities_primary_java_uuid
                    ON discord_identities(primary_java_uuid)
                    WHERE primary_java_uuid IS NOT NULL
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_discord_identities_primary_bedrock_uuid
                    ON discord_identities(primary_bedrock_uuid)
                    WHERE primary_bedrock_uuid IS NOT NULL
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    CREATE INDEX IF NOT EXISTS idx_minecraft_accounts_username
                    ON minecraft_accounts(last_username COLLATE NOCASE)
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS pending_auth_codes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        account_uuid TEXT NOT NULL,
                        player_uuid TEXT,
                        linked_java_uuid TEXT,
                        platform TEXT NOT NULL,
                        last_username TEXT NOT NULL,
                        code_hash TEXT NOT NULL UNIQUE,
                        issued_at INTEGER NOT NULL,
                        expires_at INTEGER NOT NULL,
                        consumed_at INTEGER
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    CREATE INDEX IF NOT EXISTS idx_pending_auth_codes_account
                    ON pending_auth_codes(account_uuid)
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS discord_role_cache (
                        discord_user_id TEXT PRIMARY KEY,
                        is_blacklisted INTEGER NOT NULL,
                        checked_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
            ensureColumn(connection, "minecraft_accounts", "player_uuid", "TEXT")
            ensureColumn(connection, "pending_auth_codes", "player_uuid", "TEXT")
            ensureColumn(connection, "pending_auth_codes", "linked_java_uuid", "TEXT")
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE INDEX IF NOT EXISTS idx_minecraft_accounts_player_uuid
                    ON minecraft_accounts(player_uuid)
                    """.trimIndent(),
                )
            }
        }
    }

    fun replacePendingCode(
        accountUuid: UUID,
        playerUuid: UUID,
        platform: Platform,
        lastUsername: String,
        codeHash: String,
        issuedAt: Instant,
        expiresAt: Instant,
        linkedJavaUuid: UUID? = null,
    ): Boolean {
        return try {
            withTransaction { connection ->
                connection.prepareStatement(
                    """
                    UPDATE pending_auth_codes
                    SET consumed_at = ?
                    WHERE account_uuid = ? AND consumed_at IS NULL
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, issuedAt.epochSecond)
                    statement.setString(2, accountUuid.toString())
                    statement.executeUpdate()
                }

                connection.prepareStatement(
                    """
                    INSERT INTO pending_auth_codes (
                        account_uuid,
                        player_uuid,
                        linked_java_uuid,
                        platform,
                        last_username,
                        code_hash,
                        issued_at,
                        expires_at,
                        consumed_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, accountUuid.toString())
                    statement.setString(2, playerUuid.toString())
                    statement.setString(3, linkedJavaUuid?.toString())
                    statement.setString(4, platform.name)
                    statement.setString(5, lastUsername)
                    statement.setString(6, codeHash)
                    statement.setLong(7, issuedAt.epochSecond)
                    statement.setLong(8, expiresAt.epochSecond)
                    statement.executeUpdate()
                }

                connection.prepareStatement(
                    """
                    DELETE FROM pending_auth_codes
                    WHERE expires_at < ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, issuedAt.minusSeconds(86_400).epochSecond)
                    statement.executeUpdate()
                }
            }
            true
        } catch (exception: SQLiteException) {
            if (exception.resultCode == SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE) {
                false
            } else {
                throw exception
            }
        }
    }

    fun completeAuthentication(
        discordUserId: String,
        codeHash: String,
        now: Instant,
    ): AuthCompletionResult {
        return try {
            withTransaction { connection ->
                val pending = findPendingCode(connection, codeHash, now) ?: return@withTransaction AuthCompletionResult(
                    status = AuthCompletionStatus.CODE_NOT_FOUND,
                )
                val existingAccount = findAccount(connection, pending.accountUuid)
                if (existingAccount != null && existingAccount.ownerDiscordId != discordUserId) {
                    return@withTransaction AuthCompletionResult(
                        status = AuthCompletionStatus.ACCOUNT_ALREADY_AUTHENTICATED,
                    )
                }
                val currentIdentity = getIdentity(connection, discordUserId)
                val existingPrimaryOwner = findIdentityByPrimaryUuid(connection, pending.accountUuid)
                if (existingPrimaryOwner != null && existingPrimaryOwner.discordUserId != discordUserId) {
                    return@withTransaction AuthCompletionResult(
                        status = AuthCompletionStatus.ACCOUNT_ALREADY_AUTHENTICATED,
                    )
                }
                val nextIdentity = when (pending.platform) {
                    Platform.JAVA -> {
                        if (currentIdentity?.primaryJavaUuid != null && currentIdentity.primaryJavaUuid != pending.accountUuid) {
                            return@withTransaction AuthCompletionResult(
                                status = AuthCompletionStatus.PRIMARY_JAVA_ALREADY_EXISTS,
                            )
                        }
                        DiscordIdentityRecord(
                            discordUserId = discordUserId,
                            primaryJavaUuid = pending.accountUuid,
                            primaryBedrockUuid = currentIdentity?.primaryBedrockUuid,
                        )
                    }

                    Platform.BEDROCK -> {
                        if (currentIdentity?.primaryBedrockUuid != null && currentIdentity.primaryBedrockUuid != pending.accountUuid) {
                            return@withTransaction AuthCompletionResult(
                                status = AuthCompletionStatus.PRIMARY_BEDROCK_ALREADY_EXISTS,
                            )
                        }
                        if (pending.linkedJavaUuid != null && currentIdentity?.primaryJavaUuid != pending.linkedJavaUuid) {
                            return@withTransaction AuthCompletionResult(
                                status = AuthCompletionStatus.LINKED_JAVA_MISMATCH,
                            )
                        }
                        DiscordIdentityRecord(
                            discordUserId = discordUserId,
                            primaryJavaUuid = currentIdentity?.primaryJavaUuid,
                            primaryBedrockUuid = pending.accountUuid,
                        )
                    }
                }

                if (!consumePendingCode(connection, pending.id, now)) {
                    return@withTransaction AuthCompletionResult(
                        status = AuthCompletionStatus.CODE_NOT_FOUND,
                    )
                }
                upsertIdentity(connection, nextIdentity)
                val account = MinecraftAccountRecord(
                    accountUuid = pending.accountUuid,
                    playerUuid = pending.playerUuid,
                    platform = pending.platform,
                    lastUsername = pending.lastUsername,
                    ownerDiscordId = discordUserId,
                )
                upsertAccount(connection, account, now)

                AuthCompletionResult(
                    status = AuthCompletionStatus.SUCCESS,
                    account = account,
                    identity = nextIdentity,
                )
            }
        } catch (exception: SQLiteException) {
            if (exception.resultCode == SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE) {
                AuthCompletionResult(status = AuthCompletionStatus.ACCOUNT_ALREADY_AUTHENTICATED)
            } else {
                throw exception
            }
        }
    }

    fun findAccount(accountUuid: UUID): MinecraftAccountRecord? {
        return withConnection { connection ->
            connection.prepareStatement(
                """
                SELECT account_uuid, player_uuid, platform, last_username, owner_discord_id, auth_state
                FROM minecraft_accounts
                WHERE account_uuid = ? OR player_uuid = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, accountUuid.toString())
                statement.setString(2, accountUuid.toString())
                statement.executeQuery().use(::readAccountOrNull)
            }
        }
    }

    fun findAccountsByOwner(discordUserId: String): List<MinecraftAccountRecord> {
        return withConnection { connection ->
            connection.prepareStatement(
                """
                SELECT account_uuid, player_uuid, platform, last_username, owner_discord_id, auth_state
                FROM minecraft_accounts
                WHERE owner_discord_id = ?
                ORDER BY platform ASC, last_username ASC
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, discordUserId)
                statement.executeQuery().use(::readAccounts)
            }
        }
    }

    fun findAccountsByUsername(username: String): List<MinecraftAccountRecord> {
        return withConnection { connection ->
            connection.prepareStatement(
                """
                SELECT account_uuid, player_uuid, platform, last_username, owner_discord_id, auth_state
                FROM minecraft_accounts
                WHERE lower(last_username) = lower(?)
                ORDER BY platform ASC, owner_discord_id ASC
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, username)
                statement.executeQuery().use(::readAccounts)
            }
        }
    }

    fun findIdentity(discordUserId: String): DiscordIdentityRecord? {
        return withConnection { connection ->
            getIdentity(connection, discordUserId)
        }
    }

    fun findIdentityByPrimaryUuid(uuid: UUID): DiscordIdentityRecord? {
        return withConnection { connection ->
            findIdentityByPrimaryUuid(connection, uuid)
        }
    }

    fun touchAccount(accountUuid: UUID, playerUuid: UUID, lastUsername: String, now: Instant) {
        withConnection { connection ->
            connection.prepareStatement(
                """
                UPDATE minecraft_accounts
                SET player_uuid = ?, last_username = ?, updated_at = ?
                WHERE account_uuid = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, playerUuid.toString())
                statement.setString(2, lastUsername)
                statement.setLong(3, now.epochSecond)
                statement.setString(4, accountUuid.toString())
                statement.executeUpdate()
            }
        }
    }

    fun upsertRoleCache(discordUserId: String, isBlacklisted: Boolean, checkedAt: Instant) {
        withConnection { connection ->
            connection.prepareStatement(
                """
                INSERT INTO discord_role_cache (discord_user_id, is_blacklisted, checked_at)
                VALUES (?, ?, ?)
                ON CONFLICT(discord_user_id) DO UPDATE SET
                    is_blacklisted = excluded.is_blacklisted,
                    checked_at = excluded.checked_at
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, discordUserId)
                statement.setInt(2, if (isBlacklisted) 1 else 0)
                statement.setLong(3, checkedAt.epochSecond)
                statement.executeUpdate()
            }
        }
    }

    fun getRoleCache(discordUserId: String): DiscordRoleCacheRecord? {
        return withConnection { connection ->
            connection.prepareStatement(
                """
                SELECT discord_user_id, is_blacklisted, checked_at
                FROM discord_role_cache
                WHERE discord_user_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, discordUserId)
                statement.executeQuery().use { resultSet ->
                    if (!resultSet.next()) {
                        null
                    } else {
                        DiscordRoleCacheRecord(
                            discordUserId = resultSet.getString("discord_user_id"),
                            isBlacklisted = resultSet.getInt("is_blacklisted") == 1,
                            checkedAt = Instant.ofEpochSecond(resultSet.getLong("checked_at")),
                        )
                    }
                }
            }
        }
    }

    fun unlinkAuthForAccount(accountUuid: UUID): Boolean {
        return withTransaction { connection ->
            val account = findAccount(connection, accountUuid) ?: return@withTransaction false
            val identity = getIdentity(connection, account.ownerDiscordId)

            connection.prepareStatement(
                """
                DELETE FROM minecraft_accounts
                WHERE account_uuid = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, account.accountUuid.toString())
                statement.executeUpdate()
            }

            if (identity != null) {
                val nextIdentity = when (account.platform) {
                    Platform.JAVA -> identity.copy(primaryJavaUuid = null)
                    Platform.BEDROCK -> identity.copy(primaryBedrockUuid = null)
                }
                if (nextIdentity.primaryJavaUuid == null && nextIdentity.primaryBedrockUuid == null) {
                    deleteIdentity(connection, identity.discordUserId)
                } else {
                    upsertIdentity(connection, nextIdentity)
                }
            }
            true
        }
    }

    fun unlinkAuthForDiscord(discordUserId: String): Int {
        return withTransaction { connection ->
            val removed = connection.prepareStatement(
                """
                DELETE FROM minecraft_accounts
                WHERE owner_discord_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, discordUserId)
                statement.executeUpdate()
            }
            deleteIdentity(connection, discordUserId)
            removed
        }
    }

    private fun findPendingCode(connection: Connection, codeHash: String, now: Instant): PendingAuthCodeRecord? {
        return connection.prepareStatement(
            """
            SELECT id, account_uuid, player_uuid, linked_java_uuid, platform, last_username, code_hash, issued_at, expires_at, consumed_at
            FROM pending_auth_codes
            WHERE code_hash = ? AND consumed_at IS NULL AND expires_at >= ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, codeHash)
            statement.setLong(2, now.epochSecond)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    null
                } else {
                    readPending(resultSet)
                }
            }
        }
    }

    private fun consumePendingCode(connection: Connection, pendingId: Long, consumedAt: Instant): Boolean {
        return connection.prepareStatement(
            """
            UPDATE pending_auth_codes
            SET consumed_at = ?
            WHERE id = ? AND consumed_at IS NULL
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, consumedAt.epochSecond)
            statement.setLong(2, pendingId)
            statement.executeUpdate() == 1
        }
    }

    private fun upsertAccount(connection: Connection, account: MinecraftAccountRecord, now: Instant) {
        connection.prepareStatement(
            """
            INSERT INTO minecraft_accounts (
                account_uuid,
                player_uuid,
                platform,
                last_username,
                owner_discord_id,
                auth_state,
                created_at,
                updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(account_uuid) DO UPDATE SET
                player_uuid = excluded.player_uuid,
                platform = excluded.platform,
                last_username = excluded.last_username,
                owner_discord_id = excluded.owner_discord_id,
                auth_state = excluded.auth_state,
                updated_at = excluded.updated_at
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, account.accountUuid.toString())
            statement.setString(2, account.playerUuid.toString())
            statement.setString(3, account.platform.name)
            statement.setString(4, account.lastUsername)
            statement.setString(5, account.ownerDiscordId)
            statement.setString(6, account.authState)
            statement.setLong(7, now.epochSecond)
            statement.setLong(8, now.epochSecond)
            statement.executeUpdate()
        }
    }

    private fun upsertIdentity(connection: Connection, identity: DiscordIdentityRecord) {
        connection.prepareStatement(
            """
            INSERT INTO discord_identities (discord_user_id, primary_java_uuid, primary_bedrock_uuid)
            VALUES (?, ?, ?)
            ON CONFLICT(discord_user_id) DO UPDATE SET
                primary_java_uuid = excluded.primary_java_uuid,
                primary_bedrock_uuid = excluded.primary_bedrock_uuid
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, identity.discordUserId)
            statement.setString(2, identity.primaryJavaUuid?.toString())
            statement.setString(3, identity.primaryBedrockUuid?.toString())
            statement.executeUpdate()
        }
    }

    private fun deleteIdentity(connection: Connection, discordUserId: String) {
        connection.prepareStatement(
            """
            DELETE FROM discord_identities
            WHERE discord_user_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, discordUserId)
            statement.executeUpdate()
        }
    }

    private fun getIdentity(connection: Connection, discordUserId: String): DiscordIdentityRecord? {
        return connection.prepareStatement(
            """
            SELECT discord_user_id, primary_java_uuid, primary_bedrock_uuid
            FROM discord_identities
            WHERE discord_user_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, discordUserId)
            statement.executeQuery().use(::readIdentityOrNull)
        }
    }

    private fun findIdentityByPrimaryUuid(connection: Connection, uuid: UUID): DiscordIdentityRecord? {
        return connection.prepareStatement(
            """
            SELECT discord_user_id, primary_java_uuid, primary_bedrock_uuid
            FROM discord_identities
            WHERE primary_java_uuid = ? OR primary_bedrock_uuid = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, uuid.toString())
            statement.setString(2, uuid.toString())
            statement.executeQuery().use(::readIdentityOrNull)
        }
    }

    private fun findAccount(connection: Connection, accountUuid: UUID): MinecraftAccountRecord? {
        return connection.prepareStatement(
            """
            SELECT account_uuid, player_uuid, platform, last_username, owner_discord_id, auth_state
            FROM minecraft_accounts
            WHERE account_uuid = ? OR player_uuid = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, accountUuid.toString())
            statement.setString(2, accountUuid.toString())
            statement.executeQuery().use(::readAccountOrNull)
        }
    }

    private fun readAccountOrNull(resultSet: ResultSet): MinecraftAccountRecord? {
        return if (!resultSet.next()) {
            null
        } else {
            val accountUuid = UUID.fromString(resultSet.getString("account_uuid"))
            MinecraftAccountRecord(
                accountUuid = accountUuid,
                playerUuid = resultSet.getString("player_uuid")?.let(UUID::fromString) ?: accountUuid,
                platform = Platform.valueOf(resultSet.getString("platform")),
                lastUsername = resultSet.getString("last_username"),
                ownerDiscordId = resultSet.getString("owner_discord_id"),
                authState = resultSet.getString("auth_state"),
            )
        }
    }

    private fun readAccounts(resultSet: ResultSet): List<MinecraftAccountRecord> {
        val accounts = mutableListOf<MinecraftAccountRecord>()
        while (resultSet.next()) {
            val accountUuid = UUID.fromString(resultSet.getString("account_uuid"))
            accounts += MinecraftAccountRecord(
                accountUuid = accountUuid,
                playerUuid = resultSet.getString("player_uuid")?.let(UUID::fromString) ?: accountUuid,
                platform = Platform.valueOf(resultSet.getString("platform")),
                lastUsername = resultSet.getString("last_username"),
                ownerDiscordId = resultSet.getString("owner_discord_id"),
                authState = resultSet.getString("auth_state"),
            )
        }
        return accounts
    }

    private fun readIdentityOrNull(resultSet: ResultSet): DiscordIdentityRecord? {
        return if (!resultSet.next()) {
            null
        } else {
            DiscordIdentityRecord(
                discordUserId = resultSet.getString("discord_user_id"),
                primaryJavaUuid = resultSet.getString("primary_java_uuid")?.let(UUID::fromString),
                primaryBedrockUuid = resultSet.getString("primary_bedrock_uuid")?.let(UUID::fromString),
            )
        }
    }

    private fun readPending(resultSet: ResultSet): PendingAuthCodeRecord {
        val accountUuid = UUID.fromString(resultSet.getString("account_uuid"))
        return PendingAuthCodeRecord(
            id = resultSet.getLong("id"),
            accountUuid = accountUuid,
            playerUuid = resultSet.getString("player_uuid")?.let(UUID::fromString) ?: accountUuid,
            linkedJavaUuid = resultSet.getString("linked_java_uuid")?.let(UUID::fromString),
            platform = Platform.valueOf(resultSet.getString("platform")),
            lastUsername = resultSet.getString("last_username"),
            codeHash = resultSet.getString("code_hash"),
            issuedAt = Instant.ofEpochSecond(resultSet.getLong("issued_at")),
            expiresAt = Instant.ofEpochSecond(resultSet.getLong("expires_at")),
            consumedAt = resultSet.getLong("consumed_at")
                .takeIf { !resultSet.wasNull() }
                ?.let(Instant::ofEpochSecond),
        )
    }

    private fun <T> withConnection(block: (Connection) -> T): T {
        return DriverManager.getConnection(databaseUrl, connectionProperties).use(block)
    }

    private fun <T> withTransaction(block: (Connection) -> T): T {
        return DriverManager.getConnection(databaseUrl, connectionProperties).use { connection ->
            connection.autoCommit = false
            try {
                val result = block(connection)
                connection.commit()
                result
            } catch (throwable: Throwable) {
                connection.rollback()
                throw throwable
            } finally {
                connection.autoCommit = true
            }
        }
    }

    private fun ensureColumn(
        connection: Connection,
        tableName: String,
        columnName: String,
        columnDefinition: String,
    ) {
        connection.prepareStatement("PRAGMA table_info($tableName)").use { statement ->
            statement.executeQuery().use { resultSet ->
                while (resultSet.next()) {
                    if (resultSet.getString("name") == columnName) {
                        return
                    }
                }
            }
        }
        connection.createStatement().use { statement ->
            statement.executeUpdate("ALTER TABLE $tableName ADD COLUMN $columnName $columnDefinition")
        }
    }
}
