package com.calypsan.listenup.server.sync

/**
 * Strips diacritics from [raw] for slug generation — the one step in slug normalization that needs a
 * platform Unicode primitive. Returns a string where accented Latin letters are reduced to their base
 * ASCII letter (`café` → `cafe`, `Köln` → `Koln`), leaving everything else for the pure-Kotlin slug
 * pipeline (lowercase → `&`-expand → non-alphanumeric collapse → trim) to handle.
 *
 * JVM actual: full Unicode NFKD decomposition via `java.text.Normalizer` (the historical behaviour —
 * byte-identical). Native actual: a folding table over the common Latin diacritic range. The two
 * agree on the realistic input (Latin tag/mood names); native doesn't attempt the exotic
 * compatibility decompositions (ligatures, full-width forms) NFKD also performs, which never appear
 * in display names. Each self-hosted server is internally consistent.
 */
internal expect fun foldDiacritics(raw: String): String
