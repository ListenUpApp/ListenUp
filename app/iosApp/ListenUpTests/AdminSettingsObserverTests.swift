import Foundation
import Testing
import Shared
@testable import ListenUp

@Suite("AdminSettingsObserver")
struct AdminSettingsObserverTests {
    // Tests exercise `AdminSettingsReadyModel.from(_:)` — the real KMP-Ready → Swift mapping
    // closure — so regressions that drop a field mapping are caught here, not just at runtime.

    @Test func holdNewBooksForReviewTrueMapsToReadyModel() {
        let ready = AdminSettingsUiStateReady(
            serverName: "My Server",
            remoteUrl: "https://example.com",
            holdNewBooksForReview: true,
            pushNotificationsEnabled: true,
            ratingSources: [],
            isDirty: false,
            isSaving: false,
            error: nil
        )
        let model = AdminSettingsReadyModel.from(ready)
        #expect(model.holdNewBooksForReview == true)
        #expect(model.pushNotificationsEnabled == true)
        #expect(model.serverName == "My Server")
        #expect(model.remoteUrl == "https://example.com")
        #expect(model.error == nil)
    }

    @Test func holdNewBooksForReviewFalseMapsToReadyModel() {
        let ready = AdminSettingsUiStateReady(
            serverName: "Inbox Off",
            remoteUrl: "",
            holdNewBooksForReview: false,
            pushNotificationsEnabled: false,
            ratingSources: [],
            isDirty: true,
            isSaving: false,
            error: nil
        )
        let model = AdminSettingsReadyModel.from(ready)
        #expect(model.holdNewBooksForReview == false)
        #expect(model.pushNotificationsEnabled == false)
        #expect(model.serverName == "Inbox Off")
        #expect(model.isDirty == true)
    }

    @Test func dirtyAndSavingFlagsMapped() {
        let ready = AdminSettingsUiStateReady(
            serverName: "S",
            remoteUrl: "https://example.com",
            holdNewBooksForReview: true,
            pushNotificationsEnabled: true,
            ratingSources: [],
            isDirty: true,
            isSaving: true,
            error: nil
        )
        let model = AdminSettingsReadyModel.from(ready)
        #expect(model.isDirty == true)
        #expect(model.isSaving == true)
        #expect(model.holdNewBooksForReview == true)
    }

    // MARK: - Rating sources

    @Test func readyModelMapsEveryRatingSourceRow() {
        let ready = AdminSettingsUiStateReady(
            serverName: "S",
            remoteUrl: "",
            holdNewBooksForReview: false,
            pushNotificationsEnabled: true,
            ratingSources: [
                RatingSourceStatus(source: .audible, enabled: true, lastFetchedAt: nil, lastError: nil),
                RatingSourceStatus(source: .hardcover, enabled: false, lastFetchedAt: nil, lastError: nil)
            ],
            isDirty: false,
            isSaving: false,
            error: nil
        )
        let model = AdminSettingsReadyModel.from(ready)
        #expect(model.ratingSources.count == 2)
        #expect(model.ratingSources[0].source == .audible)
        #expect(model.ratingSources[0].enabled == true)
        #expect(model.ratingSources[1].source == .hardcover)
        #expect(model.ratingSources[1].enabled == false)
    }

    @Test func neverFetchedHealthLine() {
        let row = RatingSourceRowModel.from(
            RatingSourceStatus(source: .audible, enabled: true, lastFetchedAt: nil, lastError: nil)
        )
        #expect(row.healthLine() == "Not fetched yet")
    }

    @Test func lastFetchedHealthLineReportsHowLongAgo() {
        let now = Date()
        let twoDaysAgoMs = Int64(now.addingTimeInterval(-2 * 24 * 3_600).timeIntervalSince1970 * 1_000)
        let row = RatingSourceRowModel.from(
            RatingSourceStatus(source: .audible, enabled: true, lastFetchedAt: twoDaysAgoMs, lastError: nil)
        )
        #expect(row.healthLine(now: now) == "Last fetched 2 days ago")
    }

    @Test func errorHealthLineBeatsALastFetchedTime() {
        let row = RatingSourceRowModel.from(
            RatingSourceStatus(
                source: .hardcover,
                enabled: false,
                lastFetchedAt: Int64(Date().timeIntervalSince1970 * 1_000),
                lastError: "Rate limited"
            )
        )
        #expect(row.healthLine() == "Last attempt failed: Rate limited")
    }
}
