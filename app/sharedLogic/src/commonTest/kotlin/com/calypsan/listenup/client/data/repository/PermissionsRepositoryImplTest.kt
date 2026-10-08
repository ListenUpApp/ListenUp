package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.auth.Permission
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.data.local.db.UserDao
import com.calypsan.listenup.client.data.local.db.UserEntity
import com.calypsan.listenup.core.Timestamp
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

class PermissionsRepositoryImplTest :
    FunSpec({
        fun entity(
            isAdmin: Boolean = false,
            canEdit: Boolean = true,
            canCurateLibrary: Boolean = false,
        ) = UserEntity(
            id = UserId("u1"),
            email = "u@example.com",
            displayName = "U",
            isRoot = isAdmin,
            createdAt = Timestamp(0L),
            updatedAt = Timestamp(0L),
            canEdit = canEdit,
            canCurateLibrary = canCurateLibrary,
        )

        test("an admin can do everything, whatever the stored flags say") {
            runTest {
                val dao: UserDao =
                    mock { every { observeCurrentUser() } returns MutableStateFlow(entity(isAdmin = true, canEdit = false)) }
                val repo = PermissionsRepositoryImpl(dao)
                repo.observeCan(Permission.EDIT_METADATA).test { awaitItem() shouldBe true }
                repo.observeCan(Permission.CURATE_LIBRARY).test { awaitItem() shouldBe true }
            }
        }

        test("a member can do exactly what their flags say, and the answer follows the row") {
            runTest {
                val row = MutableStateFlow<UserEntity?>(entity(canEdit = true, canCurateLibrary = false))
                val dao: UserDao = mock { every { observeCurrentUser() } returns row }
                val repo = PermissionsRepositoryImpl(dao)
                repo.observeCan(Permission.CURATE_LIBRARY).test {
                    awaitItem() shouldBe false
                    row.value = entity(canEdit = true, canCurateLibrary = true)
                    awaitItem() shouldBe true
                }
                repo.observeCan(Permission.EDIT_METADATA).test { awaitItem() shouldBe true }
            }
        }

        test("nobody signed in can do nothing, and UNKNOWN is never granted") {
            runTest {
                val signedOut: UserDao = mock { every { observeCurrentUser() } returns MutableStateFlow(null) }
                PermissionsRepositoryImpl(signedOut).observeCan(Permission.EDIT_METADATA).test { awaitItem() shouldBe false }
                val member: UserDao =
                    mock { every { observeCurrentUser() } returns MutableStateFlow(entity(canCurateLibrary = true)) }
                PermissionsRepositoryImpl(member).observeCan(Permission.UNKNOWN).test { awaitItem() shouldBe false }
            }
        }
    })
