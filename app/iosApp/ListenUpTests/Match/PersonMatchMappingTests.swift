import Foundation
import Testing
import Shared
@testable import ListenUp

// Person Match details' boundary: every shared Find, Review and receipt state lands on its native value, so
// the views only lay out what the mapping already said.

@MainActor
@Suite("Person Match details mapping")
struct PersonMatchMappingTests {
    private typealias Fixture = PersonMatchFixtures

    // MARK: - Find

    @Test func theSubtitleAndSearchFieldFollowTheRole() {
        let narrator = PersonMatchMapping.find(from: Fixture.results(role: .narrator))
        #expect(narrator.role == .narrator)
        #expect(narrator.subtitle == "Ray Porter · narrator")
        #expect(narrator.searchPrompt == "Search for a narrator")

        let author = PersonMatchMapping.find(from: Fixture.results(role: .author))
        #expect(author.role == .author)
        #expect(author.subtitle == "Ray Porter · author")
        #expect(author.searchPrompt == "Search for a person")
    }

    @Test func theCoverageNoteSaysWhichSourceHasNoProfilesAndWhichIsUsed() {
        let note = CoverageNote(withoutProfiles: [Fixture.storefront], using: [Fixture.shelfdata])
        let find = PersonMatchMapping.find(from: Fixture.results(coverageNote: note))
        #expect(find.coverageNote == "Storefront has no narrator profiles, so this search uses Shelfdata.")
        #expect(PersonMatchMapping.find(from: Fixture.results(role: .author, coverageNote: note)).coverageNote
            == "Storefront has no author profiles, so this search uses Shelfdata.")
        #expect(PersonMatchMapping.find(from: Fixture.results()).coverageNote == nil)
    }

    @Test func theLibraryStripNamesTheBooksInTheRole() {
        let titles = ["Project Hail Mary", "The Martian", "Artemis"]
        let wrote = PersonMatchMapping.library(Fixture.inLibrary(role: .author, count: 3, titles: titles), role: .author)
        #expect(wrote?.line == "Wrote 3 books in your library: Project Hail Mary, The Martian, Artemis")
        #expect(wrote?.covers.map(\.title) == titles)

        let narrated = PersonMatchMapping.library(Fixture.inLibrary(count: 1, titles: ["Bobiverse"]), role: .narrator)
        #expect(narrated?.line == "Narrated 1 book in your library: Bobiverse")

        #expect(PersonMatchMapping.library(Fixture.inLibrary(count: 0), role: .narrator) == nil)
    }

    @Test func theStepsLineSaysHowFindStarted() {
        let viaBooks = PersonMatchMapping.find(from: Fixture.results())
        #expect(viaBooks.stepsLine == "Started from the 5 books Ray Porter narrates in your library.")

        let steps: [any PersonSearchStep] = [
            PersonSearchStepExistingLink(source: Fixture.storefront), PersonSearchStepByName(query: "Ray Porter")
        ]
        let linked = PersonMatchMapping.find(from: Fixture.results(role: .author, steps: steps))
        #expect(linked.stepsLine == "Started from your Storefront link, then a search for “Ray Porter”.")

        let oneBook = PersonMatchMapping.find(
            from: Fixture.results(role: .author, steps: [PersonSearchStepViaYourBooks(bookCount: 1)])
        )
        #expect(oneBook.stepsLine == "Started from the book Ray Porter wrote in your library.")
    }

    @Test func aPersonRowSaysRoleWorksLibraryAndSources() {
        let row = PersonMatchMapping.candidate(Fixture.candidate(), searched: .narrator)
        #expect(row.roleLine == "Narrator · Project Hail Mary, Bobiverse")
        #expect(row.libraryLine == "Narrated 5 books in your library")
        #expect(row.sourcesLine == "Shelfdata")
        #expect(row.differentRole == nil)
        #expect(row.isStrong && row.isBest)
        #expect(row.accessibilityLabel
            == "Best match. Ray Porter. Narrator, Project Hail Mary and Bobiverse. Narrated 5 books in your library. Shelfdata.")
    }

    @Test func aPersonWithNoWorksNamedSaysHowManyBooks() {
        let row = PersonMatchMapping.candidate(
            Fixture.candidate(shownRole: .author, knownWorks: [], worksCount: 1, isBest: false), searched: .author
        )
        #expect(row.roleLine == "Author · 1 book")
        #expect(row.libraryLine == "Wrote 5 books in your library")
    }

    @Test func aPersonInAnotherRoleIsFlaggedDifferentRole() {
        let row = PersonMatchMapping.candidate(
            Fixture.candidate(
                shownRole: .author, knownWorks: [], worksCount: 1, libraryCount: 0, foundIn: [Fixture.shelfdata],
                isStrong: false, isBest: false, isDifferentRole: true, noBooksInLibrary: true
            ),
            searched: .narrator
        )
        #expect(row.roleLine == "Author · 1 book · Not a narrator")
        #expect(row.differentRole == "Different role")
        #expect(row.libraryLine == "No books in your library")
        #expect(row.accessibilityLabel
            == "Ray Porter. Author, 1 book. Not a narrator. Different role. No books in your library. Shelfdata.")
    }

    @Test func theRowLastOpenedInReviewIsMarked() {
        let other = Fixture.candidate(id: "shelfdata:P2:", isStrong: false, isBest: false)
        let find = PersonMatchMapping.find(
            from: Fixture.results(maybe: [other], pickedKey: Fixture.key("shelfdata:P2:"))
        )
        #expect(find.phase.results?.pickedId == "shelfdata:P2:")
        #expect(find.phase.results?.maybe.first?.isStrong == false)
    }

    @Test func searchingKeepsTheRolesPreviousResultsOnScreen() {
        let state = PersonFindUiStateSearching(
            role: .narrator, header: Fixture.header, inLibrary: nil, query: "porter", previous: Fixture.results()
        )
        let find = PersonMatchMapping.find(from: state)
        guard case .searching(let shown) = find.phase else {
            Issue.record("expected .searching")
            return
        }
        #expect(shown?.strong.map(\.id) == ["shelfdata:P1:"])
        #expect(find.query == "porter")
    }

    @Test func noProfilesSaysSoForTheRoleAndOffersEditByHand() {
        let narrator = PersonMatchMapping.find(from: PersonFindUiStateNoProfiles(
            role: .narrator, header: Fixture.header, inLibrary: nil, query: "", coverageNote: nil
        ))
        #expect(narrator.phase == .noProfiles(PersonNoProfiles(
            title: "No source has a profile for this narrator",
            message: "You can add their photo and biography yourself.",
            editTitle: "Edit by Hand"
        )))
        #expect(PersonMatchMapping.noProfiles(.author).title == "No source has a profile for this author")
    }

    @Test func aFailureOffersOnlyRetryForAPerson() {
        let failure = PersonMatchMapping.failure(FindFailureNothingFound.shared)
        #expect(failure.actions == [.retry(title: "Try Again")])
        #expect(PersonMatchMapping.failure(FindFailureOffline.shared).actions == [.retry(title: "Try Again")])
    }

    @Test func aMatchableRoleRoundTripsAndOtherRolesDont() {
        #expect(PersonMatchRole(ContributorRole.narrator)?.contributorRole == .narrator)
        #expect(PersonMatchRole(ContributorRole.author)?.contributorRole == .author)
        #expect(PersonMatchRole(ContributorRole.editor) == nil)
        #expect(PersonMatchRole.allCases.map(\.segmentTitle) == ["As author", "As narrator"])
    }

    // MARK: - Review: photo

    @Test func aPhotoThatFillsAGapStartsTickedOnTheSourcesPhoto() {
        let photo = PersonMatchMapping.photo(Fixture.photo())
        #expect(photo.isTicked)
        #expect(photo.stateTitle == "Fills a gap")
        #expect(photo.yoursCaption == "Yours · no photo")
        #expect(photo.proposedFrom == "from Shelfdata")
        #expect(photo.proposedLabel == "Photo from Shelfdata")
        #expect(photo.segments.map(\.title) == ["Shelfdata", "Keep current"])
        #expect(photo.selectedSegment == .option("p0"))
        #expect(photo.switchStyle == .segmented)
        #expect(photo.setByHandNote == nil)
    }

    @Test func aPhotoSetByHandStartsOnKeepCurrent() {
        let photo = PersonMatchMapping.photo(
            Fixture.photo(state: .userEdited, currentPath: "/people/p.jpg", setByHand: true, ticked: false)
        )
        #expect(!photo.isTicked)
        #expect(photo.selectedSegment == .keepYours)
        #expect(photo.stateTitle == "You edited this")
        #expect(photo.yoursCaption == "Your photo")
        #expect(photo.setByHandNote == "You set this photo by hand. Kept unless you choose another.")
    }

    @Test func morePhotoSourcesThanFourSegmentsBecomeAMenu() {
        let sources = (1...4).map { MetadataSource(id: "s\($0)", label: "Source \($0)") }
        #expect(PersonMatchMapping.photo(Fixture.photo(sources: sources)).switchStyle == .menu)
        #expect(PersonMatchMapping.photo(Fixture.photo(sources: Array(sources.prefix(3)))).switchStyle == .segmented)
    }

    // MARK: - Review: biography

    @Test func aBiographyThatFillsAGapIsTickedWithNothingToKeep() {
        let biography = PersonMatchMapping.biography(Fixture.biography(), viewerId: nil)
        #expect(biography.isTicked)
        #expect(biography.alreadySame == nil)
        #expect(biography.stateTitle == "Fills a gap")
        #expect(biography.values.yours == nil)
        #expect(biography.segments.map(\.title) == ["Shelfdata"])
        #expect(biography.switchStyle == .none)
        #expect(biography.values.accessibilityLabel
            == "Biography. Yours: —. Proposed from Shelfdata: \(MatchCopy.withoutFinalPeriod(Fixture.proposedBiography)).")
    }

    @Test func aBiographyYouEditedStartsUntickedAndSaysWhose() {
        let edit = HandEdit(byUserId: "u1", byName: "Simon", at: MatchFixtures.twelfthOfSeptember)
        let biography = PersonMatchMapping.biography(
            Fixture.biography(state: .userEdited, current: "My own words.", ticked: false, handEdit: edit),
            viewerId: "u1"
        )
        #expect(!biography.isTicked)
        #expect(biography.isEdited)
        #expect(biography.stateTitle == "You edited this")
        #expect(biography.editedNote?.hasPrefix("Edited by you, ") == true)
        #expect(biography.segments.map(\.title) == ["Shelfdata", "Keep yours"])
        #expect(biography.selectedSegment == .keepYours)
        #expect(biography.switchStyle == .segmented)
    }

    @Test func theSameBiographyCollapsesToASentence() {
        let biography = PersonMatchMapping.biography(
            Fixture.biography(state: .same, current: Fixture.proposedBiography, ticked: false), viewerId: nil
        )
        #expect(biography.alreadySame == "Your biography already matches.")
    }

    // MARK: - Review: apply

    @Test func theApplyBarSaysWhatApplyWrites() {
        func bar(_ photo: Bool, _ biography: Bool) -> MatchApplyBar {
            PersonMatchMapping.review(
                Fixture.ready(applyBar: PersonApplySummary(photo: photo, biography: biography, sources: [Fixture.shelfdata])),
                viewerId: nil
            ).applyBar
        }
        #expect(bar(true, true).summary == "Photo · biography")
        #expect(bar(true, false).summary == "Photo")
        #expect(bar(false, true).summary == "Biography")
        #expect(bar(false, false).summary == "Nothing selected")
        #expect(bar(true, true).canApply)
        #expect(!bar(false, false).canApply)
    }

    @Test func whatWillChangeNamesTheChangesAndTheirSource() {
        let review = PersonMatchMapping.review(Fixture.ready(), viewerId: nil)
        #expect(review.whatWillChange == "Photo and biography · from Shelfdata")
        #expect(review.header.roleLine == "Narrator · from Shelfdata")
        #expect(review.header.libraryLine == "Narrated 5 books in your library")
        #expect(review.photo != nil && review.biography != nil)
    }

    @Test func anApplyErrorSaysNothingWasChanged() {
        let error = UnknownError(
            code: "X", message: "The server refused that.", correlationId: nil, debugInfo: nil, isRetryable: false
        )
        let review = PersonMatchMapping.review(Fixture.ready(applyError: error), viewerId: nil)
        #expect(review.applyBar.error == "The server refused that. Nothing was changed.")
    }

    @Test func theReviewPhasesCarryTheCandidate() {
        let loading = PersonMatchMapping.review(
            from: PersonReviewUiStateLoading(candidate: Fixture.candidate()), viewerId: nil
        )
        #expect(loading == .loading(candidateId: "shelfdata:P1:", name: "Ray Porter"))
        #expect(PersonMatchMapping.review(from: PersonReviewUiStateNoneChosen.shared, viewerId: nil) == .noneChosen)
    }

    // MARK: - Receipt

    @Test func thePersonReceiptNamesWhatChangedAndForWhom() {
        func receipt(photo: MetadataSource?, biography: MetadataSource?) -> MatchReceiptUi {
            MatchReceiptUi(
                receiptId: "r1", fieldCount: 0, coverSource: nil, chapterNameCount: 0, photoSource: photo,
                biographySource: biography, changes: [], undoable: true
            )
        }
        #expect(MatchCopy.personReceipt(receipt(photo: Fixture.shelfdata, biography: Fixture.shelfdata), name: "Ray Porter")
            == "Changed photo and biography for Ray Porter")
        #expect(MatchCopy.personReceipt(receipt(photo: Fixture.shelfdata, biography: nil), name: "Ray Porter")
            == "Changed photo for Ray Porter")
        #expect(MatchCopy.personReceipt(receipt(photo: nil, biography: Fixture.shelfdata), name: "Ray Porter")
            == "Changed biography for Ray Porter")
        #expect(MatchCopy.personReceipt(receipt(photo: nil, biography: nil), name: "Ray Porter")
            == "Matched Ray Porter. Nothing needed changing.")

        let shown = BookMatchMapping.receipt(
            from: MatchReceiptUiStateShown(
                receipt: receipt(photo: Fixture.shelfdata, biography: Fixture.shelfdata), undoing: false, undoError: nil
            ),
            subject: .person(name: "Ray Porter")
        )
        guard case .shown(let model) = shown else {
            Issue.record("expected .shown")
            return
        }
        #expect(model.sentence == "Changed photo and biography for Ray Porter")
        #expect(!model.showsWhatChanged)
        #expect(model.canUndo)
    }

    @Test func anExpiredPersonReceiptSaysWhoChanged() {
        #expect(BookMatchMapping.receipt(from: MatchReceiptUiStateExpired.shared, subject: .person(name: "Ray Porter"))
            == .expired(message: "Ray Porter has changed since, so the match can't be undone."))
    }

    // MARK: - Observer seams

    @Test func photoChoicesBecomeTheSharedImageChoice() {
        #expect(PersonMatchObserver.imageChoice(.keepYours) is ImageChoiceKeepCurrent)
        let candidate = PersonMatchObserver.imageChoice(.option("p1")) as? ImageChoiceCandidate
        #expect(candidate?.optionId == "p1")
    }

    @Test func everyCandidateOnScreenCanBePicked() {
        let other = Fixture.candidate(id: "shelfdata:P2:")
        let keys = PersonMatchObserver.candidateKeys(in: Fixture.results(maybe: [other]))
        #expect(Set(keys.keys) == ["shelfdata:P1:", "shelfdata:P2:"])
        #expect(PersonMatchObserver.candidateKeys(in: PersonFindUiStateNoProfiles(
            role: .narrator, header: nil, inLibrary: nil, query: "", coverageNote: nil
        )).isEmpty)
    }
}
