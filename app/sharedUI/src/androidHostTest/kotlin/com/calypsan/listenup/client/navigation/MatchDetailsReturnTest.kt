package com.calypsan.listenup.client.navigation

import androidx.navigation3.runtime.NavKey
import com.calypsan.listenup.client.navigation.entries.returnToSubjectAfterMatch
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** After Apply, Match details always lands on its subject's page, which shows the receipt. */
class MatchDetailsReturnTest :
    FunSpec({
        test("a person's match opened from their page pops back to it") {
            val subject = MatchSubject.Person("c1")
            val stack = mutableListOf<NavKey>(Shell, ContributorDetail("c1"), MatchDetails(subject))

            stack.returnToSubjectAfterMatch(subject)

            stack shouldBe listOf(Shell, ContributorDetail("c1"))
        }

        test("a book's match opened from Book Detail pops back to it") {
            val subject = MatchSubject.Book("b1")
            val stack = mutableListOf<NavKey>(Shell, BookDetail("b1"), MatchDetails(subject))

            stack.returnToSubjectAfterMatch(subject)

            stack shouldBe listOf(Shell, BookDetail("b1"))
        }

        test("a book matched from the admin inbox lands on its Book Detail in the match's place") {
            val subject = MatchSubject.Book("b1")
            val stack = mutableListOf<NavKey>(Shell, AdminInbox, MatchDetails(subject))

            stack.returnToSubjectAfterMatch(subject)

            stack shouldBe listOf(Shell, AdminInbox, BookDetail("b1"))
        }
    })
