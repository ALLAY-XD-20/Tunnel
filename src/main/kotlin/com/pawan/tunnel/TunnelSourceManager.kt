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

    /** Needed later, when a lazy track is played and its real audio has to be loaded. */
    @Volatile private var playerManager: AudioPlayerManager? = null

    override fun loadItem(manager: AudioPlayerManager, reference: AudioReference): AudioItem? {
        playerManager = manager
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

        // normal search: nodes first, then other search providers on the nodes,
        // then (optionally) this server's own sources
        var failure: FriendlyException? = null
        val first: AudioItem? = try {
            load(manager, raw)
        } catch (e: FriendlyException) {
            failure = e
            null
        }
        if (first != null && first !== AudioReference.NO_TRACK) return first

        alternateSearch(manager, raw)?.let { return it }

        if (props.localFallback) {
            if (failure != null) log.warn("[Tunnel] nodes failed for '{}', using local sources: {}", raw, failure.message)
            return null
        }
        if (failure != null) throw failure
        return AudioReference.NO_TRACK
    }

    /**
     * `ytsearch:song` failed on every node (a node's YouTube may be broken): ask the nodes
     * again with other search providers they have (scsearch, spsearch, ...), up to 3.
     */
    private fun alternateSearch(manager: AudioPlayerManager, raw: String): AudioItem? {
        val colon = raw.indexOf(':')
        if (colon <= 0) return null
        val prefix = raw.substring(0, colon).trim().lowercase()
        val text = raw.substring(colon + 1).trim()
        if (text.isEmpty() || text.startsWith("http", ignoreCase = true)) return null

        var tried = 0
        for (alt in props.asd.sources) {
            if (tried >= 3) break
            val a = alt.trim().lowercase()
            if (a == prefix || ProviderCatalog.byPrefix(a) == null) continue
            val id = ProviderCatalog.byPrefix(a) ?: continue
            if (!sources.isEnabled(id) || !nodes.isAvailable(id)) continue
            tried++
            try {
                val item = load(manager, "$a:$text")
                if (item != null && item !== AudioReference.NO_TRACK) {
                    log.info("[Tunnel] '{}' had no result, answered by '{}:' instead", raw, a)
                    return item
                }
            } catch (e: Exception) {
                log.debug("[Tunnel] alternate {} failed: {}", a, e.message)
            }
        }
        return null
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
        if (props.autoFix) nodes.healNow()
        val allCandidates = nodes.candidates(ProviderResolver.resolve(query))
        // search in parallel on healthy nodes; if none gives an answer, try the rest one by one
        val candidates = allCandidates.filter { it.online && !it.recentlyFailed() }.ifEmpty { allCandidates }
        if (props.multiNode && candidates.size > 1) {
            val limit = if (props.maxSearchNodes > 0) props.maxSearchNodes else candidates.size
            val answers = nodes.loadParallel(candidates.take(limit), query)

            if (answers.ok.isEmpty()) {
                if (answers.sawEmpty || answers.lastError == null) return null
                if (allCandidates.size > candidates.size) return remoteLoadSequential(query)
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
                log.warn("[Tunnel] node {} failed for '{}': {}", node.config.name, query, TunnelHints.withHint(e.message))
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

    /** Turns one remote track into a track: lazy (info now, audio when played) or eager. */
    private fun resolve(manager: AudioPlayerManager, track: JsonNode): AudioTrack? {
        val info = track.path("info")
        val uri = info.path("uri").asText("")
        val title = info.path("title").asText("")
        if (title.isBlank() && uri.isBlank()) return null

        val trackInfo = AudioTrackInfo(
            title.ifBlank { "Unknown title" },
            info.path("author").asText("Unknown artist"),
            info.path("length").asLong(0),
            info.path("identifier").asText(uri.ifBlank { title }),
            info.path("isStream").asBoolean(false),
            uri.ifBlank { null },
            info.path("artworkUrl").asText("").ifBlank { null },
            info.path("isrc").asText("").ifBlank { null }
        )

        if (props.lazyResolve) return TunnelAudioTrack(trackInfo, this)
        return playable(trackInfo)
    }

    /**
     * Finds real audio for a track on THIS server:
     *  1. the track's own link (direct streams, SoundCloud, YouTube ...)
     *  2. otherwise "title author" on each mirror in fallbackSearch(es) until one plays
     * Every failed step is logged, so it is clear WHY a track could not be played.
     */
    fun playable(info: AudioTrackInfo): AudioTrack? {
        val manager = playerManager ?: return null
        val uri = info.uri ?: ""
        val label = "${info.title} - ${info.author}".trim(' ', '-')

        if (uri.isNotBlank()) {
            loadOne(manager, uri)?.let { return it }
            log.info("[Tunnel] '{}' could not be played from its own link here: {}", label, uri)
        }

        if (info.title.isNotBlank()) {
            val mirrors = (listOf(props.fallbackSearch) + props.fallbackSearches)
                .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            for (prefix in mirrors) {
                if (!sources.isEnabled(ProviderResolver.resolve(prefix + "x"))) continue
                loadOne(manager, "$prefix${info.title} ${info.author}".trim())?.let {
                    log.info("[Tunnel] '{}' is played through the '{}' mirror", label, prefix)
                    return it
                }
            }
        }
        log.warn("[Tunnel] could not play '{}': neither its link nor any mirror worked on this server. Install a working youtube-source plugin.", label)
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
    override fun isTrackEncodable(track: AudioTrack): Boolean = track is TunnelAudioTrack

    override fun encodeTrack(track: AudioTrack, output: DataOutput) {
        // not used
    }

    override fun decodeTrack(trackInfo: AudioTrackInfo, input: DataInput): AudioTrack? = TunnelAudioTrack(trackInfo, this)

    override fun shutdown() {
        // nothing to clean up
    }
}
