package gg.thoth.thothMcProxy

import com.velocitypowered.api.event.ResultedEvent
import com.velocitypowered.api.event.connection.LoginEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import gg.thoth.thothMcProxy.floodgate.FloodgateService
import gg.thoth.thothMcProxy.service.testConfig
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
    }

    private fun setPrivateField(target: Any, fieldName: String, value: Any) {
        target.javaClass.getDeclaredField(fieldName).apply {
            isAccessible = true
            set(target, value)
        }
    }
}
