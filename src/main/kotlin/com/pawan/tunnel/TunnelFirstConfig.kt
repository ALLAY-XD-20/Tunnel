package com.pawan.tunnel

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import dev.arbjerg.lavalink.api.AudioPlayerManagerConfiguration
import org.slf4j.LoggerFactory
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager as Apm
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager
import com.sedmelluq.discord.lavaplayer.track.AudioItem
import com.sedmelluq.discord.lavaplayer.track.AudioReference
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import org.springframework.stereotype.Service
import java.io.DataInput
import java.io.DataOutput

/**
 * Puts Tunnel at the front of Lavaplayer's source manager list, so a normal
 * `scsearch:` / `ytsearch:` / `spsearch:` ... search is answered by Tunnel's nodes
 * before this server's own sources get a chance (they stay as fallback).
 */
@Service
class TunnelFirstConfig(
    private val tunnel: TunnelSourceManager,
    private val props: TunnelProperties,
    private val sources: SourceSettings
) : AudioPlayerManagerConfiguration {

    /** Only a name for /v4/info. It never loads anything; Tunnel (first in line) does the work. */
    private class NameOnly(private val name: String) : AudioSourceManager {
        override fun getSourceName(): String = name
        override fun loadItem(manager: Apm, reference: AudioReference): AudioItem? = null
        override fun isTrackEncodable(track: AudioTrack): Boolean = false
        override fun encodeTrack(track: AudioTrack, output: DataOutput) {}
        override fun decodeTrack(trackInfo: AudioTrackInfo, input: DataInput): AudioTrack? = null
        override fun shutdown() {}
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** Lists every enabled source in /v4/info (sourceManagers), as a normal Lavalink would. */
    private fun expose(manager: AudioPlayerManager) {
        if (!props.exposeSources) return
        val have = manager.sourceManagers.map { norm(it.sourceName) }.toMutableSet()
        var added = 0
        for (p in ProviderCatalog.all) {
            if (p.category == "tts" || p.id == "http") continue
            if (!sources.isEnabled(p.id)) continue
            val n = norm(p.id)
            if (have.any { it == n || it.contains(n) || n.contains(it) }) continue
            manager.registerSourceManager(NameOnly(p.id))
            have += n
            added++
        }
        log.info("[Tunnel] {} source(s) listed in /v4/info", added)
    }

    private val log = LoggerFactory.getLogger(TunnelFirstConfig::class.java)

    override fun configure(manager: AudioPlayerManager): AudioPlayerManager {
        expose(manager)
        if (!props.interceptSearch) return manager
        try {
            var type: Class<*>? = manager.javaClass
            while (type != null) {
                val field = runCatching { type!!.getDeclaredField("sourceManagers") }.getOrNull()
                if (field != null) {
                    field.isAccessible = true
                    @Suppress("UNCHECKED_CAST")
                    val list = field.get(manager) as MutableList<Any?>
                    synchronized(list) {
                        list.remove(tunnel)
                        list.add(0, tunnel)
                    }
                    log.info("[Tunnel] searches like scsearch:/ytsearch:/spsearch: now go through Tunnel's nodes first")
                    return manager
                }
                type = type.superclass
            }
            log.warn("[Tunnel] could not move Tunnel to the front; only tunnel: searches use the nodes")
        } catch (e: Exception) {
            log.warn("[Tunnel] could not move Tunnel to the front: {}", e.message)
        }
        return manager
    }
}
