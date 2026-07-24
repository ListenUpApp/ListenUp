package com.calypsan.listenup.client.util

import kotlin.random.Random

/**
 * NanoID generator - compact, URL-safe unique identifiers.
 *
 * Matches the NanoID spec (https://github.com/ai/nanoid) and the server's
 * go-nanoid implementation for consistency across the stack.
 *
 * Properties:
 * - 21 characters by default (same collision probability as UUID v4)
 * - URL-safe alphabet: A-Za-z0-9_- (64 characters = 6 bits per char)
 * - ~126 bits of entropy (21 chars × 6 bits)
 * - Shorter than UUID (21 vs 36 characters)
 *
 * Format with prefix: "evt-V1StGXR8_Z5jdHi6B-myT"
 */
object NanoId {
    /**
     * URL-safe alphabet (64 characters).
     * Order matches NanoID spec: symbols, digits, uppercase, lowercase.
     */
    private const val ALPHABET = "_-0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    /**
     * Default size matching NanoID spec.
     * 21 characters provides ~126 bits of entropy.
     */
    private const val DEFAULT_SIZE = 21

    init {
        // Compile-time guarantee: alphabet must be exactly 64 chars for uniform distribution
        @Suppress("MagicNumber")
        require(ALPHABET.length == 64) { "Alphabet must be 64 characters for 6-bit encoding" }
    }

    /**
     * Generate a NanoID.
     *
     * @param size Number of characters (default: 21)
     * @return URL-safe unique identifier
     */
    fun generate(size: Int = DEFAULT_SIZE): String =
        buildString(size) {
            repeat(size) {
                @Suppress("MagicNumber")
                append(ALPHABET[Random.nextInt(64)])
            }
        }

    /**
     * Generate a prefixed NanoID matching server format.
     *
     * @param prefix Entity type prefix (e.g., "evt", "usr", "book")
     * @param size Number of characters after prefix (default: 21)
     * @return Prefixed identifier (e.g., "evt-V1StGXR8_Z5jdHi6B-myT")
     */
    fun generate(
        prefix: String,
        size: Int = DEFAULT_SIZE,
    ): String = "$prefix-${generate(size)}"
}
