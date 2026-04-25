package gg.thoth.thothMcProxy.floodgate

import com.velocitypowered.api.proxy.Player
import gg.thoth.thothMcProxy.model.Platform
import gg.thoth.thothMcProxy.model.ResolvedLogin
import gg.thoth.thothMcProxy.service.BedrockLinkService
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.geysermc.floodgate.api.FloodgateApi
import org.slf4j.Logger

class FloodgateService(
    private val logger: Logger,
) : BedrockLinkService {
    private data class LinkedJavaLookupResult(
        val linkedJavaUuid: UUID?,
        val lookupFailed: Boolean,
    )

    fun verifyAvailable() {
        runCatching { api() }
            .getOrElse { throwable ->
                throw IllegalStateException("Floodgate API is required for this plugin", throwable)
            }
    }

    fun resolve(player: Player): ResolvedLogin {
        val api = api()
        val playerUuid = player.uniqueId
        if (!api.isFloodgatePlayer(playerUuid)) {
            return ResolvedLogin(
                platform = Platform.JAVA,
                accountUuid = playerUuid,
                playerUuid = playerUuid,
                username = player.username,
            )
        }

        val floodgatePlayer = requireNotNull(api.getPlayer(playerUuid)) {
            "Floodgate reported a Bedrock player for ${player.username} but no FloodgatePlayer was available"
        }
        val linkedJavaLookup = linkedJavaUuidOf(floodgatePlayer)
        return ResolvedLogin(
            platform = Platform.BEDROCK,
            accountUuid = floodgatePlayer.javaUniqueId,
            playerUuid = playerUuid,
            username = player.username,
            linkedJavaUuid = linkedJavaLookup.linkedJavaUuid,
            linkedJavaLookupFailed = linkedJavaLookup.lookupFailed,
        )
    }

    fun unlink(primaryJavaUuid: UUID): Boolean {
        return runCatching {
            api().playerLink.unlinkPlayer(primaryJavaUuid).get(10, TimeUnit.SECONDS)
            true
        }.onFailure { throwable ->
            logger.warn("Failed to unlink Floodgate account for {}", primaryJavaUuid, throwable)
        }.getOrDefault(false)
    }

    override fun linkToJava(primaryJavaUuid: UUID, bedrockUuid: UUID, javaUsername: String): Boolean {
        return runCatching {
            api().playerLink.linkPlayer(bedrockUuid, primaryJavaUuid, javaUsername).get(10, TimeUnit.SECONDS)
            true
        }.onFailure { throwable ->
            logger.warn(
                "Failed to link Floodgate Bedrock account {} to Java account {} ({})",
                bedrockUuid,
                javaUsername,
                primaryJavaUuid,
                throwable,
            )
        }.getOrDefault(false)
    }

    fun linkedJavaUuidFor(bedrockUuid: UUID): UUID? {
        return runCatching {
            val playerLink = api().playerLink
            val method = playerLink.javaClass.getMethod("getLinkedPlayer", UUID::class.java)
            val future = method.invoke(playerLink, bedrockUuid) as java.util.concurrent.CompletableFuture<*>
            val linkedPlayer = future.get(10, TimeUnit.SECONDS) ?: return@runCatching null
            val uuidMethod = linkedPlayer.javaClass.getMethod("getJavaUniqueId")
            uuidMethod.invoke(linkedPlayer) as? UUID
        }.getOrElse { throwable ->
            logger.warn("Failed to resolve Floodgate link for {}", bedrockUuid, throwable)
            null
        }
    }

    private fun api(): FloodgateApi = FloodgateApi.getInstance()

    // The Floodgate API returns LinkedPlayer from another module, so we read it via reflection.
    private fun linkedJavaUuidOf(floodgatePlayer: Any): LinkedJavaLookupResult {
        return runCatching {
            val linkedPlayer = floodgatePlayer.javaClass.getMethod("getLinkedPlayer").invoke(floodgatePlayer)
                ?: return@runCatching LinkedJavaLookupResult(
                    linkedJavaUuid = null,
                    lookupFailed = false,
                )
            LinkedJavaLookupResult(
                linkedJavaUuid = linkedPlayer.javaClass.getMethod("getJavaUniqueId").invoke(linkedPlayer) as? UUID,
                lookupFailed = false,
            )
        }.getOrElse { throwable ->
            logger.warn("Failed to read Floodgate linked Java UUID", throwable)
            LinkedJavaLookupResult(
                linkedJavaUuid = null,
                lookupFailed = true,
            )
        }
    }
}
