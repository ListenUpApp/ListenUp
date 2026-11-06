package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.dto.admin.HardcoverApiTokenStatus
import com.calypsan.listenup.api.dto.admin.HardcoverSourceStatus
import com.calypsan.listenup.api.dto.auth.PasswordResetRequest
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.backup.BackupEvent
import com.calypsan.listenup.api.dto.uploads.UploadedBookStatus
import com.calypsan.listenup.api.error.HardcoverError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.model.InviteInfo
import com.calypsan.listenup.client.domain.model.PermissionPreset
import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.client.domain.model.UserPermissions
import com.calypsan.listenup.client.presentation.admin.ABSImportListUiState
import com.calypsan.listenup.client.presentation.admin.AdminBackupUiState
import com.calypsan.listenup.client.presentation.admin.AdminCategoriesUiState
import com.calypsan.listenup.client.presentation.admin.AdminCollectionDetailUiState
import com.calypsan.listenup.client.presentation.admin.AdminCollectionsUiState
import com.calypsan.listenup.client.presentation.admin.AdminInboxUiState
import com.calypsan.listenup.client.presentation.admin.AdminSettingsUiState
import com.calypsan.listenup.client.presentation.admin.AdminUiState
import com.calypsan.listenup.client.presentation.admin.CreateInviteErrorType
import com.calypsan.listenup.client.presentation.admin.CreateInviteField
import com.calypsan.listenup.client.presentation.admin.CreateInviteStatus
import com.calypsan.listenup.client.presentation.admin.CreateInviteUiState
import com.calypsan.listenup.client.presentation.admin.HardcoverTokenSave
import com.calypsan.listenup.client.presentation.admin.LibrarySettingsUiState
import com.calypsan.listenup.client.presentation.admin.OrganizeRunProgress
import com.calypsan.listenup.client.presentation.admin.OrganizeSettingsUiState
import com.calypsan.listenup.client.presentation.admin.RestoreBackupUiState
import com.calypsan.listenup.client.presentation.admin.RestoreFromFileUiState
import com.calypsan.listenup.client.presentation.admin.UserDetailUiState
import com.calypsan.listenup.client.presentation.admin.UserPermissionsUiState
import com.calypsan.listenup.client.presentation.admin.imports.BookSearchState
import com.calypsan.listenup.client.presentation.admin.imports.ImportFlowUiState
import com.calypsan.listenup.client.presentation.admin.upload.UploadBooksUiState
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.web.features.admin.AdminInboxPage
import com.calypsan.listenup.web.features.admin.AdminPage
import com.calypsan.listenup.web.features.admin.BackupsPage
import com.calypsan.listenup.web.features.admin.CategoriesPage
import com.calypsan.listenup.web.features.admin.CollectionDetailPage
import com.calypsan.listenup.web.features.admin.CollectionsPage
import com.calypsan.listenup.web.features.admin.CreateInvitePage
import com.calypsan.listenup.web.features.admin.ImportFlowPage
import com.calypsan.listenup.web.features.admin.ImportsPage
import com.calypsan.listenup.web.features.admin.LibrarySettingsPage
import com.calypsan.listenup.web.features.admin.MergeHistoryActions
import com.calypsan.listenup.web.features.admin.OrganizePage
import com.calypsan.listenup.web.features.admin.PermissionsPanelActions
import com.calypsan.listenup.web.features.admin.RestorePage
import com.calypsan.listenup.web.features.admin.ServerSettingsPage
import com.calypsan.listenup.web.features.admin.UploadPage
import com.calypsan.listenup.web.features.admin.UserDetailPage
import com.calypsan.listenup.web.features.admin.absItem
import com.calypsan.listenup.web.features.admin.adminUser
import com.calypsan.listenup.web.features.admin.backup
import com.calypsan.listenup.web.features.admin.collection
import com.calypsan.listenup.web.features.admin.entry
import com.calypsan.listenup.web.features.admin.finished
import com.calypsan.listenup.web.features.admin.genre
import com.calypsan.listenup.web.features.admin.importResult
import com.calypsan.listenup.web.features.admin.inboxBook
import com.calypsan.listenup.web.features.admin.invite
import com.calypsan.listenup.web.features.admin.node
import com.calypsan.listenup.web.features.admin.noopOrganizeActions
import com.calypsan.listenup.web.features.admin.permissionsReady
import com.calypsan.listenup.web.features.admin.personFixture
import com.calypsan.listenup.web.features.admin.preview
import com.calypsan.listenup.web.features.admin.readyBackups
import com.calypsan.listenup.web.features.admin.readyCategories
import com.calypsan.listenup.web.features.admin.readyCollections
import com.calypsan.listenup.web.features.admin.readyDetail
import com.calypsan.listenup.web.features.admin.readyImports
import com.calypsan.listenup.web.features.admin.readyInbox
import com.calypsan.listenup.web.features.admin.readyLibrary
import com.calypsan.listenup.web.features.admin.readyOrganize
import com.calypsan.listenup.web.features.admin.readyServerSettings
import com.calypsan.listenup.web.features.admin.readyUser
import com.calypsan.listenup.web.features.admin.restoreResult
import com.calypsan.listenup.web.features.admin.review
import com.calypsan.listenup.web.features.admin.scanIssue
import com.calypsan.listenup.web.features.admin.shareFixture
import com.calypsan.listenup.web.features.admin.uploaded
import com.calypsan.listenup.web.features.home.scanning
import com.calypsan.listenup.web.features.match.failed
import com.calypsan.listenup.web.features.match.results
import com.calypsan.listenup.web.features.search.bookHit
import com.calypsan.listenup.web.features.setup.LibrarySetupPage
import com.calypsan.listenup.web.features.setup.setupState
import io.kotest.core.spec.style.FunSpec

/**
 * Every class the admin pages and the people and library tools render is defined in the design sheet. A failure names the shape that rendered
 * the undefined class; see [ClassContractTest] for why the contract exists.
 */
class AdminClassContractTest :
    FunSpec({
        test("every class the admin pages and the people and library tools render is defined in the design sheet") {
            assertClassesDefined(adminContractShapes())
        }
    })

private fun adminContractShapes(): Map<String, @Composable () -> Unit> =
    librarySetupShapes() +
        librarySettingsShapes() +
        inboxShapes() +
        serverSettingsShapes() +
        categoryShapes() +
        collectionShapes() +
        backupShapes() +
        importShapes() +
        adminShapes() +
        peopleShapes() +
        adminToolShapes()

/**
 * The folder picker in every state: a browsable list with one folder chosen, an error, an empty
 * folder, and the two loading states.
 */
private fun librarySetupShapes(): Map<String, @Composable () -> Unit> =
    shapes(
        shape("librarySetup #1: { LibrarySetupPage( state = setupState(s") {
            LibrarySetupPage(
                state = setupState(selectedPaths = setOf("/srv/Audiobooks"), error = "That folder could not be read."),
                onOpenFolder = {},
                onNavigateUp = {},
                onToggleFolder = {},
                onComplete = {},
                onDismissError = {},
                onSelectFolder = {},
                onClearSelection = {},
            )
        },
        shape("librarySetup #2: { LibrarySetupPage( state = setupState(d") {
            LibrarySetupPage(
                state = setupState(directories = emptyList()),
                onOpenFolder = {},
                onNavigateUp = {},
                onToggleFolder = {},
                onComplete = {},
                onDismissError = {},
                onSelectFolder = {},
                onClearSelection = {},
            )
        },
        shape("librarySetup #3: { LibrarySetupPage( state = setupState(i") {
            LibrarySetupPage(
                state = setupState(isLoadingDirectories = true, directories = emptyList()),
                onOpenFolder = {},
                onNavigateUp = {},
                onToggleFolder = {},
                onComplete = {},
                onDismissError = {},
                onSelectFolder = {},
                onClearSelection = {},
            )
        },
    )

/**
 * Library folders in every shape: the list carrying a transient error and the post-add notice, a
 * library watching nothing, the browser populated / loading / empty, and the page's own two
 * non-Ready states.
 */
private fun librarySettingsShapes(): Map<String, @Composable () -> Unit> {
    fun page(
        state: LibrarySettingsUiState,
        scanStarted: Boolean = false,
    ): @Composable () -> Unit =
        {
            LibrarySettingsPage(state, scanStarted, {}, {}, {}, {}, {}, {}, {}, {})
        }

    return shapes(
        // A folder list wearing both the dismissible error and the "scanning it now" notice.
        "librarySettings #1: page( readyLibrary(error = InternalError" to
            page(
                readyLibrary(error = InternalError(debugInfo = "boom")),
                scanStarted = true,
            ),
        // Every folder removed. Reachable, and the only place .lset-empty renders in list mode.
        "librarySettings #2: page(readyLibrary(folders = emptyList())" to page(readyLibrary(folders = emptyList())),
        "librarySettings #3: page(readyLibrary(showFolderBrowser = tr" to page(readyLibrary(showFolderBrowser = true)),
        "librarySettings #4: page(readyLibrary(showFolderBrowser = tr" to
            page(readyLibrary(showFolderBrowser = true, isBrowserLoading = true)),
        "librarySettings #5: page(readyLibrary(showFolderBrowser = tr" to
            page(readyLibrary(showFolderBrowser = true, browserEntries = emptyList())),
        "librarySettings #6: page(LibrarySettingsUiState.Error(Intern" to
            page(LibrarySettingsUiState.Error(InternalError(debugInfo = "boom"))),
        "librarySettings #7: page(LibrarySettingsUiState.Loading)" to page(LibrarySettingsUiState.Loading),
    )
}

/**
 * A listener's page: a full profile, an empty one (whose "nothing yet" copy is the only thing that
 * renders), and the two states with no profile at all.
 */
private fun inboxShapes(): Map<String, @Composable () -> Unit> {
    fun page(state: AdminInboxUiState): @Composable () -> Unit =
        {
            AdminInboxPage(state, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }

    return shapes(
        // Both halves populated, a row selected (so the bulk bar and the tick render), and both
        // notices up. Selection is what draws `.is-sel` and `.bulk` — a contract that only listed
        // the resting state would leave every one of those unchecked.
        "inbox #1: page( readyInbox( books = listOf(inboxBo" to
            page(
                readyInbox(
                    books = listOf(inboxBook(id = "b1"), inboxBook(id = "b2", author = null)),
                    selectedBookIds = setOf("b1"),
                    lastReleasedCount = 2,
                    error = "No library available",
                    scanIssues = listOf(scanIssue(), scanIssue(id = "i2", detail = "ffprobe: EBML")),
                ),
            ),
        // Issues with no books, and books with no issues — each half renders alone.
        "inbox #2: page(readyInbox(books = emptyList(), sca" to page(readyInbox(books = emptyList(), scanIssues = listOf(scanIssue()))),
        "inbox #3: page(readyInbox())" to page(readyInbox()),
        // Both empty, which is the only shape that draws the empty block.
        "inbox #4: page(readyInbox(books = emptyList(), sca" to page(readyInbox(books = emptyList(), scanIssues = emptyList())),
        "inbox #5: page(AdminInboxUiState.Error(Server said" to page(AdminInboxUiState.Error("Server said no.")),
        "inbox #6: page(AdminInboxUiState.Loading)" to page(AdminInboxUiState.Loading),
    )
}

private fun serverSettingsShapes(): Map<String, @Composable () -> Unit> {
    fun page(state: AdminSettingsUiState): @Composable () -> Unit =
        {
            ServerSettingsPage(
                state = state,
                onServerName = {},
                onRemoteUrl = {},
                onHoldNewBooks = {},
                onPushNotifications = {},
                onSave = {},
                onClearError = {},
                onRetry = {},
                onOpenAdmin = {},
            )
        }

    return shapes(
        // Dirty and wearing a failed write — `.srv-err` renders nowhere else.
        "serverSettings #1: page(readyServerSettings(isDirty = true," to
            page(readyServerSettings(isDirty = true, error = InternalError(debugInfo = "boom"))),
        "serverSettings #2: page(readyServerSettings(isSaving = true" to page(readyServerSettings(isSaving = true, isDirty = true)),
        // The Hardcover panel (#1542): a saved token, and a refused one beside an empty field.
        "serverSettings #3: page(readyServerSettings().copy(hardcove" to
            page(
                readyServerSettings().copy(hardcoverSource = HardcoverSourceStatus(apiToken = HardcoverApiTokenStatus.Saved("simon", 1L))),
            ),
        "serverSettings #4: page( readyServerSettings().copy( hardco" to
            page(
                readyServerSettings().copy(
                    hardcoverSource = HardcoverSourceStatus(apiToken = HardcoverApiTokenStatus.Rejected("simon")),
                    hardcoverTokenSave = HardcoverTokenSave.Refused(HardcoverError.TokenRejected()),
                ),
            ),
        "serverSettings #5: page(AdminSettingsUiState.Error(Internal" to
            page(
                AdminSettingsUiState.Error(InternalError(debugInfo = "boom")),
            ),
        "serverSettings #6: page(AdminSettingsUiState.Loading)" to page(AdminSettingsUiState.Loading),
    )
}

private fun categoryShapes(): Map<String, @Composable () -> Unit> {
    fun page(state: AdminCategoriesUiState): @Composable () -> Unit =
        {
            CategoriesPage(
                state,
                {},
                {},
                {},
                { _, _ -> },
                { _, _ -> },
                {},
                { _, _ -> },
                { _, _ -> },
                {},
                {},
                null,
                MergeHistoryActions.None,
            )
        }

    val child = genre(id = "g2", name = "Fantasy", path = "/fiction/fantasy", bookCount = 3)
    val parent = genre(id = "g1", name = "Fiction", path = "/fiction", bookCount = 10)
    // Expanded, so both a parent row (with a twisty) and a leaf row (with the gap that stands in
    // for one) render — they draw different classes and only an expanded tree has both.
    val tree = listOf(node(parent, children = listOf(node(child, depth = 1))))

    return shapes(
        "category #1: page(readyCategories(tree = tree, expand" to
            page(readyCategories(tree = tree, expandedIds = setOf("g1"), totalBookCount = 13)),
        "category #2: page(readyCategories(tree = tree, error" to
            page(readyCategories(tree = tree, error = InternalError(debugInfo = "boom"))),
        // The only shape that draws the empty block.
        "category #3: page(readyCategories(tree = emptyList()," to page(readyCategories(tree = emptyList(), genres = emptyList())),
        "category #4: page(AdminCategoriesUiState.Error(Intern" to page(AdminCategoriesUiState.Error(InternalError(debugInfo = "boom"))),
        "category #5: page(AdminCategoriesUiState.Loading)" to page(AdminCategoriesUiState.Loading),
    )
}

/** The invite form in each of its shapes, and one member's page in each of its states. */
private fun peopleShapes(): Map<String, @Composable () -> Unit> {
    fun form(state: CreateInviteUiState): @Composable () -> Unit =
        {
            CreateInvitePage(
                state = state,
                onCreate = { _, _, _ -> },
                onClearError = {},
                onCreateAnother = {},
                onCopy = {},
                onOpenAdmin = {},
            )
        }

    fun member(
        state: UserDetailUiState,
        permissions: UserPermissionsUiState = permissionsReady(),
    ): @Composable () -> Unit =
        {
            UserDetailPage(state = state, permissions = permissions, actions = PermissionsPanelActions(), onOpenAdmin = {})
        }

    return shapes(
        "people #1: form(CreateInviteUiState.Ready())" to form(CreateInviteUiState.Ready()),
        // The field error is the only shape that draws `.f-box.err` on this page.
        "people #2: form( CreateInviteUiState.Ready( CreateI" to
            form(
                CreateInviteUiState.Ready(
                    CreateInviteStatus.Error(CreateInviteErrorType.ValidationError(CreateInviteField.EMAIL)),
                ),
            ),
        "people #3: form(CreateInviteUiState.Ready(CreateInv" to
            form(CreateInviteUiState.Ready(CreateInviteStatus.Error(CreateInviteErrorType.ServerError("boom")))),
        // The success half shares no markup at all with the form it replaces.
        "people #4: form(CreateInviteUiState.Ready(CreateInv" to form(CreateInviteUiState.Ready(CreateInviteStatus.Success(invite()))),
        "people #5: member(readyUser())" to member(readyUser()),
        // Custom, with the curate warning, the Unsaved tags and the save bar.
        "people #6: member( readyUser(), permissionsReady( f" to
            member(
                readyUser(),
                permissionsReady(
                    flags = UserPermissions(canEditMetadata = false, canCurateLibrary = true),
                    preset = PermissionPreset.CUSTOM,
                    curateWarning = true,
                    changeCount = 2,
                ),
            ),
        "people #7: member(readyUser(), permissionsReady(rol" to member(readyUser(), permissionsReady(role = UserRole.ADMIN)),
        "people #8: member(readyUser(), permissionsReady(pre" to member(readyUser(), permissionsReady(presetsShown = false)),
        // The owner draws the note that explains why nothing here moves.
        "people #9: member( readyUser(adminUser(isRoot = tru" to
            member(
                readyUser(adminUser(isRoot = true)),
                permissionsReady(role = UserRole.ROOT, user = adminUser(isRoot = true)),
            ),
        "people #10: member(readyUser(), permissionsReady(err" to
            member(readyUser(), permissionsReady(error = InternalError(debugInfo = "boom"))),
        "people #11: member(UserDetailUiState.Loading)" to member(UserDetailUiState.Loading),
        "people #12: member(UserDetailUiState.Error(InternalE" to member(UserDetailUiState.Error(InternalError(debugInfo = "boom"))),
    )
}

/** The organizer and the uploader, in every state that draws markup of its own. */
private fun adminToolShapes(): Map<String, @Composable () -> Unit> {
    fun organize(state: OrganizeSettingsUiState): @Composable () -> Unit =
        { OrganizePage(state = state, actions = noopOrganizeActions(), onOpenAdmin = {}) }

    fun upload(state: UploadBooksUiState): @Composable () -> Unit =
        { UploadPage(state = state, onFilesPicked = {}, onCancel = {}, onReset = {}, onOpenAdmin = {}) }

    return shapes(
        "adminTool #1: organize(readyOrganize())" to organize(readyOrganize()),
        "adminTool #2: organize(readyOrganize(error = InternalE" to organize(readyOrganize(error = InternalError(debugInfo = "boom"))),
        // The consent dialog, with a collision row and a truncated tail — the only shapes that
        // draw `.org-clash` and `.org-more`.
        "adminTool #3: organize( readyOrganize( previewDto = pr" to
            organize(
                readyOrganize(
                    previewDto =
                        preview(
                            entries = listOf(entry(), entry(bookId = "b2", collisionResolved = true)),
                            truncated = true,
                        ),
                ),
            ),
        "adminTool #4: organize(readyOrganize(run = OrganizeRun" to
            organize(readyOrganize(run = OrganizeRunProgress(completed = 2, total = 9))),
        "adminTool #5: organize(readyOrganize(run = OrganizeRun" to
            organize(readyOrganize(run = OrganizeRunProgress(movedBooks = 7, failedBooks = 2, terminal = true))),
        "adminTool #6: organize(OrganizeSettingsUiState.Loading" to organize(OrganizeSettingsUiState.Loading),
        "adminTool #7: organize(OrganizeSettingsUiState.Error(I" to
            organize(OrganizeSettingsUiState.Error(InternalError(debugInfo = "boom"))),
        "adminTool #8: upload(UploadBooksUiState.Idle)" to upload(UploadBooksUiState.Idle),
        "adminTool #9: upload(UploadBooksUiState.Uploading(file" to
            upload(UploadBooksUiState.Uploading(fileIndex = 1, fileCount = 4, filename = "02.m4b", fraction = 0.5f)),
        "adminTool #10: upload(UploadBooksUiState.Finalizing)" to upload(UploadBooksUiState.Finalizing),
        "adminTool #11: upload( finished( imported = listOf(uplo" to
            upload(
                finished(
                    imported = listOf(uploaded("Dune", UploadedBookStatus.IMPORTED)),
                    duplicates = listOf(uploaded("Mistborn", UploadedBookStatus.DUPLICATE)),
                    failed = listOf(uploaded("Broken", UploadedBookStatus.FAILED, detail = "no audio stream")),
                ),
            ),
        "adminTool #12: upload(UploadBooksUiState.Error(Internal" to
            upload(
                UploadBooksUiState.Error(InternalError(debugInfo = "boom")),
            ),
    )
}

private fun bookHit() =
    com.calypsan.listenup.client.domain.model.SearchHit(
        id = "b9",
        type = com.calypsan.listenup.client.domain.model.SearchHitType.BOOK,
        name = "The Way of Kings",
    )

private fun collectionShapes(): Map<String, @Composable () -> Unit> {
    fun list(state: AdminCollectionsUiState): @Composable () -> Unit = { CollectionsPage(state, {}, {}, {}, {}, {}, {}) }

    fun detail(state: AdminCollectionDetailUiState): @Composable () -> Unit =
        {
            CollectionDetailPage(state, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }

    return shapes(
        // An ordinary collection and a managed one side by side — the padlock renders only on the
        // second, and Delete only on the first.
        "collection #1: list( readyCollections( collections = li" to
            list(
                readyCollections(
                    collections = listOf(collection(), collection(id = "c2", name = "All books", isSystem = true)),
                    error = "No library available",
                ),
            ),
        "collection #2: list(readyCollections(collections = empt" to list(readyCollections(collections = emptyList())),
        "collection #3: list(AdminCollectionsUiState.Error(nope)" to list(AdminCollectionsUiState.Error("nope")),
        "collection #4: list(AdminCollectionsUiState.Loading)" to list(AdminCollectionsUiState.Loading),
        // Dirty, so the Save row renders; erroring, so the notice does.
        "collection #5: detail(readyDetail(editedName = Changed," to
            detail(readyDetail(editedName = "Changed", error = "nope", shares = listOf(shareFixture()))),
        // Both panels open — each draws classes that render nowhere else.
        "collection #6: detail(readyDetail(showAddBooks = true," to
            detail(readyDetail(showAddBooks = true, bookQuery = "kings", bookResults = listOf(bookHit()))),
        "collection #7: detail(readyDetail(showAddMemberSheet =" to
            detail(readyDetail(showAddMemberSheet = true, availableUsers = listOf(personFixture()))),
        "collection #8: detail(readyDetail(isSystem = true, book" to detail(readyDetail(isSystem = true, books = emptyList())),
        "collection #9: detail(AdminCollectionDetailUiState.Erro" to detail(AdminCollectionDetailUiState.Error("nope")),
        "collection #10: detail(AdminCollectionDetailUiState.Load" to detail(AdminCollectionDetailUiState.Loading),
    )
}

private fun backupShapes(): Map<String, @Composable () -> Unit> {
    fun list(
        state: AdminBackupUiState,
        uploadState: RestoreFromFileUiState = RestoreFromFileUiState.Idle,
    ): @Composable () -> Unit =
        {
            BackupsPage(state, uploadState, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }

    fun restore(
        state: RestoreBackupUiState,
        progress: BackupEvent? = null,
    ): @Composable () -> Unit = { RestorePage(state, progress, {}, {}, {}, {}) }

    return shapes(
        // A list wearing its error, with a row to draw the row classes.
        "backup #1: list(readyBackups(error = InternalError(" to list(readyBackups(error = InternalError(debugInfo = "boom"))),
        "backup #2: list(readyBackups(backups = emptyList())" to list(readyBackups(backups = emptyList())),
        "backup #3: list(AdminBackupUiState.Error(InternalEr" to list(AdminBackupUiState.Error(InternalError(debugInfo = "boom"))),
        "backup #4: list(AdminBackupUiState.Loading)" to list(AdminBackupUiState.Loading),
        // Idle wearing a prior failure — `.rst-err` renders nowhere else.
        "backup #5: restore(RestoreBackupUiState.Idle(error" to
            restore(RestoreBackupUiState.Idle(error = InternalError(debugInfo = "boom"))),
        "backup #6: restore(RestoreBackupUiState.Confirming)" to restore(RestoreBackupUiState.Confirming),
        "backup #7: restore(RestoreBackupUiState.Restoring," to restore(RestoreBackupUiState.Restoring, progress = BackupEvent.Swapping),
        // Migrated, so `.rst-schema` renders.
        "backup #8: restore(RestoreBackupUiState.Completed(r" to
            restore(RestoreBackupUiState.Completed(restoreResult(from = "6", to = "7"))),
    )
}

private fun importShapes(): Map<String, @Composable () -> Unit> {
    fun list(state: ABSImportListUiState): @Composable () -> Unit = { ImportsPage(state, {}, {}, {}, {}, {}) }

    fun flow(state: ImportFlowUiState): @Composable () -> Unit =
        {
            ImportFlowPage(state, {}, { _, _ -> }, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {})
        }

    return shapes(
        "import #1: list(readyImports(error = InternalError(" to list(readyImports(error = InternalError(debugInfo = "boom"))),
        "import #2: list(readyImports(imports = emptyList())" to list(readyImports(imports = emptyList())),
        "import #3: list(ABSImportListUiState.Error(Internal" to list(ABSImportListUiState.Error(InternalError(debugInfo = "boom"))),
        "import #4: list(ABSImportListUiState.Loading)" to list(ABSImportListUiState.Loading),
        "import #5: flow(ImportFlowUiState.Idle)" to flow(ImportFlowUiState.Idle),
        "import #6: flow(ImportFlowUiState.Analyzing(3, 10," to flow(ImportFlowUiState.Analyzing(3, 10, "Elantris", 1, 4)),
        // Review with a book needing a decision AND the search panel open — several classes
        // render only in one of those two.
        "import #7: flow( review( ambiguous = listOf(absItem" to
            flow(
                review(
                    ambiguous = listOf(absItem()),
                    bookSearch =
                        BookSearchState(
                            com.calypsan.listenup.core
                                .AbsItemId("ai1"),
                            query = "elantris",
                            results =
                                listOf(
                                    com.calypsan.listenup.client.presentation.admin.imports.BookSearchHit(
                                        com.calypsan.listenup.core
                                            .BookId("b1"),
                                        "Elantris",
                                        "Brandon Sanderson",
                                    ),
                                ),
                            isSearching = false,
                        ),
                ),
            ),
        // Done carrying books it could not place — `.iflow-note` renders nowhere else.
        "import #8: flow(ImportFlowUiState.Done(importResult" to flow(ImportFlowUiState.Done(importResult(booksNotInLibrary = 3))),
        "import #9: flow(ImportFlowUiState.Error(InternalErr" to flow(ImportFlowUiState.Error(InternalError(debugInfo = "boom"))),
    )
}

/** Admin in its loading, populated and errored shapes — the badge and banner live only in some. */
private fun adminShapes(): Map<String, @Composable () -> Unit> {
    fun user(
        id: String,
        name: String,
        isRoot: Boolean = false,
        status: String = "ACTIVE",
    ) = AdminUserInfo(
        id = id,
        email = "$id@example.com",
        displayName = name,
        firstName = null,
        lastName = null,
        isRoot = isRoot,
        role = "MEMBER",
        status = status,
        createdAt = "2026-01-01",
    )

    fun page(state: AdminUiState): @Composable () -> Unit = { AdminPage(state, 0L, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}) }

    return shapes(
        "admin #1: page(AdminUiState.Loading)" to page(AdminUiState.Loading),
        "admin #2: page(AdminUiState.Ready(error = nope))" to page(AdminUiState.Ready(error = "nope")),
        "admin #3: page( AdminUiState.Ready( users = listOf" to
            page(
                AdminUiState.Ready(
                    users = listOf(user("u1", "Simon", isRoot = true), user("u2", "Ada")),
                    pendingUsers = listOf(user("u3", "Grace", status = "PENDING_APPROVAL")),
                    pendingInvites =
                        listOf(
                            InviteInfo(
                                id = "i1",
                                code = "ABC123",
                                name = "Alan",
                                email = "alan@example.com",
                                role = "MEMBER",
                                expiresAt = "2026-02-01",
                                claimedAt = null,
                                url = "https://listenup.example/i/ABC123",
                                createdAt = "2026-01-01",
                            ),
                        ),
                    pendingPasswordResets =
                        listOf(
                            PasswordResetRequest(
                                id = "r1",
                                userId = UserId("u2"),
                                displayName = "Ada",
                                email = "ada@example.com",
                                requestedAt = 0L,
                                expiresAt = 0L,
                            ),
                        ),
                ),
            ),
    )
}
