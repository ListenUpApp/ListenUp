import SwiftUI
import Testing
import UIKit
@testable import ListenUp

// How Match details is presented, how long its receipt stays, and the contrast of its two signal colours.

@Suite("Match details presentation")
struct MatchPresentationTests {
    /// A sheet reports a compact size class on iPad, which is why the old split view never rendered: a regular
    /// width gets a full-screen cover; every compact width (iPhone, a narrow iPad Split View) a push.
    @Test func aRegularWidthGetsTheSplitViewCoverAndACompactOneAPush() {
        #expect(MatchPresentationStyle.style(for: .regular) == .cover)
        #expect(MatchPresentationStyle.style(for: .compact) == .push)
        #expect(MatchPresentationStyle.style(for: nil) == .push)
    }

    /// Never a timed toast while VoiceOver runs (lesson M-T1).
    @Test func theReceiptStaysUntilDismissedWhileVoiceOverRuns() {
        #expect(MatchReceiptTiming.autoDismissDelay(voiceOverRunning: true) == nil)
        #expect(MatchReceiptTiming.autoDismissDelay(voiceOverRunning: false) == .seconds(8))
    }

    @Test func theReceiptIsAnnouncedInEveryPhase() {
        let receipt = MatchReceiptModel(
            id: "r1", sentence: "Changed 1 field", changes: [], canUndo: true, undoing: false, undoError: nil
        )
        #expect(MatchReceiptAnnouncement.text(for: .shown(receipt)) == "Changed 1 field")
        #expect(MatchReceiptAnnouncement.text(for: .undone) == "Match undone. Everything it changed is back.")
        #expect(MatchReceiptAnnouncement.text(for: .expired)
            == "This book has changed since, so the match can't be undone.")
        #expect(MatchReceiptAnnouncement.text(for: .none) == nil)
    }
}

@Suite("Match details colour contrast")
struct MatchColorContrastTests {
    private func ratio(_ ink: UIColor, on fill: UIColor, _ traits: UITraitCollection) -> Double {
        SRGBColor(ink, resolvedFor: traits).contrastRatio(against: SRGBColor(fill, resolvedFor: traits))
    }

    /// The spec's pair: #8A6100 on #FFF4D6, and a dark counterpart that passes as well.
    @Test func youEditedThisPassesOnItsOwnFill() {
        for traits in UITraitCollection.colorAppearances {
            #expect(ratio(.matchEditedInk, on: .matchEditedFill, traits) >= ContrastMinimum.text)
        }
    }

    /// Strong match reasons are footnote text on the grouped row.
    @Test func theStrongMatchGreenPassesOnTheRow() {
        for traits in UITraitCollection.colorAppearances {
            #expect(ratio(.matchStrong, on: .secondarySystemGroupedBackground, traits) >= ContrastMinimum.text)
            #expect(ratio(.matchStrong, on: .systemBackground, traits) >= ContrastMinimum.text)
        }
    }
}
