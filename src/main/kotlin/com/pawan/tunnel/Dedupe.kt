package com.pawan.tunnel

import com.fasterxml.jackson.databind.JsonNode

/**
 * Removes duplicate tracks when several nodes (or several sources) return the same song,
 * so it is never added to the queue twice.
 *
 * Two remote tracks are the same when they share an ISRC, the same link, or the same
 * cleaned-up title with about the same length.
 */
object Dedupe {

    private val splitter = Regex("[^\\p{L}\\p{N}]+")

    private val junk = setOf(
        "official", "video", "audio", "music", "lyrics", "lyric", "hd", "hq", "4k", "mv", "visualizer", "topic"
    )

    private fun tokens(s: String) = s.lowercase().split(splitter).filter { it.isNotBlank() }

    /** Every key that identifies this track. Matching any key means "already have it". */
    fun keys(track: JsonNode): List<String> {
        val info = track.path("info")
        val out = mutableListOf<String>()

        val isrc = info.path("isrc").asText("")
        if (isrc.isNotBlank()) out += "isrc:" + isrc.uppercase()

        val uri = info.path("uri").asText("")
        if (uri.isNotBlank()) out += "uri:" + uri.lowercase().trimEnd('/')

        val ident = info.path("identifier").asText("")
        val src = info.path("sourceName").asText("")
        if (ident.isNotBlank() && src.isNotBlank()) out += "id:${src.lowercase()}:$ident"

        val title = tokens(info.path("title").asText("")).filter { it !in junk }.joinToString(" ")
        if (title.isNotBlank()) {
            val bucket = info.path("length").asLong(0) / 15_000
            out += "t:$title|$bucket"
        }
        return out
    }

    /** Keeps the first occurrence of every track (input order = priority order). */
    fun <T> distinct(items: List<T>, trackOf: (T) -> JsonNode): List<T> {
        val seen = HashSet<String>()
        val result = ArrayList<T>()
        for (item in items) {
            val k = keys(trackOf(item))
            if (k.isNotEmpty() && k.any { it in seen }) continue
            seen.addAll(k)
            result += item
        }
        return result
    }
}
