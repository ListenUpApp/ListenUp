package com.calypsan.listenup.client.navigation.entries

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.calypsan.listenup.client.features.match.BookMatchRoute
import com.calypsan.listenup.client.features.match.PersonMatchRoute
import com.calypsan.listenup.client.navigation.BookDetail
import com.calypsan.listenup.client.navigation.ContributorDetail
import com.calypsan.listenup.client.navigation.ContributorEdit
import com.calypsan.listenup.client.navigation.MatchDetails
import com.calypsan.listenup.client.navigation.MatchSubject

/** Match details, for a book or a person: one entry, the subject deciding which session it hosts. */
internal fun EntryProviderScope<NavKey>.matchEntries(backStack: NavBackStack<NavKey>) {
    entry<MatchDetails> { args ->
        when (val subject = args.subject) {
            is MatchSubject.Book -> {
                BookMatchRoute(
                    bookId = subject.bookId,
                    onBack = { backStack.removeAt(backStack.lastIndex) },
                    onApplied = { backStack.returnToSubjectAfterMatch(subject) },
                )
            }

            is MatchSubject.Person -> {
                PersonMatchRoute(
                    contributorId = subject.contributorId,
                    onBack = { backStack.removeAt(backStack.lastIndex) },
                    onApplied = { backStack.returnToSubjectAfterMatch(subject) },
                    onEditByHand = { backStack.add(ContributorEdit(subject.contributorId)) },
                )
            }
        }
    }
}

/**
 * Leaves Match details for the subject's page, which shows the receipt: a pop when that page opened it,
 * otherwise (the admin inbox's held-book triage) the page takes the match's place on the stack.
 */
internal fun MutableList<NavKey>.returnToSubjectAfterMatch(subject: MatchSubject) {
    removeAt(lastIndex)
    val page =
        when (subject) {
            is MatchSubject.Book -> BookDetail(subject.bookId)
            is MatchSubject.Person -> ContributorDetail(subject.contributorId)
        }
    if (lastOrNull() != page) add(page)
}
