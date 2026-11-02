package com.pawan.tunnel

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Extra REST endpoints. Lavalink's normal Authorization header protects them. */
@RestController
class TunnelRestController(
    private val nodes: NodeManager,
    private val updater: TunnelUpdater,
    private val asd: AdvancedSearchDriver,
    private val sources: SourceSettings
) {

    /** Quick node health check: GET /v4/tunnel/node */
    @GetMapping("/v4/tunnel/node")
    fun nodeStatus(): Map<String, Any?> {
        val list = nodes.nodes
        val online = list.count { it.online }
        return linkedMapOf(
            "status" to when {
                list.isEmpty() -> "no_nodes"
                online == list.size -> "all_online"
                online == 0 -> "all_offline"
                else -> "partial"
            },
            "total" to list.size,
            "online" to online,
            "offline" to list.size - online,
            "nodes" to list.map {
                linkedMapOf<String, Any?>(
                    "name" to it.config.name,
                    "online" to it.online,
                    "latencyMs" to it.latencyMs,
                    "version" to it.version,
                    "sources" to it.providers.size,
                    "lastError" to it.lastError,
                    "lastCheck" to it.lastCheck
                )
            }
        )
    }

    /** One node in full: GET /v4/tunnel/node/{name} */
    @GetMapping("/v4/tunnel/node/{name}")
    fun nodeStatusOne(@PathVariable("name") name: String): ResponseEntity<Map<String, Any?>> {
        val n = nodes.nodes.firstOrNull { it.config.name.equals(name, ignoreCase = true) }
            ?: return ResponseEntity.status(404).body(linkedMapOf<String, Any?>("error" to "no node named '$name'"))
        return ResponseEntity.ok(n.toStatus())
    }

    /** Every source with its on/off state and search prefixes. */
    @GetMapping("/v4/tunnel/sources")
    fun sourceList(): Map<String, Any?> {
        val list = ProviderCatalog.all.map { p ->
            linkedMapOf<String, Any?>(
                "id" to p.id,
                "name" to p.name,
                "enabled" to sources.isEnabled(p.id),
                "prefixes" to p.prefixes.map { "$it:" }
            )
        }
        return linkedMapOf(
            "total" to list.size,
            "enabled" to list.count { it["enabled"] == true },
            "sources" to list,
            "defaultPrefixes" to ProviderCatalog.allPrefixes.map { "$it:" }
        )
    }

    @PostMapping("/v4/tunnel/sources/{id}/enable")
    fun sourceEnable(@PathVariable("id") id: String) = switch(id, true)

    @PostMapping("/v4/tunnel/sources/{id}/disable")
    fun sourceDisable(@PathVariable("id") id: String) = switch(id, false)

    /** Forget every runtime change and go back to application.yml values. */
    @PostMapping("/v4/tunnel/sources/reset")
    fun sourceReset(): Map<String, Any?> {
        sources.resetAll()
        return sourceList()
    }

    private fun switch(id: String, on: Boolean): ResponseEntity<Map<String, Any?>> {
        val real = sources.set(id, on)
            ?: return ResponseEntity.status(404).body(linkedMapOf<String, Any?>("error" to "unknown source '$id', see GET /v4/tunnel/sources"))
        return ResponseEntity.ok(linkedMapOf("source" to real, "enabled" to on))
    }

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
