import Testing
import SwiftUI
import Shared
@testable import ListenUp

/// Coverage for the iOS half of a facet's visual identity: `FacetIcon` → SF Symbol, and a hue hex
/// string → `Color`. Both are pure mapping functions (`FacetIdentity.swift`), so they're pinned
/// directly with hand-built inputs — no observer, no shared VM.
@Suite("FacetIdentity")
struct FacetIdentityTests {
    /// Every `FacetIcon` case (the shared, platform-neutral enum — 27 categories) resolves to a
    /// non-empty SF Symbol. Exercising the full case list also pins that the switch in
    /// `sfSymbol(for:)` stays exhaustive as Swift Export re-generates the bridged enum.
    ///
    /// A single looping `@Test` rather than `@Test(arguments:)`: the bridged `FacetIcon` doesn't
    /// conform to `Sendable`, which Swift Testing's parameterized-argument crossing requires.
    @Test func sfSymbolIsNonEmptyForEveryIcon() {
        for icon in FacetIcon.allCases {
            #expect(!sfSymbol(for: icon).isEmpty)
        }
    }

    @Test func sfSymbolMatchesTheDocumentedExamples() {
        #expect(sfSymbol(for: .fantasy) == "sparkles")
        #expect(sfSymbol(for: .scifi) == "cube")
        #expect(sfSymbol(for: .mystery) == "magnifyingglass")
        #expect(sfSymbol(for: .romance) == "heart")
        #expect(sfSymbol(for: .horror) == "flame")
        #expect(sfSymbol(for: .`default`) == "book")
    }

    @Test func hueColorParsesAHexStringWithoutCrashing() {
        let color = hueColor("#2E5AA0")
        // `Color` has no public component accessor to assert against; resolving it against a
        // fixed environment is the closest thing to a round-trip check without crashing.
        let resolved = color.resolve(in: EnvironmentValues())
        #expect(resolved.opacity > 0)
    }

    @Test func hueColorHandlesAHexStringWithoutTheLeadingHash() {
        let color = hueColor("B04A66")
        let resolved = color.resolve(in: EnvironmentValues())
        #expect(resolved.opacity > 0)
    }
}
