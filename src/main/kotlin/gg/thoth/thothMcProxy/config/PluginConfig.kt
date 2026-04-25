package gg.thoth.thothMcProxy.config

import java.nio.file.Files
import java.nio.file.Path
import org.yaml.snakeyaml.Yaml

data class PluginConfig(
    val discord: DiscordConfig,
    val storage: StorageConfig,
    val auth: AuthConfig,
    val messages: MessageConfig,
    val policy: PolicyConfig,
) {
    fun resolveStoragePath(dataDirectory: Path): Path {
        val configured = Path.of(storage.path)
        return if (configured.isAbsolute) configured else dataDirectory.resolve(configured)
    }

    companion object {
        private const val CONFIG_FILE_NAME = "config.yml"

        fun load(dataDirectory: Path, classLoader: ClassLoader): PluginConfig {
            Files.createDirectories(dataDirectory)
            val configPath = dataDirectory.resolve(CONFIG_FILE_NAME)
            if (Files.notExists(configPath)) {
                classLoader.getResourceAsStream(CONFIG_FILE_NAME).use { input ->
                    requireNotNull(input) { "Missing bundled resource $CONFIG_FILE_NAME" }
                    Files.copy(input, configPath)
                }
            }

            val yaml = Yaml()
            val root = Files.newInputStream(configPath).use { input ->
                @Suppress("UNCHECKED_CAST")
                yaml.load<Map<String, Any?>>(input) ?: emptyMap()
            }

            val config = PluginConfig(
                discord = DiscordConfig(
                    token = root.string("discord", "token"),
                    guildId = root.string("discord", "guildId"),
                    authChannelId = root.string("discord", "authChannelId"),
                    blacklistedRoleIds = root.stringList("discord", "blacklistedRoleIds"),
                ),
                storage = StorageConfig(
                    path = root.optionalString("storage", "path") ?: "auth.db",
                ),
                auth = AuthConfig(
                    codeLength = root.optionalInt("auth", "codeLength") ?: 6,
                    codeTtlSeconds = root.optionalLong("auth", "codeTtlSeconds") ?: 600L,
                ),
                messages = MessageConfig(
                    pendingAuth = root.optionalString("messages", "pendingAuth")
                        ?: """
                            Thoth Minecraft Serverへようこそ！
                            まだ認証が完了していません。
                            Thoth Discord #minecraft_auth チャンネルで「{code}」と送信してから、
                            もう一度参加してください。
                        """.trimIndent(),
                    blacklisted = root.optionalString("messages", "blacklisted")
                        ?: """
                            Thoth Minecraft Serverへ参加できません。
                            Discord のロール設定により、認証またはログインが拒否されました。
                        """.trimIndent(),
                    discordUnavailable = root.optionalString("messages", "discordUnavailable")
                        ?: """
                            Thoth Minecraft Serverの認証状態を確認できませんでした。
                            しばらくしてからもう一度参加してください。
                        """.trimIndent(),
                    linkMismatch = root.optionalString("messages", "linkMismatch")
                        ?: """
                            Thoth Minecraft ServerのBedrock連携状態が一致しません。
                            管理者へ連絡してください。
                        """.trimIndent(),
                ),
                policy = PolicyConfig(
                    maxJavaPerDiscord = root.optionalInt("policy", "maxJavaPerDiscord") ?: 1,
                    maxBedrockPerDiscord = root.optionalInt("policy", "maxBedrockPerDiscord") ?: 1,
                ),
            )

            require(config.policy.maxJavaPerDiscord == 1) {
                "Only policy.maxJavaPerDiscord=1 is supported in this version"
            }
            require(config.policy.maxBedrockPerDiscord == 1) {
                "Only policy.maxBedrockPerDiscord=1 is supported in this version"
            }
            return config
        }
    }
}

data class DiscordConfig(
    val token: String,
    val guildId: String,
    val authChannelId: String,
    val blacklistedRoleIds: Set<String>,
)

data class StorageConfig(
    val path: String,
)

data class AuthConfig(
    val codeLength: Int,
    val codeTtlSeconds: Long,
)

data class MessageConfig(
    val pendingAuth: String,
    val blacklisted: String,
    val discordUnavailable: String,
    val linkMismatch: String,
)

data class PolicyConfig(
    val maxJavaPerDiscord: Int,
    val maxBedrockPerDiscord: Int,
)

private fun Map<String, Any?>.section(name: String): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    return this[name] as? Map<String, Any?>
        ?: error("Missing required config section: $name")
}

private fun Map<String, Any?>.string(section: String, key: String): String {
    return optionalString(section, key) ?: error("Missing required config value: $section.$key")
}

private fun Map<String, Any?>.optionalString(section: String, key: String): String? {
    return section(section)[key]?.toString()?.takeIf { it.isNotBlank() }
}

private fun Map<String, Any?>.optionalInt(section: String, key: String): Int? {
    return section(section)[key]?.toString()?.toIntOrNull()
}

private fun Map<String, Any?>.optionalLong(section: String, key: String): Long? {
    return section(section)[key]?.toString()?.toLongOrNull()
}

private fun Map<String, Any?>.stringList(section: String, key: String): Set<String> {
    val raw = section(section)[key] ?: return emptySet()
    require(raw is List<*>) { "Config value $section.$key must be a list" }
    return raw.mapIndexed { index, item ->
        requireNotNull(item?.toString()?.takeIf(String::isNotBlank)) {
            "Config value $section.$key[$index] must be a non-blank string"
        }
    }.toSet()
}
