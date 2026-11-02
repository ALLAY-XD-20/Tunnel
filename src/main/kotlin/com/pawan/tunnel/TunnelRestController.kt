package com.pawan.tunnel

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Extra REST endpoints. Lavalink's normal Authorization header protects them. */
@RestController
class TunnelRestController(
    private val nodes: NodeManager,
    private val updater: TunnelUpdater,
    private val asd: AdvancedSearchDriver
) {

    @GetMapping("/v4/tunnel/nodes")
    fun nodes(): Map<String, Any?> = linkedMapOf(
        "plugin" to "tunnel",
        "author" to TunnelDefaults.AUTHOR,
        "version" to updater.currentVersion,
        "pendingUpdate" to updater.pendingVersion,
        "lastUpdateCheck" to updater.lastResult,
        "nodes" to nodes.nodes.map { it.toStatus() }
    )

    /** Every provider found across all nodes, and which nodes offer each one. */
    @GetMapping("/v4/tunnel/providers")
    fun providers(): Map<String, Any?> = linkedMapOf(
        "providers" to nodes.allProviders(),
        "byNode" to nodes.nodes.associate { it.config.name to it.providers.sorted() }
    )

    /** All built-in providers, and which online nodes can serve each one right now. */
    @GetMapping("/v4/tunnel/catalog")
    fun catalog(): Map<String, Any?> {
        val list = ProviderCatalog.all.map { p ->
            val supporting = nodes.nodes
                .filter { it.online && it.providers.isNotEmpty() && it.supports(p.id) }
                .map { it.config.name }
            linkedMapOf<String, Any?>(
                "id" to p.id,
                "name" to p.name,
                "category" to p.category,
                "prefixes" to p.prefixes,
                "hosts" to p.hosts,
                "available" to supporting.isNotEmpty(),
                "nodes" to supporting
            )
        }
        return linkedMapOf(
            "total" to list.size,
            "available" to list.count { it["available"] == true },
            "providers" to list
        )
    }

    /** What Tunnel thinks a query is (link, prefix, ISRC or plain text). */
    @GetMapping("/v4/tunnel/asd/detect")
    fun asdDetect(@RequestParam("query") query: String): Map<String, Any?> =
        QueryDetector.detect(query).toMap()

    /** Preview of the Advanced Search Driver: ranked results with scores, nothing is played. */
    @GetMapping("/v4/tunnel/asd/search")
    fun asdSearch(@RequestParam("query") query: String): Map<String, Any?> {
        val d = QueryDetector.detect(query)
        if (d.type == QueryType.URL || d.type == QueryType.PREFIXED) {
            return linkedMapOf(
                "detection" to d.toMap(),
                "note" to "This query goes straight to a node, the search driver is not used",
                "results" to emptyList<Any>()
            )
        }
        val results = asd.search(d)
        return linkedMapOf(
            "detection" to d.toMap(),
            "count" to results.size,
            "results" to results.map { it.toMap() }
        )
    }

    /** Re-fetch /v4/info from every node right now. */
    @PostMapping("/v4/tunnel/refresh")
    fun refresh(): Map<String, Any?> {
        nodes.refreshAll()
        return nodes()
    }

    /** Check for a new plugin version right now. */
    @PostMapping("/v4/tunnel/update")
    fun update(): Map<String, Any?> = linkedMapOf(
        "result" to updater.checkNow(),
        "currentVersion" to updater.currentVersion,
        "pendingUpdate" to updater.pendingVersion
    )
}
