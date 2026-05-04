package com.calypsan.listenup.server.scanner.inference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FolderShapeTest :
    FunSpec({

        test("title only") {
            FolderShape.parse("The Way of Kings") shouldBe FolderShape(titleFolder = "The Way of Kings")
        }

        test("author / title") {
            FolderShape.parse("Brandon Sanderson/The Way of Kings") shouldBe
                FolderShape(titleFolder = "The Way of Kings", authorFolder = "Brandon Sanderson")
        }

        test("author / series / title") {
            FolderShape.parse("Brandon Sanderson/Stormlight Archive/The Way of Kings") shouldBe
                FolderShape(
                    titleFolder = "The Way of Kings",
                    seriesFolder = "Stormlight Archive",
                    authorFolder = "Brandon Sanderson",
                )
        }

        test("deeper nesting — only bottom three components used") {
            FolderShape.parse("library/extra/dirs/Brandon Sanderson/Stormlight Archive/The Way of Kings") shouldBe
                FolderShape(
                    titleFolder = "The Way of Kings",
                    seriesFolder = "Stormlight Archive",
                    authorFolder = "Brandon Sanderson",
                )
        }

        test("Windows separators normalized") {
            FolderShape.parse("Brandon Sanderson\\Stormlight Archive\\The Way of Kings") shouldBe
                FolderShape(
                    titleFolder = "The Way of Kings",
                    seriesFolder = "Stormlight Archive",
                    authorFolder = "Brandon Sanderson",
                )
        }

        test("leading and trailing slashes dropped") {
            FolderShape.parse("/Author/Title/") shouldBe
                FolderShape(titleFolder = "Title", authorFolder = "Author")
        }

        test("empty path") {
            FolderShape.parse("") shouldBe FolderShape(titleFolder = "")
        }
    })
