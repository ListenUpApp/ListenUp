package com.calypsan.listenup.web.nav

import com.calypsan.listenup.client.presentation.bookedit.BookEditUiEvent
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiState
import com.calypsan.listenup.client.presentation.bulkedit.BulkEditUiState
import com.calypsan.listenup.client.presentation.contributoredit.ContributorEditUiEvent
import com.calypsan.listenup.client.presentation.merge.MergeHistoryState
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditUiEvent
import com.calypsan.listenup.client.presentation.shelf.CreateEditShelfUiState
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.features.admin.CollectionDetailPage
import com.calypsan.listenup.web.features.admin.PermissionsPanelActions
import com.calypsan.listenup.web.features.admin.ServerSettingsPage
import com.calypsan.listenup.web.features.admin.UserDetailPage
import com.calypsan.listenup.web.features.admin.permissionsReady
import com.calypsan.listenup.web.features.admin.readyDetail
import com.calypsan.listenup.web.features.admin.readyServerSettings
import com.calypsan.listenup.web.features.admin.readyUser
import com.calypsan.listenup.web.features.bookedit.BookEditPage
import com.calypsan.listenup.web.features.bulkedit.BulkEditActions
import com.calypsan.listenup.web.features.bulkedit.BulkEditCatalog
import com.calypsan.listenup.web.features.bulkedit.BulkEditPage
import com.calypsan.listenup.web.features.contributoredit.ContributorEditPage
import com.calypsan.listenup.web.features.contributoredit.editingContributor
import com.calypsan.listenup.web.features.profile.EditProfilePage
import com.calypsan.listenup.web.features.profile.editing
import com.calypsan.listenup.web.features.seriesedit.SeriesEditPage
import com.calypsan.listenup.web.features.seriesedit.editingSeries
import com.calypsan.listenup.web.features.shelf.ShelfEditPage
import io.kotest.core.spec.style.FunSpec

/**
 * Every web edit surface that holds a draft asks before it throws it away, the way both native
 * clients do: a clean form leaves at once, a dirty one asks, and Keep stays with the draft.
 *
 * One set of specs per page, over the same three behaviours, so a page that drops its guard fails
 * by name.
 */
class UnsavedWorkPagesTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        guardedLeaveSpecs(
            page = "Book edit",
            mounts = mounts,
            mount = { registry, dirty, onLeave ->
                registry.mount {
                    BookEditPage(
                        state = BookEditUiState(isLoading = false, bookId = "b1", title = "Dune", hasChanges = dirty),
                        onEvent = { if (it is BookEditUiEvent.Cancel) onLeave() },
                        onOpenLibrary = {},
                        onOpenBook = {},
                    )
                }
            },
            control = { pageButton(it, "Cancel") },
        )

        guardedLeaveSpecs(
            page = "Book edit breadcrumb",
            mounts = mounts,
            mount = { registry, dirty, onLeave ->
                registry.mount {
                    BookEditPage(
                        state = BookEditUiState(isLoading = false, bookId = "b1", title = "Dune", hasChanges = dirty),
                        onEvent = {},
                        onOpenLibrary = onLeave,
                        onOpenBook = {},
                    )
                }
            },
            control = { breadcrumbLink(it) },
        )

        guardedLeaveSpecs(
            page = "Series edit",
            mounts = mounts,
            mount = { registry, dirty, onLeave ->
                registry.mount {
                    SeriesEditPage(
                        state = editingSeries(hasChanges = dirty),
                        mergeCandidates = emptyList(),
                        mergeHistory = MergeHistoryState.Ready(emptyList()),
                        onEvent = { if (it is SeriesEditUiEvent.CancelClicked) onLeave() },
                        onMergeQuery = {},
                    )
                }
            },
            control = { pageButton(it, "Cancel") },
        )

        guardedLeaveSpecs(
            page = "Contributor edit",
            mounts = mounts,
            mount = { registry, dirty, onLeave ->
                registry.mount {
                    ContributorEditPage(
                        state = editingContributor(hasChanges = dirty),
                        mergeCandidates = emptyList(),
                        onEvent = { if (it is ContributorEditUiEvent.Cancel) onLeave() },
                        onMergeQuery = {},
                    )
                }
            },
            control = { pageButton(it, "Cancel") },
        )

        guardedLeaveSpecs(
            page = "Edit profile",
            mounts = mounts,
            mount = { registry, dirty, onLeave ->
                registry.mount {
                    EditProfilePage(
                        state = editing(isDirty = dirty),
                        onFirstName = {},
                        onLastName = {},
                        onTagline = {},
                        onCurrentPassword = {},
                        onNewPassword = {},
                        onConfirmPassword = {},
                        onPickAvatar = { _, _ -> },
                        onRemoveAvatar = {},
                        onSave = {},
                        onCancel = onLeave,
                    )
                }
            },
            control = { pageButton(it, "Cancel") },
        )

        guardedLeaveSpecs(
            page = "Server settings",
            mounts = mounts,
            mount = { registry, dirty, onLeave ->
                registry.mount {
                    ServerSettingsPage(
                        state = readyServerSettings(isDirty = dirty),
                        onServerName = {},
                        onRemoteUrl = {},
                        onHoldNewBooks = {},
                        onPushNotifications = {},
                        onSave = {},
                        onClearError = {},
                        onRetry = {},
                        onOpenAdmin = onLeave,
                    )
                }
            },
            control = { breadcrumbLink(it) },
        )

        guardedLeaveSpecs(
            page = "Collection rename",
            mounts = mounts,
            mount = { registry, dirty, onLeave ->
                registry.mount {
                    CollectionDetailPage(
                        state = readyDetail(name = "Bedtime", editedName = if (dirty) "Bedtime stories" else "Bedtime"),
                        onNameChange = {},
                        onSaveName = {},
                        onRemoveBook = {},
                        onOpenAddBooks = {},
                        onCloseAddBooks = {},
                        onBookQuery = {},
                        onAddBook = {},
                        onShowAddMember = {},
                        onHideAddMember = {},
                        onShare = {},
                        onRevokeShare = {},
                        onClearError = {},
                        onOpenCollections = onLeave,
                        onSaveConfirmed = {},
                    )
                }
            },
            control = { breadcrumbLink(it) },
        )

        guardedLeaveSpecs(
            page = "User permissions",
            mounts = mounts,
            mount = { registry, dirty, onLeave ->
                registry.mount {
                    UserDetailPage(
                        state = readyUser(),
                        permissions = permissionsReady(changeCount = if (dirty) 1 else 0),
                        actions = PermissionsPanelActions(),
                        onOpenAdmin = onLeave,
                    )
                }
            },
            control = { breadcrumbLink(it) },
        )

        guardedLeaveSpecs(
            page = "Shelf",
            mounts = mounts,
            mount = { registry, dirty, onLeave ->
                registry.mount {
                    ShelfEditPage(
                        state = CreateEditShelfUiState.Idle,
                        isEditing = false,
                        onSave = { _, _, _ -> },
                        onDelete = {},
                        onDismissError = {},
                        onCancel = onLeave,
                        hasChanges = { _, _, _ -> dirty },
                    )
                }
            },
            control = { pageButton(it, "Cancel") },
        )

        guardedLeaveSpecs(
            page = "Bulk edit",
            mounts = mounts,
            mount = { registry, dirty, onLeave ->
                registry.mount {
                    BulkEditPage(
                        state = BulkEditUiState.Editing(bookCount = 3, changedBookCount = if (dirty) 2 else 0),
                        catalog = BulkEditCatalog(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
                        actions = bulkActions(onLeave),
                    )
                }
            },
            control = { pageButton(it, "Cancel") },
        )
    })

private fun bulkActions(onLeave: () -> Unit) =
    BulkEditActions(
        onSeriesQuery = {},
        onContributorQuery = {},
        onPublisher = {},
        onYear = {},
        onLanguage = {},
        onSeries = {},
        onContributors = {},
        onGenres = {},
        onTags = {},
        onMoods = {},
        onApply = {},
        onLeave = onLeave,
    )
