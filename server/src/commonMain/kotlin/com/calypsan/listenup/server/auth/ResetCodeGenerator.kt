package com.calypsan.listenup.server.auth

import dev.whyoleg.cryptography.random.CryptographyRandom

/**
 * Produces an 8-character reset code from a 32-symbol unambiguous alphabet (Crockford
 * Base32 minus `I`, `L`, `O`, `U`).
 *
 * Unlike [InviteCodeGenerator]'s 22-char base64url — which is built to sit in a link — this
 * is built to be **read down a phone**, because an admin has to convey it to a human. 40 bits
 * is far more than the guard rails need: a 15-minute window and a five-attempt budget mean an
 * attacker gets five guesses against 2^40. The length is chosen for legibility, not entropy.
 *
 * [generate] returns the canonical value — the one that gets hashed and compared. [format] and
 * [normalize] are the presentation and input-parsing concerns layered on top of it; neither
 * changes what "the code" is.
 */
class ResetCodeGenerator {
    /** The canonical code: 8 characters, no separator. This is the value that gets hashed. */
    fun generate(): String =
        CharArray(CODE_LENGTH) {
            ALPHABET[CryptographyRandom.nextInt(ALPHABET.length)]
        }.concatToString()

    companion object {
        /** Crockford Base32 without I, L, O, U — the characters people mishear or mistype. */
        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        private const val CODE_LENGTH = 8
        private const val GROUP = 4
        private val NON_CODE_CHARS = Regex("[^0-9A-Z]")

        /**
         * Display form, grouped as `XXXX-XXXX` — easier to read aloud, which is the entire point
         * of this code. Presentation only: never hash or compare this, always [normalize] first.
         */
        fun format(code: String): String = "${code.substring(0, GROUP)}-${code.substring(GROUP)}"

        /**
         * Canonicalises user input before comparison: upper-cases, then strips everything that
         * isn't an alphabet character — the group dash, stray spaces (a symbol keyboard or
         * dictation often renders "dash" as a space), en-dashes from autocorrect, and trailing
         * punctuation — so `abcd 2345`, `ABCD–2345`, and `abcd-2345.` all match the code issued
         * as `ABCD-2345`.
         */
        fun normalize(input: String): String = input.uppercase().replace(NON_CODE_CHARS, "")
    }
}
