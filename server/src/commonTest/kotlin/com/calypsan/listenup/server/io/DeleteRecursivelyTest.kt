package com.calypsan.listenup.server.io

import com.calypsan.listenup.server.librarywrite.createSymbolicLink
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random

/**
 * [deleteRecursively] treats a symbolic link as a leaf, wherever it points and whether or not it
 * resolves: the link is unlinked, never descended into, never followed. On the JVM and natively.
 */
class DeleteRecursivelyTest :
    FunSpec({

        fun tempDir(): Path =
            Path(SystemTemporaryDirectory, "delete-recursively-${Random.nextLong().toULong().toString(16)}")
                .also { SystemFileSystem.createDirectories(it) }

        test("removes a tree of files and directories") {
            val base = tempDir()
            val tree = Path(base, "tree")
            Path(tree, "a", "b").also { SystemFileSystem.createDirectories(it) }
            Path(tree, "a", "b", "file.txt").writeText("x")

            deleteRecursively(tree)

            SystemFileSystem.exists(tree) shouldBe false
        }

        test("a directory link inside the tree is unlinked, and what it points at survives") {
            val base = tempDir()
            val photos = Path(base, "Photos").also { SystemFileSystem.createDirectories(it) }
            val wedding = Path(photos, "wedding.jpg").apply { writeText("USER DATA") }
            val tree = Path(base, "tree").also { SystemFileSystem.createDirectories(it) }
            createSymbolicLink(Path(tree, "photos"), photos)

            deleteRecursively(tree)

            SystemFileSystem.exists(tree) shouldBe false
            withClue("the walk must never pass through the link") { wedding.readText() shouldBe "USER DATA" }
        }

        test("dangling and looping links inside the tree are removed, so the tree itself can go") {
            val base = tempDir()
            val tree = Path(base, "tree").also { SystemFileSystem.createDirectories(it) }
            createSymbolicLink(Path(tree, "dangling"), Path(base, "never-existed"))
            createSymbolicLink(Path(tree, "loop"), Path(tree, "loop"))

            deleteRecursively(tree)

            SystemFileSystem.exists(tree) shouldBe false
            isSymlink(tree) shouldBe false
        }

        test("a link passed as the root is unlinked, and its target is left alone") {
            val base = tempDir()
            val real = Path(base, "real").also { SystemFileSystem.createDirectories(it) }
            val keep = Path(real, "keep.txt").apply { writeText("keep") }
            val link = Path(base, "link")
            createSymbolicLink(link, real)

            deleteRecursively(link)

            isSymlink(link) shouldBe false
            keep.readText() shouldBe "keep"
        }
    })
