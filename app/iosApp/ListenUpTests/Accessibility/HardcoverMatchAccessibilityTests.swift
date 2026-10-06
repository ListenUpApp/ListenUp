import SwiftUI
import Testing
@testable import ListenUp

/// Find on Hardcover with a long query at the largest text sizes (#1562).
@MainActor
@Suite("Find on Hardcover accessibility", .serialized)
struct HardcoverMatchAccessibilityTests {
    private let query = "Living from a Place of Surrender The Untethered Soul in Action"

    private var model: HardcoverMatchModel {
        HardcoverMatchModel(
            bookTitle: "Living from a Place of Surrender", bookAuthors: "Michael A. Singer", query: query,
            search: .results(
                byAuthor: [
                    .init(id: 1, title: "Living from a Place of Surrender", authors: "Michael A. Singer",
                          detail: "Audiobook · 2022", ratings: "312 ratings", sharesAuthor: true)
                ],
                others: []
            ),
            current: nil, linkingId: nil, isRemoving: false, suggestions: []
        )
    }

    private func list(_ size: DynamicTypeSize) async -> HostedView {
        await HostedView(
            HardcoverMatchList(model: model, onSearchFor: { _ in }, onRetry: {}, onPick: { _ in }, onRemove: {}),
            size: CGSize(width: 393, height: 2400),
            dynamicTypeSize: size
        )
    }

    private var resultsFor: String {
        String(format: String(localized: "hardcover.match_results_for"), query)
    }

    /// The single-line field shows only the start of a long query at AX5; the results say in full what they
    /// are for.
    @Test func atAccessibilitySizesTheResultsNameTheWholeQuery() async throws {
        let hosted = await list(.accessibility5)
        defer { hosted.close() }
        #expect(hosted.stops(labelContaining: resultsFor).count == 1, "\(hosted.tree)")
    }

    /// At default sizes the field holds the query, so the line would only repeat it.
    @Test func atDefaultSizesTheFieldIsEnough() async throws {
        let hosted = await list(.large)
        defer { hosted.close() }
        #expect(hosted.stops(labelContaining: resultsFor).isEmpty, "\(hosted.tree)")
    }
}
