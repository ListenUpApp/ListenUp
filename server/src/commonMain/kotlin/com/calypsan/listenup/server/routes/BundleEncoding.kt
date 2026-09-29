package com.calypsan.listenup.server.routes

/**
 * A representation of a web-bundle file on disk: the file itself, or a sibling the build
 * precompressed. Declared in preference order — when a request accepts several equally, the first
 * one wins, because brotli is the smallest and identity the largest.
 *
 * @property token the `Content-Encoding` value, or null for the raw file.
 * @property fileSuffix what the build appends to the original file name.
 * @property etagSuffix what distinguishes this representation's ETag from the others'.
 */
internal enum class BundleEncoding(
    val token: String?,
    val fileSuffix: String,
    val etagSuffix: String,
) {
    BROTLI("br", ".br", "-br"),
    GZIP("gzip", ".gz", "-gz"),
    IDENTITY(null, "", ""),
    ;

    companion object {
        /** The precompressed variants, in the order the route probes for them. */
        val compressed: List<BundleEncoding> = entries.filter { it.token != null }
    }
}

/**
 * Picks the representation to send, given the request's `Accept-Encoding` and the variants that
 * exist on disk. Follows RFC 9110 §12.5.3: q-values rank, `q=0` refuses, `*` covers what is not
 * named, and `identity` is acceptable unless refused.
 *
 * Two deliberate departures, both toward serving *something*:
 * - no header gets the raw file, where the RFC would allow any coding — an old client that
 *   sends nothing may well not decode brotli;
 * - an unnamed `identity` ranks below every named coding rather than at an implied 1.0, so
 *   `gzip;q=0.5` gets gzip;
 * - when everything acceptable is missing, the raw file goes out anyway rather than a 406. A
 *   browser that refuses identity yet cannot use what we have is not a case worth stranding a
 *   page load for.
 */
internal fun negotiateEncoding(
    acceptEncoding: String?,
    available: Set<BundleEncoding>,
): BundleEncoding {
    if (acceptEncoding.isNullOrBlank()) return BundleEncoding.IDENTITY
    val weights = parseAcceptEncoding(acceptEncoding)
    val wildcard = weights["*"]

    fun weightOf(encoding: BundleEncoding): Double =
        when (val token = encoding.token) {
            // Unnamed identity is acceptable but ranked last: a client that lists only
            // `gzip;q=0.5` is asking for gzip, not for the raw file at an implied 1.0.
            null -> weights["identity"] ?: wildcard?.takeIf { it == 0.0 } ?: UNNAMED_IDENTITY_WEIGHT

            else -> weights[token] ?: wildcard ?: 0.0
        }

    return BundleEncoding.entries
        .filter { it == BundleEncoding.IDENTITY || it in available }
        .map { it to weightOf(it) }
        .filter { (_, weight) -> weight > 0.0 }
        // maxByOrNull keeps the FIRST of equal maxima, and entries are in preference order.
        .maxByOrNull { (_, weight) -> weight }
        ?.first
        ?: BundleEncoding.IDENTITY
}

private const val UNNAMED_IDENTITY_WEIGHT = 0.0001

/** `"br;q=0.5, gzip"` → `{br=0.5, gzip=1.0}`, lower-cased, with `x-gzip` folded into `gzip`. */
private fun parseAcceptEncoding(header: String): Map<String, Double> =
    header
        .split(',')
        .mapNotNull { element ->
            val parts = element.split(';').map { it.trim().lowercase() }
            val coding = parts.first().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val quality =
                parts
                    .drop(1)
                    .firstOrNull { it.startsWith("q=") }
                    ?.removePrefix("q=")
                    ?.toDoubleOrNull()
                    ?.coerceIn(0.0, 1.0)
                    ?: 1.0
            (if (coding == "x-gzip") "gzip" else coding) to quality
        }.toMap()
