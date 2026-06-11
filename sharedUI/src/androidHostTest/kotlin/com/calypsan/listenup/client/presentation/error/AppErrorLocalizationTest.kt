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
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for the [AppError] UI-layer error renderer.
 *
 * [localizedString] (and the composable [localized]) ultimately call compose-resources'
 * [org.jetbrains.compose.resources.getString], which resolves the system [ResourceEnvironment]
 * via `Resources.getSystem()`. A plain JVM unit test receives an unmocked `Resources` that throws.
 * Robolectric supplies a real Android resource environment (default locale `en`), so `getString`
 * reads the packaged `error_*` strings exactly as production code does. JUnit4 +
 * [RobolectricTestRunner] follows the precedent set by
 * [com.calypsan.listenup.client.deeplink.DeepLinkParserTest]. `@Config(sdk = [35])` pins
 * Robolectric to the highest SDK its 4.15.1 release ships, since the module's compileSdk is newer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppErrorLocalizationTest {
    @Test
    fun `resourceKey lowercases the error code with the error_ prefix`() {
        assertEquals("error_transport_timeout", TransportError.Timeout().resourceKey())
    }

    @Test
    fun `resolved maps Server4xx 409 to the conflict resource`() {
        assertEquals(Res.string.error_conflict, TransportError.Server4xx(statusCode = 409).resolved())
    }

    @Test
    fun `resolved maps Server4xx 403 to the forbidden resource`() {
        assertEquals(Res.string.error_forbidden, TransportError.Server4xx(statusCode = 403).resolved())
    }

    @Test
    fun `resolved maps Server4xx 404 to the not_found resource`() {
        assertEquals(Res.string.error_not_found, TransportError.Server4xx(statusCode = 404).resolved())
    }

    @Test
    fun `resolved resolves a dynamically-keyed error to a non-null resource`() {
        assertNotNull(AuthError.SessionExpired().resolved())
    }

    @Test
    fun `resolved returns null for an unmapped error code`() {
        // TRANSPORT_SERVER_5XX has no error_* key; falls through to the message fallback.
        assertNull(TransportError.Server5xx(statusCode = 500).resolved())
    }

    @Test
    fun `localizedString renders a mapped error to its localized text`() =
        runTest {
            assertEquals(
                "Your session expired. Please sign in again.",
                AuthError.SessionExpired().localizedString(),
            )
        }

    @Test
    fun `localizedString falls back to the error message for an unmapped error`() =
        runTest {
            val error = TransportError.Server5xx(statusCode = 500)
            assertEquals(error.message, error.localizedString())
        }
}
