package com.calypsan.listenup.client.data.local.db

import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * The single-book triage check every refusing gate uses (playback, download): a held book gets a
 * typed [BookError.HeldForReview]; any other book — including one just released — gets null.
 */
class HeldBookRefusalTest :
    FunSpec({
        test("a held book is refused, typed") {
            withHeldBookDb { db ->
                HeldBookFixture.seedBook(db, "held")
                HeldBookFixture.hold(db, "held")

                db.bookDao().heldRefusal(BookId("held")).shouldBeInstanceOf<BookError.HeldForReview>()
            }
        }

        test("an ordinary book, and a released one, are not refused") {
            withHeldBookDb { db ->
                HeldBookFixture.seedBook(db, "visible")
                HeldBookFixture.publish(db, "visible")
                HeldBookFixture.seedBook(db, "held")
                HeldBookFixture.hold(db, "held")
                HeldBookFixture.applyReleaseEcho(db, "held")

                db.bookDao().heldRefusal(BookId("visible")).shouldBeNull()
                db.bookDao().heldRefusal(BookId("held")).shouldBeNull()
            }
        }
    })
