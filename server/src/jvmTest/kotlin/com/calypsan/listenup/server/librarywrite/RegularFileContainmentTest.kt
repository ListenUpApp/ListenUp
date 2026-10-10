package com.calypsan.listenup.server.librarywrite

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlinx.io.files.Path as IoPath

private fun Path.io(): IoPath = IoPath(toRealPath().toString())

@OptIn(ExperimentalPathApi::class)
class RegularFileContainmentTest :
    FunSpec({

        fun withDirs(block: (root: Path, outside: Path) -> Unit) {
            val root = Files.createTempDirectory("lib-root")
            val outside = Files.createTempDirectory("lib-outside")
            try {
                block(root, outside)
            } finally {
                root.deleteRecursively()
                outside.deleteRecursively()
            }
        }

        test("a plain file inside the root is a contained regular file") {
            withDirs { root, _ ->
                val file = root.resolve("a.mp3").apply { writeText("x") }
                isRegularFileInsideAnyRoot(file.io(), listOf(root.io())) shouldBe true
            }
        }

        test("a link to a file inside the root is a contained regular file") {
            withDirs { root, _ ->
                val target = root.resolve("a.mp3").apply { writeText("x") }
                val link = root.resolve("alias.mp3")
                runCatching { Files.createSymbolicLink(link, target) }.getOrElse { return@withDirs }
                isRegularFileInsideAnyRoot(IoPath(link.toString()), listOf(root.io())) shouldBe true
            }
        }

        test("a link to a file outside every root is refused") {
            withDirs { root, outside ->
                val target = outside.resolve("secret.txt").apply { writeText("x") }
                val link = root.resolve("escape.pdf")
                runCatching { Files.createSymbolicLink(link, target) }.getOrElse { return@withDirs }
                isRegularFileInsideAnyRoot(IoPath(link.toString()), listOf(root.io())) shouldBe false
            }
        }

        test("a link to a directory inside the root is refused") {
            withDirs { root, _ ->
                val dir = root.resolve("sub").apply { createDirectories() }
                val link = root.resolve("dirlink")
                runCatching { Files.createSymbolicLink(link, dir) }.getOrElse { return@withDirs }
                isRegularFileInsideAnyRoot(IoPath(link.toString()), listOf(root.io())) shouldBe false
            }
        }

        test("a dangling link is refused") {
            withDirs { root, _ ->
                val link = root.resolve("dangling")
                runCatching { Files.createSymbolicLink(link, root.resolve("missing")) }.getOrElse { return@withDirs }
                isRegularFileInsideAnyRoot(IoPath(link.toString()), listOf(root.io())) shouldBe false
            }
        }

        test("a directory is refused") {
            withDirs { root, _ ->
                val dir = root.resolve("sub").apply { createDirectories() }
                isRegularFileInsideAnyRoot(dir.io(), listOf(root.io())) shouldBe false
            }
        }

        test("a path that does not exist is refused") {
            withDirs { root, _ ->
                isRegularFileInsideAnyRoot(IoPath(root.resolve("nope.mp3").toString()), listOf(root.io())) shouldBe false
            }
        }
    })
