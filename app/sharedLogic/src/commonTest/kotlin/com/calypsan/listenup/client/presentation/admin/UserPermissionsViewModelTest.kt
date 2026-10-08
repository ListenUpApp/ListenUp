package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.api.dto.auth.Permission
import com.calypsan.listenup.api.dto.auth.PermissionGroup
import com.calypsan.listenup.api.dto.auth.UserPermissionsPatch
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.model.PermissionPreset
import com.calypsan.listenup.client.domain.model.UserPermissions
import com.calypsan.listenup.client.domain.repository.AdminRepository
import com.calypsan.listenup.client.test.fake.FakeInstanceRepository
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class UserPermissionsViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()
        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        fun member(
            flags: UserPermissions = UserPermissions(),
            role: String = "MEMBER",
            isRoot: Boolean = false,
        ) = AdminUserInfo(
            id = "u1",
            email = "sevro@example.com",
            displayName = "Sevro",
            firstName = null,
            lastName = null,
            isRoot = isRoot,
            role = role,
            status = "ACTIVE",
            permissions = flags,
            createdAt = "0",
        )

        fun TestScope.open(
            repo: AdminRepository,
            flags: Set<String> = setOf("canEdit", "canCurateLibrary"),
        ): UserPermissionsViewModel {
            val viewModel = UserPermissionsViewModel("u1", repo, FakeInstanceRepository(flags), ErrorBus())
            backgroundScope.launch { viewModel.state.collect { } }
            advanceUntilIdle()
            return viewModel
        }

        val everyFlag = Permission.known.map { it.wireKey }.toSet()

        fun UserPermissionsViewModel.ready() = state.value.shouldBeInstanceOf<UserPermissionsUiState.Ready>()

        test("a default member opens on Contributor, with the Library toggles and nothing unsaved") {
            runTest {
                val repo: AdminRepository = mock { everySuspend { getUser("u1") } returns AppResult.Success(member()) }
                val ready = open(repo).ready()
                ready.preset shouldBe PermissionPreset.CONTRIBUTOR
                ready.presetsShown shouldBe true
                ready.sections.map { it.group } shouldContainExactly listOf(PermissionGroup.LIBRARY)
                ready.sections
                    .single()
                    .rows
                    .map { it.permission } shouldContainExactly
                    listOf(Permission.EDIT_METADATA, Permission.CURATE_LIBRARY)
                ready.changeCount shouldBe 0
            }
        }

        test("picking Librarian sets both flags, warns about curate, and counts one change") {
            runTest {
                val repo: AdminRepository = mock { everySuspend { getUser("u1") } returns AppResult.Success(member()) }
                val viewModel = open(repo)
                viewModel.selectPreset(PermissionPreset.LIBRARIAN)
                advanceUntilIdle()
                val ready = viewModel.ready()
                ready.preset shouldBe PermissionPreset.LIBRARIAN
                ready.flags shouldBe UserPermissions(canEditMetadata = true, canCurateLibrary = true)
                ready.curateWarningShown shouldBe true
                ready.changeCount shouldBe 1
                ready.sections
                    .single()
                    .rows
                    .single { it.permission == Permission.CURATE_LIBRARY }
                    .isUnsaved shouldBe true
            }
        }

        test("one toggle off a preset reads as Custom; discard puts the saved flags back") {
            runTest {
                val repo: AdminRepository = mock { everySuspend { getUser("u1") } returns AppResult.Success(member()) }
                val viewModel = open(repo)
                viewModel.setPermission(Permission.EDIT_METADATA, false)
                viewModel.setPermission(Permission.CURATE_LIBRARY, true)
                advanceUntilIdle()
                viewModel.ready().preset shouldBe PermissionPreset.CUSTOM
                viewModel.ready().changeCount shouldBe 2
                viewModel.discard()
                advanceUntilIdle()
                viewModel.ready().preset shouldBe PermissionPreset.CONTRIBUTOR
                viewModel.ready().changeCount shouldBe 0
            }
        }

        test("save sends only the changed flags, then the saved state is the new baseline") {
            runTest {
                val saved = member(UserPermissions(canEditMetadata = true, canCurateLibrary = true))
                val repo: AdminRepository =
                    mock {
                        everySuspend { getUser("u1") } returns AppResult.Success(member())
                        everySuspend {
                            updateUser(userId = "u1", role = null, permissions = UserPermissionsPatch(canCurateLibrary = true))
                        } returns AppResult.Success(saved)
                    }
                val viewModel = open(repo)
                viewModel.setPermission(Permission.CURATE_LIBRARY, true)
                viewModel.save()
                advanceUntilIdle()
                verifySuspend(VerifyMode.exactly(1)) {
                    repo.updateUser(userId = "u1", role = null, permissions = UserPermissionsPatch(canCurateLibrary = true))
                }
                viewModel.ready().changeCount shouldBe 0
                viewModel.ready().curateWarningShown shouldBe false
                viewModel.ready().preset shouldBe PermissionPreset.LIBRARIAN
            }
        }

        test("a server that enforces reading orders shows their own group, and saving a toggle names only that flag") {
            runTest {
                val all = setOf("canEdit", "canCurateLibrary", "canMakeReadingOrders")
                val patch = UserPermissionsPatch(canMakeReadingOrders = false)
                val repo: AdminRepository =
                    mock {
                        everySuspend { getUser("u1") } returns AppResult.Success(member())
                        everySuspend { updateUser(userId = "u1", role = null, permissions = patch) } returns
                            AppResult.Success(member(UserPermissions(canMakeReadingOrders = false)))
                    }
                val viewModel = open(repo, flags = all)
                val ready = viewModel.ready()
                ready.preset shouldBe PermissionPreset.CONTRIBUTOR
                ready.sections.map { it.group } shouldContainExactly
                    listOf(PermissionGroup.LIBRARY, PermissionGroup.READING_ORDERS)
                ready.sections
                    .last()
                    .rows
                    .map { it.permission } shouldContainExactly listOf(Permission.MAKE_READING_ORDERS)
                viewModel.setPermission(Permission.MAKE_READING_ORDERS, false)
                advanceUntilIdle()
                viewModel.ready().preset shouldBe PermissionPreset.CUSTOM
                viewModel.save()
                advanceUntilIdle()
                verifySuspend(VerifyMode.exactly(1)) { repo.updateUser(userId = "u1", role = null, permissions = patch) }
                viewModel.ready().changeCount shouldBe 0
            }
        }

        test("changing Edit metadata names Curate library too, so edit off with curate on is kept") {
            runTest {
                val librarian = member(UserPermissions(canEditMetadata = true, canCurateLibrary = true))
                val patch = UserPermissionsPatch(canEditMetadata = false, canCurateLibrary = true)
                val repo: AdminRepository =
                    mock {
                        everySuspend { getUser("u1") } returns AppResult.Success(librarian)
                        everySuspend { updateUser(userId = "u1", role = null, permissions = patch) } returns
                            AppResult.Success(member(UserPermissions(canEditMetadata = false, canCurateLibrary = true)))
                    }
                val viewModel = open(repo)
                viewModel.setPermission(Permission.EDIT_METADATA, false)
                viewModel.save()
                advanceUntilIdle()
                verifySuspend(VerifyMode.exactly(1)) { repo.updateUser(userId = "u1", role = null, permissions = patch) }
            }
        }

        test("against an older server, changing Edit metadata sends it alone") {
            runTest {
                val patch = UserPermissionsPatch(canEditMetadata = false)
                val repo: AdminRepository =
                    mock {
                        everySuspend { getUser("u1") } returns AppResult.Success(member())
                        everySuspend { updateUser(userId = "u1", role = null, permissions = patch) } returns
                            AppResult.Success(member(UserPermissions(canEditMetadata = false)))
                    }
                val viewModel = open(repo, flags = setOf("canEdit"))
                viewModel.setPermission(Permission.EDIT_METADATA, false)
                viewModel.save()
                advanceUntilIdle()
                verifySuspend(VerifyMode.exactly(1)) { repo.updateUser(userId = "u1", role = null, permissions = patch) }
            }
        }

        test("a failed save keeps the draft and carries the error") {
            runTest {
                val repo: AdminRepository =
                    mock {
                        everySuspend { getUser("u1") } returns AppResult.Success(member())
                        everySuspend { updateUser(any(), any(), any()) } returns AppResult.Failure(TransportError.NetworkUnavailable())
                    }
                val viewModel = open(repo)
                viewModel.setPermission(Permission.EDIT_METADATA, false)
                viewModel.save()
                advanceUntilIdle()
                viewModel.ready().changeCount shouldBe 1
                viewModel.ready().isSaving shouldBe false
                viewModel.ready().error.shouldBeInstanceOf<TransportError.NetworkUnavailable>()
            }
        }

        test("promoting to Admin asks first; confirming drafts the role, and save sends the role alone") {
            runTest {
                val repo: AdminRepository =
                    mock {
                        everySuspend { getUser("u1") } returns AppResult.Success(member())
                        everySuspend { updateUser(userId = "u1", role = UserRole.ADMIN, permissions = null) } returns
                            AppResult.Success(member(role = "ADMIN"))
                    }
                val viewModel = open(repo)
                viewModel.requestRole(UserRole.ADMIN)
                advanceUntilIdle()
                viewModel.ready().isConfirmingAdminPromotion shouldBe true
                viewModel.ready().role shouldBe UserRole.MEMBER

                viewModel.confirmAdminPromotion()
                advanceUntilIdle()
                viewModel.ready().role shouldBe UserRole.ADMIN
                viewModel.ready().isConfirmingAdminPromotion shouldBe false
                viewModel.ready().changeCount shouldBe 1

                viewModel.save()
                advanceUntilIdle()
                verifySuspend(VerifyMode.exactly(1)) { repo.updateUser(userId = "u1", role = UserRole.ADMIN, permissions = null) }
            }
        }

        test("cancelling the promotion changes nothing; demoting needs no confirmation") {
            runTest {
                val repo: AdminRepository = mock { everySuspend { getUser("u1") } returns AppResult.Success(member(role = "ADMIN")) }
                val viewModel = open(repo)
                viewModel.ready().role shouldBe UserRole.ADMIN
                viewModel.requestRole(UserRole.MEMBER)
                advanceUntilIdle()
                viewModel.ready().role shouldBe UserRole.MEMBER
                viewModel.ready().isConfirmingAdminPromotion shouldBe false

                viewModel.requestRole(UserRole.ADMIN)
                viewModel.cancelAdminPromotion()
                advanceUntilIdle()
                viewModel.ready().role shouldBe UserRole.MEMBER
            }
        }

        test("against an older server, presets are hidden and only Edit metadata is offered") {
            runTest {
                val repo: AdminRepository = mock { everySuspend { getUser("u1") } returns AppResult.Success(member()) }
                val ready = open(repo, flags = setOf("canEdit")).ready()
                ready.presetsShown shouldBe false
                ready.sections
                    .single()
                    .rows
                    .map { it.permission } shouldContainExactly listOf(Permission.EDIT_METADATA)
            }
        }

        test("the owner is protected: nothing can be drafted") {
            runTest {
                val repo: AdminRepository =
                    mock { everySuspend { getUser("u1") } returns AppResult.Success(member(role = "ROOT", isRoot = true)) }
                val viewModel = open(repo)
                viewModel.ready().isProtected shouldBe true
                viewModel.setPermission(Permission.EDIT_METADATA, false)
                viewModel.requestRole(UserRole.MEMBER)
                advanceUntilIdle()
                viewModel.ready().changeCount shouldBe 0
            }
        }

        test("a server enforcing Story World shows its group after Library, Contribute then Curate") {
            runTest {
                val repo: AdminRepository = mock { everySuspend { getUser("u1") } returns AppResult.Success(member()) }
                val ready = open(repo, flags = everyFlag).ready()
                ready.sections.map { it.group } shouldContainExactly
                    listOf(PermissionGroup.LIBRARY, PermissionGroup.STORY_WORLD, PermissionGroup.READING_ORDERS)
                ready.sections
                    .single { it.group == PermissionGroup.STORY_WORLD }
                    .rows
                    .map { it.permission } shouldContainExactly
                    listOf(Permission.CONTRIBUTE_STORY_WORLD, Permission.CURATE_STORY_WORLD)
                ready.preset shouldBe PermissionPreset.CONTRIBUTOR
            }
        }

        test("Listener turns Contribute Story World off, and save sends the flags that changed and nothing else") {
            runTest {
                val listener =
                    UserPermissions(
                        canEditMetadata = false,
                        canCurateLibrary = false,
                        canContributeStoryWorld = false,
                        canMakeReadingOrders = false,
                    )
                val patch =
                    UserPermissionsPatch(
                        canEditMetadata = false,
                        canCurateLibrary = false,
                        canContributeStoryWorld = false,
                        canMakeReadingOrders = false,
                    )
                val repo: AdminRepository =
                    mock {
                        everySuspend { getUser("u1") } returns AppResult.Success(member())
                        everySuspend { updateUser(userId = "u1", role = null, permissions = patch) } returns
                            AppResult.Success(member(listener))
                    }
                val viewModel = open(repo, flags = everyFlag)
                viewModel.selectPreset(PermissionPreset.LISTENER)
                advanceUntilIdle()
                viewModel.ready().flags shouldBe listener
                viewModel.save()
                advanceUntilIdle()
                verifySuspend(VerifyMode.exactly(1)) { repo.updateUser(userId = "u1", role = null, permissions = patch) }
                viewModel.ready().preset shouldBe PermissionPreset.LISTENER
            }
        }

        test("Curate Story World on its own is Custom, and saves as that one flag") {
            runTest {
                val curator = UserPermissions(canCurateStoryWorld = true)
                val repo: AdminRepository =
                    mock {
                        everySuspend { getUser("u1") } returns AppResult.Success(member())
                        everySuspend {
                            updateUser(userId = "u1", role = null, permissions = UserPermissionsPatch(canCurateStoryWorld = true))
                        } returns AppResult.Success(member(curator))
                    }
                val viewModel = open(repo, flags = everyFlag)
                viewModel.setPermission(Permission.CURATE_STORY_WORLD, true)
                advanceUntilIdle()
                viewModel.ready().preset shouldBe PermissionPreset.CUSTOM
                viewModel.ready().curateWarningShown shouldBe false
                viewModel.save()
                advanceUntilIdle()
                verifySuspend(VerifyMode.exactly(1)) {
                    repo.updateUser(userId = "u1", role = null, permissions = UserPermissionsPatch(canCurateStoryWorld = true))
                }
            }
        }

        test("a failed load is the Error state") {
            runTest {
                val repo: AdminRepository =
                    mock { everySuspend { getUser("u1") } returns AppResult.Failure(TransportError.NetworkUnavailable()) }
                open(repo).state.value.shouldBeInstanceOf<UserPermissionsUiState.Error>()
            }
        }
    })
