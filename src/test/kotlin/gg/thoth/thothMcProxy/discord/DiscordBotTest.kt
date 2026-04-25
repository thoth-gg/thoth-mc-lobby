package gg.thoth.thothMcProxy.discord

import gg.thoth.thothMcProxy.service.testConfig
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import net.dv8tion.jda.api.JDA
import org.slf4j.helpers.NOPLogger

class DiscordBotTest {
    @Test
    fun `start returns null when JDA bootstrap fails`() {
        val bot = DiscordBot(
            config = testConfig(),
            handler = mockk(relaxed = true),
            logger = NOPLogger.NOP_LOGGER,
            startJda = { throw IllegalStateException("discord unavailable") },
        )

        val started = bot.start()

        assertNull(started)
    }

    @Test
    fun `start returns JDA instance when bootstrap succeeds`() {
        val jda = mockk<JDA>(relaxed = true)
        val bot = DiscordBot(
            config = testConfig(),
            handler = mockk(relaxed = true),
            logger = NOPLogger.NOP_LOGGER,
            startJda = { jda },
        )

        val started = bot.start()

        assertNotNull(started)
        kotlin.test.assertSame(jda, started)
    }
}
