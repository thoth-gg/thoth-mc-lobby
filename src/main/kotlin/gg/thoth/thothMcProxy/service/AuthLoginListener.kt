package gg.thoth.thothMcProxy.service

import com.velocitypowered.api.event.EventTask
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.LoginEvent
import gg.thoth.thothMcProxy.ThothMcProxy

class AuthLoginListener(
    private val plugin: ThothMcProxy,
) {
    @Subscribe
    fun onLogin(event: LoginEvent): EventTask {
        return EventTask.async {
            plugin.handleLogin(event)
        }
    }
}
