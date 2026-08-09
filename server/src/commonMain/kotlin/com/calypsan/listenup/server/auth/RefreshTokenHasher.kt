package com.calypsan.listenup.server.auth

/**
 * [PepperedHasher] scoped to refresh tokens. A distinct type so Koin can bind the
 * refresh-token pepper independently of any other peppered secret — the same reason
 * `CoverImageStore` wraps `ImageStore` rather than subclassing it. Composition also
 * keeps `PepperedHasher` non-`open`: in this codebase `open` means "test seam," and a
 * subclassable hasher would tempt a future contributor to mark `hash()` or the pepper
 * length check `open` "for testability," quietly undoing the guarantee that no code
 * path can override them.
 */
class RefreshTokenHasher(
    pepper: ByteArray,
) {
    private val hasher = PepperedHasher(pepper)

    /** Returns lowercase hex of the HMAC-SHA-256 digest (64 chars). */
    fun hash(token: String): String = hasher.hash(token)
}
