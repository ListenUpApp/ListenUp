package com.calypsan.listenup.server.metadata.spi

import com.calypsan.listenup.api.dto.match.MetadataSource
import kotlin.jvm.JvmInline

/**
 * Stable identity of a metadata provider — the value the enrichment router names
 * a provider by in its priority chains.
 *
 * A provider id crosses the RPC wire only as an opaque key (`MetadataSource.id`,
 * `ExternalRef.provider`), always beside its label; clients never branch on it. The
 * [value] is also the operator-facing config token used in `LISTENUP_ENRICHMENT_ORDER`
 * / `_ROUTES`.
 */
@JvmInline
value class MetadataProviderId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "MetadataProviderId cannot be blank" }
    }

    override fun toString(): String = value

    companion object {
        /** The Audible catalog. */
        val AUDIBLE = MetadataProviderId("audible")

        /** The iTunes / Apple Books catalog. */
        val ITUNES = MetadataProviderId("itunes")

        /** The Audnexus aggregator (contributor profiles, chapters, genres). */
        val AUDNEXUS = MetadataProviderId("audnexus")

        /**
         * Hardcover's community catalogue (#1542): ratings, and the gaps Audible and Audnexus leave —
         * moods above all, then genres, series, descriptions and author photos. A [gapFillers] member.
         */
        val HARDCOVER = MetadataProviderId("hardcover")

        /** Every built-in id the router recognizes from config tokens. */
        val known: List<MetadataProviderId> = listOf(AUDIBLE, ITUNES, AUDNEXUS, HARDCOVER)

        /**
         * Providers that fill gaps rather than identify books. The coordinator never treats one as having
         * found a book (its fields join a match another catalog made), unions its genres with the
         * primary's, and always labels a field it supplied — even where it is that field's primary.
         */
        val gapFillers: Set<MetadataProviderId> = setOf(HARDCOVER)

        /**
         * The prefix that marks an operator-declared custom provider id — the token
         * `custom:<name>` a `LISTENUP_CUSTOM_PROVIDERS` entry and any route naming it
         * share. Kept lowercase so a route token and the config-derived id always match.
         */
        const val CUSTOM_PREFIX: String = "custom:"

        /**
         * The id for an operator-declared custom provider named [name] — `custom:<name>`,
         * with [name] trimmed and lowercased so config and route tokens resolve identically.
         */
        fun custom(name: String): MetadataProviderId = MetadataProviderId(CUSTOM_PREFIX + name.trim().lowercase())

        /**
         * Resolves a config token (case-insensitive) to an id, or `null` if unrecognized.
         *
         * A built-in token matches one of [known]; a `custom:<name>` token resolves to
         * that operator-declared provider's id (so a route can name a custom source), as
         * long as `<name>` is non-blank.
         */
        fun fromToken(token: String): MetadataProviderId? {
            val trimmed = token.trim()
            known.firstOrNull { it.value.equals(trimmed, ignoreCase = true) }?.let { return it }
            if (trimmed.startsWith(CUSTOM_PREFIX, ignoreCase = true)) {
                val name = trimmed.substring(CUSTOM_PREFIX.length).trim()
                if (name.isNotEmpty()) return custom(name)
            }
            return null
        }
    }
}

/**
 * Human display label for a provider — what the client shows in provenance chips/footers.
 * Built-ins get branded names; a `custom:<name>` id title-cases `<name>` (the only affordance —
 * custom providers carry no configured display name).
 */
fun MetadataProviderId.displayLabel(): String =
    when (this) {
        MetadataProviderId.AUDIBLE -> {
            "Audible"
        }

        MetadataProviderId.ITUNES -> {
            "iTunes"
        }

        MetadataProviderId.AUDNEXUS -> {
            "Audnexus"
        }

        MetadataProviderId.HARDCOVER -> {
            "Hardcover"
        }

        else -> {
            value
                .removePrefix(MetadataProviderId.CUSTOM_PREFIX)
                .split(Regex("[\\s_-]+"))
                .filter { it.isNotBlank() }
                .joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
        }
    }

/** The source a provider is shown as. Audnexus serves Audible's data, so it is presented as Audible. */
fun MetadataProviderId.presentedAs(): MetadataProviderId =
    if (this == MetadataProviderId.AUDNEXUS) MetadataProviderId.AUDIBLE else this

/** This provider as clients show it: the presented id as an opaque key, with its display label. */
fun MetadataProviderId.toMetadataSource(): MetadataSource =
    presentedAs().let { MetadataSource(id = it.value, label = it.displayLabel()) }
