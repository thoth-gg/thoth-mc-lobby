package gg.thoth.thothMcProxy.model

data class RoleStatusSnapshot(
    val isBlacklisted: Boolean?,
    val source: RoleStatusSource,
)

enum class RoleStatusSource {
    LIVE,
    MESSAGE,
    CACHE,
    UNAVAILABLE,
}
