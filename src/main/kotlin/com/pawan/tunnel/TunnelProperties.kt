package com.pawan.tunnel

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/** One remote Lavalink server. */
class NodeConfig {
    var name: String = ""
    var host: String = ""
    var port: Int = 2333
    var password: String = ""
    var secure: Boolean = false
    var priority: Int = 0
    var enabled: Boolean = true
}

class AutoUpdateConfig {
    var enabled: Boolean = true

    /** github = jar attached to a GitHub release (works for closed-source releases), jitpack = built by JitPack */
    var source: String = "github"
    var owner: String = "pawan"

    /** the PUBLIC repo that only holds releases (github) or the code repo (jitpack) */
    var repo: String = "tunnel-releases"

    /** jar name prefix. Empty = "tunnel" (github) or the repo name (jitpack) */
    var artifact: String = ""

    /** delete old Tunnel jars after an update, instead of keeping them as *.jar.bak */
    var deleteOldVersions: Boolean = true
    var checkIntervalMinutes: Long = 60
    var pluginsDir: String = "./plugins"

    /** Lavalink's application.yml. If Tunnel is listed there as a dependency, its version is rewritten. */
    var configFile: String = "./application.yml"
    var updateConfigFile: Boolean = true

    /** Exit Lavalink after an update so a process manager (systemd, Docker, panel) starts the new version. */
    var restartAfterUpdate: Boolean = false
    var restartDelaySeconds: Long = 60
}

/** Advanced Search Driver settings. */
class AsdConfig {
    var enabled: Boolean = true

    /** merge = search several providers at once and rank everything; first = stop at the first provider with results */
    var mode: String = "merge"

    /** search prefixes to use, in order of preference (only ones your online nodes have are used) */
    var sources: MutableList<String> =
        mutableListOf(
            "ytsearch", "scsearch", "spsearch", "dzsearch", "amsearch", "ytmsearch",
            "tdsearch", "qbsearch", "jssearch", "ymsearch", "bcsearch", "pdsearch", "gnsearch",
            "adsearch", "mcsearch", "audiomacksearch", "neteasesearch", "vmsearch", "twsearch"
        )

    var maxProviders: Int = 3
    var minScore: Double = 0.2
    var timeoutSeconds: Long = 20
}

/** Bound from `plugins.tunnel` in Lavalink's application.yml. */
@Component
@ConfigurationProperties(prefix = "plugins.tunnel")
class TunnelProperties {
    var useDefaultNodes: Boolean = true
    var nodes: MutableList<NodeConfig> = mutableListOf()
    var refreshSeconds: Long = 60
    var requestTimeoutSeconds: Long = 15
    var searchResults: Int = 5
    var maxPlaylistTracks: Int = 100
    var fallbackSearch: String = "ytsearch:"

    /**
     * More mirrors, tried in this order when a track's own link cannot be played on this server
     * (Spotify / Apple / Deezer links have no audio of their own). Only prefixes this server has work.
     */
    /**
     * true = a search returns the nodes' track info at once and the audio is found when the track
     * is played (fast, never empty because of a slow local source). false = every result is
     * loaded on this server during the search (old behaviour).
     */
    var lazyResolve: Boolean = true

    var fallbackSearches: MutableList<String> =
        mutableListOf("ytsearch:", "ytmsearch:", "scsearch:", "jssearch:", "dzsearch:")
    /** true = a search asks several nodes at the same time and merges the results (duplicates removed) */
    var multiNode: Boolean = true

    /**
     * true = a normal search such as `scsearch:Alan Walker Faded` or `ytsearch:...` sent to Lavalink
     * (no `tunnel:` in front) is answered by Tunnel's nodes. Users never add their own node.
     */
    var interceptSearch: Boolean = true

    /** true = every enabled source is listed in /v4/info "sourceManagers", like on a normal Lavalink */
    var exposeSources: Boolean = true

    /** true = if the nodes can't answer an intercepted search, this server's own sources try it */
    var localFallback: Boolean = true

    /** how many nodes one search may ask at once. 0 = all nodes that have the source */
    var maxSearchNodes: Int = 0

    /**
     * Source switches: provider id -> true/false. Every built-in source is ON by default.
     * Example:  sources: { youtube: true, spotify: true, tiktok: false }
     * Ids are listed by GET /v4/tunnel/sources. A source that is not listed stays ON.
     */
    var sources: MutableMap<String, Boolean> =
        ProviderCatalog.all.associateTo(linkedMapOf()) { it.id to true }

    var autoUpdate: AutoUpdateConfig = AutoUpdateConfig()
    var asd: AsdConfig = AsdConfig()
}
