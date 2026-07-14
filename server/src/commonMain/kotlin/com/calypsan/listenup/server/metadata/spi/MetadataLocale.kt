package com.calypsan.listenup.server.metadata.spi

import kotlin.jvm.JvmInline

/**
 * The provider-neutral locale an enrichment lookup runs in.
 *
 * Each provider maps this to its own regional vocabulary (Audible maps it to an
 * `AudibleRegion`, iTunes to a storefront country) — the SPI stays neutral so no
 * capability signature leaks a single catalog's region enum. [code] is a short
 * lowercase region/market token (e.g. `us`, `uk`, `de`).
 *
 * A provider that does not recognize a locale is expected to fall back to its own
 * default rather than fail — the never-strand rule at the provider edge.
 */
@JvmInline
value class MetadataLocale(
    val code: String,
) {
    init {
        require(code.isNotBlank()) { "MetadataLocale code cannot be blank" }
    }

    override fun toString(): String = code

    companion object {
        /** The default market when the caller has no library-specific locale. */
        val DEFAULT = MetadataLocale("us")
    }
}
