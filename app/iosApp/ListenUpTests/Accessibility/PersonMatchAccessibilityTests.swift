import SwiftUI
import Testing
import Shared
@testable import ListenUp

/// Person Match details for VoiceOver and large text (spec, "Accessibility requirements"): headings for every
/// Review section, ticks that are toggles, a row that says who, what and where in one stop, 44-point targets,
/// an Apply tray that leaves the screen to the review at AX5, and a receipt with Undo.
@MainActor
@Suite("Person Match details accessibility", .serialized)
struct PersonMatchAccessibilityTests {
    private typealias Fixture = PersonMatchFixtures

    private func review(_ ready: PersonReviewUiStateReady = Fixture.ready()) -> PersonReview {
        PersonMatchMapping.review(ready, viewerId: "u1")
    }

    private func hostedReview(_ review: PersonReview, size: DynamicTypeSize = .large) async -> HostedView {
        await HostedView(
            PersonReviewContent(review: review, contributorId: nil, yourName: "Ray Porter"),
            size: CGSize(width: 393, height: 3000),
            dynamicTypeSize: size
        )
    }

    // MARK: - Review

    @Test func everyReviewSectionIsAHeading() async throws {
        let hosted = await hostedReview(review())
        defer { hosted.close() }
        for title in ["What will change", "Photo", "Biography"] {
            let heading = try #require(
                hosted.stops(labelContaining: title).first { $0.isHeader }, "\(title)\n\(hosted.tree)"
            )
            #expect(heading.isHeader)
        }
    }

    /// The photo and biography ticks are toggles with their state in words, and full targets.
    @Test func thePhotoAndBiographyTicksAreTogglesThatSayTheirState() async throws {
        let hosted = await hostedReview(review(Fixture.ready(
            biography: Fixture.biography(state: .userEdited, current: "My own words.", ticked: false)
        )))
        defer { hosted.close() }
        let photo = try #require(hosted.stop(labelled: "Change photo"), "\(hosted.tree)")
        #expect(photo.value == "Selected")
        let biography = try #require(hosted.stop(labelled: "Apply biography"), "\(hosted.tree)")
        #expect(biography.value == "Not selected")
        for tick in [photo, biography] {
            #expect(tick.traits.contains(.toggleButton), "\(tick)")
            #expect(tick.frame.height >= TapTarget.minimum - 0.5, "\(tick)")
        }
    }

    /// Yours and Proposed are images that say whose photo each is.
    @Test func thePhotoFiguresSayWhoseEachIs() async throws {
        let hosted = await hostedReview(review())
        defer { hosted.close() }
        #expect(hosted.stop(labelled: "Yours · no photo") != nil, "\(hosted.tree)")
        #expect(hosted.stop(labelled: "Photo from Shelfdata") != nil, "\(hosted.tree)")
    }

    /// The biography's values read in full as one stop.
    @Test func theBiographyValuesAreOneStopReadInFull() async throws {
        let section = PersonMatchMapping.biography(Fixture.biography(), viewerId: nil)
        let hosted = await HostedView(
            List { PersonBiographyRowView(biography: section, actions: PersonReviewActions()) }
        )
        defer { hosted.close() }
        #expect(hosted.stop(labelled: section.values.accessibilityLabel) != nil, "\(hosted.tree)")
    }

    // MARK: - Find

    /// "Ray Porter. Narrator, … Narrated 4 of your books · Translated 1. …" — one stop for the whole row.
    @Test func aPersonRowIsOneStopThatSaysItAll() async throws {
        let row = PersonMatchMapping.candidate(
            Fixture.candidate(libraryCredits: [
                LibraryCredit(role: .narrator, bookCount: 4), LibraryCredit(role: .translator, bookCount: 1)
            ])
        )
        let hosted = await HostedView(List { PersonCandidateRowView(row: row) })
        defer { hosted.close() }
        #expect(hosted.stop(labelled: row.accessibilityLabel) != nil, "\(hosted.tree)")
        #expect(hosted.stops(labelContaining: "Translated 1").count == 1, "\(hosted.tree)")
    }

    /// No profiles anywhere: what happened, and Edit by Hand as a full target.
    @Test func noProfilesOffersEditByHand() async throws {
        let hosted = await HostedView(
            PersonNoProfilesView(noProfiles: PersonMatchMapping.noProfiles(), onEditByHand: {})
        )
        defer { hosted.close() }
        #expect(!hosted.stops(labelContaining: "No source has a profile for this person").isEmpty, "\(hosted.tree)")
        let edit = try #require(hosted.stop(labelled: "Edit by Hand"), "\(hosted.tree)")
        #expect(edit.isButton)
        #expect(edit.frame.height >= TapTarget.minimum - 0.5, "\(edit)")
    }

    /// A failed person search offers Retry as a full target (HIG, Content unavailable).
    @Test func aFailureOffersRetryAsAFullTarget() async throws {
        let failure = PersonMatchMapping.failure(FindFailureOffline.shared)
        let hosted = await HostedView(MatchFailureView(failure: failure, onAction: { _ in }))
        defer { hosted.close() }
        let retry = try #require(hosted.stop(labelled: "Try Again"), "\(hosted.tree)")
        #expect(retry.isButton)
        #expect(retry.frame.height >= TapTarget.minimum - 0.5, "\(retry)")
    }

    /// Find has no role to choose: its sections offer no As author, no As narrator.
    @Test func findOffersNoRoleToChoose() async throws {
        let find = PersonMatchMapping.find(from: Fixture.results())
        let hosted = await HostedView(
            List {
                PersonFindSections(
                    find: find, onRetrySource: {}, onFailureAction: { _ in }, onEditByHand: {}
                ) { PersonCandidateRowView(row: $0) }
            }
        )
        defer { hosted.close() }
        #expect(hosted.stops(labelContaining: "As author").isEmpty, "\(hosted.tree)")
        #expect(hosted.stops(labelContaining: "As narrator").isEmpty, "\(hosted.tree)")
        #expect(!hosted.stops(labelContaining: "Narrated 5 of your books").isEmpty, "\(hosted.tree)")
    }

    // MARK: - Apply tray (lesson M8)

    @Test func atAX5TheApplyTrayStaysUnderAQuarterOfTheScreen() async throws {
        let screen = CGSize(width: 393, height: 852)
        let bar = review().applyBar
        let hosted = await HostedView(
            MatchApplyBarView(bar: bar, onApply: {}), size: screen, dynamicTypeSize: .accessibility5
        )
        defer { hosted.close() }
        let apply = try #require(hosted.stop(labelled: "Apply Changes"), "\(hosted.tree)")
        #expect(apply.hint == "Photo · biography")
        let height = hosted.fittingHeight()
        #expect(height <= screen.height * 0.25, "tray is \(height) of \(screen.height)")
    }

    // MARK: - Receipt

    /// A person's receipt offers Undo and Dismiss as full targets, and no See What Changed (plan D6).
    @Test func thePersonReceiptOffersUndoButNotSeeWhatChanged() async throws {
        let receipt = MatchReceiptModel(
            id: "r1", sentence: "Changed photo and biography for Ray Porter", changes: [],
            canUndo: true, undoing: false, undoError: nil, showsWhatChanged: false
        )
        let hosted = await HostedView(
            MatchReceiptCapsule(phase: .shown(receipt), onUndo: {}, onSeeWhatChanged: {}, onDismiss: {})
        )
        defer { hosted.close() }
        #expect(hosted.stop(labelled: receipt.sentence) != nil, "\(hosted.tree)")
        #expect(hosted.stop(labelled: "See What Changed") == nil, "\(hosted.tree)")
        for name in ["Undo", "Dismiss"] {
            let button = try #require(hosted.stop(labelled: name), "\(name)\n\(hosted.tree)")
            #expect(button.isButton)
            #expect(button.frame.height >= TapTarget.minimum - 0.5, "\(button)")
        }
    }
}
