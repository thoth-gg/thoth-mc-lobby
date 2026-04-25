package gg.thoth.thothMcProxy.repository

import gg.thoth.thothMcProxy.model.AuthCompletionStatus
import gg.thoth.thothMcProxy.model.Platform
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

class AuthRepositoryTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `completeAuthentication enforces one java and one bedrock per discord`() {
        val repository = repository()
        val now = Instant.parse("2026-04-25T00:00:00Z")
        val java1 = UUID.randomUUID()
        val java2 = UUID.randomUUID()
        val bedrock1 = UUID.randomUUID()
        val bedrock2 = UUID.randomUUID()
        val bedrockPlayer1 = UUID.randomUUID()
        val bedrockPlayer2 = UUID.randomUUID()

        repository.replacePendingCode(java1, java1, Platform.JAVA, "java1", hash("JAVA01"), now, now.plusSeconds(600))
        repository.replacePendingCode(java2, java2, Platform.JAVA, "java2", hash("JAVA02"), now, now.plusSeconds(600))
        repository.replacePendingCode(bedrock1, bedrockPlayer1, Platform.BEDROCK, "bedrock1", hash("BED001"), now, now.plusSeconds(600))
        repository.replacePendingCode(bedrock2, bedrockPlayer2, Platform.BEDROCK, "bedrock2", hash("BED002"), now, now.plusSeconds(600))

        assertEquals(
            AuthCompletionStatus.SUCCESS,
            repository.completeAuthentication("discord-1", hash("JAVA01"), now).status,
        )
        assertEquals(
            AuthCompletionStatus.PRIMARY_JAVA_ALREADY_EXISTS,
            repository.completeAuthentication("discord-1", hash("JAVA02"), now).status,
        )
        assertEquals(
            AuthCompletionStatus.SUCCESS,
            repository.completeAuthentication("discord-1", hash("BED001"), now).status,
        )
        assertEquals(
            AuthCompletionStatus.PRIMARY_BEDROCK_ALREADY_EXISTS,
            repository.completeAuthentication("discord-1", hash("BED002"), now).status,
        )
    }

    @Test
    fun `role cache persists and unlink removes account mappings`() {
        val repository = repository()
        val now = Instant.parse("2026-04-25T00:00:00Z")
        val javaUuid = UUID.randomUUID()

        repository.upsertRoleCache("discord-1", true, now)
        val cached = repository.getRoleCache("discord-1")
        assertNotNull(cached)
        assertEquals(true, cached.isBlacklisted)

        repository.replacePendingCode(javaUuid, javaUuid, Platform.JAVA, "alice", hash("ABC123"), now, now.plusSeconds(600))
        assertEquals(
            AuthCompletionStatus.SUCCESS,
            repository.completeAuthentication("discord-1", hash("ABC123"), now).status,
        )
        assertNotNull(repository.findAccount(javaUuid))

        assertEquals(true, repository.unlinkAuthForAccount(javaUuid))
        assertNull(repository.findAccount(javaUuid))
        assertNull(repository.findIdentity("discord-1"))
    }

    @Test
    fun `unlinkAuthForDiscord removes all mappings`() {
        val repository = repository()
        val now = Instant.parse("2026-04-25T00:00:00Z")
        val javaUuid = UUID.randomUUID()
        val bedrockUuid = UUID.randomUUID()
        val bedrockPlayerUuid = UUID.randomUUID()

        repository.replacePendingCode(javaUuid, javaUuid, Platform.JAVA, "alice", hash("JAVA01"), now, now.plusSeconds(600))
        repository.replacePendingCode(bedrockUuid, bedrockPlayerUuid, Platform.BEDROCK, "alice-bedrock", hash("BED001"), now, now.plusSeconds(600))
        repository.completeAuthentication("discord-1", hash("JAVA01"), now)
        repository.completeAuthentication("discord-1", hash("BED001"), now)

        val removed = repository.unlinkAuthForDiscord("discord-1")

        assertEquals(2, removed)
        assertNull(repository.findIdentity("discord-1"))
        assertFalse(repository.findAccountsByOwner("discord-1").isNotEmpty())
    }

    @Test
    fun `completeAuthentication consumes a code only once`() {
        val repository = repository()
        val now = Instant.parse("2026-04-25T00:00:00Z")
        val javaUuid = UUID.randomUUID()

        repository.replacePendingCode(javaUuid, javaUuid, Platform.JAVA, "alice", hash("ABC123"), now, now.plusSeconds(600))

        assertEquals(AuthCompletionStatus.SUCCESS, repository.completeAuthentication("discord-1", hash("ABC123"), now).status)
        assertEquals(AuthCompletionStatus.CODE_NOT_FOUND, repository.completeAuthentication("discord-1", hash("ABC123"), now).status)
    }

    @Test
    fun `completeAuthentication rejects code for account already owned by another discord`() {
        val repository = repository()
        val now = Instant.parse("2026-04-25T00:00:00Z")
        val javaUuid = UUID.randomUUID()

        repository.replacePendingCode(javaUuid, javaUuid, Platform.JAVA, "alice", hash("JAVA01"), now, now.plusSeconds(600))
        assertEquals(AuthCompletionStatus.SUCCESS, repository.completeAuthentication("discord-1", hash("JAVA01"), now).status)

        repository.replacePendingCode(
            javaUuid,
            javaUuid,
            Platform.JAVA,
            "alice",
            hash("JAVA02"),
            now.plusSeconds(1),
            now.plusSeconds(601),
        )

        assertEquals(
            AuthCompletionStatus.ACCOUNT_ALREADY_AUTHENTICATED,
            repository.completeAuthentication("discord-2", hash("JAVA02"), now.plusSeconds(1)).status,
        )
        assertEquals("discord-1", repository.findAccount(javaUuid)?.ownerDiscordId)
        assertEquals(
            AuthCompletionStatus.SUCCESS,
            repository.completeAuthentication("discord-1", hash("JAVA02"), now.plusSeconds(1)).status,
        )
    }

    @Test
    fun `replacePendingCode returns false on auth code collision without consuming existing code`() {
        val repository = repository()
        val now = Instant.parse("2026-04-25T00:00:00Z")
        val originalUuid = UUID.randomUUID()
        val collidingUuid = UUID.randomUUID()

        assertEquals(
            true,
            repository.replacePendingCode(originalUuid, originalUuid, Platform.JAVA, "alice", hash("ABC123"), now, now.plusSeconds(600)),
        )

        assertEquals(
            false,
            repository.replacePendingCode(
                collidingUuid,
                collidingUuid,
                Platform.JAVA,
                "bob",
                hash("ABC123"),
                now.plusSeconds(1),
                now.plusSeconds(601),
            ),
        )

        assertEquals(AuthCompletionStatus.SUCCESS, repository.completeAuthentication("discord-1", hash("ABC123"), now).status)
        assertNull(repository.findAccount(collidingUuid))
    }

    @Test
    fun `completeAuthentication does not consume code when primary slot is already occupied`() {
        val repository = repository()
        val now = Instant.parse("2026-04-25T00:00:00Z")
        val existingJavaUuid = UUID.randomUUID()
        val newJavaUuid = UUID.randomUUID()

        repository.replacePendingCode(existingJavaUuid, existingJavaUuid, Platform.JAVA, "alice", hash("JAVA01"), now, now.plusSeconds(600))
        repository.replacePendingCode(newJavaUuid, newJavaUuid, Platform.JAVA, "alice-alt", hash("JAVA02"), now, now.plusSeconds(600))

        assertEquals(
            AuthCompletionStatus.SUCCESS,
            repository.completeAuthentication("discord-1", hash("JAVA01"), now).status,
        )
        assertEquals(
            AuthCompletionStatus.PRIMARY_JAVA_ALREADY_EXISTS,
            repository.completeAuthentication("discord-1", hash("JAVA02"), now).status,
        )

        assertEquals(
            AuthCompletionStatus.SUCCESS,
            repository.completeAuthentication("discord-2", hash("JAVA02"), now).status,
        )
        assertNotNull(repository.findAccount(newJavaUuid))
    }

    private fun repository(): AuthRepository {
        return AuthRepository(tempDir.resolve("auth.db")).also(AuthRepository::migrate)
    }

    private fun hash(code: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(code.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
