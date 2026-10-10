package com.calypsan.listenup.server.librarywrite

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.io.files.Path

/**
 * [ancestorPruneOps] — the one empty-ancestor walk Delete Book and Organize share. Pure path
 * arithmetic; the broker-backed behaviour is pinned by `BookDeleterTest` and
 * `MoveManifestExecutorTest`.
 */
class AncestorPruneOpsTest :
    FunSpec({
        val root = "/library"

        test("one prune per level between the directory and the library root, deepest first") {
            ancestorPruneOps(Path(root, "Author/Series/Book 1"), "Author/Series/Book 1") shouldBe
                listOf(
                    WriteOp.DeleteDirIfEmpty(Path(root, "Author/Series")),
                    WriteOp.DeleteDirIfEmpty(Path(root, "Author")),
                )
        }

        test("a directory directly under the root has no ancestors to prune — the root is never one") {
            ancestorPruneOps(Path(root, "Book"), "Book").shouldBeEmpty()
        }

        test("an empty relative path reaches nothing, not the root") {
            ancestorPruneOps(Path(root), "").shouldBeEmpty()
        }

        test("a trailing slash on the relative path does not add a level") {
            ancestorPruneOps(Path(root, "Author/Book"), "Author/Book/") shouldBe
                listOf(WriteOp.DeleteDirIfEmpty(Path(root, "Author")))
        }
    })
