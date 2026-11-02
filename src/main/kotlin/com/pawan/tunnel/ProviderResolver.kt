package com.pawan.tunnel

import java.net.URI

/**
 * Works out which provider an identifier needs, using the built-in [ProviderCatalog],
 * so Tunnel only asks nodes that actually have that provider.
 */
object ProviderResolver {

    /** Returns the provider id, or null when it can't be told (any node is then fine). */
    fun resolve(identifier: String): String? {
        val id = identifier.trim()

        val colon = id.indexOf(':')
        if (colon > 0) {
            ProviderCatalog.byPrefix(id.substring(0, colon))?.let { return it }
        }

        if (id.startsWith("http://") || id.startsWith("https://")) {
            val host = runCatching { URI(id).host?.lowercase() }.getOrNull() ?: return null
            ProviderCatalog.byHost(host)?.let { return it }
            return "http"
        }
        return null
    }
}
