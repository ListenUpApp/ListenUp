package com.calypsan.listenup.client.features.admin

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.api.dto.auth.Permission
import com.calypsan.listenup.api.dto.auth.PermissionGroup
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.model.PermissionPreset
import com.calypsan.listenup.client.domain.model.UserPermissions
import com.calypsan.listenup.client.presentation.admin.PermissionRow
import com.calypsan.listenup.client.presentation.admin.PermissionSection
import com.calypsan.listenup.client.presentation.admin.UserPermissionsUiState
import com.calypsan.listenup.client.testing.Windows
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Permissions screen's content against the canvas's states: Contributor, Librarian unsaved, Admin, older server. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Windows.PHONE)
class UserPermissionsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val presses = mutableListOf<String>()

    private val actions =
        PermissionsActions(
            onSelectPreset = { presses += "preset:$it" },
            onSetPermission = { permission, granted -> presses += "set:$permission=$granted" },
            onRequestRole = { presses += "role:$it" },
        )

    private fun show(state: UserPermissionsUiState.Ready) {
        composeRule.setContent { MaterialTheme { UserPermissionsContent(state = state, actions = actions) } }
    }

    @Test
    fun `a default member shows the three presets, Contributor described, and both Library toggles`() {
        show(ready())
        composeRule.onNodeWithText("Listener").assertIsDisplayed()
        composeRule.onNodeWithText("Contributor").assertIsDisplayed()
        composeRule.onNodeWithText("Librarian").assertIsDisplayed()
        composeRule.onNodeWithText("Fixes books, adds to Story World and makes reading orders. Can't merge or delete.").assertIsDisplayed()
        composeRule.onNodeWithText("Library").assertIsDisplayed()
        composeRule.onNodeWithText("Edit metadata").assertIsDisplayed()
        composeRule.onNodeWithText("Curate library").assertIsDisplayed()
        composeRule.onNodeWithText("Custom").assertDoesNotExist()
    }

    @Test
    fun `picking a preset and flipping a toggle reach the ViewModel`() {
        show(ready())
        composeRule.onNodeWithText("Librarian").performClick()
        composeRule.onNodeWithText("Curate library").performClick()
        assertEquals(listOf("preset:LIBRARIAN", "set:CURATE_LIBRARY=true"), presses)
    }

    @Test
    fun `an unsaved curate grant warns, naming the member and that deletes are final`() {
        show(
            ready(
                flags = UserPermissions(canEditMetadata = true, canCurateLibrary = true),
                preset = PermissionPreset.LIBRARIAN,
                curateWarning = true,
            ),
        )
        composeRule
            .onNodeWithText("Quinn will be able to merge and delete these for everyone on this server. Deletes can't be undone.")
            .assertIsDisplayed()
    }

    @Test
    fun `the Story World group shows Contribute and Curate, and a Curate grant needs no warning`() {
        val flags = UserPermissions(canCurateStoryWorld = true)
        show(
            ready(
                flags = flags,
                preset = PermissionPreset.CUSTOM,
                sections =
                    listOf(
                        PermissionSection(
                            PermissionGroup.STORY_WORLD,
                            listOf(
                                PermissionRow(Permission.CONTRIBUTE_STORY_WORLD, flags.canContributeStoryWorld, isUnsaved = false),
                                PermissionRow(Permission.CURATE_STORY_WORLD, flags.canCurateStoryWorld, isUnsaved = true),
                            ),
                        ),
                    ),
            ),
        )
        composeRule.onNodeWithText("Story World").assertIsDisplayed()
        composeRule.onNodeWithText("Contribute").assertIsDisplayed()
        composeRule.onNodeWithText("Add, edit and delete characters, places and events.").assertIsDisplayed()
        composeRule.onNodeWithText("Curate").assertIsDisplayed()
        composeRule.onNodeWithText("Merge duplicate characters, places and events.").assertIsDisplayed()
        composeRule.onNodeWithText("Deletes can't be undone.", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Contribute").performClick()
        assertEquals(listOf("set:CONTRIBUTE_STORY_WORLD=false"), presses)
    }

    @Test
    fun `Custom is a label, not a button`() {
        show(ready(flags = UserPermissions(canEditMetadata = false, canCurateLibrary = true), preset = PermissionPreset.CUSTOM))
        composeRule.onNodeWithText("Custom").assertIsDisplayed()
        composeRule.onNodeWithText("These don't match a preset. Pick one to start again from it.").assertIsDisplayed()
    }

    @Test
    fun `an admin sees that admins can do everything, and no toggles`() {
        show(ready(role = UserRole.ADMIN))
        composeRule.onNodeWithText("Admins can do everything").assertIsDisplayed()
        composeRule.onNodeWithText("To choose what Quinn can do, change their role to Member.").assertIsDisplayed()
        composeRule.onNodeWithText("Edit metadata").assertDoesNotExist()
        composeRule.onNodeWithText("Preset").assertDoesNotExist()
    }

    @Test
    fun `an older server shows one toggle, no presets, and says why`() {
        show(ready(presetsShown = false, sections = listOf(section(Permission.EDIT_METADATA))))
        composeRule.onNodeWithText("Edit metadata").assertIsDisplayed()
        composeRule.onNodeWithText("Preset").assertDoesNotExist()
        composeRule
            .onNodeWithText("This server can only set this one permission. Update ListenUp on the server for the rest.")
            .assertIsDisplayed()
    }

    private fun section(vararg permissions: Permission) =
        PermissionSection(PermissionGroup.LIBRARY, permissions.map { PermissionRow(it, granted = true, isUnsaved = false) })

    private fun ready(
        role: UserRole = UserRole.MEMBER,
        flags: UserPermissions = UserPermissions(),
        preset: PermissionPreset = PermissionPreset.CONTRIBUTOR,
        presetsShown: Boolean = true,
        curateWarning: Boolean = false,
        sections: List<PermissionSection> =
            listOf(
                PermissionSection(
                    PermissionGroup.LIBRARY,
                    listOf(
                        PermissionRow(Permission.EDIT_METADATA, flags.canEditMetadata, isUnsaved = false),
                        PermissionRow(Permission.CURATE_LIBRARY, flags.canCurateLibrary, isUnsaved = curateWarning),
                    ),
                ),
            ),
    ) = UserPermissionsUiState.Ready(
        user =
            AdminUserInfo(
                id = "u1",
                email = "quinn@example.com",
                displayName = "Quinn",
                firstName = null,
                lastName = null,
                isRoot = false,
                role = role.name,
                status = "ACTIVE",
                permissions = flags,
                createdAt = "0",
            ),
        role = role,
        flags = flags,
        sections = sections,
        preset = preset,
        presetsShown = presetsShown,
        curateWarningShown = curateWarning,
        changeCount = if (curateWarning) 1 else 0,
        isProtected = false,
        isConfirmingAdminPromotion = false,
        isSaving = false,
        error = null,
    )
}
