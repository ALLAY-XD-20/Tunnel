package com.pawan.tunnel

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.stereotype.Service
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** A remote Lavalink plus what Tunnel has learned about it. */
class TunnelNode(val config: NodeConfig) {
    @Volatile var online: Boolean = false
    @Volatile var providers: Set<String> = emptySet()
    @Volatile var plugins: List<String> = emptyList()
    @Volatile var version: String? = null
    @Volatile var latencyMs: Long = -1
    @Volatile var lastError: String? = null
    @Volatile var lastCheck: Long = 0

    val baseUrl: String
        get() = "${if (config.secure) "https" else "http"}://${config.host}:${config.port}"

    /** An empty provider list means "not fetched yet", so the node is not ruled out. */
    fun supports(provider: String): Boolean {
        if (providers.isEmpty()) return true
        val wanted = ProviderCatalog.namesFor(provider)
        if (providers.any { have -> wanted.any { w -> have == w || have.contains(w) } }) return true
        // a yt-dlp style node can load any website-based provider
        return ProviderCatalog.isSiteBased(provider) && providers.any { ProviderCatalog.isUniversal(it) }
    }

    /** Never includes the password. */
    fun toStatus(): Map<String, Any?> = linkedMapOf(
        "name" to config.name,
        "host" to config.host,
        "port" to config.port,
        "secure" to config.secure,
        "priority" to config.priority,
        "online" to online,
        "latencyMs" to latencyMs,
        "version" to version,
        "providers" to providers.sorted(),
        "plugins" to plugins,
        "lastError" to lastError,
        "lastCheck" to lastCheck
    )
}

@Service
class NodeManager(private val props: TunnelProperties) : InitializingBean {

    private val log = LoggerFactory.getLogger(NodeManager::class.java)
    private val mapper = ObjectMapper()
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    @Volatile
    var nodes: List<TunnelNode> = emptyList()
        private set

    override fun afterPropertiesSet() {
        nodes = buildNodes()
        log.info(
            "[Tunnel] by {} v{} loaded with {} node(s): {}",
            TunnelDefaults.AUTHOR, TunnelDefaults.VERSION, nodes.size,
            nodes.joinToString { it.config.name }
        )

        val every = props.refreshSeconds.coerceAtLeast(10)
        TunnelExecutors.scheduler.scheduleWithFixedDelay({
            runCatching { refreshAll() }.onFailure { log.warn("[Tunnel] refresh failed: {}", it.message) }
        }, 0, every, TimeUnit.SECONDS)
    }

    /** Built-in nodes first, then application.yml nodes (same name = override). */
    private fun buildNodes(): List<TunnelNode> {
        val merged = LinkedHashMap<String, NodeConfig>()
        if (props.useDefaultNodes) TunnelDefaults.nodes().forEach { merged[it.name] = it }
        props.nodes.forEach { merged[it.name.ifBlank { "${it.host}:${it.port}" }] = it }
        return merged.values
            .filter { it.enabled && it.host.isNotBlank() }
            .map { cfg ->
                if (cfg.name.isBlank()) cfg.name = "${cfg.host}:${cfg.port}"
                TunnelNode(cfg)
            }
    }

    // ---------------------------------------------------------------- provider discovery

    fun refreshAll() {
        nodes.map { n -> CompletableFuture.runAsync({ refresh(n) }, TunnelExecutors.pool) }
            .forEach { runCatching { it.get(20, TimeUnit.SECONDS) } }
    }

    /** Fetches /v4/info from the node: version, every source manager (provider) and plugin. */
    private fun refresh(node: TunnelNode) {
        val wasOnline = node.online
        val firstCheck = node.lastCheck == 0L
        val start = System.nanoTime()
        try {
            val json = mapper.readTree(get(node, "/v4/info"))
            node.latencyMs = (System.nanoTime() - start) / 1_000_000
            node.providers = json.path("sourceManagers").map { it.asText().lowercase() }.toSet()
            node.plugins = json.path("plugins").map { "${it.path("name").asText()}@${it.path("version").asText()}" }
            node.version = json.path("version").path("semver").let { if (it.isMissingNode) null else it.asText() }
            node.lastError = null
            node.online = true
            if (!wasOnline) {
                log.info(
                    "[Tunnel] node {} online ({} ms), providers: {}",
                    node.config.name, node.latencyMs, node.providers.sorted().joinToString()
                )
            }
        } catch (e: Exception) {
            node.online = false
            node.lastError = e.message ?: e.javaClass.simpleName
            if (wasOnline || firstCheck) {
                log.warn("[Tunnel] node {} offline: {}", node.config.name, node.lastError)
            }
        } finally {
            node.lastCheck = System.currentTimeMillis()
        }
    }

    // ---------------------------------------------------------------- node selection

    /** True if some online node has [provider]. Before any node was checked nothing is ruled out. */
    fun isAvailable(provider: String): Boolean {
        val known = nodes.filter { it.online && it.providers.isNotEmpty() }
        if (known.isEmpty()) return true
        return known.any { it.supports(provider) }
    }

    /** Every provider that at least one node offers. */
    fun allProviders(): Set<String> = nodes.flatMap { it.providers }.toSortedSet()

    /**
     * Nodes that can serve [provider] (null = any), best first: higher priority, then lower latency.
     * If every node looks offline they are all returned anyway, as a last resort.
     */
    fun candidates(provider: String?): List<TunnelNode> {
        val online = nodes.filter { it.online }
        val pool = if (online.isNotEmpty()) online else nodes
        val matching = if (provider == null) pool else pool.filter { it.supports(provider) }
        return matching.sortedWith(
            compareByDescending<TunnelNode> { it.config.priority }
                .thenBy { if (it.latencyMs < 0) Long.MAX_VALUE else it.latencyMs }
        )
    }

    fun markFailed(node: TunnelNode, e: Exception) {
        node.online = false
        node.lastError = e.message ?: e.javaClass.simpleName
    }

    // ---------------------------------------------------------------- remote calls

    fun get(node: TunnelNode, path: String): String {
        val request = HttpRequest.newBuilder(URI.create(node.baseUrl + path))
            .timeout(Duration.ofSeconds(props.requestTimeoutSeconds))
            .header("Authorization", node.config.password)
            .header("User-Agent", "Tunnel/${TunnelDefaults.VERSION} (by ${TunnelDefaults.AUTHOR})")
            .GET()
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw IllegalStateException("HTTP ${response.statusCode()} from node ${node.config.name}")
        }
        return response.body()
    }

    fun loadTracks(node: TunnelNode, identifier: String): JsonNode {
        val encoded = URLEncoder.encode(identifier, StandardCharsets.UTF_8)
        return mapper.readTree(get(node, "/v4/loadtracks?identifier=$encoded"))
    }
}
