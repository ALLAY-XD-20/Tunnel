package com.pawan.tunnel

import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** One remote track found by the driver, with its score. */
class AsdResult(
    val track: JsonNode,
    val provider: String,
    val prefix: String,
    val node: String,
    val order: Int,
    val boost: Double
) {
    var score: Double = 0.0

    fun toMap(): Map<String, Any?> {
        val info = track.path("info")
        return linkedMapOf(
            "title" to info.path("title").asText(""),
            "author" to info.path("author").asText(""),
            "uri" to info.path("uri").asText(""),
            "length" to info.path("length").asLong(0),
            "isrc" to info.path("isrc").asText(null),
            "provider" to provider,
            "prefix" to prefix,
            "node" to node,
            "score" to Math.round(score * 1000) / 1000.0
        )
    }
}

/**
 * Advanced Search Driver (ASD).
 *
 * For plain-text searches (and ISRC codes) it:
 *  1. picks the search providers that your online nodes actually have,
 *  2. searches them (in parallel, or one by one until something is found),
 *  3. scores every result, removes duplicates, and returns the best first.
 */
@Service
class AdvancedSearchDriver(
    private val nodes: NodeManager,
    private val props: TunnelProperties
) {
    private val log = LoggerFactory.getLogger(AdvancedSearchDriver::class.java)

    private class Step(val prefix: String, val provider: String, val text: String, val boost: Double, val order: Int)

    fun search(d: Detection): List<AsdResult> {
        val cfg = props.asd
        if (d.text.isBlank()) return emptyList()

        // ISRC codes: ask the providers that can look one up directly first
        if (d.type == QueryType.ISRC) {
            val steps = mutableListOf<Step>()
            for (prefix in listOf("dzisrc", "qbisrc")) {
                val id = ProviderCatalog.byPrefix(prefix) ?: continue
                if (nodes.isAvailable(id)) steps += Step(prefix, id, d.text, 1.0, steps.size)
            }
            if (steps.isNotEmpty()) {
                val found = run(steps, cfg)
                if (found.isNotEmpty()) return rank(found, d.text, cfg)
            }
        }

        val steps = regularSteps(d.text, cfg)
        if (steps.isEmpty()) {
            log.warn("[Tunnel] ASD: none of the configured search providers is available on an online node")
            return emptyList()
        }
        return rank(run(steps, cfg), d.text, cfg)
    }

    /** Configured search prefixes, one per provider, only those some online node has. */
    private fun regularSteps(text: String, cfg: AsdConfig): List<Step> {
        val chosen = cfg.sources
            .mapNotNull { prefix -> ProviderCatalog.byPrefix(prefix)?.let { id -> prefix.lowercase() to id } }
            .distinctBy { it.second }
            .filter { nodes.isAvailable(it.second) }
            .take(cfg.maxProviders.coerceAtLeast(1))

        return chosen.mapIndexed { i, (prefix, id) -> Step(prefix, id, text, 0.0, i) }
    }

    private fun run(steps: List<Step>, cfg: AsdConfig): List<AsdResult> {
        if (cfg.mode.equals("first", ignoreCase = true)) {
            for (step in steps) {
                val r = runStep(step)
                if (r.isNotEmpty()) return r
            }
            return emptyList()
        }

        // merge: every provider at the same time
        return steps
            .map { s -> CompletableFuture.supplyAsync({ runStep(s) }, TunnelExecutors.pool) }
            .flatMap { runCatching { it.get(cfg.timeoutSeconds.coerceAtLeast(5), TimeUnit.SECONDS) }.getOrDefault(emptyList()) }
    }

    /** Searches one provider, trying every node that has it until one gives results. */
    private fun runStep(step: Step): List<AsdResult> {
        for (node in nodes.candidates(step.provider)) {
            try {
                val root = nodes.loadTracks(node, "${step.prefix}:${step.text}")
                val data = root.path("data")
                val tracks: List<JsonNode> = when (root.path("loadType").asText()) {
                    "search" -> data.toList()
                    "track" -> listOf(data)
                    "playlist" -> data.path("tracks").toList()
                    else -> emptyList()
                }
                if (tracks.isNotEmpty()) {
                    return tracks.take(15).map { AsdResult(it, step.provider, step.prefix, node.config.name, step.order, step.boost) }
                }
            } catch (e: Exception) {
                nodes.markFailed(node, e)
                log.warn("[Tunnel] ASD: node {} failed on {}: {}", node.config.name, step.prefix, e.message)
            }
        }
        return emptyList()
    }

    // ---------------------------------------------------------------- ranking

    private val splitter = Regex("[^\\p{L}\\p{N}]+")

    /** Words that usually mean "not the original song", unless the user typed them. */
    private val noise = setOf(
        "cover", "remix", "live", "karaoke", "instrumental", "slowed", "reverb", "nightcore",
        "sped", "8d", "boosted", "mashup", "reaction", "tutorial", "parody", "loop", "hour", "hours"
    )

    private val junk = setOf(
        "official", "video", "audio", "music", "lyrics", "lyric", "hd", "hq", "4k", "mv", "visualizer", "topic"
    )

    private fun tokens(s: String): List<String> =
        s.lowercase().split(splitter).filter { it.isNotBlank() }

    private fun rank(results: List<AsdResult>, text: String, cfg: AsdConfig): List<AsdResult> {
        val queryTokens = tokens(text)
        val querySet = queryTokens.toSet()
        val queryNorm = queryTokens.joinToString(" ")
        val total = ((results.maxOfOrNull { it.order } ?: 0) + 1).toDouble()

        results.forEach { it.score = score(it, querySet, queryNorm, total) }

        // remove duplicates (same ISRC, or same title and about the same length), keep the best
        val best = LinkedHashMap<String, AsdResult>()
        for (r in results.sortedByDescending { it.score }) best.putIfAbsent(dedupeKey(r), r)

        val sorted = best.values.toList()
        val passing = sorted.filter { it.score >= cfg.minScore }
        return if (passing.isNotEmpty()) passing else sorted.take(3)
    }

    private fun score(r: AsdResult, querySet: Set<String>, queryNorm: String, total: Double): Double {
        val info = r.track.path("info")
        val titleTokens = tokens(info.path("title").asText(""))
        val authorTokens = tokens(info.path("author").asText(""))
        val hay = (titleTokens + authorTokens).toSet()

        val coverage = if (querySet.isEmpty()) 0.0 else querySet.count { it in hay }.toDouble() / querySet.size

        val exact = if (
            titleTokens.joinToString(" ") == queryNorm ||
            (authorTokens + titleTokens).joinToString(" ") == queryNorm ||
            (titleTokens + authorTokens).joinToString(" ") == queryNorm
        ) 0.25 else 0.0

        val noiseHits = noise.count { it in titleTokens && it !in querySet }
        val penalty = (noiseHits * 0.2).coerceAtMost(0.6)

        val length = info.path("length").asLong(0)
        val duration = when {
            info.path("isStream").asBoolean(false) -> -0.2
            length in 1L..29_999L -> -0.25
            length > 1_200_000L -> -0.2
            else -> 0.0
        }

        val providerBonus = 0.1 * (1.0 - r.order.toDouble() / total)
        val isrcBonus = if (info.path("isrc").asText("").isNotBlank()) 0.03 else 0.0

        return coverage + exact - penalty + duration + providerBonus + isrcBonus + r.boost
    }

    private fun dedupeKey(r: AsdResult): String {
        val info = r.track.path("info")
        val isrc = info.path("isrc").asText("")
        if (isrc.isNotBlank()) return "isrc:" + isrc.uppercase()

        val title = tokens(info.path("title").asText("")).filter { it !in junk }.joinToString(" ")
        val bucket = info.path("length").asLong(0) / 15_000
        return "$title|$bucket"
    }
}
