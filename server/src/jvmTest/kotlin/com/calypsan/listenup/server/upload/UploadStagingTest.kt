package com.calypsan.listenup.server.upload

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path as NioPath
import kotlinx.io.files.Path as IoPath

/**
 * The staging half of the upload flow: session lifecycle, the `.part` indirection that keeps an
 * interrupted transfer from masquerading as a whole file, and the refusal of a session directory
 * that is a symbolic link.
 */
class UploadStagingTest :
    FunSpec({

        fun withHome(block: (UploadStaging, NioPath) -> Unit) {
            val home = Files.createTempDirectory("listenup-upload-staging-")
            try {
                block(UploadStaging(UploadPaths(IoPath(home.toString()))), home)
            } finally {
                home.toFile().deleteRecursively()
            }
        }

        test("a created session is openable, prefixed, and lives under uploads/") {
            withHome { staging, home ->
                val id = staging.createSession()
                id.startsWith(UPLOAD_SESSION_ID_PREFIX) shouldBe true
                val dir = staging.openSession(id).shouldNotBeNull()
                dir.toString() shouldBe home.resolve("uploads").resolve(id).toString()
                staging.stats(dir) shouldBe UploadSessionStats(fileCount = 0, totalBytes = 0L)
            }
        }

        test("an id this server did not mint never resolves to a path") {
            withHome { staging, _ ->
                staging.openSession("../../etc").shouldBeNull()
                staging.openSession("etc").shouldBeNull()
                staging.openSession("up-..").shouldBeNull()
                staging.openSession(UPLOAD_SESSION_ID_PREFIX).shouldBeNull()
            }
        }

        test("a session directory that is a symbolic link is refused") {
            withHome { staging, home ->
                // Without this refusal every later containment check is tautological: the session
                // root would resolve to wherever the link points, and files "inside the session"
                // would be files inside the link's target.
                val outside = Files.createTempDirectory("listenup-upload-symlink-target-")
                try {
                    val uploads = Files.createDirectories(home.resolve("uploads"))
                    val id = "${UPLOAD_SESSION_ID_PREFIX}symlinked"
                    Files.createSymbolicLink(uploads.resolve(id), outside)
                    staging.openSession(id).shouldBeNull()
                } finally {
                    outside.toFile().deleteRecursively()
                }
            }
        }

        test("a committed file counts toward the session totals; a part file is swept before ingest") {
            withHome { staging, _ ->
                val dir = staging.openSession(staging.createSession()).shouldNotBeNull()

                val whole = IoPath(dir, "book", "01.mp3")
                val part = staging.beginFile(whole)
                Files.write(NioPath.of(part.toString()), ByteArray(64))
                staging.commitFile(part, whole)

                // A second transfer that never completed leaves its part file behind.
                val interrupted = staging.beginFile(IoPath(dir, "book", "02.mp3"))
                Files.write(NioPath.of(interrupted.toString()), ByteArray(16))

                staging.stats(dir) shouldBe UploadSessionStats(fileCount = 2, totalBytes = 80L)
                staging.sweepPartFiles(dir) shouldBe 1
                staging.stats(dir) shouldBe UploadSessionStats(fileCount = 1, totalBytes = 64L)
            }
        }

        test("deleting a session removes everything staged under it") {
            withHome { staging, _ ->
                val id = staging.createSession()
                val dir = staging.openSession(id).shouldNotBeNull()
                val file = IoPath(dir, "deep", "nested", "x.m4b")
                val part = staging.beginFile(file)
                Files.write(NioPath.of(part.toString()), ByteArray(8))
                staging.commitFile(part, file)

                staging.deleteSession(dir)
                staging.openSession(id).shouldBeNull()
            }
        }
    })
