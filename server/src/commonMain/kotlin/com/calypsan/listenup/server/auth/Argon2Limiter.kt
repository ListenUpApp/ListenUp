package com.calypsan.listenup.server.auth

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Bounds how many Argon2id hash/verify operations run at once (C3).
 *
 * Argon2id is deliberately expensive — [PasswordHasher] is tuned to 64 MB × 3 iterations × 4 lanes,
 * so each call briefly holds ~64 MB and saturates a core. Left unbounded, a burst of concurrent
 * logins (or the login timing-oracle dummy hash on every unknown email) becomes a CPU-and-memory
 * amplification: a handful of cheap requests pin the whole box. This gate wraps every Argon2 call in
 * the auth path in a [Semaphore], so at most [permits] hashes run concurrently and the rest queue —
 * throughput is capped, memory can't stampede, and the timing-oracle defence still holds because the
 * dummy hash goes through the same gate as a real verify.
 *
 * The primary constructor takes the two hashing operations as suspend function references rather than
 * the concrete [PasswordHasher] (an `expect class`, hence final and un-fakeable), so a test can inject
 * counting stand-ins and assert the concurrency ceiling directly. Production wires the
 * [PasswordHasher] convenience constructor.
 */
class Argon2Limiter internal constructor(
    permits: Int,
    private val hashFn: suspend (CharSequence) -> String,
    private val verifyFn: suspend (CharSequence, String) -> Boolean,
) {
    /** Production binding: gate the real [hasher] at [permits] concurrent operations. */
    constructor(hasher: PasswordHasher, permits: Int = DEFAULT_ARGON2_PARALLELISM) :
        this(permits, hasher::hash, hasher::verify)

    private val gate = Semaphore(permits)

    /** Argon2id-hash [plaintext], blocking past the concurrency ceiling. */
    suspend fun hash(plaintext: CharSequence): String = gate.withPermit { hashFn(plaintext) }

    /** Verify [plaintext] against [encoded], blocking past the concurrency ceiling. */
    suspend fun verify(
        plaintext: CharSequence,
        encoded: String,
    ): Boolean = gate.withPermit { verifyFn(plaintext, encoded) }
}

/**
 * Default concurrent-Argon2 ceiling. Four balances login throughput against the ~64 MB-per-hash
 * memory burst; an operator on a small box can lower it via `auth.argon2Parallelism`. Not derived
 * from the CPU count so the bound stays predictable across the JVM and native server builds.
 */
const val DEFAULT_ARGON2_PARALLELISM = 4
