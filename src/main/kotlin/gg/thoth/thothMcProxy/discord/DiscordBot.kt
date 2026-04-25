package gg.thoth.thothMcProxy.discord

import gg.thoth.thothMcProxy.config.PluginConfig
import gg.thoth.thothMcProxy.model.ReactionDecision
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.MemberCachePolicy
import org.slf4j.Logger

class DiscordBot(
    private val config: PluginConfig,
    private val handler: DiscordAuthMessageHandler,
    private val logger: Logger,
    private val startJda: () -> JDA = {
        JDABuilder.createDefault(config.discord.token)
            .enableIntents(
                GatewayIntent.GUILD_MESSAGES,
                GatewayIntent.GUILD_MEMBERS,
                GatewayIntent.MESSAGE_CONTENT,
            )
            .setMemberCachePolicy(MemberCachePolicy.ALL)
            .addEventListeners(
                DiscordMessageListener(config, handler, logger),
                DiscordReadyListener(config, logger),
            )
            .build()
    },
) {
    @Volatile
    private var jda: JDA? = null

    fun start(): JDA? {
        return runCatching {
            startJda()
        }.onSuccess { instance ->
            jda = instance
            logger.info("Started Discord bot client; role checks will use cache fallback until JDA becomes ready.")
        }.onFailure { throwable ->
            logger.warn(
                "Failed to start Discord bot client. The plugin will continue with cached role fallback and no new Discord auth completions until Discord recovers or /thothauth reload is run.",
                throwable,
            )
        }.getOrNull()
    }

    fun shutdown() {
        jda?.shutdownNow()
        jda = null
    }
}

private class DiscordReadyListener(
    private val config: PluginConfig,
    private val logger: Logger,
) : ListenerAdapter() {
    override fun onReady(event: ReadyEvent) {
        if (event.jda.getGuildById(config.discord.guildId) == null) {
            logger.warn("Discord bot is ready but guild {} was not found. Role refreshes will fall back to cache.", config.discord.guildId)
            return
        }
        logger.info("Discord bot is ready and guild {} is available.", config.discord.guildId)
    }
}

private class DiscordMessageListener(
    private val config: PluginConfig,
    private val handler: DiscordAuthMessageHandler,
    private val logger: Logger,
) : ListenerAdapter() {
    override fun onMessageReceived(event: MessageReceivedEvent) {
        if (!event.isFromGuild) {
            return
        }

        val decision = handler.handle(
            discordUserId = event.author.id,
            channelId = event.channel.id,
            content = event.message.contentRaw,
            roleIds = event.member?.roles?.map { it.id },
            isBot = event.author.isBot,
        ) ?: return

        event.message.addReaction(emojiFor(decision)).queue(
            {},
            { throwable ->
                logger.warn("Failed to add Discord reaction for user {}", event.author.id, throwable)
            },
        )
    }

    private fun emojiFor(decision: ReactionDecision): Emoji {
        val emoji = when (decision) {
            ReactionDecision.SUCCESS -> config.reactions.success
            ReactionDecision.CODE_NOT_FOUND -> config.reactions.codeNotFound
            ReactionDecision.ALREADY_LINKED -> config.reactions.alreadyLinked
            ReactionDecision.SLOT_FULL -> config.reactions.slotFull
            ReactionDecision.LINK_MISMATCH -> config.reactions.linkMismatch
            ReactionDecision.BLOCKED -> config.reactions.blocked
        }
        return if (emoji.startsWith("<") && emoji.endsWith(">")) {
            Emoji.fromFormatted(emoji)
        } else {
            Emoji.fromUnicode(emoji)
        }
    }
}
