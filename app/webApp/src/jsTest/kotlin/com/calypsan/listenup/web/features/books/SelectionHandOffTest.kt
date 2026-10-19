package com.calypsan.listenup.web.features.books

import androidx.compose.runtime.CompositionLocalProvider
import com.calypsan.listenup.client.presentation.books.SelectionMode
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.features.library.BookSelectionScaffold
import com.calypsan.listenup.web.nav.Router
import com.calypsan.listenup.web.nav.Routes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.Text

/**
 * A selection survives a trip to the bulk editor and back, and an applied edit ends it — what
 * Android's Library does. A web page's selection session closes with its route, so without the
 * hand-off the picks were gone on return.
 */
class SelectionHandOffTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest {
            mounts.disposeAll()
            window.history.replaceState(null, "", "/")
        }

        test("held picks are handed back once, to the page they were made on") {
            val handOff = SelectionHandOff()
            handOff.hold(Routes.discover, listOf("b1", "b2"))
            handOff.origin?.toUrl() shouldBe "/discover"
            handOff.takeFor(Routes.library).shouldBeNull()
            handOff.takeFor(Routes.discover) shouldBe listOf("b1", "b2")
            handOff.takeFor(Routes.discover).shouldBeNull()
        }

        test("an applied edit ends the selection, so nothing comes back") {
            val handOff = SelectionHandOff()
            handOff.hold(Routes.discover, listOf("b1"))
            handOff.end()
            handOff.takeFor(Routes.discover).shouldBeNull()
            handOff.origin.shouldBeNull()
        }

        test("returning from the editor restores the picks on the page's new session") {
            window.history.replaceState(null, "", "/discover")
            val router = Router()
            val handOff = SelectionHandOff().apply { hold(Routes.discover, listOf("b1", "b2")) }
            val calls = mutableListOf<String>()
            mounts.mount {
                CompositionLocalProvider(LocalSelectionHandOff provides handOff) {
                    BookSelectionScaffold(
                        fixedMultiSelect(
                            selectionMode = SelectionMode.None,
                            onEnter = { calls += "enter" },
                            onToggle = { calls += "toggle:$it" },
                        ),
                        router,
                        onToast = {},
                    ) { Text("page") }
                }
            }
            awaitFrame()
            calls shouldBe listOf("enter", "toggle:b1", "toggle:b2")
        }
    })
