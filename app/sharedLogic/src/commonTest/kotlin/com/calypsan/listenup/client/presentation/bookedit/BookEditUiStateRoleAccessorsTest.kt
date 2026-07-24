package com.calypsan.listenup.client.presentation.bookedit

import com.calypsan.listenup.client.domain.model.ContributorSearchResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Pins the bridge-safe role-generic accessors that let iOS render EVERY visible role section
 * dynamically (not just the hardcoded Author/Narrator) without ever subscripting the
 * `[ContributorRole: …]` maps from Swift.
 */
class BookEditUiStateRoleAccessorsTest :
    FunSpec({

        test("searchQueryForRole / searchLoadingForRole / searchResultsForRole read the role-keyed maps") {
            val state =
                BookEditUiState(
                    roleSearchQueries = mapOf(ContributorRole.EDITOR to "carol"),
                    roleSearchLoading = mapOf(ContributorRole.EDITOR to true),
                    roleSearchResults =
                        mapOf(ContributorRole.EDITOR to listOf(ContributorSearchResult(id = "e1", name = "Carol", bookCount = 2))),
                )

            state.searchQueryForRole(ContributorRole.EDITOR) shouldBe "carol"
            state.searchLoadingForRole(ContributorRole.EDITOR) shouldBe true
            state.searchResultsForRole(ContributorRole.EDITOR).single().id shouldBe "e1"

            // A role with no entry falls back to empty defaults (never null, never a crash).
            state.searchQueryForRole(ContributorRole.TRANSLATOR) shouldBe ""
            state.searchLoadingForRole(ContributorRole.TRANSLATOR) shouldBe false
            state.searchResultsForRole(ContributorRole.TRANSLATOR).shouldBeEmpty()
        }

        test("orderedVisibleRoles returns the visible roles in enum declaration order, not set order") {
            val state =
                BookEditUiState(
                    visibleRoles = setOf(ContributorRole.EDITOR, ContributorRole.AUTHOR, ContributorRole.NARRATOR),
                )

            state.orderedVisibleRoles shouldBe
                listOf(ContributorRole.AUTHOR, ContributorRole.NARRATOR, ContributorRole.EDITOR)
        }

        test("availableRolesToAdd excludes the visible roles") {
            val state = BookEditUiState(visibleRoles = setOf(ContributorRole.AUTHOR, ContributorRole.NARRATOR))

            state.availableRolesToAdd shouldBe
                listOf(
                    ContributorRole.EDITOR,
                    ContributorRole.TRANSLATOR,
                    ContributorRole.FOREWORD,
                    ContributorRole.INTRODUCTION,
                    ContributorRole.AFTERWORD,
                    ContributorRole.PRODUCER,
                    ContributorRole.ADAPTER,
                    ContributorRole.ILLUSTRATOR,
                )
        }
    })
