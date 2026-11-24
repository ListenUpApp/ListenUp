package com.calypsan.listenup.web.features.admin

import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.client.presentation.admin.AdminCategoriesUiState
import com.calypsan.listenup.client.presentation.admin.AdminCollectionDetailUiState
import com.calypsan.listenup.client.presentation.admin.AdminCollectionsUiState
import com.calypsan.listenup.client.presentation.admin.LibrarySettingsUiState
import com.calypsan.listenup.client.presentation.admin.OrganizeSettingsUiState
import com.calypsan.listenup.client.presentation.admin.UserDetailUiState
import com.calypsan.listenup.client.presentation.admin.UserPermissionsUiState
import com.calypsan.listenup.web.MountRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

private val offline = TransportError.NetworkUnavailable()

private fun tryAgain(within: HTMLElement): HTMLButtonElement =
    within
        .querySelectorAll("button")
        .asList()
        .filterIsInstance<HTMLButtonElement>()
        .firstOrNull { it.textContent?.trim() == "Try again" }
        .shouldNotBeNull()

/**
 * An admin page whose load failed offers Try again, and Try again reaches the ViewModel — today's only
 * other recovery is leaving and coming back.
 */
class AdminTryAgainTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        test("library settings") {
            var retries = 0
            val host =
                mounts.mount {
                    LibrarySettingsPage(
                        state = LibrarySettingsUiState.Error(offline),
                        scanStarted = false,
                        onRemoveFolder = {},
                        onAddPath = {},
                        onScan = {},
                        onShowBrowser = {},
                        onOpenBrowserPath = {},
                        onBrowserUp = {},
                        onClearError = {},
                        onRetry = { retries++ },
                        onOpenAdmin = {},
                    )
                }

            tryAgain(host).click()

            retries shouldBe 1
        }

        test("collections") {
            var retries = 0
            val host =
                mounts.mount {
                    CollectionsPage(
                        state = AdminCollectionsUiState.Error("Collections could not be read."),
                        onCreate = {},
                        onDelete = {},
                        onClearError = {},
                        onRetry = { retries++ },
                        onOpenCollection = {},
                        onOpenAdmin = {},
                        onCreateConfirmed = {},
                    )
                }

            tryAgain(host).click()

            retries shouldBe 1
        }

        test("one collection") {
            var retries = 0
            val host =
                mounts.mount {
                    CollectionDetailPage(
                        state = AdminCollectionDetailUiState.Error("Collection not found"),
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
                        onRetry = { retries++ },
                        onOpenCollections = {},
                        onSaveConfirmed = {},
                    )
                }

            tryAgain(host).click()

            retries shouldBe 1
        }

        test("categories") {
            var retries = 0
            val host =
                mounts.mount {
                    CategoriesPage(
                        state = AdminCategoriesUiState.Error(offline),
                        onToggleExpanded = {},
                        onExpandAll = {},
                        onCollapseAll = {},
                        onCreate = { _, _ -> },
                        onRename = { _, _ -> },
                        onDelete = {},
                        onMove = { _, _ -> },
                        onMerge = { _, _ -> },
                        onClearError = {},
                        onRetry = { retries++ },
                        onOpenAdmin = {},
                        mergeHistory = null,
                        mergeHistoryActions = MergeHistoryActions.None,
                    )
                }

            tryAgain(host).click()

            retries shouldBe 1
        }

        test("file organization") {
            var retries = 0
            val host =
                mounts.mount {
                    OrganizePage(
                        state = OrganizeSettingsUiState.Error(offline),
                        actions = noopOrganizeActions().copy(onRetry = { retries++ }),
                        onOpenAdmin = {},
                    )
                }

            tryAgain(host).click()

            retries shouldBe 1
        }

        test("a member") {
            var retries = 0
            val host =
                mounts.mount {
                    UserDetailPage(
                        state = UserDetailUiState.Error(offline),
                        permissions = UserPermissionsUiState.Loading,
                        actions = PermissionsPanelActions(),
                        onRetry = { retries++ },
                        onOpenAdmin = {},
                    )
                }

            tryAgain(host).click()

            retries shouldBe 1
        }

        test("a member's permissions, inside their panel") {
            var retries = 0
            val host =
                mounts.mount {
                    PermissionsPanel(
                        state = UserPermissionsUiState.Error(offline),
                        actions = PermissionsPanelActions(onRetry = { retries++ }),
                    )
                }

            tryAgain(host.querySelector(".perm").shouldNotBeNull() as HTMLElement).click()

            retries shouldBe 1
        }
    })
