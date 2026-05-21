package com.calypsan.listenup.server.services

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
