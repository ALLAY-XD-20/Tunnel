package com.pawan.tunnel

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

class Finding(val level: String, val title: String, val detail: String, val fix: String?) {
    fun toMap(): Map<String, Any?> = linkedMapOf("level" to level, "title" to title, "detail" to detail, "fix" to fix)
}

/**
 * Auto-debug: looks at nodes, config and what clients do, and says in plain words what is wrong and how to fix it.
 * GET /v4/tunnel/debug            full report
 * GET /v4/tunnel/debug?query=ytsearch:song   also tests that search on every node
 * It also runs by itself and writes only NEW problems to the log.
 */
@Service
class TunnelDiagnostics(
    private val nodes: NodeManager,
    private val props: TunnelProperties,
    private val updater: TunnelUpdater
) : InitializingBean {

    private val log = LoggerFactory.getLogger(TunnelDiagnostics::class.java)
    @Volatile private var lastSignature: String = ""

    override fun afterPropertiesSet() {
        if (!props.autoDebug) return
        val every = props.autoDebugMinutes.coerceAtLeast(1)
        TunnelExecutors.scheduler.scheduleWithFixedDelay({
            runCatching { logProblems() }.onFailure { log.debug("[Tunnel][debug] failed: {}", it.message) }
        }, 30, every * 60, TimeUnit.SECONDS)
    }

    fun findings(): List<Finding> {
        val out = mutableListOf<Finding>()
        val list = nodes.nodes

        if (list.isEmpty()) {
            out += Finding("ERROR", "No nodes configured", "Tunnel has no node to forward searches to.", "Set useDefaultNodes: true or add nodes under plugins.tunnel.nodes.")
        } else if (list.none { it.online }) {
            out += Finding("ERROR", "No node is online", "Every search falls back to this server's own sources.", "Check the node errors below. Auto-reconnect keeps retrying.")
        }

        for (n in list) {
            val name = n.config.name
            if (!n.online) {
                out += Finding("WARN", "Node $name is offline", n.lastError ?: "no answer", TunnelHints.explain(n.lastError))
            } else if (n.recentlyFailed()) {
                out += Finding("WARN", "Node $name is online but its searches fail", n.lastError ?: "unknown error", TunnelHints.explain(n.lastError))
            }
        }

        val onlineProviders = list.filter { it.online }.flatMap { it.providers }.toSet()
        val mirrors = (listOf(props.fallbackSearch) + props.fallbackSearches).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val missing = mirrors.filter {
            val id = ProviderResolver.resolve(it + "x")
            id != null && onlineProviders.isNotEmpty() && !nodes.isAvailable(id)
        }
        if (missing.isNotEmpty()) {
            out += Finding("INFO", "Some mirror searches have no node", "No online node offers: ${missing.joinToString(", ")}", "Harmless, they are just skipped.")
        }

        if (TunnelDebugState.foreignTracks.isNotEmpty()) {
            val list = TunnelDebugState.foreignTracks.entries.joinToString { "${it.key} x${it.value}" }
            if (props.autoFix) {
                out += Finding(
                    "WARN", "Tracks from another Lavalink were sent here (auto-fixed)",
                    "Played through a mirror instead of failing: $list",
                    "Works, but the bot should search AND play on this same server for exact audio and speed."
                )
            } else {
                out += Finding(
                    "ERROR", "Tracks from another Lavalink were sent here",
                    "Cannot decode: $list",
                    "Use ONLY this server in the bot for both search and play, or set autoFix: true."
                )
            }
        }

        if (props.exposeSources) {
            out += Finding("INFO", "Sources in /v4/info are names only", "Tunnel lists sources it does not really have locally.", "A track must be searched and played on this same server.")
        }
        if (updater.pendingVersion != null) {
            out += Finding("INFO", "Update downloaded", "Version ${updater.pendingVersion} is ready.", "Restart Lavalink to use it.")
        }
        return out
    }

    private fun testSearch(query: String): List<Map<String, Any?>> =
        nodes.nodes.map { n ->
            val start = System.currentTimeMillis()
            try {
                val r = nodes.loadTracks(n, query)
                val data = r.path("data")
                linkedMapOf<String, Any?>(
                    "node" to n.config.name,
                    "ok" to true,
                    "loadType" to r.path("loadType").asText(),
                    "results" to (if (data.isArray) data.size() else if (data.isMissingNode || data.isNull) 0 else 1),
                    "ms" to System.currentTimeMillis() - start
                )
            } catch (e: Exception) {
                linkedMapOf<String, Any?>(
                    "node" to n.config.name,
                    "ok" to false,
                    "error" to e.message,
                    "hint" to TunnelHints.explain(e.message),
                    "ms" to System.currentTimeMillis() - start
                )
            }
        }

    fun report(query: String?): Map<String, Any?> {
        val f = findings()
        val status = when {
            f.any { it.level == "ERROR" } -> "broken"
            f.any { it.level == "WARN" } -> "degraded"
            else -> "healthy"
        }
        val out = linkedMapOf<String, Any?>(
            "status" to status,
            "version" to updater.currentVersion,
            "settings" to linkedMapOf(
                "interceptSearch" to props.interceptSearch,
                "lazyResolve" to props.lazyResolve,
                "localFallback" to props.localFallback,
                "multiNode" to props.multiNode,
                "autoReconnect" to props.autoReconnect,
                "autoFix" to props.autoFix
            ),
            "selfFixes" to TunnelDebugState.fixes.toList(),
            "nodes" to nodes.nodes.map { it.toStatus() },
            "findings" to f.map { it.toMap() }
        )
        if (!query.isNullOrBlank()) out["searchTest"] = testSearch(query)
        return out
    }

    private fun logProblems() {
        val bad = findings().filter { it.level != "INFO" }
        val signature = bad.joinToString("|") { it.title }
        if (signature == lastSignature) return
        lastSignature = signature
        if (bad.isEmpty()) {
            log.info("[Tunnel][debug] all clear, no problems found")
            return
        }
        for (b in bad) {
            log.warn("[Tunnel][debug] {}: {}{}", b.title, b.detail, b.fix?.let { "  FIX: $it" } ?: "")
        }
    }
}
