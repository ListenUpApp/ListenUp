package com.calypsan.listenup.server.io

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import java.nio.file.Files
import kotlinx.io.files.Path

class IsSymlinkTest :
    FunSpec({

        test("returns true for a symbolic link") {
            val dir = Files.createTempDirectory("listenup-symlink-")
            val target = Files.createFile(dir.resolve("target.txt"))
            val link = dir.resolve("link.txt")
            Files.createSymbolicLink(link, target)
            isSymlink(Path(link.toString())).shouldBeTrue()
        }

        test("returns false for a regular file") {
            val file = Files.createTempFile("listenup-regular-", ".txt")
            isSymlink(Path(file.toString())).shouldBeFalse()
        }

        test("returns false for a regular directory") {
            val dir = Files.createTempDirectory("listenup-dir-")
            isSymlink(Path(dir.toString())).shouldBeFalse()
        }

        test("returns false for a path that does not exist") {
            isSymlink(Path("/nonexistent/listenup/path/xyz")).shouldBeFalse()
        }
    })
