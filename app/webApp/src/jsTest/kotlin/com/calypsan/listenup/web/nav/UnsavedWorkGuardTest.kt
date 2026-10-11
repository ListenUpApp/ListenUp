package com.calypsan.listenup.web.nav

import androidx.compose.runtime.CompositionLocalProvider
import com.calypsan.listenup.client.domain.model.ListenerRating
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiState
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.features.bookedit.BookEditPage
import com.calypsan.listenup.web.features.ratings.RateBookDialog
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.Event
import org.w3c.dom.EventInit

/**
 * The guard's reach beyond the page's own Cancel: the router asks before a sidebar link or Back
 * leaves the page, and a closed tab raises the browser's prompt, but only while there is a draft.
 */
class UnsavedWorkGuardTest :
    FunSpec({
        val mounts = MountRegistry()
        var originalUrl = ""
        beforeTest { originalUrl = window.location.pathname + window.location.search }
        afterTest {
            mounts.disposeAll()
            window.history.replaceState(null, "", originalUrl)
        }

        fun mountEdit(
            router: Router,
            dirty: Boolean,
        ): HTMLElement =
            mounts.mount {
                CompositionLocalProvider(LocalRouter provides router) {
                    BookEditPage(
                        state = BookEditUiState(isLoading = false, bookId = "b1", title = "Dune", hasChanges = dirty),
                        onEvent = {},
                        onOpenLibrary = {},
                        onOpenBook = {},
                    )
                }
            }

        test("an in-app navigation while a draft is held asks, and Discard then goes") {
            val router = Router()
            try {
                val host = mountEdit(router, dirty = true)
                awaitFrame()

                router.navigate(Route(listOf("library")))
                awaitFrame()

                asksToDiscard(host) shouldBe true
                window.location.pathname shouldBe originalUrl.substringBefore('?')

                discardDialogButton(host, "Discard").shouldNotBeNull().click()
                awaitFrame()

                window.location.pathname shouldBe "/library"
                router.current.segments shouldBe listOf("library")
            } finally {
                router.dispose()
            }
        }

        test("an in-app navigation while a draft is held, answered Keep, stays on the page") {
            val router = Router()
            try {
                val host = mountEdit(router, dirty = true)
                awaitFrame()

                router.navigate(Route(listOf("library")))
                awaitFrame()
                discardDialogButton(host, "Cancel").shouldNotBeNull().click()
                awaitFrame()

                asksToDiscard(host) shouldBe false
                window.location.pathname shouldBe originalUrl.substringBefore('?')
            } finally {
                router.dispose()
            }
        }

        test("a clean page does not hold the router") {
            val router = Router()
            try {
                val host = mountEdit(router, dirty = false)
                awaitFrame()

                router.navigate(Route(listOf("library")))
                awaitFrame()

                asksToDiscard(host) shouldBe false
                router.current.segments shouldBe listOf("library")
            } finally {
                router.dispose()
            }
        }

        test("the browser's Back while a draft is held asks, and Discard then goes back") {
            val router = Router()
            try {
                router.navigate(Route(listOf("library")))
                router.navigate(Route(listOf("book", "b1", "edit")))
                val host = mountEdit(router, dirty = true)
                awaitFrame()

                window.history.back()
                awaitPopstate()
                awaitFrame()

                asksToDiscard(host) shouldBe true
                router.current.segments shouldBe listOf("book", "b1", "edit")
                window.location.pathname shouldBe "/book/b1/edit"

                discardDialogButton(host, "Discard").shouldNotBeNull().click()
                awaitPopstate()
                awaitFrame()

                router.current.segments shouldBe listOf("library")
            } finally {
                router.dispose()
            }
        }

        // Only the dirty half is asserted: other specs mount edit pages and never dispose them, so a
        // "clean page does not prompt" check would hear their listeners on the shared window.
        test("closing the tab raises the browser prompt while there is a draft") {
            mountEdit(Router().apply { dispose() }, dirty = true)
            awaitFrame()
            val closing = Event("beforeunload", EventInit(cancelable = true))

            window.dispatchEvent(closing)

            closing.defaultPrevented shouldBe true
        }

        // The rate dialog is a modal over the page, so its question lives inside it.
        fun mountRate(
            current: ListenerRating?,
            onDismiss: () -> Unit,
        ): HTMLElement =
            mounts.mount {
                RateBookDialog(open = true, current = current, onSave = {
                    _,
                    _,
                    ->
                }, onClear = {}, onDismiss = onDismiss)
            }

        test("rate dialog: an untouched draft closes at once") {
            var closed = 0
            val host = mountRate(current = null) { closed++ }
            awaitFrame()

            discardDialogButton(host, "Cancel").shouldNotBeNull().click()
            awaitFrame()

            closed shouldBe 1
        }

        test("rate dialog: a typed note asks first; Cancel keeps it, Discard closes") {
            var closed = 0
            val host = mountRate(current = null) { closed++ }
            awaitFrame()
            val note = host.querySelector("textarea") as HTMLTextAreaElement
            note.value = "Gripping"
            note.dispatchEvent(Event("input", EventInit(bubbles = true)))
            awaitFrame()

            discardDialogButton(host, "Cancel").shouldNotBeNull().click()
            awaitFrame()

            closed shouldBe 0
            host
                .querySelector("dialog")
                ?.textContent
                .orEmpty()
                .contains("Discard your changes?") shouldBe true

            discardDialogButton(host, "Cancel").shouldNotBeNull().click()
            awaitFrame()
            closed shouldBe 0
            (host.querySelector("textarea") as HTMLTextAreaElement).value shouldBe "Gripping"

            discardDialogButton(host, "Cancel").shouldNotBeNull().click()
            awaitFrame()
            discardDialogButton(host, "Discard").shouldNotBeNull().click()
            awaitFrame()
            closed shouldBe 1
        }
    })

private suspend fun awaitPopstate() {
    kotlin.coroutines.suspendCoroutine<Unit> { continuation ->
        window.addEventListener("popstate", { _ -> continuation.resumeWith(Result.success(Unit)) }, js("({ once: true })"))
    }
}
