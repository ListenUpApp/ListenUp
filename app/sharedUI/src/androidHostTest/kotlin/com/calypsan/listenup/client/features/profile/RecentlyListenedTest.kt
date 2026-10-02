package com.calypsan.listenup.client.features.profile

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.domain.model.ProfileRecentBook
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.core.BookId
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify
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
 * A profile's "Recently listened" strip.
 *
 * The strip's books come from the synced activity feed, which knows a book only by its id — there is
 * no local cover path. The card must therefore resolve its cover from the book id (local file, else
 * the server), not from a path that is never set: the old card asked for `coverPath` and drew a blank
 * square for every book. And the heading says what the strip is — "Recently listened", in the first
 * person on your own profile — not "Recently finished", which it never was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class RecentlyListenedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val images =
        mock<ImageRepository>(MockMode.autoUnit) {
            every { getBookCoverPath(any()) } returns "/tmp/cover-b1.webp"
            every { bookCoverExists(any()) } returns true
        }

    /** BookCoverImage resolves an id-only cover through the GLOBAL Koin context. */
    @Before
    fun startKoinForCovers() {
        startKoin {
            modules(
                module {
                    single<ImageRepository> { images }
                    single<ServerConfig> { mock(MockMode.autoUnit) }
                    single<AuthSession> { mock(MockMode.autoUnit) }
                },
            )
        }
    }

    @After
    fun stopKoinAfterCovers() {
        stopKoin()
    }

    @Test
    fun `a book with no cover path still gets its cover, looked up by book id`() {
        composeRule.setContent {
            MaterialTheme {
                RecentBooksRow(
                    books = listOf(ProfileRecentBook(bookId = "b1", title = "The Way of Kings", coverHash = null)),
                    onBookClick = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("The Way of Kings").assertIsDisplayed()
        verify { images.bookCoverExists(BookId("b1")) }
    }

    @Test
    fun `someone else's profile says Recently listened`() {
        composeRule.setContent {
            MaterialTheme { RecentlyListenedHeader(isOwnProfile = false) }
        }
        composeRule.onNodeWithText("Recently listened").assertIsDisplayed()
        composeRule.onNodeWithText("Recently finished").assertDoesNotExist()
    }

    @Test
    fun `your own profile says what you've been listening to`() {
        composeRule.setContent {
            MaterialTheme { RecentlyListenedHeader(isOwnProfile = true) }
        }
        composeRule.onNodeWithText("What you've been listening to").assertIsDisplayed()
    }
}
