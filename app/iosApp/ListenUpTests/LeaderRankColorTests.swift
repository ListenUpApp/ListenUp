import SwiftUI
import Testing
import UIKit
@testable import ListenUp

/// The leaderboard's rank disc: the board's medal fills under one dark ink for the podium, and a
/// secondary numeral below it that clears the bold-text floor where tertiary did not (spec §8.2).
@Suite("Leader rank colours")
struct LeaderRankColorTests {
    private typealias Traits = UITraitCollection

    private static let fills: [ColorResource] = [.podiumGold, .podiumSilver, .podiumBronze]

    private func hex(_ color: SRGBColor) -> String {
        let channels = [color.red, color.green, color.blue].map { Int(($0 * 255).rounded()) }
        return String(format: "%02lX%02lX%02lX", channels[0], channels[1], channels[2])
    }

    @Test func thePodiumWearsGoldSilverAndBronze() {
        #expect(LeaderRankMedal(rank: 1) == .gold)
        #expect(LeaderRankMedal(rank: 2) == .silver)
        #expect(LeaderRankMedal(rank: 3) == .bronze)
        #expect(LeaderRankMedal(rank: 4) == nil)
        #expect(LeaderRankMedal(rank: 0) == nil)
    }

    @Test func theLightFillsAreTheBoardsMedals() {
        let light = Traits.appearance(.light, contrast: .normal)
        let values = Self.fills.map { hex(SRGBColor(UIColor(resource: $0), resolvedFor: light)) }
        #expect(values == ["FFE08A", "E5E5EA", "F3C9A0"])
        #expect(hex(SRGBColor(UIColor(resource: .podiumInk), resolvedFor: light)) == "241A00")
    }

    @Test func theInkReadsOnEveryMedalInEveryAppearance() {
        for traits in Traits.colorAppearances {
            let ink = SRGBColor(UIColor(resource: .podiumInk), resolvedFor: traits)
            for fill in Self.fills {
                let ratio = ink.contrastRatio(against: SRGBColor(UIColor(resource: fill), resolvedFor: traits))
                #expect(ratio >= ContrastMinimum.text, "podium ink \(ratio) in \(traits.userInterfaceStyle.rawValue)")
            }
        }
    }

    /// Ranks 4 and up: a bold secondary numeral on the leaderboard's card, in every appearance.
    @Test func anUnmedalledRankReadsOnTheCard() {
        for traits in Traits.colorAppearances {
            let card = SRGBColor(.secondarySystemBackground, resolvedFor: traits)
            let numeral = SRGBColor(.secondaryLabel, resolvedFor: traits, over: card)
            let ratio = numeral.contrastRatio(against: card)
            #expect(ratio >= ContrastMinimum.largeText, "rank numeral \(ratio) in \(traits.userInterfaceStyle.rawValue)")
        }
    }
}
