package com.calypsan.listenup.client.features.connect

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.calypsan.listenup.api.error.ServerConnectError
import com.calypsan.listenup.client.features.permission.LocalNetworkPermissionRecovery
import com.calypsan.listenup.client.features.permission.LocalNetworkRecoveryAction
import com.calypsan.listenup.client.presentation.connect.ServerConnectUiState
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Manual server entry on Android 17 with local network access denied: the screen has to say so,
 * and offer the one action that fixes it, instead of reporting a working server as unreachable.
 */
@RunWith(RobolectricTestRunner::class)
class ServerSetupContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var recoveries = 0

    private fun recovery(
        isGranted: Boolean,
        action: LocalNetworkRecoveryAction = LocalNetworkRecoveryAction.RequestPermission,
    ) = LocalNetworkPermissionRecovery(isGranted = isGranted, action = action, recover = { recoveries++ })

    private fun show(
        state: ServerConnectUiState,
        localNetwork: LocalNetworkPermissionRecovery,
        serverUrl: String = "192.168.1.5:8080",
    ) {
        composeRule.setContent {
            MaterialTheme {
                ServerSetupContent(
                    state = state,
                    serverUrl = serverUrl,
                    onServerUrlChange = {},
                    onConnect = {},
                    localNetwork = localNetwork,
                    onBack = null,
                )
            }
        }
    }

    @Test
    fun `a connect the permission blocked offers to allow access`() {
        show(
            state = ServerConnectUiState.Error(ServerConnectError.LocalNetworkPermissionDenied()),
            localNetwork = recovery(isGranted = false),
        )

        composeRule
            .onNodeWithText("Allow access")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        recoveries shouldBe 1
    }

    @Test
    fun `an unreachable server offers no permission action`() {
        show(
            state = ServerConnectUiState.Error(ServerConnectError.ServerNotReachable()),
            localNetwork = recovery(isGranted = true),
        )

        composeRule
            .onNodeWithText("Cannot reach server. Check that it's running and the URL is correct.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Allow access").assertDoesNotExist()
        composeRule.onNodeWithText("Open Settings").assertDoesNotExist()
    }

    @Test
    fun `a denied permission is explained before the user even tries to connect`() {
        show(state = ServerConnectUiState.Idle, localNetwork = recovery(isGranted = false))

        composeRule
            .onNodeWithText(
                "ListenUp needs local network access to find and connect to servers on your network.",
                substring = true,
            ).performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Allow access").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `once the system stops asking, the card sends the user to Settings and says what to change`() {
        show(
            state = ServerConnectUiState.Idle,
            localNetwork = recovery(isGranted = false, action = LocalNetworkRecoveryAction.OpenSettings),
        )

        composeRule
            .onNodeWithText("Open Settings")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule
            .onNodeWithText("In Settings, open Permissions and allow Nearby devices.", substring = true)
            .assertExists()
        composeRule.onNodeWithText("Allow access").assertDoesNotExist()
        recoveries shouldBe 1
    }

    @Test
    fun `with access granted and no failure there is no card`() {
        show(state = ServerConnectUiState.Idle, localNetwork = recovery(isGranted = true))

        composeRule.onNodeWithText("Allow access").assertDoesNotExist()
        composeRule.onNodeWithText("Open Settings").assertDoesNotExist()
    }

    @Test
    fun `a clearly remote address sets the card aside even while the permission is denied`() {
        show(
            state = ServerConnectUiState.Idle,
            localNetwork = recovery(isGranted = false),
            serverUrl = "https://yourname.listenup.app",
        )

        composeRule.onNodeWithText("Allow access").assertDoesNotExist()
    }

    @Test
    fun `a name that might be local keeps the card`() {
        show(state = ServerConnectUiState.Idle, localNetwork = recovery(isGranted = false), serverUrl = "nas.lan")

        composeRule.onNodeWithText("Allow access").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a failed local connect keeps the card even with a remote-looking address typed after it`() {
        show(
            state = ServerConnectUiState.Error(ServerConnectError.LocalNetworkPermissionDenied()),
            localNetwork = recovery(isGranted = false),
            serverUrl = "https://yourname.listenup.app",
        )

        composeRule.onNodeWithText("Allow access").performScrollTo().assertIsDisplayed()
    }
}
