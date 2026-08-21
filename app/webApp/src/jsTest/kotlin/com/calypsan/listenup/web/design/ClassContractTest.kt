package com.calypsan.listenup.web.design

import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.client.presentation.auth.LoginErrorType
import com.calypsan.listenup.client.presentation.auth.LoginUiState
import com.calypsan.listenup.client.presentation.auth.PendingApprovalUiState
import com.calypsan.listenup.client.presentation.auth.RegisterUiState
import com.calypsan.listenup.client.presentation.auth.SetupUiState
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.web.features.auth.AuthLayout
import com.calypsan.listenup.web.features.auth.LoginForm
import com.calypsan.listenup.web.features.auth.PendingApprovalPanel
import com.calypsan.listenup.web.features.auth.RegisterForm
import com.calypsan.listenup.web.features.auth.SetupForm
import com.calypsan.listenup.web.features.bookdetail.readyBook
import androidx.compose.runtime.Composable
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.renderComposable
import com.calypsan.listenup.web.features.bookdetail.BookDetailPage
import com.calypsan.listenup.client.presentation.library.LibraryUiState
import com.calypsan.listenup.web.features.library.LibraryPage
import com.calypsan.listenup.web.features.library.contractBook
import com.calypsan.listenup.web.features.library.contractLibrary
import com.calypsan.listenup.web.features.nowplaying.PlaybackNotice
import com.calypsan.listenup.web.features.nowplaying.TransportBar
import com.calypsan.listenup.web.features.nowplaying.TransportState
import com.calypsan.listenup.web.shell.AccountMenu
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
                            onPlay = {},
                        )
                        BookDetailPage(
                            state = readyBook(),
                            tab = "chapters",
                            onSelectTab = {},
                            onOpenLibrary = {},
                            onPlay = {},
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
                            onPlay = {},
                        )
                        BookDetailPage(
                            state = BookDetailUiState.Loading,
                            tab = "overview",
                            onSelectTab = {},
                            onOpenLibrary = {},
                            onPlay = {},
                        )
                        // Every Library state draws classes of its own — the grid and sort row
                        // from a loaded page, and the two empty states, which are the ones most
                        // likely to be styled by eye and never looked at again.
                        LibraryPage(
                            state = contractLibrary(books = listOf(contractBook("b1", "Dune"))),
                            onEvent = {},
                            onOpenBook = {},
                        )
                        LibraryPage(state = contractLibrary(syncing = true), onEvent = {}, onOpenBook = {})
                        LibraryPage(state = contractLibrary(), onEvent = {}, onOpenBook = {})
                        LibraryPage(state = LibraryUiState.Loading, onEvent = {}, onOpenBook = {})
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
                        Field(label = "Email", value = "", leading = WebIcon.Mail, onInput = {})
                        Field(label = "Email", value = "", error = true, onInput = {})
                        PasswordField(label = "Password", value = "", onInput = {})
                        AuthLayout(title = "Sign in", subtitle = "Sub", badge = "Server administrator") {
                            LoginForm(
                                state = LoginUiState.Error(LoginErrorType.InvalidCredentials),
                                openRegistration = true,
                                onSubmit = { _, _ -> },
                                onRegister = {},
                            )
                        }
                        AuthLayout(title = "Create admin account") {
                            SetupForm(state = SetupUiState.Idle, onSubmit = { _, _, _, _, _ -> })
                        }
                        AuthLayout(title = "Create account") {
                            RegisterForm(
                                state = RegisterUiState.Error("nope"),
                                onSubmit = { _, _, _, _ -> },
                                onBack = {},
                            )
                        }
                        // Each pending state draws different classes, so each belongs in the
                        // contract — an invented class hides just as well in a denied state.
                        AuthLayout(title = "Waiting for approval") {
                            PendingApprovalPanel(
                                state = PendingApprovalUiState.Waiting,
                                email = "ada@example.com",
                                onCheckStatus = {},
                                onCancel = {},
                                onAcknowledge = {},
                            )
                        }
                        AuthLayout(title = "Waiting for approval") {
                            PendingApprovalPanel(
                                state = PendingApprovalUiState.Approved,
                                email = "ada@example.com",
                                onCheckStatus = {},
                                onCancel = {},
                                onAcknowledge = {},
                            )
                        }
                        AuthLayout(title = "Waiting for approval") {
                            PendingApprovalPanel(
                                state = PendingApprovalUiState.Denied("no"),
                                email = "ada@example.com",
                                onCheckStatus = {},
                                onCancel = {},
                                onAcknowledge = {},
                            )
                        }
                        AccountMenu(onSignOut = {})
                        PlaybackNotice(message = "Couldn't start this book.", onDismiss = {})
                        TransportBar(
                            state =
                                TransportState(
                                    title = "The Institute",
                                    isPlaying = true,
                                    positionMs = 61_000,
                                    durationMs = 3_600_000,
                                ),
                            onPlayPause = {},
                            onSeek = {},
                        )
                    }
                }

            val undefined = used - definedClasses()
            undefined shouldBe emptySet()
        }
    })

private val CLASS_SELECTOR = Regex("\\.([A-Za-z][A-Za-z0-9_-]*)")
