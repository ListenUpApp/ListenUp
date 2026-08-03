package com.calypsan.listenup.client.automotive

import androidx.media3.session.MediaConstants
import androidx.media3.session.SessionError
import androidx.test.core.app.ApplicationProvider
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.domain.model.AuthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the typed signed-out browse error (#1239): authentication-expired code plus the
 * error-resolution PendingIntent extras that let the head unit deep-link into sign-in,
 * and the auth-state gate deciding when browse returns it.
 */
@RunWith(RobolectricTestRunner::class)
class AutoBrowseErrorsTest {
    @Test
    fun `signedOutError carries auth code and resolution intent extras`() {
        val error = AutoBrowseErrors.signedOutError(ApplicationProvider.getApplicationContext())

        assertEquals(SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED, error.code)
        assertNotNull(error.extras.getString(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT))
        assertNotNull(
            error.extras.getParcelable(
                MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT,
                android.app.PendingIntent::class.java,
            ),
        )
    }

    @Test
    fun `gate fires for genuinely signed-out states only`() {
        assertTrue(browseNeedsSignIn(AuthState.NeedsServerUrl))
        assertTrue(browseNeedsSignIn(AuthState.NeedsSetup))
        assertTrue(browseNeedsSignIn(AuthState.NeedsLogin(openRegistration = false)))
        assertTrue(browseNeedsSignIn(AuthState.PendingApproval(UserId("u1"), "u@example.com")))

        assertFalse(browseNeedsSignIn(AuthState.Initializing))
        assertFalse(browseNeedsSignIn(AuthState.CheckingServer))
        assertFalse(browseNeedsSignIn(AuthState.Authenticated(UserId("u1"), SessionId("s1"))))
        // Never-stranded: a lapsed session leaves local data intact — browse keeps working
        // offline from Room; the car must NOT throw up a sign-in wall.
        assertFalse(browseNeedsSignIn(AuthState.SessionLapsed(UserId("u1"))))
    }
}
