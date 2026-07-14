package com.calypsan.listenup.server.metadata.spi

import com.calypsan.listenup.api.dto.ContributorRole

/**
 * The provider-neutral core metadata for one book — every textual book-core field
 * plus the book's credited contributors.
 *
 * Book *credits* (which people authored/narrated *this* book) fold into [authors]
 * and [narrators] here rather than onto a contributor capability. This keeps the
 * [ContributorSource] capability honest — it does contributor *profiles* only, so
 * a catalog like Audible that has book credits but no standalone profile endpoint
 * isn't forced to stub a half-capability.
 *
 * Every field is nullable/empty-able: a provider returns only what its catalog
 * knows, and the router composes fields across providers.
 */
data class BookCoreMeta(
    /** Canonical title. */
    val title: String? = null,
    /** Subtitle, when the catalog distinguishes one. */
    val subtitle: String? = null,
    /** Long-form description / synopsis. */
    val description: String? = null,
    /** Publishing imprint. */
    val publisher: String? = null,
    /** Release date in the catalog's raw form (ISO date or year). */
    val releaseDate: String? = null,
    /** BCP-47 / catalog language token. */
    val language: String? = null,
    /** Total runtime in minutes when the catalog reports one; `null`/0 when unknown. */
    val runtimeMinutes: Int? = null,
    /** Whether the catalog flags explicit content; `null` when unknown. */
    val explicit: Boolean? = null,
    /** Whether the edition is abridged; `null` when unknown. */
    val abridged: Boolean? = null,
    /** Credited authors on this book, in catalog order. */
    val authors: List<BookContributorMeta> = emptyList(),
    /** Credited narrators on this book, in catalog order. */
    val narrators: List<BookContributorMeta> = emptyList(),
)

/**
 * One credited contributor on a book, as a provider reports it.
 *
 * [key] is the catalog's stable id for the person when it exposes one (lets a
 * later step fetch their [ContributorMeta] profile); it is `null` for catalogs
 * that only give a name string. [role] reuses the shared [ContributorRole]
 * vocabulary so credits align with the rest of the contract.
 */
data class BookContributorMeta(
    /** Catalog contributor key, when available; `null` for name-only catalogs. */
    val key: String? = null,
    /** Display name as credited. */
    val name: String,
    /** The role this person played on the book. */
    val role: ContributorRole,
)
