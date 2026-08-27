package com.calypsan.listenup.web.features.shelf

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** The shelf URL grammar, testable without mounting anything. */
class ShelfRouteTest :
    FunSpec({

        test("the three shelf URLs each read as their own screen") {
            shelfRouteOf(listOf("shelf", "new")) shouldBe ShelfRoute.Create
            shelfRouteOf(listOf("shelf", "s1")) shouldBe ShelfRoute.Detail("s1")
            shelfRouteOf(listOf("shelf", "s1", "edit")) shouldBe ShelfRoute.Edit("s1")
        }

        test("a shelf literally named new still resolves to the create form") {
            // Documented rather than defended: `new` is a reserved segment here. A shelf whose ID
            // were the string "new" would be unreachable — ids are server-minted, so this is a note
            // for whoever changes that, not a live hazard.
            shelfRouteOf(listOf("shelf", "new")) shouldBe ShelfRoute.Create
        }

        test("anything that is not a shelf URL is not a shelf route") {
            shelfRouteOf(emptyList()) shouldBe null
            shelfRouteOf(listOf("library")) shouldBe null
            shelfRouteOf(listOf("shelf")) shouldBe null
        }

        test("a malformed shelf URL falls through rather than opening an empty screen") {
            // A blank id would load a shelf that cannot exist and render its error; better to let
            // the shell say it does not know this URL.
            shelfRouteOf(listOf("shelf", "")) shouldBe null
            shelfRouteOf(listOf("shelf", "s1", "banana")) shouldBe null
            shelfRouteOf(listOf("shelf", "s1", "edit", "extra")) shouldBe null
        }
    })
