package com.pawan.tunnel

enum class QueryType {
    /** `asd:` or `auto:` in front: always use the Advanced Search Driver */
    FORCED,

    /** a known search prefix (ytsearch:, spsearch:, ...) or a shorthand like yt: or sp: */
    PREFIXED,

    /** a http(s) link */
    URL,

    /** an ISRC code such as USRC17607839 */
    ISRC,

    /** plain words, e.g. "never gonna give you up" */
    TEXT
}

/**
 * @param provider provider id from the catalog when it could be told
 * @param query    the identifier to send to a node as-is (PREFIXED and URL)
 * @param text     the search words, without any prefix
 */
class Detection(
    val type: QueryType,
    val provider: String?,
    val query: String,
    val text: String
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "type" to type.name,
        "provider" to provider,
        "query" to query,
        "text" to text
    )
}

/** Auto-detects what the user typed after `tunnel:`. */
object QueryDetector {

    private val isrcPattern = Regex("^[A-Za-z]{2}[A-Za-z0-9]{3}\\d{7}$")

    private val shorthand = mapOf(
        "yt" to "ytsearch", "ytm" to "ytmsearch", "sc" to "scsearch", "sp" to "spsearch",
        "am" to "amsearch", "dz" to "dzsearch", "td" to "tdsearch", "qb" to "qbsearch",
        "ym" to "ymsearch", "vk" to "vksearch", "js" to "jssearch"
    )

    fun detect(raw: String): Detection {
        val q = raw.trim()
        val lower = q.lowercase()

        for (marker in listOf("asd:", "auto:")) {
            if (lower.startsWith(marker)) {
                val text = q.substring(marker.length).trim()
                return Detection(QueryType.FORCED, null, text, text)
            }
        }

        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return Detection(QueryType.URL, ProviderResolver.resolve(q), q, q)
        }

        val colon = q.indexOf(':')
        if (colon > 0) {
            val head = lower.substring(0, colon)
            val rest = q.substring(colon + 1).trim()

            ProviderCatalog.byPrefix(head)?.let {
                return Detection(QueryType.PREFIXED, it, q, rest)
            }
            shorthand[head]?.let { prefix ->
                return Detection(QueryType.PREFIXED, ProviderCatalog.byPrefix(prefix), "$prefix:$rest", rest)
            }
        }

        val compact = q.replace("-", "")
        if (q.length <= 15 && isrcPattern.matches(compact)) {
            return Detection(QueryType.ISRC, null, q, compact.uppercase())
        }

        return Detection(QueryType.TEXT, null, q, q)
    }
}
