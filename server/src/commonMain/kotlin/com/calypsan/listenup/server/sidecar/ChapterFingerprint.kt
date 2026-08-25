package com.calypsan.listenup.server.sidecar

import com.calypsan.listenup.server.io.hashBytesSha256

/** Bucket width (ms) chapter durations are rounded to before hashing — see [SidecarIdentity.chapterFingerprint]. */
private const val FINGERPRINT_DURATION_BUCKET_MS = 5_000L

/**
 * The canonical v1 chapter-snapshot fingerprint over [chapters] as `(title, durationMs)` pairs in
 * chapter order — see [SidecarIdentity.chapterFingerprint]'s KDoc for the formula itself and why
 * it can never change. Returns null when there are no chapters to fingerprint.
 *
 * It takes plain pairs rather than any one chapter type on purpose. Two callers need this value
 * from two different shapes — the sidecar assembler from a stored `BookChapterPayload`, the
 * upload duplicate check from the analyzer's freshly-parsed `Chapter` — and an identity formula
 * copied into a second place is a second formula that will eventually disagree with the first.
 * One implementation, two projections onto it.
 */
internal fun chapterFingerprintOf(chapters: List<Pair<String, Long>>): String? {
    if (chapters.isEmpty()) return null
    val key =
        chapters.joinToString("|") { (title, durationMs) ->
            "${title.trim().lowercase()}:${durationMs / FINGERPRINT_DURATION_BUCKET_MS}"
        }
    return hashBytesSha256(key.encodeToByteArray())
}
