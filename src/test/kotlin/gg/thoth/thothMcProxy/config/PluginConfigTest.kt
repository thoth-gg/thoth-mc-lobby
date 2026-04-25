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
        assertTrue(config.messages.pendingAuth.contains("{code}"))
        assertTrue(config.messages.discordUnavailable.contains('\n'))
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
            Thoth Minecraft Serverへようこそ！
            まだ認証が完了していません。
            Thoth Discord #minecraft_auth チャンネルで「{code}」と送信してから、
            もう一度参加してください。
            """.trimIndent(),
            config.messages.pendingAuth,
        )
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
