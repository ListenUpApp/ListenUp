package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.web.shell.KeyTarget
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Spec §5.8's web key table, and the rule for when the player stands down. Pure. */
class PlayerKeysTest :
    FunSpec({
        val loaded = KeyContext(isLoaded = true, onPlayingPage = false)
        val onPage = KeyContext(isLoaded = true, onPlayingPage = true)

        val table =
            listOf(
                KeyPress(" ") to PlayerKey.PlayPause,
                KeyPress("ArrowLeft") to PlayerKey.SkipBack,
                KeyPress("ArrowRight") to PlayerKey.SkipForward,
                KeyPress("ArrowLeft", shift = true) to PlayerKey.PreviousChapter,
                KeyPress("ArrowRight", shift = true) to PlayerKey.NextChapter,
                KeyPress("<", shift = true) to PlayerKey.Slower,
                KeyPress(">", shift = true) to PlayerKey.Faster,
                KeyPress("t") to PlayerKey.Sleep,
                KeyPress("T", shift = true) to PlayerKey.Sleep,
                KeyPress("c") to PlayerKey.Chapters,
                KeyPress("p") to PlayerKey.TogglePlayer,
                KeyPress("?", shift = true) to PlayerKey.Shortcuts,
            )

        test("every row of the table maps to its key on the page itself") {
            table.forEach { (press, key) -> playerKeyFor(press, loaded) shouldBe key }
        }

        test("keys are ignored in an <input>, and in every other field") {
            val field = loaded.copy(target = KeyTarget(isEditable = true))
            (table.map { it.first } + KeyPress("Escape")).forEach { press ->
                playerKeyFor(press, field.copy(onPlayingPage = true)) shouldBe null
            }
        }

        test("inside a dialog every key stands down, Esc too: the panel closes itself") {
            val dialog = onPage.copy(target = KeyTarget(isInDialog = true))
            (table.map { it.first } + KeyPress("Escape")).forEach { press -> playerKeyFor(press, dialog) shouldBe null }
        }

        test("Space on a focused button is the button's; Shift+arrow on a button is still the chapter") {
            val button = loaded.copy(target = KeyTarget(activates = true))
            playerKeyFor(KeyPress(" "), button) shouldBe null
            playerKeyFor(KeyPress("ArrowLeft", shift = true), button) shouldBe PlayerKey.PreviousChapter
            playerKeyFor(KeyPress("t"), button) shouldBe PlayerKey.Sleep
        }

        test("an arrow in a radio group or a grid moves through it") {
            val group = loaded.copy(target = KeyTarget(activates = true, inComposite = true))
            playerKeyFor(KeyPress("ArrowLeft"), group) shouldBe null
            playerKeyFor(KeyPress("ArrowRight", shift = true), group) shouldBe null
        }

        test("Ctrl, ⌘ and Alt belong to the browser, the OS and the palette") {
            playerKeyFor(KeyPress("ArrowLeft", ctrl = true), loaded) shouldBe null
            playerKeyFor(KeyPress("ArrowLeft", meta = true), loaded) shouldBe null
            playerKeyFor(KeyPress("ArrowLeft", alt = true), loaded) shouldBe null
            playerKeyFor(KeyPress("k", meta = true), loaded) shouldBe null
        }

        test("a press being composed, or one already handled, is not the player's") {
            playerKeyFor(KeyPress(" ", isComposing = true), loaded) shouldBe null
            playerKeyFor(KeyPress(" ", defaultPrevented = true), loaded) shouldBe null
        }

        test("with nothing loaded there is nothing to control") {
            val nothing = KeyContext(isLoaded = false, onPlayingPage = false)
            (table.map { it.first } + KeyPress("Escape")).forEach { press -> playerKeyFor(press, nothing) shouldBe null }
        }

        test("Esc closes Now Playing, and only there; P still toggles it there") {
            playerKeyFor(KeyPress("Escape"), loaded) shouldBe null
            playerKeyFor(KeyPress("Escape"), onPage) shouldBe PlayerKey.ClosePlayer
            playerKeyFor(KeyPress("p"), onPage) shouldBe PlayerKey.TogglePlayer
        }

        test("keys the table does not name are left alone, / included, which is the palette's") {
            playerKeyFor(KeyPress("/"), loaded) shouldBe null
            playerKeyFor(KeyPress("x"), loaded) shouldBe null
            playerKeyFor(KeyPress("Enter"), loaded) shouldBe null
        }

        test("the sheet's table names each key once and spells its aria-keyshortcuts") {
            playerShortcuts(platform = "MacIntel").map { it.aria } shouldBe
                listOf(
                    "Space",
                    "ArrowLeft ArrowRight",
                    "Shift+ArrowLeft Shift+ArrowRight",
                    "< >",
                    "T",
                    "C",
                    "P",
                    "Escape",
                    "?",
                    "/ Control+K Meta+K",
                )
        }

        test("search reads ⌘K on Apple platforms and Ctrl+K everywhere else; both work everywhere") {
            val mac = playerShortcuts(platform = "macOS").last()
            mac.keys shouldBe listOf("/", "⌘K")
            playerShortcuts(platform = "iPad").last().keys shouldBe listOf("/", "⌘K")
            listOf("Windows", "Linux x86_64", "Android", "").forEach { platform ->
                val search = playerShortcuts(platform = platform).last()
                search.keys shouldBe listOf("/", "Ctrl+K")
                search.aria shouldBe "/ Control+K Meta+K"
            }
        }

        test("Shift+← stands down in a chapterless book, where there is no previous chapter; ← still skips") {
            val chapterless = loaded.copy(hasPreviousChapter = false)
            playerKeyFor(KeyPress("ArrowLeft", shift = true), chapterless) shouldBe null
            playerKeyFor(KeyPress("ArrowLeft"), chapterless) shouldBe PlayerKey.SkipBack
            playerKeyFor(KeyPress("ArrowRight", shift = true), chapterless) shouldBe PlayerKey.NextChapter
        }
    })
