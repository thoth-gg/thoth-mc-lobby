package gg.thoth.thothMcProxy.command

import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.command.SimpleCommand
import gg.thoth.thothMcProxy.ThothMcProxy
import gg.thoth.thothMcProxy.model.DiscordIdentityRecord
import gg.thoth.thothMcProxy.model.MinecraftAccountRecord
import gg.thoth.thothMcProxy.model.Platform
import java.util.UUID
import net.kyori.adventure.text.Component
import org.slf4j.Logger

class ThothAuthCommand(
    private val plugin: ThothMcProxy,
    private val logger: Logger,
) : SimpleCommand {
    override fun execute(invocation: SimpleCommand.Invocation) {
        val source = invocation.source()
        val args = invocation.arguments()
        if (args.isEmpty()) {
            source.sendMessage(Component.text("Usage: /thothauth <status|unlink-auth|unlink-link|reload> <target?>"))
            return
        }

        when (args[0].lowercase()) {
            "reload" -> source.sendMessage(Component.text(plugin.reloadPlugin()))
            "status" -> handleStatus(source, args.getOrNull(1))
            "unlink-auth" -> handleUnlinkAuth(source, args.getOrNull(1))
            "unlink-link" -> handleUnlinkLink(source, args.getOrNull(1))
            else -> source.sendMessage(Component.text("Unknown subcommand: ${args[0]}"))
        }
    }

    override fun hasPermission(invocation: SimpleCommand.Invocation): Boolean {
        return invocation.source().hasPermission("thoth.auth.admin")
    }

    private fun handleStatus(source: CommandSource, targetArg: String?) {
        val resolution = resolveTarget(targetArg ?: return source.sendMessage(Component.text("Target is required.")))
        when (resolution) {
            is TargetResolution.NotFound -> source.sendMessage(Component.text("No auth record matched '$targetArg'."))
            is TargetResolution.Ambiguous -> source.sendMessage(
                Component.text("Ambiguous target '$targetArg': ${resolution.matches.joinToString(", ") { it.lastUsername }}"),
            )

            is TargetResolution.IdentityTarget -> {
                val identity = resolution.identity
                val accounts = plugin.repository().findAccountsByOwner(identity.discordUserId)
                val cache = plugin.repository().getRoleCache(identity.discordUserId)

                source.sendMessage(Component.text("Discord: ${identity.discordUserId}"))
                source.sendMessage(Component.text("Primary Java: ${identity.primaryJavaUuid ?: "-"}"))
                source.sendMessage(Component.text("Primary Bedrock: ${identity.primaryBedrockUuid ?: "-"}"))
                source.sendMessage(
                    Component.text(
                        "Role cache: ${
                            cache?.let { "${it.isBlacklisted} @ ${it.checkedAt}" } ?: "none"
                        }",
                    ),
                )
                if (accounts.isEmpty()) {
                    source.sendMessage(Component.text("Accounts: none"))
                } else {
                    accounts.forEach { account ->
                        source.sendMessage(Component.text(formatAccountLine(account)))
                    }
                }
            }

            is TargetResolution.AccountTarget -> {
                val account = resolution.account
                val identity = plugin.repository().findIdentity(account.ownerDiscordId)
                val linkedJava = if (account.platform == Platform.BEDROCK) {
                    plugin.floodgateService().linkedJavaUuidFor(account.playerUuid)
                } else {
                    null
                }

                source.sendMessage(Component.text(formatAccountLine(account)))
                source.sendMessage(Component.text("Owner Discord: ${account.ownerDiscordId}"))
                source.sendMessage(Component.text("Primary Java: ${identity?.primaryJavaUuid ?: "-"}"))
                source.sendMessage(Component.text("Primary Bedrock: ${identity?.primaryBedrockUuid ?: "-"}"))
                if (account.platform == Platform.BEDROCK) {
                    source.sendMessage(Component.text("Floodgate linked Java: ${linkedJava ?: "-"}"))
                }
            }
        }
    }

    private fun handleUnlinkAuth(source: CommandSource, targetArg: String?) {
        val resolution = resolveTarget(targetArg ?: return source.sendMessage(Component.text("Target is required.")))
        when (resolution) {
            is TargetResolution.NotFound -> source.sendMessage(Component.text("No auth record matched '$targetArg'."))
            is TargetResolution.Ambiguous -> source.sendMessage(Component.text("Target '$targetArg' is ambiguous. Use UUID or Discord ID."))
            is TargetResolution.IdentityTarget -> {
                val removed = plugin.repository().unlinkAuthForDiscord(resolution.identity.discordUserId)
                source.sendMessage(Component.text("Removed $removed auth mappings for Discord ${resolution.identity.discordUserId}."))
            }

            is TargetResolution.AccountTarget -> {
                val removed = plugin.repository().unlinkAuthForAccount(resolution.account.accountUuid)
                source.sendMessage(
                    Component.text(
                        if (removed) {
                            "Removed auth mapping for ${resolution.account.lastUsername} (${resolution.account.accountUuid})."
                        } else {
                            "No auth mapping was removed."
                        },
                    ),
                )
            }
        }
    }

    private fun handleUnlinkLink(source: CommandSource, targetArg: String?) {
        val resolution = resolveTarget(targetArg ?: return source.sendMessage(Component.text("Target is required.")))
        val identity = when (resolution) {
            is TargetResolution.NotFound -> {
                source.sendMessage(Component.text("No auth record matched '$targetArg'."))
                return
            }

            is TargetResolution.Ambiguous -> {
                source.sendMessage(Component.text("Target '$targetArg' is ambiguous. Use UUID or Discord ID."))
                return
            }

            is TargetResolution.IdentityTarget -> resolution.identity
            is TargetResolution.AccountTarget -> plugin.repository().findIdentity(resolution.account.ownerDiscordId)
        }

        val primaryJavaUuid = identity?.primaryJavaUuid
        if (primaryJavaUuid == null) {
            source.sendMessage(Component.text("No primary Java account is registered for this target."))
            return
        }

        val unlinked = plugin.floodgateService().unlink(primaryJavaUuid)
        source.sendMessage(
            Component.text(
                if (unlinked) {
                    "Requested Floodgate unlink for Java UUID $primaryJavaUuid."
                } else {
                    "Failed to unlink Floodgate mapping for Java UUID $primaryJavaUuid. Check logs."
                },
            ),
        )
    }

    private fun resolveTarget(rawTarget: String): TargetResolution {
        val repository = plugin.repository()
        if (rawTarget.all(Char::isDigit)) {
            val identity = repository.findIdentity(rawTarget)
            val accounts = repository.findAccountsByOwner(rawTarget)
            if (identity != null || accounts.isNotEmpty()) {
                return TargetResolution.IdentityTarget(
                    identity = identity ?: DiscordIdentityRecord(
                        discordUserId = rawTarget,
                        primaryJavaUuid = null,
                        primaryBedrockUuid = null,
                    ),
                )
            }
        }

        val uuid = rawTarget.toUuidOrNull()
        if (uuid != null) {
            val account = repository.findAccount(uuid)
            if (account != null) {
                return TargetResolution.AccountTarget(account)
            }
            val identity = repository.findIdentityByPrimaryUuid(uuid)
            if (identity != null) {
                return TargetResolution.IdentityTarget(identity)
            }
        }

        val matches = repository.findAccountsByUsername(rawTarget)
        return when (matches.size) {
            0 -> TargetResolution.NotFound
            1 -> TargetResolution.AccountTarget(matches.single())
            else -> TargetResolution.Ambiguous(matches)
        }
    }

    private fun formatAccountLine(account: MinecraftAccountRecord): String {
        return buildString {
            append("Account ${account.lastUsername} [${account.platform}] authUuid=${account.accountUuid}")
            if (account.playerUuid != account.accountUuid) {
                append(" playerUuid=${account.playerUuid}")
            }
            append(" owner=${account.ownerDiscordId}")
        }
    }
}

private sealed interface TargetResolution {
    data class IdentityTarget(val identity: DiscordIdentityRecord) : TargetResolution

    data class AccountTarget(val account: MinecraftAccountRecord) : TargetResolution

    data class Ambiguous(val matches: List<MinecraftAccountRecord>) : TargetResolution

    data object NotFound : TargetResolution
}

private fun String.toUuidOrNull(): UUID? = runCatching(UUID::fromString).getOrNull()
