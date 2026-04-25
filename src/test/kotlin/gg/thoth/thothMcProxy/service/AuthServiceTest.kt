package gg.thoth.thothMcProxy.service

import gg.thoth.thothMcProxy.config.AuthConfig
import gg.thoth.thothMcProxy.config.DiscordConfig
import gg.thoth.thothMcProxy.config.MessageConfig
import gg.thoth.thothMcProxy.config.PluginConfig
import gg.thoth.thothMcProxy.config.PolicyConfig
import gg.thoth.thothMcProxy.config.ReactionConfig
import gg.thoth.thothMcProxy.config.StorageConfig
import gg.thoth.thothMcProxy.model.AuthCompletionStatus
import gg.thoth.thothMcProxy.model.Platform
import gg.thoth.thothMcProxy.model.ReactionDecision
import gg.thoth.thothMcProxy.model.ResolvedLogin
import gg.thoth.thothMcProxy.model.RoleStatusSnapshot
import gg.thoth.thothMcProxy.model.RoleStatusSource
import gg.thoth.thothMcProxy.repository.AuthRepository
import io.mockk.every
import io.mockk.mockk
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class AuthServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private val now = Instant.parse("2026-04-25T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `evaluateLogin issues new code and reissues on subsequent login`() {
        val repository = repository()
        val roleService = FakeRoleStatusService()
        val generator = mockk<AuthCodeGenerator>()
        every { generator.generate(6) } returnsMany listOf("ABC123", "XYZ789")

        val service = service(repository, roleService, generator)
        val login = ResolvedLogin(
            platform = Platform.JAVA,
            accountUuid = UUID.randomUUID(),
            playerUuid = UUID.randomUUID(),
            username = "alice",
        )

        val first = service.evaluateLogin(login)
        val second = service.evaluateLogin(login)

        assertFalse(first.allowed)
        assertFalse(second.allowed)
        assertTrue(first.message!!.contains("ABC123"))
        assertTrue(second.message!!.contains("XYZ789"))
        assertEquals(
            AuthCompletionStatus.CODE_NOT_FOUND,
            repository.completeAuthentication("discord-1", hash("ABC123"), now).status,
        )
        assertEquals(
            AuthCompletionStatus.SUCCESS,
            repository.completeAuthentication("discord-1", hash("XYZ789"), now).status,
        )
    }

    @Test
    fun `evaluateLogin retries when generated auth code collides`() {
        val repository = repository()
        val collidingAccount = UUID.randomUUID()
        repository.replacePendingCode(
            collidingAccount,
            collidingAccount,
            Platform.JAVA,
            "bob",
            hash("ABC123"),
            now,
            now.plusSeconds(600),
        )
        val roleService = FakeRoleStatusService()
        val generator = mockk<AuthCodeGenerator>()
        every { generator.generate(6) } returnsMany listOf("ABC123", "XYZ789")
        val service = service(repository, roleService, generator)
        val login = ResolvedLogin(
            platform = Platform.JAVA,
            accountUuid = UUID.randomUUID(),
            playerUuid = UUID.randomUUID(),
            username = "alice",
        )

        val decision = service.evaluateLogin(login)

        assertFalse(decision.allowed)
        assertTrue(decision.message!!.contains("XYZ789"))
        assertEquals(
            AuthCompletionStatus.SUCCESS,
            repository.completeAuthentication("discord-1", hash("XYZ789"), now).status,
        )
    }

    @Test
    fun `completeAuthentication returns detailed reaction decisions for failed auth results`() {
        val repository = repository()
        val service = service(repository, FakeRoleStatusService(), mockk(relaxed = true))
        val existingJava = UUID.randomUUID()
        val secondJava = UUID.randomUUID()

        seedAuthorizedAccount(repository, "discord-1", existingJava, Platform.JAVA, "alice")
        repository.replacePendingCode(existingJava, existingJava, Platform.JAVA, "alice", hash("JAVA02"), now, now.plusSeconds(600))
        repository.replacePendingCode(secondJava, secondJava, Platform.JAVA, "alice-alt", hash("JAVA03"), now, now.plusSeconds(600))

        assertEquals(ReactionDecision.CODE_NOT_FOUND, service.completeAuthentication("discord-1", "BAD999"))
        assertEquals(ReactionDecision.ALREADY_LINKED, service.completeAuthentication("discord-2", "JAVA02"))
        assertEquals(ReactionDecision.SLOT_FULL, service.completeAuthentication("discord-1", "JAVA03"))
    }

    @Test
    fun `evaluateLogin denies when Discord role status is unavailable and uncached`() {
        val repository = repository()
        seedAuthorizedAccount(repository, "discord-1", UUID.randomUUID(), Platform.JAVA, "alice")
        val roleService = FakeRoleStatusService(
            loginSnapshot = RoleStatusSnapshot(null, RoleStatusSource.UNAVAILABLE),
        )
        val service = service(repository, roleService, mockk(relaxed = true))
        val account = repository.findAccountsByOwner("discord-1").single()
        val login = ResolvedLogin(
            platform = Platform.JAVA,
            accountUuid = account.accountUuid,
            playerUuid = account.playerUuid,
            username = "alice",
        )

        val decision = service.evaluateLogin(login)

        assertFalse(decision.allowed)
        assertEquals(testConfig().messages.discordUnavailable, decision.message)
    }

    @Test
    fun `evaluateLogin denies bedrock account when linked java does not match primary java`() {
        val repository = repository()
        val primaryJava = UUID.randomUUID()
        val bedrock = UUID.randomUUID()
        seedAuthorizedAccount(repository, "discord-1", primaryJava, Platform.JAVA, "alice")
        seedAuthorizedAccount(repository, "discord-1", bedrock, Platform.BEDROCK, "alice-bedrock")
        val roleService = FakeRoleStatusService(
            loginSnapshot = RoleStatusSnapshot(false, RoleStatusSource.CACHE),
        )
        val service = service(repository, roleService, mockk(relaxed = true))

        val decision = service.evaluateLogin(
            ResolvedLogin(
                platform = Platform.BEDROCK,
                accountUuid = bedrock,
                playerUuid = UUID.randomUUID(),
                username = "alice-bedrock",
                linkedJavaUuid = UUID.randomUUID(),
            ),
        )

        assertFalse(decision.allowed)
        assertEquals(testConfig().messages.linkMismatch, decision.message)
    }

    @Test
    fun `evaluateLogin does not rewrite stored account metadata when login is denied`() {
        val repository = repository()
        val primaryJava = UUID.randomUUID()
        val bedrock = UUID.randomUUID()
        seedAuthorizedAccount(repository, "discord-1", primaryJava, Platform.JAVA, "alice")
        seedAuthorizedAccount(repository, "discord-1", bedrock, Platform.BEDROCK, "alice-bedrock")
        val original = requireNotNull(repository.findAccount(bedrock))
        val roleService = FakeRoleStatusService(
            loginSnapshot = RoleStatusSnapshot(false, RoleStatusSource.CACHE),
        )
        val service = service(repository, roleService, mockk(relaxed = true))
        val rejectedPlayerUuid = UUID.randomUUID()

        val decision = service.evaluateLogin(
            ResolvedLogin(
                platform = Platform.BEDROCK,
                accountUuid = bedrock,
                playerUuid = rejectedPlayerUuid,
                username = "spoofed-name",
                linkedJavaUuid = UUID.randomUUID(),
            ),
        )

        val storedAfterDeniedLogin = requireNotNull(repository.findAccount(bedrock))
        assertFalse(decision.allowed)
        assertEquals(testConfig().messages.linkMismatch, decision.message)
        assertNotEquals(original.playerUuid, rejectedPlayerUuid)
        assertEquals(original.playerUuid, storedAfterDeniedLogin.playerUuid)
        assertEquals(original.lastUsername, storedAfterDeniedLogin.lastUsername)
    }

    @Test
    fun `evaluateLogin allows bedrock account when linked java matches primary java`() {
        val repository = repository()
        val primaryJava = UUID.randomUUID()
        val bedrock = UUID.randomUUID()
        seedAuthorizedAccount(repository, "discord-1", primaryJava, Platform.JAVA, "alice")
        seedAuthorizedAccount(repository, "discord-1", bedrock, Platform.BEDROCK, "alice-bedrock")
        val roleService = FakeRoleStatusService(
            loginSnapshot = RoleStatusSnapshot(false, RoleStatusSource.CACHE),
        )
        val service = service(repository, roleService, mockk(relaxed = true))

        val decision = service.evaluateLogin(
            ResolvedLogin(
                platform = Platform.BEDROCK,
                accountUuid = bedrock,
                playerUuid = UUID.randomUUID(),
                username = "alice-bedrock",
                linkedJavaUuid = primaryJava,
            ),
        )

        assertTrue(decision.allowed)
    }

    @Test
    fun `evaluateLogin denies bedrock account when Floodgate link lookup fails`() {
        val repository = repository()
        val primaryJava = UUID.randomUUID()
        val bedrock = UUID.randomUUID()
        seedAuthorizedAccount(repository, "discord-1", primaryJava, Platform.JAVA, "alice")
        seedAuthorizedAccount(repository, "discord-1", bedrock, Platform.BEDROCK, "alice-bedrock")
        val roleService = FakeRoleStatusService(
            loginSnapshot = RoleStatusSnapshot(false, RoleStatusSource.CACHE),
        )
        val service = service(repository, roleService, mockk(relaxed = true))

        val decision = service.evaluateLogin(
            ResolvedLogin(
                platform = Platform.BEDROCK,
                accountUuid = bedrock,
                playerUuid = UUID.randomUUID(),
                username = "alice-bedrock",
                linkedJavaLookupFailed = true,
            ),
        )

        assertFalse(decision.allowed)
        assertEquals(testConfig().messages.discordUnavailable, decision.message)
    }

    @Test
    fun `evaluateLogin denies new bedrock account when Floodgate link lookup fails`() {
        val repository = repository()
        val roleService = FakeRoleStatusService()
        val service = service(repository, roleService, mockk(relaxed = true))
        val bedrock = UUID.randomUUID()

        val decision = service.evaluateLogin(
            ResolvedLogin(
                platform = Platform.BEDROCK,
                accountUuid = bedrock,
                playerUuid = UUID.randomUUID(),
                username = "alice-bedrock",
                linkedJavaLookupFailed = true,
            ),
        )

        assertFalse(decision.allowed)
        assertEquals(testConfig().messages.discordUnavailable, decision.message)
        assertNull(repository.findAccount(bedrock))
    }

    @Test
    fun `completeAuthentication rejects new linked bedrock account when linked java mismatches primary java`() {
        val repository = repository()
        val primaryJava = UUID.randomUUID()
        val bedrock = UUID.randomUUID()
        seedAuthorizedAccount(repository, "discord-1", primaryJava, Platform.JAVA, "alice")
        val roleService = FakeRoleStatusService()
        val generator = mockk<AuthCodeGenerator>()
        every { generator.generate(6) } returns "BED001"
        val service = service(repository, roleService, generator)

        val pending = service.evaluateLogin(
            ResolvedLogin(
                platform = Platform.BEDROCK,
                accountUuid = bedrock,
                playerUuid = UUID.randomUUID(),
                username = "alice-bedrock",
                linkedJavaUuid = UUID.randomUUID(),
            ),
        )
        val result = service.completeAuthentication("discord-1", "BED001")

        assertFalse(pending.allowed)
        assertEquals(ReactionDecision.LINK_MISMATCH, result)
        assertNull(repository.findAccount(bedrock))
        assertEquals(
            AuthCompletionStatus.LINKED_JAVA_MISMATCH,
            repository.completeAuthentication("discord-1", hash("BED001"), now).status,
        )
    }

    @Test
    fun `completeAuthentication allows new linked bedrock account when linked java matches primary java`() {
        val repository = repository()
        val primaryJava = UUID.randomUUID()
        val bedrock = UUID.randomUUID()
        seedAuthorizedAccount(repository, "discord-1", primaryJava, Platform.JAVA, "alice")
        val roleService = FakeRoleStatusService()
        val generator = mockk<AuthCodeGenerator>()
        every { generator.generate(6) } returns "BED001"
        val service = service(repository, roleService, generator)

        val pending = service.evaluateLogin(
            ResolvedLogin(
                platform = Platform.BEDROCK,
                accountUuid = bedrock,
                playerUuid = UUID.randomUUID(),
                username = "alice-bedrock",
                linkedJavaUuid = primaryJava,
            ),
        )
        val result = service.completeAuthentication("discord-1", "BED001")

        assertFalse(pending.allowed)
        assertEquals(ReactionDecision.SUCCESS, result)
        assertNotNull(repository.findAccount(bedrock))
    }

    private fun seedAuthorizedAccount(
        repository: AuthRepository,
        discordUserId: String,
        accountUuid: UUID,
        platform: Platform,
        username: String,
    ) {
        val playerUuid = if (platform == Platform.JAVA) accountUuid else UUID.randomUUID()
        val code = when (platform) {
            Platform.JAVA -> "JAVA01"
            Platform.BEDROCK -> "BED001"
        }
        repository.replacePendingCode(accountUuid, playerUuid, platform, username, hash(code), now, now.plusSeconds(600))
        assertEquals(
            AuthCompletionStatus.SUCCESS,
            repository.completeAuthentication(discordUserId, hash(code), now).status,
        )
    }

    private fun repository(): AuthRepository {
        return AuthRepository(tempDir.resolve("auth.db")).also(AuthRepository::migrate)
    }

    private fun service(
        repository: AuthRepository,
        roleService: RoleStatusService,
        generator: AuthCodeGenerator,
    ): AuthService {
        return AuthService(
            repository = repository,
            roleStatusService = roleService,
            codeGenerator = generator,
            clock = clock,
            config = testConfig(),
        )
    }

    private fun hash(code: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(code.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

private class FakeRoleStatusService(
    var loginSnapshot: RoleStatusSnapshot = RoleStatusSnapshot(false, RoleStatusSource.CACHE),
) : RoleStatusService {
    override fun refreshForLogin(discordUserId: String): RoleStatusSnapshot = loginSnapshot

    override fun updateFromObservedRoles(
        discordUserId: String,
        roleIds: Collection<String>?,
    ): RoleStatusSnapshot {
        return if (roleIds == null) {
            RoleStatusSnapshot(null, RoleStatusSource.UNAVAILABLE)
        } else {
            RoleStatusSnapshot(false, RoleStatusSource.MESSAGE)
        }
    }
}

internal fun testConfig(): PluginConfig {
    return PluginConfig(
        discord = DiscordConfig(
            token = "token",
            guildId = "guild",
            authChannelId = "channel",
            blacklistedRoleIds = setOf("blocked"),
        ),
        storage = StorageConfig("auth.db"),
        auth = AuthConfig(
            codeLength = 6,
            codeTtlSeconds = 600,
        ),
        messages = MessageConfig(
            pendingAuth = "pending {code}",
            blacklisted = "blacklisted",
            discordUnavailable = "discord-unavailable",
            linkMismatch = "link-mismatch",
        ),
        reactions = ReactionConfig(
            success = "✅",
            codeNotFound = "❓",
            alreadyLinked = "🔒",
            slotFull = "📦",
            linkMismatch = "🔗",
            blocked = "⛔",
        ),
        policy = PolicyConfig(
            maxJavaPerDiscord = 1,
            maxBedrockPerDiscord = 1,
        ),
    )
}
