package gg.thoth.thothMcProxy.config

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class PluginConfigTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `load copies bundled config with multiline auth messages`() {
        val config = PluginConfig.load(tempDir, javaClass.classLoader)

        assertTrue(config.messages.pendingAuth.contains('\n'))
        assertTrue(config.messages.pendingAuth.contains("<aqua>{code}</aqua>"))
        assertTrue(config.messages.discordUnavailable.contains('\n'))
        assertEquals("✅", config.reactions.success)
    }

    @Test
    fun `load uses multiline fallback messages`() {
        Files.writeString(
            tempDir.resolve("config.yml"),
            """
            discord:
              token: "token"
              guildId: "guild"
              authChannelId: "channel"
              blacklistedRoleIds: []
            storage: {}
            auth: {}
            messages: {}
            policy: {}
            """.trimIndent(),
        )

        val config = PluginConfig.load(tempDir, javaClass.classLoader)

        assertEquals(
            """
            <yellow>Thoth Minecraft Serverへようこそ！</yellow>

            <white>まだ認証が完了していません。</white>
            <gray>Thoth Discord <aqua>#minecraft_auth</aqua> チャンネルで</gray>
            <aqua>{code}</aqua> <white>と送信してから、もう一度参加してください。</white>
            """.trimIndent(),
            config.messages.pendingAuth,
        )
        assertEquals("❓", config.reactions.codeNotFound)
    }

    @Test
    fun `load uses configured reaction emoji`() {
        Files.writeString(
            tempDir.resolve("config.yml"),
            """
            discord:
              token: "token"
              guildId: "guild"
              authChannelId: "channel"
              blacklistedRoleIds: []
            storage: {}
            auth: {}
            messages: {}
            reactions:
              success: "<:thoth_ok:111111111111111111>"
              codeNotFound: "<:thoth_code_ng:222222222222222222>"
              alreadyLinked: "<:thoth_already_linked:333333333333333333>"
              slotFull: "<:thoth_slot_full:444444444444444444>"
              linkMismatch: "<:thoth_link_mismatch:555555555555555555>"
              blocked: "<:thoth_blocked:666666666666666666>"
            policy: {}
            """.trimIndent(),
        )

        val config = PluginConfig.load(tempDir, javaClass.classLoader)

        assertEquals("<:thoth_ok:111111111111111111>", config.reactions.success)
        assertEquals("<:thoth_code_ng:222222222222222222>", config.reactions.codeNotFound)
        assertEquals("<:thoth_already_linked:333333333333333333>", config.reactions.alreadyLinked)
        assertEquals("<:thoth_slot_full:444444444444444444>", config.reactions.slotFull)
        assertEquals("<:thoth_link_mismatch:555555555555555555>", config.reactions.linkMismatch)
        assertEquals("<:thoth_blocked:666666666666666666>", config.reactions.blocked)
    }

    @Test
    fun `load rejects non-list blacklistedRoleIds`() {
        Files.writeString(
            tempDir.resolve("config.yml"),
            """
            discord:
              token: "token"
              guildId: "guild"
              authChannelId: "channel"
              blacklistedRoleIds: "blocked"
            storage: {}
            auth: {}
            messages: {}
            policy: {}
            """.trimIndent(),
        )

        assertFailsWith<IllegalArgumentException> {
            PluginConfig.load(tempDir, javaClass.classLoader)
        }
    }
}
