/**
 * Domain types for embedded audio metadata: tags, chapters, artwork.
 *
 * Populated by the server-side `embeddedmeta` parser package; consumed across the
 * RPC boundary by clients (e.g. the Books domain returns [EmbeddedAudioMetadata]
 * fields). Every public type in this package is `@Serializable`.
 *
 * "Embedded" disambiguates this package from external metadata sources
 * (Audnexus, Audiobookshelf server, manual entry) integrated elsewhere.
 *
 * Konsist rule `embeddedMetaTypesInCommonMain` (Task 57) enforces that these
 * types stay in commonMain.
 */
package com.calypsan.listenup.domain.embeddedmeta
