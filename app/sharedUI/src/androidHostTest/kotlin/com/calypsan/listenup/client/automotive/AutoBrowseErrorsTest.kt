package com.calypsan.listenup.client.automotive

import android.app.PendingIntent
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.SessionError
import androidx.test.core.app.ApplicationProvider
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.localization.SystemStrings
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the typed signed-out browse error (#1239): authentication-expired code plus the
 * error-resolution PendingIntent extras that let the head unit deep-link into sign-in,
 * and the auth-state gate deciding when browse returns it.
 *
 * Kotest matchers on a JUnit4 runner, matching `ListenUpSessionCallbackTest`: Kotest's
 * `FunSpec` cannot host `RobolectricTestRunner`, so the runner stays and the assertions
 * modernize — that is the whole of the available migration for a Robolectric spec.
 */
@RunWith(RobolectricTestRunner::class)
class AutoBrowseErrorsTest {
    @Test
    fun `signedOutError carries auth code and resolution intent extras`() {
        val error =
            AutoBrowseErrors.signedOutError(
                ApplicationProvider.getApplicationContext(),
                SystemStrings.ENGLISH_FALLBACK,
            )

        error.code shouldBe SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED
        error.extras.getString(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT).shouldNotBeNull()
        error.extras
            .getParcelable(
                MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT,
                PendingIntent::class.java,
            ).shouldNotBeNull()
    }

    // ⛔ The invariant that makes the sign-in wall reach a car at all, and the one that can rot
    // silently. Android Auto is a legacy MediaBrowserCompat client, so the SessionError returned
    // from onGetChildren never reaches it directly — Media3 flattens an error LibraryResult to
    // `sendResult(null)` and drops the message, the label and the PendingIntent on the way out
    // (MediaLibraryServiceLegacyStub.createMediaItemsToBrowserItemsAsyncFunction).
    //
    // The extras survive only by replication into the platform PlaybackStateCompat, and Media3
    // replicates exactly two codes (MediaLibrarySessionImpl.isReplicationErrorCode):
    //
    //     resultCode == RESULT_ERROR_SESSION_AUTHENTICATION_EXPIRED
    //         || resultCode == RESULT_ERROR_SESSION_PARENTAL_CONTROL_RESTRICTED
    //
    // Note Media3's own javadoc (MediaLibraryService.setLibraryErrorReplicationMode) disagrees
    // with that code and claims PREMIUM_ACCOUNT_REQUIRED instead — the implementation is what
    // runs, so the implementation is what this test encodes.
    //
    // Swap `signedOutError`'s code to any other SessionError constant and nothing fails: it still
    // compiles, `onGetChildren` still returns an error, every other test here still passes — and
    // the car silently stops being told to sign in. That is precisely the failure mode that cost
    // two Play policy rejections, so it gets a test that names it.
    @Test
    fun `signedOutError uses a code Media3 replicates to the platform playback state`() {
        val error =
            AutoBrowseErrors.signedOutError(
                ApplicationProvider.getApplicationContext(),
                SystemStrings.ENGLISH_FALLBACK,
            )

        val replicatedByMedia3 =
            setOf(
                LibraryResult.RESULT_ERROR_SESSION_AUTHENTICATION_EXPIRED,
                LibraryResult.RESULT_ERROR_SESSION_PARENTAL_CONTROL_RESTRICTED,
            )

        (error.code in replicatedByMedia3) shouldBe true
    }

    @Test
    fun `gate fires for genuinely signed-out states only`() {
        browseNeedsSignIn(AuthState.NeedsServerUrl) shouldBe true
        browseNeedsSignIn(AuthState.NeedsSetup) shouldBe true
        browseNeedsSignIn(AuthState.NeedsLogin(openRegistration = false)) shouldBe true
        browseNeedsSignIn(AuthState.PendingApproval(UserId("u1"), "u@example.com")) shouldBe true

        browseNeedsSignIn(AuthState.Initializing) shouldBe false
        browseNeedsSignIn(AuthState.CheckingServer) shouldBe false
        browseNeedsSignIn(AuthState.Authenticated(UserId("u1"), SessionId("s1"))) shouldBe false
        // Never-stranded: a lapsed session leaves local data intact — browse keeps working
        // offline from Room; the car must NOT throw up a sign-in wall.
        browseNeedsSignIn(AuthState.SessionLapsed(UserId("u1"))) shouldBe false
    }
}
