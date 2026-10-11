package com.calypsan.listenup.server.auth

/**
 * Equality that takes the same time whether the first byte differs or the last, so a caller
 * cannot learn how much of a secret they guessed from how long the refusal took. Length is not
 * hidden: every secret compared here has a fixed, public length.
 */
internal fun constantTimeEquals(
    a: ByteArray,
    b: ByteArray,
): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}

/** [constantTimeEquals] over the UTF-8 bytes of two strings. */
internal fun constantTimeEquals(
    a: String,
    b: String,
): Boolean = constantTimeEquals(a.encodeToByteArray(), b.encodeToByteArray())
