package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.scanner.pipeline.SortKeys

/**
 * Normalizes a contributor or series display name into its deduplication key:
 * lowercased, trimmed, and internal whitespace collapsed to single spaces.
 *
 * Two display names that differ only by case or spacing — "Brandon Sanderson"
 * and "  brandon   sanderson  " — share one normalized key and therefore one
 * catalogue row. The first writer's display casing is preserved on the row;
 * later writers with a matching key reuse it.
 */
internal fun normalizeForDedup(name: String): String = name.lowercase().trim().replace(Regex("\\s+"), " ")

/**
 * The contributor dedup key: the normalized sort name. Falls back to a derived
 * `Last, First` sort form when no explicit [sortName] is supplied, so every
 * creation path (scanner, manual edit, enrichment) buckets the same person
 * identically regardless of which path created the row first.
 *
 * Using the sort name as the key means "Brandon Sanderson" (display order) and
 * "Sanderson, Brandon" (sort order) both normalize to `"sanderson, brandon"` and
 * converge to one contributor row.
 */
internal fun contributorDedupKey(
    name: String,
    sortName: String?,
): String = normalizeForDedup(sortName ?: SortKeys.sortName(name, null))
