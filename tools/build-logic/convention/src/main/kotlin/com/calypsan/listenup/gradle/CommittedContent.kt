package com.calypsan.listenup.gradle

import java.io.File
import java.util.concurrent.TimeUnit

/** Seconds to wait for a `git` invocation before giving up and treating git as unavailable. */
private const val GIT_TIMEOUT_SECONDS = 30L

/**
 * Reads file content as it exists in the repository's `HEAD` commit.
 *
 * This is the baseline `verifyStrings` compares against, and it is deliberately NOT the working tree:
 *  - **Race-free.** `generateStrings` rewrites the very files `verifyStrings` reads, and both can be
 *    scheduled in one build (`verifyLocal`, or any compose-resources task pulling in `generateStrings`).
 *    A working-tree read can observe a file mid-`writeText` — truncated — and report phantom drift.
 *    Nothing rewrites a git object mid-read, so a committed baseline cannot be torn. Ordering the two
 *    tasks would not fix it: with `generateStrings` in the same build, verify would compare freshly
 *    generated output against itself and pass unconditionally — a gate that cannot fail.
 *  - **Truer to the gate.** The gate exists to prove the *committed* artifacts match the JSON source.
 *    Against the working tree, a developer who regenerates but never commits passes; they should fail.
 *
 * Obtain one with [of], which returns null when git cannot answer here (git absent, or a source
 * tarball rather than a checkout — a legitimate way to build). Callers fall back to the working tree.
 */
class CommittedContent private constructor(
    private val repoRoot: File,
) {
    /**
     * The content of [file] in `HEAD`, or null when it is not committed there (a brand-new artifact —
     * which the caller reports as drift, not as a crash).
     *
     * @param file an absolute path inside [repoRoot]; git is addressed with the repo-root-relative,
     *   forward-slashed form it expects.
     */
    fun read(file: File): String? {
        val path = file.relativeTo(repoRoot).invariantSeparatorsPath
        return git(repoRoot, "show", "HEAD:$path")
    }

    companion object {
        /**
         * A reader for [repoRoot], or null when git cannot serve a baseline there — no git binary, not a
         * checkout, or a checkout with no commits yet.
         */
        fun of(repoRoot: File): CommittedContent? =
            git(repoRoot, "rev-parse", "--verify", "HEAD")?.let { CommittedContent(repoRoot) }

        /**
         * Runs git in [dir], returning stdout on success and null on any failure (including a missing binary).
         *
         * stderr is DISCARDed rather than drained: a `git show` of a large artifact (the iOS String
         * Catalog runs to hundreds of KB) overflows the OS pipe buffer, so git blocks writing stdout while
         * this side blocks on any other stream — a deadlock. Draining exactly one pipe, to completion,
         * is the only shape that cannot hang. git's diagnostics are not needed; the exit code is.
         */
        private fun git(
            dir: File,
            vararg args: String,
        ): String? =
            runCatching {
                val process =
                    ProcessBuilder(listOf("git") + args)
                        .directory(dir)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start()
                val stdout = process.inputStream.use { it.readBytes() }.decodeToString()
                if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    null
                } else if (process.exitValue() == 0) {
                    stdout
                } else {
                    null
                }
            }.getOrNull()
    }
}
