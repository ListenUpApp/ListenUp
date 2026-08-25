package com.calypsan.listenup.server.upload

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path as NioPath
import kotlinx.io.files.Path as IoPath

/**
 * The traversal surface of the upload flow. `relPath` is attacker-controlled input that decides
 * where bytes land, so every refusal here is asserted directly rather than being left to the
 * broker's containment backstop at the far end of the pipeline.
 *
 * Each case names the shape it defends against, because a table of paths with no reasons is a
 * table nobody can safely edit later.
 */
class UploadRelPathTest :
    FunSpec({

        fun withSession(block: (IoPath, NioPath) -> Unit) {
            val dir = Files.createTempDirectory("listenup-upload-relpath-")
            try {
                block(IoPath(dir.toString()), dir)
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        test("a plain nested path resolves inside the session directory") {
            withSession { session, _ ->
                val resolved = resolveUploadTarget(session, "The Way of Kings/CD1/01.mp3")
                val accepted = resolved.shouldBeInstanceOf<UploadTarget.Accepted>()
                accepted.path.toString() shouldBe "$session/The Way of Kings/CD1/01.mp3"
            }
        }

        test("a bare filename resolves directly inside the session directory") {
            withSession { session, _ ->
                val accepted = resolveUploadTarget(session, "book.m4b").shouldBeInstanceOf<UploadTarget.Accepted>()
                accepted.path.toString() shouldBe "$session/book.m4b"
            }
        }

        // ── traversal ───────────────────────────────────────────────────────────

        test("an absolute path is refused — an upload names a location within the selection, never a root") {
            withSession { session, _ ->
                val refused = resolveUploadTarget(session, "/etc/cron.d/evil").shouldBeInstanceOf<UploadTarget.Refused>()
                refused.reason shouldEndWith "/etc/cron.d/evil"
            }
        }

        test("a leading .. escape is refused") {
            withSession { session, _ ->
                resolveUploadTarget(session, "../../etc/passwd").shouldBeInstanceOf<UploadTarget.Refused>()
            }
        }

        test("a .. buried mid-path is refused — the segment check is not just a prefix check") {
            withSession { session, _ ->
                resolveUploadTarget(session, "book/../../../etc/passwd").shouldBeInstanceOf<UploadTarget.Refused>()
            }
        }

        test("a trailing .. is refused") {
            withSession { session, _ ->
                resolveUploadTarget(session, "book/..").shouldBeInstanceOf<UploadTarget.Refused>()
            }
        }

        test("percent-encoded .. is refused once decoded — Ktor decodes query parameters before we see them") {
            withSession { session, _ ->
                // What arrives at resolveUploadTarget when the wire carried %2e%2e%2f%2e%2e%2fetc%2fpasswd.
                resolveUploadTarget(session, "../../etc/passwd").shouldBeInstanceOf<UploadTarget.Refused>()
            }
        }

        test("percent-encoding left literally is harmless — it is just an odd filename, still inside the session") {
            withSession { session, _ ->
                // The safety proof for NOT decoding a second time: if Ktor ever stopped decoding,
                // the escape sequence is inert rather than dangerous.
                val accepted =
                    resolveUploadTarget(session, "%2e%2e%2fpasswd").shouldBeInstanceOf<UploadTarget.Accepted>()
                accepted.path.toString() shouldBe "$session/%2e%2e%2fpasswd"
            }
        }

        test("backslash-separated traversal is refused — separators are normalised before the segment check") {
            withSession { session, _ ->
                resolveUploadTarget(session, WINDOWS_TRAVERSAL).shouldBeInstanceOf<UploadTarget.Refused>()
            }
        }

        test("a Windows drive-rooted path is refused") {
            withSession { session, _ ->
                resolveUploadTarget(session, WINDOWS_DRIVE_PATH).shouldBeInstanceOf<UploadTarget.Refused>()
            }
        }

        test("a symlinked directory inside the session cannot be used to escape it") {
            withSession { session, sessionNio ->
                val outside = Files.createTempDirectory("listenup-upload-outside-")
                try {
                    Files.createSymbolicLink(sessionNio.resolve("escape"), outside)
                    // Lexically this stays inside the session; only resolving the existing prefix
                    // through the link exposes that it does not.
                    resolveUploadTarget(session, "escape/pwned.m4b").shouldBeInstanceOf<UploadTarget.Refused>()
                } finally {
                    outside.toFile().deleteRecursively()
                }
            }
        }

        // ── malformed segments ──────────────────────────────────────────────────

        test("an empty relPath is refused") {
            withSession { session, _ -> resolveUploadTarget(session, "").shouldBeInstanceOf<UploadTarget.Refused>() }
        }

        test("a blank relPath is refused") {
            withSession { session, _ -> resolveUploadTarget(session, "   ").shouldBeInstanceOf<UploadTarget.Refused>() }
        }

        test("a doubled separator is refused rather than silently collapsed") {
            withSession { session, _ ->
                resolveUploadTarget(session, "book//01.mp3").shouldBeInstanceOf<UploadTarget.Refused>()
            }
        }

        test("a lone . segment is refused") {
            withSession { session, _ ->
                resolveUploadTarget(session, "book/./01.mp3").shouldBeInstanceOf<UploadTarget.Refused>()
            }
        }

        test("a whitespace-only segment is refused") {
            withSession { session, _ ->
                resolveUploadTarget(session, "book/ /01.mp3").shouldBeInstanceOf<UploadTarget.Refused>()
            }
        }

        test("a NUL byte is refused") {
            withSession { session, _ ->
                resolveUploadTarget(session, "book/\u0000evil.mp3").shouldBeInstanceOf<UploadTarget.Refused>()
            }
        }

        test("an absurdly deep path is refused") {
            withSession { session, _ ->
                val deep = (1..40).joinToString("/") { "d$it" } + "/x.mp3"
                resolveUploadTarget(session, deep).shouldBeInstanceOf<UploadTarget.Refused>()
            }
        }

        test("an overlong single segment is refused") {
            withSession { session, _ ->
                resolveUploadTarget(session, "a".repeat(300) + ".mp3").shouldBeInstanceOf<UploadTarget.Refused>()
            }
        }
    })

/** A backslash-separated escape, as a Windows client would spell it. */
private const val WINDOWS_TRAVERSAL = """..\..\Windows\evil.dll"""

/** A drive-rooted Windows path — rooted, so never a location within a selection. */
private const val WINDOWS_DRIVE_PATH = """C:\Windows\evil.dll"""
