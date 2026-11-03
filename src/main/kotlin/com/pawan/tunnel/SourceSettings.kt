package com.pawan.tunnel

import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * On/off switch for every source (youtube, spotify, ...).
 *
 *  - start-up values come from `plugins.tunnel.sources` (everything is ON by default)
 *  - changes made through POST /v4/tunnel/sources/{id}/enable|disable apply at once
 *    (they last until Lavalink restarts, edit application.yml to keep them)
 */
@Service
class SourceSettings(private val props: TunnelProperties) {

    private val runtime = ConcurrentHashMap<String, Boolean>()

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** Resolves a provider id, alias or search prefix (yt, ytsearch, youtube-music...) to a provider id. */
    fun idOf(name: String): String? {
        val n = norm(name)
        ProviderCatalog.all.firstOrNull { norm(it.id) == n }?.let { return it.id }
        ProviderCatalog.all.firstOrNull { p -> p.aliases.any { norm(it) == n } }?.let { return it.id }
        ProviderCatalog.byPrefix(name)?.let { return it }
        return null
    }

    /** null (can't be told) is treated as enabled. */
    fun isEnabled(provider: String?): Boolean {
        if (provider == null) return true
        val id = idOf(provider) ?: provider
        runtime[id]?.let { return it }
        for ((k, v) in props.sources) if (idOf(k) == id) return v
        return true
    }

    fun set(name: String, enabled: Boolean): String? {
        val id = idOf(name) ?: return null
        runtime[id] = enabled
        return id
    }

    fun resetAll() = runtime.clear()

    fun snapshot(): Map<String, Boolean> =
        ProviderCatalog.all.associateTo(linkedMapOf()) { it.id to isEnabled(it.id) }
}
