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
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
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
        assertEquals(NamedTextColor.RED, result.captured.getReasonComponent().orElseThrow().textParts().first().color())
    }

    @Test
    fun `handleLogin highlights auth code and inserts readable line breaks`() {
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
            message = (
                "Thoth Minecraft Serverへようこそ！あなたはまだ認証が完了していません。" +
                    "Thoth Discord #minecraft_auth チャンネルで 'ABC123' と送信してからもう一度参加してください！"
                ),
            denialSeverity = LoginDenialSeverity.ACTION_REQUIRED,
            highlightedText = "ABC123",
        )
        every { event.result = capture(result) } just runs

        plugin.handleLogin(event)

        assertTrue(result.isCaptured)
        assertFalse(result.captured.isAllowed)
        val textParts = result.captured.getReasonComponent().orElseThrow().textParts()
        assertTrue(textParts.any { it.content().contains('\n') })
        assertTrue(textParts.mapNotNull(TextComponent::color).toSet().size > 1)
        assertTrue(
            textParts.any {
                it.content() == "ABC123" &&
                    it.color() == NamedTextColor.AQUA &&
                    it.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE
            },
        )
    }

    private fun setPrivateField(target: Any, fieldName: String, value: Any) {
        target.javaClass.getDeclaredField(fieldName).apply {
            isAccessible = true
            set(target, value)
        }
    }

    private fun Component.textParts(): List<TextComponent> {
        val parts = mutableListOf<TextComponent>()

        fun collect(component: Component) {
            if (component is TextComponent && component.content().isNotEmpty()) {
                parts += component
            }
            component.children().forEach(::collect)
        }

        collect(this)
        return parts
    }
}
