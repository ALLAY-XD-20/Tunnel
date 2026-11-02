package com.pawan.tunnel

/**
 * Tunnel by Pawan.
 *
 * EDIT [nodes] BEFORE BUILDING: these remote Lavalink servers are built into the plugin
 * and used by default. More nodes can be added in application.yml under plugins.tunnel.nodes.
 */
object TunnelDefaults {
    const val AUTHOR = "Pawan"
    const val VERSION = "1.0.0"
    const val PREFIX = "tunnel:"

    fun nodes(): List<NodeConfig> = listOf(
        node("node-1", "echo.rozhost.in", 2333, "Lyrixa", secure = false, priority = 10),
        node("node-2", "echo.rozhost.in", 4002, "Lyrixa", secure = false, priority = 5)
    )

    private fun node(
        name: String,
        host: String,
        port: Int,
        password: String,
        secure: Boolean,
        priority: Int
    ) = NodeConfig().also {
        it.name = name
        it.host = host
        it.port = port
        it.password = password
        it.secure = secure
        it.priority = priority
    }
}
