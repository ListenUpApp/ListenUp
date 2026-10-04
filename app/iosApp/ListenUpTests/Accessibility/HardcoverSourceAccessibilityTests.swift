import SwiftUI
import Testing
@testable import ListenUp

/// Administration → Hardcover's API token, for VoiceOver, Voice Control and large text (#1562).
@MainActor
@Suite("Hardcover token accessibility", .serialized, .flakyOnCI)
struct HardcoverSourceAccessibilityTests {
    private func model(
        _ token: HardcoverSourceModel.Token, isBusy: Bool = false, refusal: String? = nil
    ) -> HardcoverSourceModel {
        HardcoverSourceModel(token: token, metadataEnabled: true, metadataUnavailable: false, isBusy: isBusy,
                             refusal: refusal)
    }

    private func section(_ model: HardcoverSourceModel) async -> HostedView {
        await HostedView(
            Form {
                HardcoverSourceSection(model: model, onSave: { _ in }, onRemove: {},
                                       onMetadataEnabledChange: { _ in }, onClearError: {})
            },
            size: CGSize(width: 393, height: 1600)
        )
    }

    // MARK: m10 — Replace and Remove name their object

    @Test func replaceAndRemoveSayWhatTheyActOn() async throws {
        let hosted = await section(model(.saved(username: "rigadmin")))
        defer { hosted.close() }
        let replace = try #require(hosted.stop(labelled: String(localized: "admin.hardcover_token_replace_label")),
                                   "\(hosted.tree)")
        let remove = try #require(hosted.stop(labelled: String(localized: "admin.hardcover_token_remove_label")),
                                  "\(hosted.tree)")
        // Voice Control still answers to the word on screen.
        #expect(replace.inputLabels.contains(String(localized: "admin.hardcover_token_replace")), "\(replace.inputLabels)")
        #expect(remove.inputLabels.contains(String(localized: "common.remove")), "\(remove.inputLabels)")
    }

    // MARK: m11 — checking and rejection are said

    @Test func checkingAndItsOutcomeAreAnnounced() {
        let idle = model(.notSet)
        let checking = model(.notSet, isBusy: true)
        #expect(HardcoverSourceSection.announcement(from: idle, to: checking)
            == String(localized: "admin.hardcover_token_checking"))
        #expect(HardcoverSourceSection.announcement(from: checking, to: model(.notSet, refusal: "Not a token."))
            == "Not a token.")
        #expect(HardcoverSourceSection.announcement(from: checking, to: model(.rejected(username: "rigadmin")))
            == String(localized: "admin.hardcover_token_rejected"))
        let saved = model(.saved(username: "rigadmin"))
        #expect(HardcoverSourceSection.announcement(from: checking, to: saved) == saved.savedLine)
        #expect(HardcoverSourceSection.announcement(from: idle, to: idle) == nil)
        #expect(HardcoverSourceSection.announcement(from: saved, to: saved) == nil)
    }

    // MARK: m12 — the rejection reads at 4.5:1

    /// The rejected line and the field's refusal were system red on white, 3.57:1. The glyph stays red; the
    /// sentence is set in the primary colour.
    @Test func theRejectionIsReadable() async throws {
        let refused = model(.rejected(username: "rigadmin"), refusal: "Hardcover didn't accept that token.")
        let hosted = await section(refused)
        defer { hosted.close() }
        let drawn = DrawnContrast(hosted)
        let rejected = try #require(hosted.stop(labelled: String(localized: "admin.hardcover_token_rejected")),
                                    "\(hosted.tree)")
        #expect(drawn.strongestInk(in: rejected.frame) >= ContrastMinimum.text)
        let refusal = try #require(hosted.stops(labelContaining: "Hardcover didn't accept").first, "\(hosted.tree)")
        #expect(drawn.strongestInk(in: refusal.frame) >= ContrastMinimum.text)
    }

    // MARK: m14 — Show password is a 44-point target

    @Test func showPasswordIsAFullTarget() async throws {
        let hosted = await section(model(.notSet))
        defer { hosted.close() }
        let reveal = try #require(hosted.stop(labelled: String(localized: "common.show_password")), "\(hosted.tree)")
        #expect(reveal.frame.width >= TapTarget.minimum - 0.5 && reveal.frame.height >= TapTarget.minimum - 0.5, "\(reveal)")
    }

    // MARK: m13 — "Hardcover metadata" keeps its words whole

    /// The decorative tile took ~45 points from a narrow column, hyphenating "Hardcov-er". It steps aside at
    /// accessibility sizes; it is hidden from VoiceOver already.
    @Test func theToggleRowTileStepsAsideAtAccessibilitySizes() {
        #expect(ToggleRow.showsIconTile(at: .large))
        #expect(ToggleRow.showsIconTile(at: .xxxLarge))
        #expect(!ToggleRow.showsIconTile(at: .accessibility1))
        #expect(!ToggleRow.showsIconTile(at: .accessibility5))
    }
}
