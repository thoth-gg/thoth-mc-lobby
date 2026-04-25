package gg.thoth.thothMcProxy.config

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.io.TempDir

class PluginConfigTest {
    @TempDir
    lateinit var tempDir: Path

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
