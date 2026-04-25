package gg.thoth.thothMcProxy.discord

import gg.thoth.thothMcProxy.model.RoleStatusSource
import gg.thoth.thothMcProxy.repository.AuthRepository
import gg.thoth.thothMcProxy.service.testConfig
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.requests.restaction.CacheRestAction
import org.junit.jupiter.api.io.TempDir

class DiscordRoleServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private val now = Instant.parse("2026-04-25T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `refreshForLogin returns unavailable when Discord is unreachable and no cache exists`() {
        val repository = repository()
        val service = DiscordRoleService(testConfig(), repository, org.slf4j.helpers.NOPLogger.NOP_LOGGER, clock)

        val result = service.refreshForLogin("discord-1")

        assertNull(result.isBlacklisted)
        assertEquals(RoleStatusSource.UNAVAILABLE, result.source)
    }

    @Test
    fun `refreshForLogin falls back to cached role status`() {
        val repository = repository()
        val service = DiscordRoleService(testConfig(), repository, org.slf4j.helpers.NOPLogger.NOP_LOGGER, clock)

        service.updateFromObservedRoles("discord-1", listOf("blocked"))
        val result = service.refreshForLogin("discord-1")

        assertEquals(true, result.isBlacklisted)
        assertEquals(RoleStatusSource.CACHE, result.source)
    }

    @Test
    fun `refreshForLogin returns live role status using timed member lookup`() {
        val repository = repository()
        val service = DiscordRoleService(testConfig(), repository, org.slf4j.helpers.NOPLogger.NOP_LOGGER, clock)
        val jda = mockk<JDA>()
        val guild = mockk<Guild>()
        val action = mockk<CacheRestAction<Member>>()
        val member = mockk<Member>()
        val role = mockk<Role>()

        every { jda.getGuildById(testConfig().discord.guildId) } returns guild
        every { guild.retrieveMemberById("discord-1") } returns action
        every { action.submit() } returns CompletableFuture.completedFuture(member)
        every { member.roles } returns listOf(role)
        every { role.id } returns "blocked"

        service.bind(jda)

        val result = service.refreshForLogin("discord-1")

        assertEquals(true, result.isBlacklisted)
        assertEquals(RoleStatusSource.LIVE, result.source)
        assertEquals(true, repository.getRoleCache("discord-1")?.isBlacklisted)
    }

    private fun repository(): AuthRepository {
        return AuthRepository(tempDir.resolve("auth.db")).also(AuthRepository::migrate)
    }
}
