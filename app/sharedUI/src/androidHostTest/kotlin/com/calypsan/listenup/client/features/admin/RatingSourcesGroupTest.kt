package com.calypsan.listenup.client.features.admin

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import com.calypsan.listenup.api.dto.admin.RatingSourceStatus
import com.calypsan.listenup.api.dto.admin.RatingSourceUnavailable
import com.calypsan.listenup.client.util.formatDateLong
import com.calypsan.listenup.api.sync.ExternalRatingSource
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The admin "Rating sources" section: one row per outside catalog, its switch, and its health
 * line — the surface [com.calypsan.listenup.client.presentation.admin.AdminSettingsViewModel.setRatingSourceEnabled]
 * is wired to.
 */
@OptIn(ExperimentalTime::class)
@RunWith(RobolectricTestRunner::class)
class RatingSourcesGroupTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `lists every source with its health line`() {
        composeRule.setContent {
            MaterialTheme {
                RatingSourcesGroup(
                    sources =
                        listOf(
                            RatingSourceStatus(
                                source = ExternalRatingSource.AUDIBLE,
                                enabled = true,
                                lastFetchedAt = null,
                                lastError = null,
                            ),
                            RatingSourceStatus(
                                source = ExternalRatingSource.HARDCOVER,
                                enabled = false,
                                lastFetchedAt = null,
                                lastError = "Timed out",
                            ),
                        ),
                    onSourceEnabledChange = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("Audible").assertIsDisplayed()
        composeRule.onNodeWithText("Not fetched yet").assertIsDisplayed()
        composeRule.onNodeWithText("Hardcover").assertIsDisplayed()
        composeRule.onNodeWithText("Last attempt failed: Timed out").assertIsDisplayed()
    }

    @Test
    fun `a source's switch reflects its enabled flag`() {
        composeRule.setContent {
            MaterialTheme {
                RatingSourcesGroup(
                    sources =
                        listOf(
                            RatingSourceStatus(
                                source = ExternalRatingSource.AUDIBLE,
                                enabled = true,
                                lastFetchedAt = null,
                                lastError = null,
                            ),
                        ),
                    onSourceEnabledChange = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithTag("ratingSourceSwitch_AUDIBLE").assertIsOn()
    }

    @Test
    fun `toggling a source's switch calls setRatingSourceEnabled with its source`() {
        var toggledSource: ExternalRatingSource? = null
        var toggledEnabled: Boolean? = null
        composeRule.setContent {
            MaterialTheme {
                RatingSourcesGroup(
                    sources =
                        listOf(
                            RatingSourceStatus(
                                source = ExternalRatingSource.AUDIBLE,
                                enabled = true,
                                lastFetchedAt = null,
                                lastError = null,
                            ),
                        ),
                    onSourceEnabledChange = { source, enabled ->
                        toggledSource = source
                        toggledEnabled = enabled
                    },
                )
            }
        }

        composeRule.onNodeWithTag("ratingSourceSwitch_AUDIBLE").performClick()

        toggledSource shouldBe ExternalRatingSource.AUDIBLE
        toggledEnabled shouldBe false
    }

    // --- Health lines: what TalkBack reads for each state, in priority order ---

    private fun showOne(status: RatingSourceStatus) {
        composeRule.setContent {
            MaterialTheme {
                RatingSourcesGroup(sources = listOf(status), onSourceEnabledChange = { _, _ -> })
            }
        }
    }

    /** The row's one merged TalkBack node reads exactly these texts, in order. */
    private fun assertRowReads(
        source: ExternalRatingSource,
        vararg texts: String,
    ) {
        val node = composeRule.onNodeWithTag("ratingSourceSwitch_${source.name}").fetchSemanticsNode()
        node.config[SemanticsProperties.Text].map { it.text } shouldBe texts.toList()
    }

    private fun status(
        source: ExternalRatingSource = ExternalRatingSource.HARDCOVER,
        lastFetchedAt: Long? = null,
        lastError: String? = null,
        pausedUntil: Long? = null,
        unavailable: RatingSourceUnavailable? = null,
        connectionUsername: String? = null,
    ) = RatingSourceStatus(
        source = source,
        enabled = true,
        lastFetchedAt = lastFetchedAt,
        lastError = lastError,
        pausedUntil = pausedUntil,
        unavailable = unavailable,
        connectionUsername = connectionUsername,
    )

    private val inAWeek = Clock.System.now().toEpochMilliseconds() + 7L * 24 * 60 * 60 * 1000

    @Test
    fun `a source the server has not set up says so, above a pause and an error`() {
        showOne(
            status(
                unavailable = RatingSourceUnavailable.NOT_CONFIGURED,
                pausedUntil = inAWeek,
                lastError = "Timed out",
            ),
        )

        assertRowReads(ExternalRatingSource.HARDCOVER, "Hardcover", "Not set up on this server")
    }

    @Test
    fun `a source waiting for a Hardcover connection asks for one`() {
        showOne(status(unavailable = RatingSourceUnavailable.NO_CONNECTION, lastFetchedAt = 1L))

        assertRowReads(ExternalRatingSource.HARDCOVER, "Hardcover", "Connect a Hardcover account to enable")
    }

    @Test
    fun `a reason from a newer server still reads as unavailable`() {
        showOne(status(unavailable = RatingSourceUnavailable.UNKNOWN))

        assertRowReads(ExternalRatingSource.HARDCOVER, "Hardcover", "Unavailable on this server")
    }

    @Test
    fun `an unavailable source's switch stays operable`() {
        showOne(status(unavailable = RatingSourceUnavailable.NO_CONNECTION))

        composeRule.onNodeWithTag("ratingSourceSwitch_HARDCOVER").assertIsEnabled()
    }

    @Test
    fun `a paused source says until when, and why`() {
        showOne(status(source = ExternalRatingSource.GOODREADS, pausedUntil = inAWeek, lastError = "Timed out"))

        assertRowReads(
            ExternalRatingSource.GOODREADS,
            "Goodreads",
            "Paused until ${formatDateLong(inAWeek)}: Timed out",
        )
    }

    @Test
    fun `a paused source with no recorded reason still says until when`() {
        showOne(status(source = ExternalRatingSource.GOODREADS, pausedUntil = inAWeek))

        assertRowReads(ExternalRatingSource.GOODREADS, "Goodreads", "Paused until ${formatDateLong(inAWeek)}")
    }

    @Test
    fun `a failing source that is not paused reports its last error`() {
        showOne(status(source = ExternalRatingSource.AUDIBLE, lastError = "Timed out", lastFetchedAt = 1L))

        assertRowReads(ExternalRatingSource.AUDIBLE, "Audible", "Last attempt failed: Timed out")
    }

    @Test
    fun `a healthy source says when it last fetched`() {
        showOne(status(source = ExternalRatingSource.AUDIBLE, lastFetchedAt = Clock.System.now().toEpochMilliseconds()))

        assertRowReads(ExternalRatingSource.AUDIBLE, "Audible", "Last fetched just now")
    }

    @Test
    fun `a source that never ran says so`() {
        showOne(status(source = ExternalRatingSource.AUDIBLE))

        assertRowReads(ExternalRatingSource.AUDIBLE, "Audible", "Not fetched yet")
    }

    @Test
    fun `Hardcover names whose account it uses on a second line`() {
        showOne(status(lastFetchedAt = Clock.System.now().toEpochMilliseconds(), connectionUsername = "simon"))

        assertRowReads(
            ExternalRatingSource.HARDCOVER,
            "Hardcover",
            "Last fetched just now\nUsing simon's Hardcover account",
        )
    }
}
