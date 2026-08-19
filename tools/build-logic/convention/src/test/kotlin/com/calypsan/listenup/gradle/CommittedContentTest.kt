package com.calypsan.listenup.gradle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the `verifyStrings` baseline reader: the gate compares rendered artifacts against what is
 * COMMITTED, so it can never observe a file mid-write from a concurrent `generateStrings`.
 */
class CommittedContentTest {
    @Test
    fun `reads the committed content, not the working tree`() {
        val repo = gitRepo() ?: return
        val artifact = File(repo, "values/strings.xml")
        artifact.parentFile.mkdirs()
        artifact.writeText("committed\n")
        git(repo, "add", "-A")
        git(repo, "commit", "-m", "artifact")

        // Working tree diverges (or is being rewritten right now) — the reader must ignore it.
        artifact.writeText("")

        val committed = CommittedContent.of(repo)
        assertTrue(committed != null, "git repo with a HEAD commit must yield a reader")
        assertEquals("committed\n", committed.read(artifact))
    }

    @Test
    fun `reads a file larger than the OS pipe buffer`() {
        val repo = gitRepo() ?: return
        // The iOS String Catalog runs to hundreds of KB. git blocks writing stdout once the ~64K pipe
        // buffer fills, so any reader that drains a different stream first deadlocks — this hung the
        // real `verifyStrings` task until the reader was narrowed to stdout alone.
        val big = File(repo, "Localizable.xcstrings")
        val content = "x".repeat(1_000_000) + "\n"
        big.writeText(content)
        git(repo, "add", "-A")
        git(repo, "commit", "-m", "big")

        assertEquals(content, CommittedContent.of(repo)!!.read(big))
    }

    @Test
    fun `returns null for a file that is not in HEAD`() {
        val repo = gitRepo() ?: return
        File(repo, "seed.txt").writeText("seed\n")
        git(repo, "add", "-A")
        git(repo, "commit", "-m", "seed")

        val brandNew = File(repo, "values-fr/strings.xml")
        brandNew.parentFile.mkdirs()
        brandNew.writeText("generated but never committed\n")

        // Not a crash — a missing artifact is drift, and the caller reports it as such.
        assertNull(CommittedContent.of(repo)!!.read(brandNew))
    }

    @Test
    fun `yields no reader outside a git checkout`() {
        val notARepo =
            File.createTempFile("listenup-not-a-repo-", "").let {
                it.delete()
                it.mkdirs()
                it
            }
        notARepo.deleteOnExit()
        // A tarball export is a legitimate way to build — callers fall back to the working tree.
        assertNull(CommittedContent.of(notARepo))
    }

    @Test
    fun `yields no reader in a git repo with no commits`() {
        val repo = gitRepo() ?: return
        assertNull(CommittedContent.of(repo))
    }

    /** A fresh temp git repo, or null when git is unavailable (the test then trivially passes). */
    private fun gitRepo(): File? {
        val dir =
            File.createTempFile("listenup-git-", "").let {
                it.delete()
                it.mkdirs()
                it
            }
        dir.deleteOnExit()
        return if (git(dir, "init", "--quiet") &&
            git(dir, "config", "user.email", "test@example.com") &&
            git(dir, "config", "user.name", "Test")
        ) {
            dir
        } else {
            null
        }
    }

    private fun git(
        dir: File,
        vararg args: String,
    ): Boolean =
        runCatching {
            ProcessBuilder(listOf("git") + args)
                .directory(dir)
                .redirectErrorStream(true)
                .start()
                .let {
                    it.inputStream.readBytes()
                    it.waitFor()
                } == 0
        }.getOrDefault(false)
}
