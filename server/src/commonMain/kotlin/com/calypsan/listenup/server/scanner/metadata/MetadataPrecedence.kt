package com.calypsan.listenup.server.scanner.metadata

/**
 * Resolve the metadata precedence for a library: its own configured value when set, else
 * [fallback] (the operator-global default). A blank value inherits [fallback] (not `parse`'s
 * hardcoded all-sources DEFAULT); an unparseable value also falls back rather than failing the
 * scan — a malformed per-library config must never strand ingest.
 */
fun resolveLibraryPrecedence(
    libraryValue: String,
    fallback: MetadataPrecedence,
): MetadataPrecedence =
    if (libraryValue.isBlank()) {
        fallback
    } else {
        runCatching { MetadataPrecedence.parse(libraryValue) }.getOrDefault(fallback)
    }

/** A metadata signal source, in the scanner's precedence vocabulary. */
enum class MetadataPrecedenceSource(
    val token: String,
) {
    /** ListenUp's own `listenup.json` curation sidecar — the highest-precedence source. */
    LISTENUP("listenup.json"),
    ABS_METADATA("metadata.json"),
    EMBEDDED("embedded"),
    SIDECAR("sidecar"),
    FILENAME("filename"),
    FOLDER("folder"),
}

/**
 * The textual-metadata precedence chain for a library — highest precedence first.
 * A source omitted from [order] is disabled (never consulted). Operator-configured
 * via `LISTENUP_METADATA_PRECEDENCE`.
 */
data class MetadataPrecedence(
    val order: List<MetadataPrecedenceSource>,
) {
    init {
        require(order.isNotEmpty()) { "metadata precedence cannot be empty" }
    }

    fun serialize(): String = order.joinToString(",") { it.token }

    companion object {
        /** Declared highest-first, so this is the documented default order. */
        val DEFAULT = MetadataPrecedence(MetadataPrecedenceSource.entries.toList())

        /** Parse a comma-separated token list. Blank → [DEFAULT]. Unknown token → throws. */
        fun parse(raw: String): MetadataPrecedence {
            if (raw.isBlank()) return DEFAULT
            val byToken = MetadataPrecedenceSource.entries.associateBy { it.token }
            val sources =
                raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { token ->
                    byToken[token]
                        ?: throw IllegalArgumentException(
                            "Unknown metadata precedence source '$token'. " +
                                "Valid: ${byToken.keys.joinToString(", ")}",
                        )
                }
            return MetadataPrecedence(sources)
        }
    }
}
