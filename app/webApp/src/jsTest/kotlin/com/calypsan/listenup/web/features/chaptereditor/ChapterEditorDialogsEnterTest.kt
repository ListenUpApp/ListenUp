package com.calypsan.listenup.web.features.chaptereditor

import com.calypsan.listenup.client.presentation.chaptereditor.ChapterMarkerTier
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.w3c.dom.EventInit
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event

/**
 * Enter in the chapter editor's one-field dialogs does what the verb does — once, and only when the
 * verb is enabled. Driven through `requestSubmit()`, the path a browser's implicit submission takes
 * (see `EnterSubmitsTest`), because a synthetic keypress never submits anything.
 */
class ChapterEditorDialogsEnterTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        suspend fun typeAndPressEnter(
            host: HTMLElement,
            value: String,
        ) {
            val field = host.querySelector("dialog input") as HTMLInputElement
            field.value = value
            field.dispatchEvent(Event("input", EventInit(bubbles = true)))
            awaitFrame()
            val form = field.form ?: error("not in a form — Enter cannot submit it")
            form.asDynamic().requestSubmit()
            awaitFrame()
        }

        test("Enter renames a chapter, once") {
            val renamed = mutableListOf<String>()
            val host =
                mounts.mount { RenameChapterDialog(initialTitle = "One", onConfirm = { renamed += it }, onDismiss = {}) }

            typeAndPressEnter(host, "The Gunslinger")

            renamed shouldBe listOf("The Gunslinger")
        }

        test("Enter on a blank chapter title renames nothing") {
            val renamed = mutableListOf<String>()
            val host =
                mounts.mount { RenameChapterDialog(initialTitle = "One", onConfirm = { renamed += it }, onDismiss = {}) }

            typeAndPressEnter(host, "   ")

            renamed shouldBe emptyList()
        }

        test("Enter names a new marker, once") {
            val named = mutableListOf<String>()
            val host =
                mounts.mount {
                    MarkerNameDialog(
                        tier = ChapterMarkerTier.Part,
                        isNew = true,
                        initialName = "",
                        onConfirm = { named += it },
                        onDismiss = {},
                    )
                }

            typeAndPressEnter(host, " Part One ")

            named shouldBe listOf("Part One")
        }

        test("Enter sets a typed start time, and not one that is not a time") {
            val set = mutableListOf<Long>()
            val host = mounts.mount { ChapterTimeDialog(initialMs = 0L, onConfirm = { set += it }, onDismiss = {}) }

            typeAndPressEnter(host, "nonsense")
            set shouldBe emptyList()

            typeAndPressEnter(host, "1:00")
            set shouldBe listOf(60_000L)
        }
    })
