package com.pawan.tunnel

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioItem
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioReference
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.DataInput
import java.io.DataOutput
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Handles identifiers that start with `tunnel:`, and (interceptSearch: true) normal search
 * identifiers such as `scsearch:Alan Walker Faded`, so users never need a node of their own.
 *
 *   tunnel:ytsearch:never gonna give you up
 *   tunnel:https://open.spotify.com/track/...
 *
 * The identifier is sent to a remote node that has the needed provider (nodes and their
 * providers are fetched automatically). The remote answers with track info, then this
 * server loads the real track URL with its own source managers and plays it locally.
 */
@Service
class TunnelSourceManager(
    private val nodes: NodeManager,
    private val props: TunnelProperties,
    private val asd: AdvancedSearchDriver,
    private val sources: SourceSettings
) : AudioSourceManager {

    private val log = LoggerFactory.getLogger(TunnelSourceManager::class.java)
    private val mapper = ObjectMapper()

    override fun getSourceName(): String = "tunnel"

    /** Identifiers Tunnel is loading itself right now: they must not be picked up again. */
    private val internal = ConcurrentHashMap<String, Int>()

    /** A plain `xxsearch:` identifier (no `tunnel:`) that Tunnel should answer through its nodes. */
    private fun shouldIntercept(identifier: String): Boolean {
        if (!props.interceptSearch) return false
        val colon = identifier.indexOf(':')
        if (colon <= 0 || identifier.substring(colon + 1).isBlank()) return false
        val id = ProviderCatalog.byPrefix(identifier.substring(0, colon).trim()) ?: return false
        return ProviderCatalog.byId(id)?.category != "tts"
    }

    override fun loadItem(manager: AudioPlayerManager, reference: AudioReference): AudioItem? {
        val identifier = reference.identifier ?: return null
        if (internal.containsKey(identifier)) return null // Tunnel's own lookup, let the local sources handle it

        if (identifier.startsWith(TunnelDefaults.PREFIX)) {
            return handle(manager, identifier.removePrefix(TunnelDefaults.PREFIX).trim(), false)
        }
        if (!shouldIntercept(identifier)) return null // not ours
        return handle(manager, identifier.trim(), true)
    }

    private fun handle(manager: AudioPlayerManager, raw: String, intercepted: Boolean): AudioItem? {
        if (raw.isEmpty()) return AudioReference.NO_TRACK

        // a switched-off source stays off, also for normal searches
        val firstProvider = QueryDetector.detect(raw).provider ?: ProviderResolver.resolve(raw)
        if (intercepted && !sources.isEnabled(firstProvider)) {
            throw FriendlyException("Tunnel: the '$firstProvider' source is disabled", FriendlyException.Severity.COMMON, null)
        }

        if (!intercepted) return load(manager, raw)

        // normal search: nodes first, then (optionally) this server's own sources
        return try {
            val item = load(manager, raw)
            if (item === AudioReference.NO_TRACK && props.localFallback) null else item
        } catch (e: FriendlyException) {
            if (props.localFallback) {
                log.warn("[Tunnel] nodes failed for '{}', using local sources: {}", raw, e.message)
                null
            } else throw e
        }
    }

    private fun load(manager: AudioPlayerManager, raw: String): AudioItem? {
        // Advanced Search Driver: auto-detect what was typed
        val detection = QueryDetector.detect(raw)
        val useAsd = props.asd.enabled &&
            (detection.type == QueryType.TEXT || detection.type == QueryType.FORCED || detection.type == QueryType.ISRC)

        if (useAsd) {
            val found = asd.search(detection).take(props.searchResults.coerceAtLeast(1))
            val tracks = resolveAll(manager, found.map { it.track })
            return if (tracks.isEmpty()) AudioReference.NO_TRACK
            else BasicAudioPlaylist("Search results for: ${detection.text}", tracks, null, true)
        }

        val query = detection.query

        // source switch: youtube = false, spotify = false, ...
        val wanted = detection.provider ?: ProviderResolver.resolve(query)
        if (!sources.isEnabled(wanted)) {
            throw FriendlyException(
                "Tunnel: the '$wanted' source is disabled",
                FriendlyException.Severity.COMMON,
                null
            )
        }

        val root = remoteLoad(query) ?: return AudioReference.NO_TRACK
        val data = root.path("data")

        return when (root.path("loadType").asText()) {
            "track" -> resolve(manager, data) ?: AudioReference.NO_TRACK

            "search" -> {
                val tracks = resolveAll(manager, data.take(props.searchResults.coerceAtLeast(1)))
                if (tracks.isEmpty()) AudioReference.NO_TRACK
                else BasicAudioPlaylist("Search results for: $query", tracks, null, true)
            }

            "playlist" -> {
                val name = data.path("info").path("name").asText("Tunnel playlist")
                val tracks = resolveAll(manager, data.path("tracks").take(props.maxPlaylistTracks.coerceAtLeast(1)))
                if (tracks.isEmpty()) AudioReference.NO_TRACK
                else BasicAudioPlaylist(name, tracks, null, false)
            }

            else -> AudioReference.NO_TRACK
        }
    }

    // ---------------------------------------------------------------- remote side

    /**
     * Asks the nodes that have the needed provider.
     *  - multiNode on: all of them at the same time. Search results from every node are merged
     *    and duplicates removed; for a single track or playlist the best node's answer is used.
     *  - multiNode off: one by one until a node answers.
     */
    private fun remoteLoad(query: String): JsonNode? {
        val candidates = nodes.candidates(ProviderResolver.resolve(query))
        if (props.multiNode && candidates.size > 1) {
            val limit = if (props.maxSearchNodes > 0) props.maxSearchNodes else candidates.size
            val answers = nodes.loadParallel(candidates.take(limit), query)

            if (answers.ok.isEmpty()) {
                if (answers.sawEmpty || answers.lastError == null) return null
                throw FriendlyException("Tunnel: all nodes failed (${answers.lastError})", FriendlyException.Severity.SUSPICIOUS, null)
            }

            val searches = answers.ok.filter { it.second.path("loadType").asText() == "search" }
            if (searches.size > 1) {
                val merged = mapper.createArrayNode()
                Dedupe.distinct(searches.flatMap { it.second.path("data").toList() }) { it }.forEach { merged.add(it) }
                return mapper.createObjectNode().also {
                    it.put("loadType", "search")
                    it.set<JsonNode>("data", merged)
                }
            }
            return answers.ok.first().second
        }
        return remoteLoadSequential(query)
    }

    /** Tries every node that has the needed provider until one answers. */
    private fun remoteLoadSequential(query: String): JsonNode? {
        val provider = ProviderResolver.resolve(query)
        val candidates = nodes.candidates(provider)
        if (candidates.isEmpty()) {
            throw FriendlyException(
                "Tunnel: no node offers the '$provider' provider",
                FriendlyException.Severity.COMMON,
                null
            )
        }

        var lastError: String? = null
        var sawEmpty = false

        for (node in candidates) {
            try {
                val root = nodes.loadTracks(node, query)
                when (root.path("loadType").asText()) {
                    "track", "search", "playlist" -> return root
                    "error" -> lastError = root.path("data").path("message").asText("remote error")
                    else -> sawEmpty = true
                }
            } catch (e: Exception) {
                nodes.markFailed(node, e)
                lastError = e.message
                log.warn("[Tunnel] node {} failed for '{}': {}", node.config.name, query, e.message)
            }
        }

        if (sawEmpty || lastError == null) return null
        throw FriendlyException("Tunnel: all nodes failed ($lastError)", FriendlyException.Severity.SUSPICIOUS, null)
    }

    // ---------------------------------------------------------------- local side

    private fun resolveAll(manager: AudioPlayerManager, items: List<JsonNode>): List<AudioTrack> =
        Dedupe.distinct(items.filter { sourceAllowed(it) }) { it }
            .map { n -> CompletableFuture.supplyAsync({ runCatching { resolve(manager, n) }.getOrNull() }, TunnelExecutors.pool) }
            .mapNotNull { runCatching { it.get(40, TimeUnit.SECONDS) }.getOrNull() }
            .let { list -> list.distinctBy { it.info.uri ?: it.identifier } } // same song never twice in the queue

    /** Drops remote tracks that belong to a source you switched off. */
    private fun sourceAllowed(track: JsonNode): Boolean {
        val uri = track.path("info").path("uri").asText("")
        if (uri.isBlank()) return true
        return sources.isEnabled(ProviderResolver.resolve(uri))
    }

    /** Turns one remote track into a local, playable track. */
    private fun resolve(manager: AudioPlayerManager, track: JsonNode): AudioTrack? {
        val info = track.path("info")
        val uri = info.path("uri").asText("")
        val title = info.path("title").asText("")
        val author = info.path("author").asText("")

        if (uri.isNotBlank()) loadOne(manager, uri)?.let { return it }

        // The URL can't be played here (e.g. a provider this server lacks): search for it instead
        val fallbackOn = props.fallbackSearch.isNotBlank() &&
            sources.isEnabled(ProviderResolver.resolve(props.fallbackSearch + "x"))
        if (fallbackOn && title.isNotBlank()) {
            return loadOne(manager, "${props.fallbackSearch}$title $author".trim())
        }
        return null
    }

    private fun loadOne(manager: AudioPlayerManager, identifier: String): AudioTrack? {
        val future = CompletableFuture<AudioTrack?>()
        internal.merge(identifier, 1) { a, b -> a + b }

        manager.loadItem(identifier, object : AudioLoadResultHandler {
            override fun trackLoaded(track: AudioTrack) {
                future.complete(track)
            }

            override fun playlistLoaded(playlist: AudioPlaylist) {
                future.complete(playlist.selectedTrack ?: playlist.tracks.firstOrNull())
            }

            override fun noMatches() {
                future.complete(null)
            }

            override fun loadFailed(exception: FriendlyException) {
                future.complete(null)
            }
        })

        return try {
            runCatching { future.get(25, TimeUnit.SECONDS) }.getOrNull()
        } finally {
            internal.compute(identifier) { _, v -> if (v == null || v <= 1) null else v - 1 }
        }
    }

    // Tunnel hands back the local tracks (YouTube, HTTP, ...), which are encoded and
    // decoded by their own source managers, so there is nothing to do here.
    override fun isTrackEncodable(track: AudioTrack): Boolean = false

    override fun encodeTrack(track: AudioTrack, output: DataOutput) {
        // not used
    }

    override fun decodeTrack(trackInfo: AudioTrackInfo, input: DataInput): AudioTrack? = null

    override fun shutdown() {
        // nothing to clean up
    }
}
