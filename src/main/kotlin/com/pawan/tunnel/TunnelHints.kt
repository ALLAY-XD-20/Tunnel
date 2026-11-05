package com.pawan.tunnel

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/** Turns a raw error message into a plain-language cause and fix. */
object TunnelHints {

    fun explain(message: String?): String? {
        val m = message ?: return null
        return when {
            m.contains("AbstractMethodError") || m.contains("NoSuchMethodError") ->
                "a plugin on that node was built for a different Lavalink/Lavaplayer version. Update or remove that plugin on the node."
            m.contains("HTTP 400") ->
                "that port is not a Lavalink REST API (wrong port or a proxy in front), or the node needs https. Set secure: true or fix the port."
            m.contains("HTTP 401") || m.contains("HTTP 403") ->
                "wrong password for that node. Check the password in the node settings."
            m.contains("HTTP 404") ->
                "that is not a Lavalink v4 node (old Lavalink v3, or a wrong path or port)."
            m.contains("HTTP 429") ->
                "that node is rate-limiting you. Slow down or add another node."
            m.contains("HTTP 500") ->
                "that node's Lavalink crashed while handling the request. Open the NODE's console: a broken plugin or source (YouTube/Spotify) is the usual cause."
            m.contains("HTTP 502") || m.contains("HTTP 503") || m.contains("HTTP 504") ->
                "the node (or the proxy in front of it) is down or restarting."
            m.contains("timed out", ignoreCase = true) ->
                "the host or port is unreachable (node is off, wrong port, or a firewall blocks it)."
            m.contains("refused", ignoreCase = true) ->
                "nothing is listening on that host and port. Start the node or fix the port."
            m.contains("UnknownHost", ignoreCase = true) || m.contains("nodename nor servname", ignoreCase = true) ->
                "the host name does not resolve. Check the spelling of the host."
            m.contains("SSL", ignoreCase = true) || m.contains("PKIX", ignoreCase = true) || m.contains("certificate", ignoreCase = true) ->
                "TLS/certificate problem. The node may not be https, or its certificate is invalid."
            else -> null
        }
    }

    fun withHint(message: String?): String {
        val text = message ?: "unknown error"
        val hint = explain(message) ?: return text
        return "$text  ->  $hint"
    }
}

/** Counters used by the auto-debug report. */
object TunnelDebugState {
    private val log = LoggerFactory.getLogger(TunnelDebugState::class.java)

    /** source name -> how many tracks of it arrived here but cannot be decoded here */
    val foreignTracks = ConcurrentHashMap<String, Int>()
    private val lastLogged = ConcurrentHashMap<String, Long>()

    /** The last self-fixes Tunnel applied (newest last). */
    val fixes = ConcurrentLinkedDeque<String>()

    fun fixed(message: String) {
        log.info("[Tunnel][autofix] {}", message)
        fixes.addLast("${java.time.Instant.now()}  $message")
        while (fixes.size > 20) fixes.pollFirst()
    }

    /** A client sent a track that belongs to another Lavalink (e.g. resolved on node-1, played here). */
    fun foreignTrack(source: String, adopted: Boolean = false) {
        foreignTracks.merge(source, 1) { a, b -> a + b }
        val now = System.currentTimeMillis()
        val last = lastLogged[source] ?: 0L
        if (now - last > 60_000) {
            lastLogged[source] = now
            if (adopted) {
                fixed(
                    "a '$source' track came from another Lavalink; it is played here through a mirror instead of failing. " +
                        "For best results make the bot search AND play on this same server."
                )
            } else {
                log.warn(
                    "[Tunnel][debug] a '{}' track was sent here but this server has no real '{}' source, so it cannot be decoded " +
                        "(\"Failed to decode track\"). The bot searched on one Lavalink and played on another. " +
                        "Make the bot search AND play on this same server (use only this node in the bot).",
                    source, source
                )
            }
        }
    }
}
