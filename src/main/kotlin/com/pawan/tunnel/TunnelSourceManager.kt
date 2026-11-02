package com.pawan.tunnel

import com.fasterxml.jackson.databind.JsonNode
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Handles identifiers that start with `tunnel:`.
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
    private val asd: AdvancedSearchDriver
) : AudioSourceManager {

    private val log = LoggerFactory.getLogger(TunnelSourceManager::class.java)

    override fun getSourceName(): String = "tunnel"

    override fun loadItem(manager: AudioPlayerManager, reference: AudioReference): AudioItem? {
        val identifier = reference.identifier ?: return null
        if (!identifier.startsWith(TunnelDefaults.PREFIX)) return null // not ours

        val raw = identifier.removePrefix(TunnelDefaults.PREFIX).trim()
        if (raw.isEmpty()) return AudioReference.NO_TRACK

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

    /** Tries every node that has the needed provider until one answers. */
    private fun remoteLoad(query: String): JsonNode? {
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
        items
            .map { n -> CompletableFuture.supplyAsync({ runCatching { resolve(manager, n) }.getOrNull() }, TunnelExecutors.pool) }
            .mapNotNull { runCatching { it.get(40, TimeUnit.SECONDS) }.getOrNull() }

    /** Turns one remote track into a local, playable track. */
    private fun resolve(manager: AudioPlayerManager, track: JsonNode): AudioTrack? {
        val info = track.path("info")
        val uri = info.path("uri").asText("")
        val title = info.path("title").asText("")
        val author = info.path("author").asText("")

        if (uri.isNotBlank()) loadOne(manager, uri)?.let { return it }

        // The URL can't be played here (e.g. a provider this server lacks): search for it instead
        if (props.fallbackSearch.isNotBlank() && title.isNotBlank()) {
            return loadOne(manager, "${props.fallbackSearch}$title $author".trim())
        }
        return null
    }

    private fun loadOne(manager: AudioPlayerManager, identifier: String): AudioTrack? {
        val future = CompletableFuture<AudioTrack?>()

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

        return runCatching { future.get(25, TimeUnit.SECONDS) }.getOrNull()
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
