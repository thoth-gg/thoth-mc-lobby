package gg.thoth.thothMcProxy.floodgate

import com.velocitypowered.api.proxy.Player
import gg.thoth.thothMcProxy.model.Platform
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.geysermc.floodgate.api.FloodgateApi
import org.geysermc.floodgate.api.link.PlayerLink
import org.geysermc.floodgate.api.player.FloodgatePlayer
import org.geysermc.floodgate.util.LinkedPlayer

class FloodgateServiceTest {
    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `resolve returns java login when player is not from floodgate`() {
        val playerUuid = UUID.randomUUID()
        val api = mockk<FloodgateApi>()
        val player = mockk<Player>()

        mockkStatic(FloodgateApi::class)
        every { FloodgateApi.getInstance() } returns api
        every { api.isFloodgatePlayer(playerUuid) } returns false
        every { player.uniqueId } returns playerUuid
        every { player.username } returns "alice"

        val result = FloodgateService(org.slf4j.helpers.NOPLogger.NOP_LOGGER).resolve(player)

        assertEquals(Platform.JAVA, result.platform)
        assertEquals(playerUuid, result.accountUuid)
        assertNull(result.linkedJavaUuid)
    }

    @Test
    fun `resolve returns bedrock login and linked java uuid when linked`() {
        val playerUuid = UUID.randomUUID()
        val authUuid = UUID.randomUUID()
        val linkedJavaUuid = UUID.randomUUID()
        val api = mockk<FloodgateApi>()
        val player = mockk<Player>()
        val floodgatePlayer = mockk<FloodgatePlayer>()
        val linkedPlayer = mockk<LinkedPlayer>()

        mockkStatic(FloodgateApi::class)
        every { FloodgateApi.getInstance() } returns api
        every { api.isFloodgatePlayer(playerUuid) } returns true
        every { api.getPlayer(playerUuid) } returns floodgatePlayer
        every { player.uniqueId } returns playerUuid
        every { player.username } returns "alice-bedrock"
        every { floodgatePlayer.javaUniqueId } returns authUuid
        every { floodgatePlayer.isLinked } returns true
        every { floodgatePlayer.linkedPlayer } returns linkedPlayer
        every { linkedPlayer.javaUniqueId } returns linkedJavaUuid

        val result = FloodgateService(org.slf4j.helpers.NOPLogger.NOP_LOGGER).resolve(player)

        assertEquals(Platform.BEDROCK, result.platform)
        assertEquals(authUuid, result.accountUuid)
        assertEquals(linkedJavaUuid, result.linkedJavaUuid)
    }

    @Test
    fun `unlink delegates to Floodgate player link`() {
        val javaUuid = UUID.randomUUID()
        val api = mockk<FloodgateApi>()
        val playerLink = mockk<PlayerLink>()

        mockkStatic(FloodgateApi::class)
        every { FloodgateApi.getInstance() } returns api
        every { api.playerLink } returns playerLink
        every { playerLink.unlinkPlayer(javaUuid) } returns CompletableFuture.completedFuture(null)

        val result = FloodgateService(org.slf4j.helpers.NOPLogger.NOP_LOGGER).unlink(javaUuid)

        assertTrue(result)
    }

    @Test
    fun `ensureLinkedToJava delegates to Floodgate player link`() {
        val javaUuid = UUID.randomUUID()
        val bedrockUuid = UUID.randomUUID()
        val api = mockk<FloodgateApi>()
        val playerLink = mockk<PlayerLink>()

        mockkStatic(FloodgateApi::class)
        every { FloodgateApi.getInstance() } returns api
        every { api.playerLink } returns playerLink
        every { playerLink.linkPlayer(bedrockUuid, javaUuid, "alice") } returns CompletableFuture.completedFuture(null)

        val result = FloodgateService(org.slf4j.helpers.NOPLogger.NOP_LOGGER)
            .ensureLinkedToJava(javaUuid, null, bedrockUuid, "alice")

        assertTrue(result)
    }

    @Test
    fun `ensureLinkedToJava unlinks mismatched Java account before linking`() {
        val oldJavaUuid = UUID.randomUUID()
        val javaUuid = UUID.randomUUID()
        val bedrockUuid = UUID.randomUUID()
        val api = mockk<FloodgateApi>()
        val playerLink = mockk<PlayerLink>()

        mockkStatic(FloodgateApi::class)
        every { FloodgateApi.getInstance() } returns api
        every { api.playerLink } returns playerLink
        every { playerLink.unlinkPlayer(oldJavaUuid) } returns CompletableFuture.completedFuture(null)
        every { playerLink.linkPlayer(bedrockUuid, javaUuid, "alice") } returns CompletableFuture.completedFuture(null)

        val result = FloodgateService(org.slf4j.helpers.NOPLogger.NOP_LOGGER)
            .ensureLinkedToJava(javaUuid, oldJavaUuid, bedrockUuid, "alice")

        assertTrue(result)
    }
}
