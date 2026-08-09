package com.calypsan.listenup.web.design

import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.web.features.bookdetail.readyBook
import androidx.compose.runtime.Composable
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.renderComposable
import com.calypsan.listenup.web.features.bookdetail.BookDetailPage
import com.calypsan.listenup.web.shell.NavEntry
import com.calypsan.listenup.web.shell.NavSection
import com.calypsan.listenup.web.shell.Shell
import org.w3c.dom.HTMLElement
import org.w3c.dom.css.CSSStyleSheet

/**
 * Guards the seam between Kotlin and `web.css`.
 *
 * The kit styles by class name, which makes a typo or an invented class invisible: the component
 * compiles, its tests pass, and it renders unstyled. That already happened once — a header span
 * was given a `.dt-head` class the sheet has never defined, and nothing caught it but eye.
 *
 * Asserting the *rendered* DOM against the *loaded* stylesheet catches both directions of that
 * mistake, and does it for every component at once rather than one assertion per class.
 */
class ClassContractTest :
    FunSpec({

        /** Every class selector defined anywhere in the loaded sheets. */
        fun definedClasses(): Set<String> {
            val defined = mutableSetOf<String>()
            val sheets = document.styleSheets
            for (i in 0 until sheets.length) {
                val sheet = sheets.item(i) as? CSSStyleSheet ?: continue
                val rules =
                    runCatching { sheet.cssRules }.getOrNull() ?: continue
                for (j in 0 until rules.length) {
                    val selector = rules.item(j).asDynamic().selectorText as? String ?: continue
                    CLASS_SELECTOR.findAll(selector).forEach { defined += it.groupValues[1] }
                }
            }
            return defined
        }

        fun classesUsedIn(content: @Composable () -> Unit): Set<String> {
            val host = document.createElement("div") as HTMLElement
            document.body!!.appendChild(host)
            renderComposable(root = host) { content() }

            val used = mutableSetOf<String>()
            val all = host.querySelectorAll("*")
            for (i in 0 until all.length) {
                val element = all.item(i) as? HTMLElement ?: continue
                val list = element.classList
                for (j in 0 until list.length) {
                    list.item(j)?.let { used += it }
                }
            }
            return used
        }

        test("web.css is actually loaded, or this whole spec is vacuous") {
            // Without this, an unloaded stylesheet makes `definedClasses()` empty and every
            // assertion below would fail loudly rather than silently — but a *partially* loaded
            // sheet would not. Pin a class we know the sheet defines.
            definedClasses().contains("tblwrap") shouldBe true
        }

        test("every class the kit renders is defined in the design sheet") {
            val used =
                classesUsedIn {
                    DataTable(
                        columns =
                            listOf(
                                TableColumn<String>("a", "A", mono = true) { Text(it) },
                                TableColumn<String>("b", "B") { Text(it) },
                            ),
                        rows = listOf("one", "two"),
                        selectable = true,
                        isSelected = { it == "one" },
                        isPlaying = { it == "two" },
                        sortKey = "a",
                        allState = SelectAllState.Some,
                        rowActions = listOf(WebIcon.Play, WebIcon.Pencil),
                    )
                    WebAppSurface {
                        Shell(
                            sections =
                                listOf(
                                    NavSection(listOf(NavEntry("home", "Home", WebIcon.Home))),
                                    NavSection(listOf(NavEntry("shelves", "Shelves", WebIcon.Bookmark)), label = "Yours"),
                                ),
                            active = "home",
                            footer = listOf(NavEntry("settings", "Settings", WebIcon.Cog)),
                            onToggleCollapse = {},
                        ) {}
                        Shell(
                            sections = listOf(NavSection(listOf(NavEntry("home", "Home", WebIcon.Home)))),
                            active = "home",
                            collapsed = true,
                            onToggleCollapse = {},
                        ) {}
                        BookDetailPage(
                            state = readyBook(),
                            tab = "overview",
                            onSelectTab = {},
                            onOpenLibrary = {},
                        )
                        BookDetailPage(
                            state = readyBook(),
                            tab = "chapters",
                            onSelectTab = {},
                            onOpenLibrary = {},
                            selection = setOf(1, 2),
                        )
                        // The states with no book draw classes of their own, so they belong in
                        // the contract too — an invented class hides just as well in an empty
                        // state as in a full one.
                        BookDetailPage(
                            state = BookDetailUiState.Error(BookError.NotFound()),
                            tab = "overview",
                            onSelectTab = {},
                            onOpenLibrary = {},
                        )
                        BookDetailPage(
                            state = BookDetailUiState.Loading,
                            tab = "overview",
                            onSelectTab = {},
                            onOpenLibrary = {},
                        )
                        BulkBar(count = 2, actions = listOf(BulkAction("Merge", WebIcon.Merge) {}), onClear = {})
                        Panel(title = "Details", trailing = { Text("x") }) {
                            MetaList(listOf(MetaEntry("Duration", "18:40:11", machine = true)))
                        }
                        Breadcrumb(listOf("Library", "The Institute"))
                        Tabs(listOf(TabItem("a", "A", count = "3")), active = "a")
                        SegmentedControl(listOf(SegmentItem("s", "S")), active = "s")
                        Pill("Horror", selected = true, onRemove = {})
                        Cover(title = "The Institute")
                        ProgressLine(percent = 49, remaining = "9h 18m left")
                    }
                }

            val undefined = used - definedClasses()
            undefined shouldBe emptySet()
        }
    })

private val CLASS_SELECTOR = Regex("\\.([A-Za-z][A-Za-z0-9_-]*)")
