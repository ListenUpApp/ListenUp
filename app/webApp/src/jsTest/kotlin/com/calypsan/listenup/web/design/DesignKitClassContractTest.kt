package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.presentation.auth.ForgotPasswordUiState
import com.calypsan.listenup.client.presentation.auth.LoginErrorType
import com.calypsan.listenup.client.presentation.auth.LoginUiState
import com.calypsan.listenup.client.presentation.auth.PendingApprovalUiState
import com.calypsan.listenup.client.presentation.auth.RegisterUiState
import com.calypsan.listenup.client.presentation.auth.SetupUiState
import com.calypsan.listenup.client.presentation.invite.ClaimInviteUiState
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.client.presentation.library.SortDirection
import com.calypsan.listenup.client.presentation.library.SortState
import com.calypsan.listenup.web.features.auth.AuthLayout
import com.calypsan.listenup.web.features.auth.ClaimInvitePanel
import com.calypsan.listenup.web.features.auth.ForgotPasswordPanel
import com.calypsan.listenup.web.features.auth.LoginForm
import com.calypsan.listenup.web.features.auth.PendingApprovalPanel
import com.calypsan.listenup.web.features.auth.RegisterForm
import com.calypsan.listenup.web.features.auth.SetupForm
import com.calypsan.listenup.web.features.auth.invitePreview
import com.calypsan.listenup.web.shell.AccountMenu
import com.calypsan.listenup.web.shell.NavEntry
import com.calypsan.listenup.web.shell.NavSection
import com.calypsan.listenup.web.shell.Shell
import io.kotest.core.spec.style.FunSpec
import org.jetbrains.compose.web.dom.Text

/**
 * Every class the design kit's own components and the sign-in flow render is defined in the design sheet. A failure names the shape that rendered
 * the undefined class; see [ClassContractTest] for why the contract exists.
 */
class DesignKitClassContractTest :
    FunSpec({
        test("every class the design kit's own components and the sign-in flow render is defined in the design sheet") {
            assertClassesDefined(kitContractShapes())
        }
    })

private fun kitContractShapes(): Map<String, @Composable () -> Unit> =
    kitShapes1() +
        kitShapes2() +
        kitShapes3()

private fun kitShapes1(): Map<String, @Composable () -> Unit> =
    shapes(
        shape("#1: DataTable( columns = listOf( TableColumn<String>(a,") {
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
        },
        shape("#2: Shell( sections = listOf( NavSection(listOf(NavEntry") {
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
        },
        shape("#3: Shell( sections = listOf(NavSection(listOf(NavEntry(") {
            Shell(
                sections = listOf(NavSection(listOf(NavEntry("home", "Home", WebIcon.Home)))),
                active = "home",
                collapsed = true,
                onToggleCollapse = {},
            ) {}
        },
        shape("#4: BulkBar( count = 2, label = 2 rows selected, actions") {
            BulkBar(
                count = 2,
                label = "2 rows selected",
                actions = listOf(BulkAction("Merge", WebIcon.Merge) {}),
                onClear = {},
            )
        },
        shape("#5: Panel(title = Details, trailing = { Text(x) }) { Met") {
            Panel(title = "Details", trailing = { Text("x") }) {
                MetaList(listOf(MetaEntry("Duration", "18:40:11", machine = true)))
            }
        },
        shape("#6: Breadcrumb(listOf(Library, The Institute))") {
            Breadcrumb(listOf("Library", "The Institute"))
        },
        shape("#7: Tabs(listOf(TabItem(a, A, count = 3)), active = a, i") {
            Tabs(listOf(TabItem("a", "A", count = "3")), active = "a", idBase = "cc")
        },
        shape("#8: TabPanel(idBase = cc, key = a) { Text(A) }") {
            TabPanel(idBase = "cc", key = "a") { Text("A") }
        },
        shape("#9: SegmentedControl(listOf(SegmentItem(s, S)), active =") {
            SegmentedControl(listOf(SegmentItem("s", "S")), active = "s", label = "Show")
        },
        shape("#10: SortDropdown( noun = books, categories = listOf(Sort") {
            SortDropdown(
                noun = "books",
                categories = listOf(SortCategory.TITLE, SortCategory.AUTHOR),
                state = SortState(SortCategory.TITLE, SortDirection.ASCENDING),
                onCategory = {},
                onToggleDirection = {},
                ignoreArticles = true,
                onToggleIgnoreArticles = {},
            )
        },
        shape("#11: LinkButton(Back to sign in, onClick = {})") {
            LinkButton("Back to sign in", onClick = {})
        },
        shape("#12: Pill(Horror, selected = true, onRemove = {})") {
            Pill("Horror", selected = true, onRemove = {})
        },
        shape("#13: Cover(title = The Institute)") {
            Cover(title = "The Institute")
        },
        shape("#14: ProgressLook.entries.forEach { look -> ProgressBar(v") {
            ProgressLook.entries.forEach { look -> ProgressBar(value = 0.49f, label = "Progress", look = look) }
        },
        shape("#15: ProgressBar(value = null, label = Progress, caption") {
            ProgressBar(value = null, label = "Progress", caption = "9h 18m left")
        },
        shape("#16: EmptyState(title = Nothing yet, body = Body, icon =") {
            EmptyState(title = "Nothing yet", body = "Body", icon = WebIcon.Book) {}
        },
    )

private fun kitShapes2(): Map<String, @Composable () -> Unit> =
    shapes(
        shape("#17: EmptyState(title = Nothing yet, look = EmptyLook.Ins") {
            EmptyState(title = "Nothing yet", look = EmptyLook.Inset)
        },
        shape("#18: EmptyState(title = Nothing yet, look = EmptyLook.Inl") {
            EmptyState(title = "Nothing yet", look = EmptyLook.Inline)
        },
        shape("#19: LoadingState()") {
            LoadingState()
        },
        shape("#20: PageHeader(title = Title, eyebrow = Eyebrow, subtitl") {
            PageHeader(title = "Title", eyebrow = "Eyebrow", subtitle = "Sub", display = true, pending = true) {}
        },
        shape("#21: ButtonKind.entries.forEach { kind -> ButtonSize.entr") {
            ButtonKind.entries.forEach { kind ->
                ButtonSize.entries.forEach { size -> Button(kind = kind, size = size, label = "Go", fill = true) {} }
            }
        },
        shape("#22: Field(label = Email, value = , leading = WebIcon.Mai") {
            Field(label = "Email", value = "", leading = WebIcon.Mail, onInput = {})
        },
        shape("#23: Field(label = Email, value = , error = true, onInput") {
            Field(label = "Email", value = "", error = true, onInput = {})
        },
        shape("#24: PasswordField(label = Password, value = , onInput =") {
            PasswordField(label = "Password", value = "", onInput = {})
        },
        shape("#25: AuthLayout(title = Sign in, subtitle = Sub, badge =") {
            AuthLayout(title = "Sign in", subtitle = "Sub", badge = "Server administrator") {
                LoginForm(
                    state = LoginUiState.Error(LoginErrorType.InvalidCredentials),
                    openRegistration = true,
                    onSubmit = { _, _ -> },
                    onRegister = {},
                    onForgotPassword = {},
                    onClaimInvite = {},
                )
            }
        },
        shape("#26: AuthLayout(title = Create admin account) { SetupForm") {
            AuthLayout(title = "Create admin account") {
                SetupForm(state = SetupUiState.Idle, onSubmit = { _, _, _, _, _ -> })
            }
        },
        shape("#27: AuthLayout(title = Create account) { RegisterForm( s") {
            AuthLayout(title = "Create account") {
                RegisterForm(
                    state = RegisterUiState.Error("nope"),
                    onSubmit = { _, _, _, _ -> },
                    onBack = {},
                )
            }
        },
        shape("#28: AuthLayout(title = Waiting for approval) { PendingAp") {
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
        },
        shape("#29: AuthLayout(title = Waiting for approval) { PendingAp") {
            AuthLayout(title = "Waiting for approval") {
                PendingApprovalPanel(
                    state = PendingApprovalUiState.Approved,
                    email = "ada@example.com",
                    onCheckStatus = {},
                    onCancel = {},
                    onAcknowledge = {},
                )
            }
        },
        shape("#30: AuthLayout(title = Waiting for approval) { PendingAp") {
            AuthLayout(title = "Waiting for approval") {
                PendingApprovalPanel(
                    state = PendingApprovalUiState.Denied("no"),
                    email = "ada@example.com",
                    onCheckStatus = {},
                    onCancel = {},
                    onAcknowledge = {},
                )
            }
        },
    )

private fun kitShapes3(): Map<String, @Composable () -> Unit> =
    shapes(
        shape("#31: listOf( ForgotPasswordUiState.EnterEmail, ForgotPass") {
            // Every step of the reset flow draws a different tree, so each belongs
            // in the contract for the same reason the pending states above do.
            listOf(
                ForgotPasswordUiState.EnterEmail,
                ForgotPasswordUiState.AwaitingApproval("t1"),
                ForgotPasswordUiState.EnterCode("t1", attemptsRemaining = 2, error = "That code is wrong."),
                ForgotPasswordUiState.Denied,
                ForgotPasswordUiState.Complete,
                ForgotPasswordUiState.Error("Your reset request expired. Please start again."),
            ).forEach { resetState ->
                AuthLayout(title = "Reset your password") {
                    ForgotPasswordPanel(
                        state = resetState,
                        onRequestReset = {},
                        onCompleteReset = { _, _ -> },
                        onCheckStatus = {},
                        onRetryRequest = {},
                        onBackToSignIn = {},
                    )
                }
            }
        },
        shape("#32: listOf( ClaimInviteUiState.Idle, ClaimInviteUiState.") {
            listOf(
                ClaimInviteUiState.Idle,
                ClaimInviteUiState.Preview(invitePreview()),
                ClaimInviteUiState.Preview(invitePreview(valid = false)),
                ClaimInviteUiState.Submitting,
                ClaimInviteUiState.Claimed,
                ClaimInviteUiState.Error("That code does not exist."),
            ).forEach { inviteState ->
                AuthLayout(title = "Join a library") {
                    ClaimInvitePanel(
                        state = inviteState,
                        onCodeEntered = {},
                        onClaim = { _, _, _ -> },
                        onBackToSignIn = {},
                    )
                }
            }
        },
        shape("#33: ToastHost( ToastQueue().apply { show(Saved., ToastTo") {
            ToastHost(
                ToastQueue().apply {
                    show("Saved.", ToastTone.Notice)
                    show("Could not reach the server.", ToastTone.Failure)
                },
            )
        },
        shape("#34: AccountMenu(onSignOut = {})") {
            AccountMenu(onSignOut = {})
        },
    )
