package gg.thoth.thothMcProxy.discord

import gg.thoth.thothMcProxy.model.Platform
import gg.thoth.thothMcProxy.model.ReactionDecision
import gg.thoth.thothMcProxy.repository.AuthRepository
import gg.thoth.thothMcProxy.service.AuthCodeGenerator
import gg.thoth.thothMcProxy.service.AuthService
import gg.thoth.thothMcProxy.service.testConfig
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

class DiscordAuthMessageHandlerTest {
    @TempDir
    lateinit var tempDir: Path

    private val now = Instant.parse("2026-04-25T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `handle succeeds for valid code and caches non-blacklisted role state`() {
        val repository = repository()
        val roleService = DiscordRoleService(testConfig(), repository, org.slf4j.helpers.NOPLogger.NOP_LOGGER, clock)
        val authService = AuthService(
            repository = repository,
            roleStatusService = roleService,
            codeGenerator = AuthCodeGenerator(),
            clock = clock,
            config = testConfig(),
        )
        val handler = DiscordAuthMessageHandler(
            config = testConfig(),
            roleStatusService = roleService,
            authService = authService,
            logger = org.slf4j.helpers.NOPLogger.NOP_LOGGER,
        )
        val accountUuid = UUID.randomUUID()

        repository.replacePendingCode(accountUuid, accountUuid, Platform.JAVA, "alice", hash("ABC123"), now, now.plusSeconds(600))

        val result = handler.handle(
            discordUserId = "discord-1",
            channelId = testConfig().discord.authChannelId,
            content = "ABC123",
            roleIds = emptyList(),
            isBot = false,
        )

        assertEquals(ReactionDecision.SUCCESS, result)
        assertNotNull(repository.findAccount(accountUuid))
        assertEquals(false, repository.getRoleCache("discord-1")?.isBlacklisted)
    }

    @Test
    fun `handle fails for blacklisted Discord role and does not authenticate`() {
        val repository = repository()
        val roleService = DiscordRoleService(testConfig(), repository, org.slf4j.helpers.NOPLogger.NOP_LOGGER, clock)
        val authService = AuthService(
            repository = repository,
            roleStatusService = roleService,
            codeGenerator = AuthCodeGenerator(),
            clock = clock,
            config = testConfig(),
        )
        val handler = DiscordAuthMessageHandler(
            config = testConfig(),
            roleStatusService = roleService,
            authService = authService,
            logger = org.slf4j.helpers.NOPLogger.NOP_LOGGER,
        )
        val accountUuid = UUID.randomUUID()

        repository.replacePendingCode(accountUuid, accountUuid, Platform.JAVA, "alice", hash("ABC123"), now, now.plusSeconds(600))

        val result = handler.handle(
            discordUserId = "discord-1",
            channelId = testConfig().discord.authChannelId,
            content = "'ABC123'",
            roleIds = listOf("blocked"),
            isBot = false,
        )

        assertEquals(ReactionDecision.FAILURE, result)
        assertNull(repository.findAccount(accountUuid))
        assertEquals(true, repository.getRoleCache("discord-1")?.isBlacklisted)
    }

    @Test
    fun `handle fails when Discord member roles are unavailable and does not overwrite cache`() {
        val repository = repository()
        val roleService = DiscordRoleService(testConfig(), repository, org.slf4j.helpers.NOPLogger.NOP_LOGGER, clock)
        val authService = AuthService(
            repository = repository,
            roleStatusService = roleService,
            codeGenerator = AuthCodeGenerator(),
            clock = clock,
            config = testConfig(),
        )
        val handler = DiscordAuthMessageHandler(
            config = testConfig(),
            roleStatusService = roleService,
            authService = authService,
            logger = org.slf4j.helpers.NOPLogger.NOP_LOGGER,
        )
        val accountUuid = UUID.randomUUID()

        repository.upsertRoleCache("discord-1", true, now)
        repository.replacePendingCode(accountUuid, accountUuid, Platform.JAVA, "alice", hash("ABC123"), now, now.plusSeconds(600))

        val result = handler.handle(
            discordUserId = "discord-1",
            channelId = testConfig().discord.authChannelId,
            content = "ABC123",
            roleIds = null,
            isBot = false,
        )

        assertEquals(ReactionDecision.FAILURE, result)
        assertNull(repository.findAccount(accountUuid))
        assertEquals(true, repository.getRoleCache("discord-1")?.isBlacklisted)
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
