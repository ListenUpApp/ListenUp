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

    @Test func theSubtitleIsTheNameAndTheSearchIsForAPerson() {
        let find = PersonMatchMapping.find(from: Fixture.results())
        #expect(find.subtitle == "Ray Porter")
        #expect(find.searchPrompt == "Search for a person")
    }

    @Test func theLibraryStripSaysWhatTheyDidHereInEveryRole() {
        let titles = ["Project Hail Mary", "The Martian", "Artemis"]
        let wrote = PersonMatchMapping.library(
            Fixture.inLibrary(credits: [LibraryCredit(role: .author, bookCount: 3)], count: 3, titles: titles)
        )
        #expect(wrote?.line == "Wrote 3 of your books: Project Hail Mary, The Martian, Artemis")
        #expect(wrote?.covers.map(\.title) == titles)

        let ray = PersonMatchMapping.library(Fixture.inLibrary(titles: ["Bobiverse"]))
        #expect(ray?.line == "Narrated 5 of your books · Translated 1: Bobiverse")

        let foreword = PersonMatchMapping.library(
            Fixture.inLibrary(credits: [LibraryCredit(role: .foreword, bookCount: 1)], count: 1)
        )
        #expect(foreword?.line == "Wrote the foreword for 1 of your books")

        #expect(PersonMatchMapping.library(Fixture.inLibrary(credits: [], count: 0)) == nil)
    }

    @Test func theStepsLineSaysHowFindStarted() {
        let viaBooks = PersonMatchMapping.find(from: Fixture.results())
        #expect(viaBooks.stepsLine == "Started from the 5 books crediting Ray Porter in your library.")

        let steps: [any PersonSearchStep] = [
            PersonSearchStepExistingLink(source: Fixture.storefront), PersonSearchStepByName(query: "Ray Porter")
        ]
        let linked = PersonMatchMapping.find(from: Fixture.results(steps: steps))
        #expect(linked.stepsLine == "Started from your Storefront link, then a search for “Ray Porter”.")

        let oneBook = PersonMatchMapping.find(from: Fixture.results(steps: [PersonSearchStepViaYourBooks(bookCount: 1)]))
        #expect(oneBook.stepsLine == "Started from the book crediting Ray Porter in your library.")
    }

    @Test func aPersonRowSaysRoleWorksEveryRoleTheyHoldHereAndSources() {
        let row = PersonMatchMapping.candidate(
            Fixture.candidate(libraryCredits: [
                LibraryCredit(role: .narrator, bookCount: 4), LibraryCredit(role: .translator, bookCount: 1)
            ])
        )
        #expect(row.roleLine == "Narrator · Project Hail Mary, Bobiverse")
        #expect(row.libraryLine == "Narrated 4 of your books · Translated 1")
        #expect(row.sourcesLine == "Shelfdata")
        #expect(row.isStrong && row.isBest)
        #expect(row.accessibilityLabel == "Best match. Ray Porter. Narrator, Project Hail Mary and Bobiverse. "
            + "Narrated 4 of your books · Translated 1. Shelfdata.")
    }

    @Test func aPersonWithNoWorksNamedSaysHowManyBooks() {
        let row = PersonMatchMapping.candidate(
            Fixture.candidate(shownRole: .author, knownWorks: [], worksCount: 1, isBest: false)
        )
        #expect(row.roleLine == "Author · 1 book")
        #expect(row.libraryLine == "Narrated 5 of your books")
    }

    @Test func aTranslatorIsNamedAsOne() {
        let row = PersonMatchMapping.candidate(Fixture.candidate(shownRole: .translator, knownWorks: ["Three Body"]))
        #expect(row.roleLine == "Translator · Three Body")
    }

    @Test func aPersonOnNoneOfYourBooksSaysSoAndNothingElse() {
        let row = PersonMatchMapping.candidate(
            Fixture.candidate(
                shownRole: .author, knownWorks: [], worksCount: 1, libraryCount: 0, foundIn: [Fixture.shelfdata],
                isStrong: false, isBest: false, noBooksInLibrary: true
            )
        )
        #expect(row.roleLine == "Author · 1 book")
        #expect(row.libraryLine == "No books in your library")
        #expect(row.accessibilityLabel == "Ray Porter. Author, 1 book. No books in your library. Shelfdata.")
    }

    @Test func theRowLastOpenedInReviewIsMarked() {
        let other = Fixture.candidate(id: "shelfdata:P2:", isStrong: false, isBest: false)
        let find = PersonMatchMapping.find(
            from: Fixture.results(maybe: [other], pickedKey: Fixture.key("shelfdata:P2:"))
        )
        #expect(find.phase.results?.pickedId == "shelfdata:P2:")
        #expect(find.phase.results?.maybe.first?.isStrong == false)
    }

    @Test func searchingKeepsThePreviousResultsOnScreen() {
        let state = PersonFindUiStateSearching(
            header: Fixture.header, inLibrary: nil, query: "porter", previous: Fixture.results()
        )
        let find = PersonMatchMapping.find(from: state)
        guard case .searching(let shown) = find.phase else {
            Issue.record("expected .searching")
            return
        }
        #expect(shown?.strong.map(\.id) == ["shelfdata:P1:"])
        #expect(find.query == "porter")
    }

    @Test func noProfilesSaysSoForThePersonAndOffersEditByHand() {
        let none = PersonMatchMapping.find(from: PersonFindUiStateNoProfiles(
            header: Fixture.header, inLibrary: nil, query: ""
        ))
        #expect(none.phase == .noProfiles(PersonNoProfiles(
            title: "No source has a profile for this person",
            message: "You can add their photo and biography yourself.",
            editTitle: "Edit by Hand"
        )))
    }

    @Test func aFailureOffersOnlyRetryForAPerson() {
        let failure = PersonMatchMapping.failure(FindFailureNothingFound.shared)
        #expect(failure.actions == [.retry(title: "Try Again")])
        #expect(PersonMatchMapping.failure(FindFailureOffline.shared).actions == [.retry(title: "Try Again")])
    }

    @Test func everyRoleHasAWordAndAnEvidencePhrase() {
        let roles: [ContributorRole] = [
            .author, .narrator, .editor, .translator, .foreword, .introduction, .afterword, .producer, .adapter,
            .illustrator
        ]
        for role in roles {
            #expect(MatchCopy.roleWord(role)?.isEmpty == false, "\(role)")
            let line = MatchCopy.creditsLine([LibraryCredit(role: role, bookCount: 2)])
            #expect(line?.hasSuffix("2 of your books") == true, "\(role): \(line ?? "nil")")
        }
    }

    @Test func aReviewHeaderWithNoRoleSaysOnlyWhereTheyWereFound() {
        #expect(MatchCopy.personHeaderFrom(role: nil, sources: "Shelfdata") == "From Shelfdata")
        #expect(MatchCopy.personHeaderFrom(role: "Narrator", sources: "Shelfdata") == "Narrator · from Shelfdata")
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
            == "Biography. Yours: —. Proposed from Shelfdata: "
            + "\(MatchCopy.withoutFinalPeriod(Fixture.proposedBiography)).")
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
                Fixture.ready(
                    applyBar: PersonApplySummary(photo: photo, biography: biography, sources: [Fixture.shelfdata])
                ),
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
        #expect(review.header.libraryLine == "Narrated 5 of your books")
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
        let both = receipt(photo: Fixture.shelfdata, biography: Fixture.shelfdata)
        #expect(MatchCopy.personReceipt(both, name: "Ray Porter") == "Changed photo and biography for Ray Porter")
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
            header: nil, inLibrary: nil, query: ""
        )).isEmpty)
    }
}
