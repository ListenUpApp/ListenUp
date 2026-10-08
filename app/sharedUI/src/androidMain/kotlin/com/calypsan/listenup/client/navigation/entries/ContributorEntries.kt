package com.calypsan.listenup.client.navigation.entries

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.calypsan.listenup.client.design.transitions.HeroEntry
import com.calypsan.listenup.client.design.transitions.heroEntryTransitions
import com.calypsan.listenup.client.navigation.BookDetail
import com.calypsan.listenup.client.navigation.ContributorBooks
import com.calypsan.listenup.client.navigation.ContributorDetail
import com.calypsan.listenup.client.navigation.ContributorEdit
import com.calypsan.listenup.client.navigation.ListDetailScene
import com.calypsan.listenup.client.navigation.MatchDetails
import com.calypsan.listenup.client.navigation.MatchSubject
import com.calypsan.listenup.client.navigation.navigateFrom
import com.calypsan.listenup.client.navigation.popFrom

/** Contributor navigation entries. */
internal fun EntryProviderScope<NavKey>.contributorEntries(backStack: NavBackStack<NavKey>) {
    // List panes: on a wide window the book they open sits beside them, so they navigate from
    // themselves.
    entry<ContributorDetail>(metadata = heroEntryTransitions + ListDetailScene.listPane()) { args ->
        HeroEntry {
            com.calypsan.listenup.client.features.contributordetail.ContributorDetailScreen(
                contributorId = args.contributorId,
                onBackClick = {
                    backStack.popFrom(args)
                },
                onBookClick = { bookId ->
                    backStack.navigateFrom(args, BookDetail(bookId))
                },
                onEditClick = { contributorId ->
                    backStack.navigateFrom(args, ContributorEdit(contributorId))
                },
                onViewAllClick = { contributorId, role ->
                    backStack.navigateFrom(args, ContributorBooks(contributorId, role))
                },
                onMatchDetailsClick = { contributorId ->
                    backStack.navigateFrom(args, MatchDetails(MatchSubject.Person(contributorId)))
                },
            )
        }
    }
    entry<ContributorEdit> { args ->
        com.calypsan.listenup.client.features.contributoredit.ContributorEditScreen(
            contributorId = args.contributorId,
            onBackClick = {
                backStack.removeAt(backStack.lastIndex)
            },
            onSaveSuccess = {
                // Navigate back after successful save
                backStack.removeAt(backStack.lastIndex)
            },
            onMergedInto = { survivingContributorId ->
                // Drop BOTH the editor and the detail page beneath it: a rename-collision merge
                // soft-deleted the contributor they describe, so popping only the editor lands on
                // an empty shell that takes another Back to escape (an alias merge survives in
                // place — for it this is a reload of the same page). Land on the survivor instead.
                backStack.removeAt(backStack.lastIndex)
                if (backStack.lastOrNull() is ContributorDetail) {
                    backStack.removeAt(backStack.lastIndex)
                }
                backStack.add(ContributorDetail(survivingContributorId))
            },
        )
    }
    entry<ContributorBooks>(metadata = ListDetailScene.listPane()) { args ->
        com.calypsan.listenup.client.features.contributordetail.ContributorBooksScreen(
            contributorId = args.contributorId,
            role = args.role,
            onBackClick = {
                backStack.popFrom(args)
            },
            onBookClick = { bookId ->
                backStack.navigateFrom(args, BookDetail(bookId))
            },
        )
    }
}
