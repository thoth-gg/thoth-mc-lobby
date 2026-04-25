package gg.thoth.thothMcProxy

import com.velocitypowered.api.event.ResultedEvent
import com.velocitypowered.api.event.connection.LoginEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import gg.thoth.thothMcProxy.floodgate.FloodgateService
import gg.thoth.thothMcProxy.model.LoginDenialSeverity
import gg.thoth.thothMcProxy.model.LoginDecision
import gg.thoth.thothMcProxy.model.Platform
import gg.thoth.thothMcProxy.model.ResolvedLogin
import gg.thoth.thothMcProxy.service.AuthService
import gg.thoth.thothMcProxy.service.testConfig
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.kyori.adventure.text.format.NamedTextColor
import org.junit.jupiter.api.io.TempDir
import org.slf4j.helpers.NOPLogger

class ThothMcProxyTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `handleLogin denies login when auth evaluation throws`() {
        val plugin = ThothMcProxy(
            server = mockk<ProxyServer>(relaxed = true),
            logger = NOPLogger.NOP_LOGGER,
            dataDirectory = tempDir,
        )
        val floodgateService = mockk<FloodgateService>()
        val event = mockk<LoginEvent>()
        val player = mockk<Player>()
        val result = slot<ResultedEvent.ComponentResult>()

        setPrivateField(plugin, "config", testConfig())
        setPrivateField(plugin, "floodgateService", floodgateService)
        every { event.player } returns player
        every { player.username } returns "alice"
        every { floodgateService.resolve(player) } throws IllegalStateException("Floodgate unavailable")
        every { event.result = capture(result) } just runs

        plugin.handleLogin(event)

        assertTrue(result.isCaptured)
        assertFalse(result.captured.isAllowed)
        assertEquals(NamedTextColor.RED, result.captured.getReasonComponent().orElseThrow().color())
    }

    @Test
    fun `handleLogin colors action-required auth denial yellow`() {
        val plugin = ThothMcProxy(
            server = mockk<ProxyServer>(relaxed = true),
            logger = NOPLogger.NOP_LOGGER,
            dataDirectory = tempDir,
        )
        val floodgateService = mockk<FloodgateService>()
        val authService = mockk<AuthService>()
        val event = mockk<LoginEvent>()
        val player = mockk<Player>()
        val result = slot<ResultedEvent.ComponentResult>()
        val login = ResolvedLogin(
            platform = Platform.JAVA,
            accountUuid = UUID.randomUUID(),
            playerUuid = UUID.randomUUID(),
            username = "alice",
        )

        setPrivateField(plugin, "floodgateService", floodgateService)
        setPrivateField(plugin, "authService", authService)
        every { event.player } returns player
        every { floodgateService.resolve(player) } returns login
        every { authService.evaluateLogin(login) } returns LoginDecision.deny(
            message = "pending auth",
            denialSeverity = LoginDenialSeverity.ACTION_REQUIRED,
        )
        every { event.result = capture(result) } just runs

        plugin.handleLogin(event)

        assertTrue(result.isCaptured)
        assertFalse(result.captured.isAllowed)
        assertEquals(NamedTextColor.YELLOW, result.captured.getReasonComponent().orElseThrow().color())
    }

    private fun setPrivateField(target: Any, fieldName: String, value: Any) {
        target.javaClass.getDeclaredField(fieldName).apply {
            isAccessible = true
            set(target, value)
        }
    }
}
