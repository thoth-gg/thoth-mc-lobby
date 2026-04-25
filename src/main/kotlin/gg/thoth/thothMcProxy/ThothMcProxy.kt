package gg.thoth.thothMcProxy

import com.google.inject.Inject
import com.velocitypowered.api.command.CommandMeta
import com.velocitypowered.api.event.ResultedEvent
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import gg.thoth.thothMcProxy.command.ThothAuthCommand
import gg.thoth.thothMcProxy.config.PluginConfig
import gg.thoth.thothMcProxy.discord.DiscordAuthMessageHandler
import gg.thoth.thothMcProxy.discord.DiscordBot
import gg.thoth.thothMcProxy.discord.DiscordRoleService
import gg.thoth.thothMcProxy.floodgate.FloodgateService
import gg.thoth.thothMcProxy.repository.AuthRepository
import gg.thoth.thothMcProxy.service.AuthCodeGenerator
import gg.thoth.thothMcProxy.service.AuthLoginListener
import gg.thoth.thothMcProxy.service.AuthService
import java.nio.file.Path
import java.time.Clock
import net.kyori.adventure.text.minimessage.MiniMessage
import org.slf4j.Logger

class ThothMcProxy @Inject constructor(
    private val server: ProxyServer,
    private val logger: Logger,
    @param:DataDirectory private val dataDirectory: Path,
) {
    private val lifecycleLock = Any()
    private lateinit var loginListener: AuthLoginListener
    private lateinit var commandMeta: CommandMeta

    @Volatile
    private lateinit var config: PluginConfig

    @Volatile
    private lateinit var repository: AuthRepository

    @Volatile
    private lateinit var roleService: DiscordRoleService

    @Volatile
    private lateinit var authService: AuthService

    @Volatile
    private lateinit var floodgateService: FloodgateService

    @Volatile
    private lateinit var discordBot: DiscordBot

    private val miniMessage = MiniMessage.miniMessage()

    @Subscribe
    fun onProxyInitialization(event: ProxyInitializeEvent) {
        synchronized(lifecycleLock) {
            floodgateService = FloodgateService(logger)
            floodgateService.verifyAvailable()
            reloadRuntime(registerCommandAndListeners = true)
        }
        logger.info("thoth-mc-proxy initialized")
    }

    @Subscribe
    fun onProxyShutdown(event: ProxyShutdownEvent) {
        synchronized(lifecycleLock) {
            if (this::discordBot.isInitialized) {
                discordBot.shutdown()
            }
            if (this::roleService.isInitialized) {
                roleService.unbind()
            }
        }
    }

    internal fun handleLogin(event: com.velocitypowered.api.event.connection.LoginEvent) {
        runCatching {
            val resolvedLogin = floodgateService.resolve(event.player)
            val decision = authService.evaluateLogin(resolvedLogin)
            if (!decision.allowed) {
                event.result = deniedLogin(
                    message = requireNotNull(decision.message),
                )
            }
        }.onFailure { throwable ->
            logger.warn("Failed to evaluate thoth auth for {}", event.player.username, throwable)
            val message = runCatching { config.messages.discordUnavailable }
                .getOrDefault(
                    """
                    <red>Thoth Minecraft Serverの認証状態を確認できませんでした。</red>
                    <white>しばらくしてからもう一度参加してください。</white>
                    """.trimIndent(),
                )
            event.result = deniedLogin(message)
        }
    }

    private fun deniedLogin(message: String): ResultedEvent.ComponentResult {
        return ResultedEvent.ComponentResult.denied(miniMessage.deserialize(message))
    }

    internal fun reloadPlugin(): String {
        val reloaded = synchronized(lifecycleLock) {
            reloadRuntime(registerCommandAndListeners = false)
        }
        return if (reloaded) {
            "Reloaded thoth auth configuration and Discord bot."
        } else {
            "Kept the existing thoth auth runtime because the replacement Discord bot failed to start."
        }
    }

    internal fun repository(): AuthRepository = repository

    internal fun config(): PluginConfig = config

    internal fun floodgateService(): FloodgateService = floodgateService

    internal fun authService(): AuthService = authService

    private fun reloadRuntime(registerCommandAndListeners: Boolean): Boolean {
        val previousBot = if (this::discordBot.isInitialized) discordBot else null
        val previousRoleService = if (this::roleService.isInitialized) roleService else null
        val newConfig = PluginConfig.load(dataDirectory, javaClass.classLoader)
        val databasePath = newConfig.resolveStoragePath(dataDirectory)
        val newRepository = AuthRepository(databasePath)
        newRepository.migrate()

        val newRoleService = DiscordRoleService(
            config = newConfig,
            repository = newRepository,
            logger = logger,
            clock = Clock.systemUTC(),
        )
        val newAuthService = AuthService(
            repository = newRepository,
            roleStatusService = newRoleService,
            bedrockLinkService = floodgateService,
            codeGenerator = AuthCodeGenerator(),
            clock = Clock.systemUTC(),
            config = newConfig,
        )
        val discordHandler = DiscordAuthMessageHandler(
            config = newConfig,
            roleStatusService = newRoleService,
            authService = newAuthService,
            logger = logger,
        )
        val newDiscordBot = DiscordBot(
            config = newConfig,
            handler = discordHandler,
            logger = logger,
        )
        val startedJda = newDiscordBot.start()
        if (previousBot != null && startedJda == null) {
            logger.warn("Keeping the existing Discord bot runtime because the replacement bot failed to start.")
            return false
        }
        startedJda?.let(newRoleService::bind)

        config = newConfig
        repository = newRepository
        roleService = newRoleService
        authService = newAuthService
        discordBot = newDiscordBot

        previousRoleService?.unbind()
        previousBot?.shutdown()

        if (registerCommandAndListeners) {
            loginListener = AuthLoginListener(this)
            server.eventManager.register(this, loginListener)

            val command = ThothAuthCommand(this, logger)
            commandMeta = server.commandManager.metaBuilder("thothauth")
                .plugin(this)
                .build()
            server.commandManager.register(commandMeta, command)
        }
        return true
    }
}
