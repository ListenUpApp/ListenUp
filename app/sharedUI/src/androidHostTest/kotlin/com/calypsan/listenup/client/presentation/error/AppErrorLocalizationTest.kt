package com.calypsan.listenup.client.presentation.error

import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.TransportError
import kotlinx.coroutines.test.runTest
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.error_conflict
import listenup.composeapp.generated.resources.error_forbidden
import listenup.composeapp.generated.resources.error_not_found
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain

/**
 * Tests for the [AppError] UI-layer error renderer.
 *
 * [localizedString] (and the composable [localized]) ultimately call compose-resources'
 * [org.jetbrains.compose.resources.getString], which resolves the system [ResourceEnvironment]
 * via `Resources.getSystem()`. A plain JVM unit test receives an unmocked `Resources` that throws.
 * Robolectric supplies a real Android resource environment (default locale `en`), so `getString`
 * reads the packaged `error_*` strings exactly as production code does. JUnit4 +
 * [RobolectricTestRunner] follows the precedent set by
 * [com.calypsan.listenup.client.deeplink.DeepLinkParserTest].
 */
@RunWith(RobolectricTestRunner::class)
class AppErrorLocalizationTest {
    @Test
    fun `resourceKey lowercases the error code with the error_ prefix`() {
        TransportError.Timeout().resourceKey() shouldBe "error_transport_timeout"
    }

    @Test
    fun `resolved maps Server4xx 409 to the conflict resource`() {
        TransportError.Server4xx(statusCode = 409).resolved() shouldBe Res.string.error_conflict
    }

    @Test
    fun `resolved maps Server4xx 403 to the forbidden resource`() {
        TransportError.Server4xx(statusCode = 403).resolved() shouldBe Res.string.error_forbidden
    }

    @Test
    fun `resolved maps Server4xx 404 to the not_found resource`() {
        TransportError.Server4xx(statusCode = 404).resolved() shouldBe Res.string.error_not_found
    }

    @Test
    fun `resolved resolves a dynamically-keyed error to a non-null resource`() {
        AuthError.SessionExpired().resolved() shouldNotBe null
    }

    @Test
    fun `resolved returns null for an unmapped error code`() {
        // TRANSPORT_SERVER_5XX has no error_* key; falls through to the message fallback.
        TransportError.Server5xx(statusCode = 500).resolved() shouldBe null
    }

    @Test
    fun `localizedString renders a mapped error to its localized text`() =
        runTest {
            AuthError.SessionExpired().localizedString() shouldBe "Your session expired. Please sign in again."
        }

    @Test
    fun `localizedString falls back to the error message for an unmapped error`() =
        runTest {
            val error = TransportError.Server5xx(statusCode = 500)
            error.localizedString() shouldBe error.message
        }

    @Test
    fun `an apostrophe-bearing string renders without a leaked backslash escape`() =
        runTest {
            // Regression for #1079: the Android/compose-resources catalog was AAPT-escaping
            // apostrophes (`\'`), and compose-resources' XML parser does not unescape that form,
            // so the backslash leaked to the UI. 403 -> error_forbidden = "You don't ...".
            val rendered = TransportError.Server4xx(statusCode = 403).localizedString()
            rendered shouldBe "You don't have permission to do that."
            rendered shouldNotContain "\\"
        }
}
