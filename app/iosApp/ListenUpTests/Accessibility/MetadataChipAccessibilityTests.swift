import SwiftUI
import Testing
@testable import ListenUp

/// Select metadata's genre and mood chips, and its apply tray, for VoiceOver and large text (#1562).
@MainActor
@Suite("Metadata chips accessibility", .serialized)
struct MetadataChipAccessibilityTests {
    private let genres = [
        MetadataGenreSelection(id: "g1", label: "Science Fiction & Fantasy", isSelected: true, sourceLabel: nil),
        MetadataGenreSelection(id: "g2", label: "Humor", isSelected: true, sourceLabel: nil),
        MetadataGenreSelection(id: "g3", label: "Fantasy", isSelected: true, sourceLabel: "Hardcover"),
        MetadataGenreSelection(id: "g4", label: "Sci-fi", isSelected: false, sourceLabel: "Hardcover")
    ]

    private var genresLabel: String { String(localized: "metadata.field_genres") }

    private func genresField(_ size: DynamicTypeSize = .large) async -> HostedView {
        await HostedView(
            List {
                Section(String(localized: "metadata.section_classification")) {
                    MetadataChipField(
                        systemImage: "tag", label: genresLabel,
                        runs: MetadataMatchMapping.sourceRuns(genres),
                        onToggleAll: {}, onToggle: { _ in }
                    )
                }
            }
            .listStyle(.insetGrouped),
            size: CGSize(width: 393, height: 2400),
            dynamicTypeSize: size
        )
    }

    private func fromSource(_ label: String, _ source: String) -> String {
        String(format: String(localized: "metadata.chip_from_source"), label, source)
    }

    // MARK: M6 — every chip is its own stop, and says where it came from

    /// The whole field row was one "Genres, Selected" button: the chips were its children, and a double-tap
    /// flipped every genre at once.
    @Test func eachChipIsItsOwnToggle() async throws {
        let hosted = await genresField()
        defer { hosted.close() }
        let primary = try #require(hosted.stop(labelled: "Science Fiction & Fantasy"), "\(hosted.tree)")
        #expect(primary.isButton && primary.isSelected, "\(primary)")
        #expect(try #require(hosted.stop(labelled: "Humor"), "\(hosted.tree)").isSelected)

        let fromHardcover = try #require(hosted.stop(labelled: fromSource("Fantasy", "Hardcover")), "\(hosted.tree)")
        #expect(fromHardcover.isButton && fromHardcover.isSelected, "\(fromHardcover)")
        let off = try #require(hosted.stop(labelled: fromSource("Sci-fi", "Hardcover")), "\(hosted.tree)")
        #expect(off.isButton && !off.isSelected, "\(off)")
    }

    /// The field's own check is still there, as its own control: "Genres, Selected", turning them all on or off.
    @Test func theFieldCheckIsASeparateToggleAll() async throws {
        let hosted = await genresField()
        defer { hosted.close() }
        let all = try #require(hosted.stops(labelled: genresLabel).first { $0.isButton }, "\(hosted.tree)")
        #expect(all.isSelected)
        #expect(all.hint == String(localized: "metadata.field_toggle_all_hint"))
    }

    // MARK: M7 — chips wrap inside the card

    /// At AX5 "Science Fiction" ran off the card ("✓ Science Ficti"): FlowLayout placed each chip at its ideal
    /// width with no cap.
    @Test func atAccessibilitySizesChipsStayInsideTheCard() async throws {
        let hosted = await genresField(.accessibility5)
        defer { hosted.close() }
        let all = try #require(hosted.stops(labelled: genresLabel).first { $0.isButton }, "\(hosted.tree)")
        let chips = hosted.stops.filter { $0.isButton && $0.label != genresLabel }
        try #require(chips.count == 4, "\(hosted.tree)")
        for chip in chips {
            #expect(chip.frame.maxX <= all.frame.minX + 1, "\(chip) runs past the check at \(all.frame.minX)")
        }
    }

    // MARK: m15 — chips are 44-point targets

    @Test func chipsAreFullTargets() async throws {
        let hosted = await genresField()
        defer { hosted.close() }
        let chips = hosted.stops.filter { $0.isButton && $0.label != genresLabel }
        try #require(chips.count == 4, "\(hosted.tree)")
        for chip in chips {
            #expect(chip.frame.height >= TapTarget.minimum - 0.5, "\(chip)")
        }
    }

    // MARK: M8 — the apply tray leaves the screen to the fields

    private let sources = ["Audible", "Audnexus", "Hardcover", "iTunes"]

    /// At AX5 "Merged from …" and a two-line button filled ~45% of the screen. The provenance moves into the
    /// list; the bar keeps only the button.
    @Test func atAccessibilitySizesTheTrayHoldsOnlyTheButton() async throws {
        let screen = CGSize(width: 393, height: 852)
        let hosted = await HostedView(
            MetadataApplyTray(isApplying: false, isEnabled: true, applyError: nil, contributingSources: sources,
                              action: {}),
            size: screen, dynamicTypeSize: .accessibility5
        )
        defer { hosted.close() }
        #expect(hosted.stops(labelContaining: "Audnexus").isEmpty, "\(hosted.tree)")
        let height = hosted.fittingHeight()
        #expect(height <= screen.height * 0.25, "tray is \(height) of \(screen.height)")
    }

    @Test func atDefaultSizesTheTrayStillSaysWhereTheMetadataCameFrom() async throws {
        let hosted = await HostedView(
            MetadataApplyTray(isApplying: false, isEnabled: true, applyError: nil, contributingSources: sources,
                              action: {})
        )
        defer { hosted.close() }
        #expect(hosted.stops(labelContaining: "Audnexus").count == 1, "\(hosted.tree)")
    }

    /// Where the provenance went at accessibility sizes: the summary under the matched edition.
    @Test func atAccessibilitySizesTheSummarySaysWhereTheMetadataCameFrom() async throws {
        let large = await HostedView(
            MetadataSelectionSummary(selectedCount: 9, totalCount: 12, contributingSources: sources),
            dynamicTypeSize: .accessibility5
        )
        defer { large.close() }
        #expect(large.stops(labelContaining: "Audnexus").count == 1, "\(large.tree)")

        let regular = await HostedView(
            MetadataSelectionSummary(selectedCount: 9, totalCount: 12, contributingSources: sources)
        )
        defer { regular.close() }
        #expect(regular.stops(labelContaining: "Audnexus").isEmpty, "\(regular.tree)")
    }
}
