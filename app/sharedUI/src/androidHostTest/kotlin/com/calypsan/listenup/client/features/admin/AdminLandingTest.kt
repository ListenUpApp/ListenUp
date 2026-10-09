package com.calypsan.listenup.client.features.admin

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import com.calypsan.listenup.api.dto.admin.HardcoverSourceStatus
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.presentation.admin.AdminUiState
import com.calypsan.listenup.client.presentation.admin.HardcoverTokenSave
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.UserProfileRepository
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A "Someone wants to join" tap opens Admin on its pending registrations, not on server settings
 * with the request below the fold — on a phone's one list and a tablet's two panes alike.
 */
@RunWith(RobolectricTestRunner::class)
class AdminLandingTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Each user row's avatar resolves its profile and photo through the GLOBAL Koin context. */
    @Before
    fun startKoinForAvatars() {
        startKoin {
            modules(
                module {
                    single<UserProfileRepository> {
                        mock(MockMode.autoUnit) { every { observeProfile(any()) } returns flowOf(null) }
                    }
                    single<ImageStorage> {
                        mock(MockMode.autoUnit) {
                            every { userAvatarExists(any()) } returns false
                            every { getUserAvatarPath(any()) } returns "/nonexistent/avatar.jpg"
                        }
                    }
                    single<ImageRepository> { mock(MockMode.autoUnit) }
                },
            )
        }
    }

    @After
    fun stopKoinAfterAvatars() {
        stopKoin()
    }

    private fun member(
        id: String,
        name: String,
        status: String = "active",
    ) = AdminUserInfo(
        id = id,
        email = "$id@example.com",
        displayName = name,
        firstName = null,
        lastName = null,
        isRoot = false,
        role = "member",
        status = status,
        createdAt = "2026-10-01T00:00:00Z",
    )

    private val waiting =
        AdminUiState.Ready(
            registrationPolicy = RegistrationPolicy.APPROVAL_QUEUE,
            users = (1..8).map { member("u$it", "Listener $it") },
            pendingUsers = listOf(member("p1", "Second Reader", status = "pending_approval")),
        )

    private fun show(focus: AdminFocus) {
        composeRule.setContent {
            MaterialTheme {
                AdminContent(
                    state = waiting,
                    focus = focus,
                    onRegistrationPolicyChange = {},
                    onApproveUserClick = {},
                    onDenyUserClick = {},
                    onDeleteUserClick = {},
                    onUserClick = {},
                    onCopyInviteClick = {},
                    onRevokeInviteClick = {},
                    onApprovePasswordResetClick = {},
                    onDenyPasswordResetClick = {},
                    onInviteClick = {},
                    onCollectionsClick = {},
                    onCategoriesClick = {},
                    onBackupClick = {},
                    onImportClick = {},
                    onUploadBooksClick = {},
                    onInboxClick = {},
                    onLibrarySettingsClick = {},
                    onOrganizeClick = {},
                    serverName = "Rig",
                    onServerNameChange = {},
                    remoteUrl = "",
                    onRemoteUrlChange = {},
                    holdNewBooksForReview = false,
                    onHoldNewBooksForReviewChange = {},
                    pushNotificationsEnabled = true,
                    onPushNotificationsEnabledChange = {},
                    ratingSources = emptyList(),
                    onRatingSourceEnabledChange = { _, _ -> },
                    metadataRegion = "us",
                    onMetadataRegionChange = {},
                    hardcoverSource = HardcoverSourceStatus(),
                    hardcoverTokenSave = HardcoverTokenSave.Idle,
                    hardcoverActions = HardcoverSourceActions(),
                )
            }
        }
        composeRule.waitForIdle()
    }

    /** Whether [text] is laid out inside the window — a lazy list composes little beyond what shows. */
    private fun onScreen(text: String): Boolean {
        val windowHeight =
            composeRule
                .onRoot()
                .fetchSemanticsNode()
                .size.height
        return composeRule
            .onAllNodesWithText(text)
            .fetchSemanticsNodes()
            .any { node -> node.boundsInRoot.bottom > 0f && node.boundsInRoot.top < windowHeight }
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `on a phone, Admin opens at the top unless it is asked for the pending requests`() {
        show(AdminFocus.TOP)
        onScreen("Second Reader") shouldBe false
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `on a phone, the approvals tap lands on the pending request`() {
        show(AdminFocus.PENDING_REGISTRATIONS)
        onScreen("Second Reader") shouldBe true
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `on a tablet, the approvals tap lands on the pending request`() {
        show(AdminFocus.PENDING_REGISTRATIONS)
        onScreen("Second Reader") shouldBe true
    }
}
