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
            hardcoverSource: nil,
            hardcoverTokenSave: HardcoverTokenSaveIdle.shared,
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
            hardcoverSource: nil,
            hardcoverTokenSave: HardcoverTokenSaveIdle.shared,
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
            hardcoverSource: nil,
            hardcoverTokenSave: HardcoverTokenSaveIdle.shared,
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
            hardcoverSource: nil,
            hardcoverTokenSave: HardcoverTokenSaveIdle.shared,
                status(.audible, enabled: true),
                status(.hardcover, enabled: false)
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
        let row = RatingSourceRowModel.from(status(.audible))
        #expect(row.healthLine() == "Not fetched yet")
    }

    @Test func lastFetchedHealthLineReportsHowLongAgo() {
        let now = Date()
        let twoDaysAgoMs = Int64(now.addingTimeInterval(-2 * 24 * 3_600).timeIntervalSince1970 * 1_000)
        let row = RatingSourceRowModel.from(status(.audible, lastFetchedAt: twoDaysAgoMs))
        #expect(row.healthLine(now: now) == "Last fetched 2 days ago")
    }

    @Test func errorHealthLineBeatsALastFetchedTime() {
        let row = RatingSourceRowModel.from(
            status(
                .hardcover,
                enabled: false,
                lastFetchedAt: Int64(Date().timeIntervalSince1970 * 1_000),
                lastError: "Rate limited"
            )
        )
        #expect(row.healthLine() == "Last attempt failed: Rate limited")
    }

    // MARK: - Why a source is paused or waiting (first match wins: unavailable, paused, error,
    // last fetched, never)

    /// Noon UTC on October 6, 2026 — a date that reads the same in the fixed test time zone.
    private let october6Ms: Int64 = 1_791_288_000_000
    private let utc = TimeZone(identifier: "UTC")!
    private let english = Locale(identifier: "en_US")

    @Test func notConfiguredBeatsEveryOtherHealthLine() {
        let row = RatingSourceRowModel.from(status(
            .hardcover,
            lastFetchedAt: october6Ms,
            lastError: "Timed out",
            pausedUntil: october6Ms,
            unavailable: .notConfigured
        ))
        #expect(row.healthLine() == "Not set up on this server")
    }

    @Test func noConnectionAsksTheAdminToConnectHardcover() {
        let row = RatingSourceRowModel.from(status(.hardcover, unavailable: .noConnection))
        #expect(row.healthLine() == "Add a Hardcover API token or connect an account to enable")
    }

    @Test func anUnavailabilityThisBuildCannotNameReadsAsUnavailable() {
        let row = RatingSourceRowModel.from(status(.hardcover, unavailable: .unknown))
        #expect(row.healthLine() == "Unavailable on this server")
    }

    @Test func pausedWithAReasonSaysUntilWhenAndWhy() {
        let row = RatingSourceRowModel.from(status(
            .goodreads,
            lastFetchedAt: october6Ms,
            lastError: "Rate limited",
            pausedUntil: october6Ms
        ))
        #expect(row.healthLine(timeZone: utc, locale: english) == "Paused until October 6, 2026: Rate limited")
    }

    @Test func pausedWithoutAReasonSaysUntilWhen() {
        let row = RatingSourceRowModel.from(status(.goodreads, pausedUntil: october6Ms))
        #expect(row.healthLine(timeZone: utc, locale: english) == "Paused until October 6, 2026")
    }

    @Test func hardcoverSaysWhoseAccountItFetchesWith() {
        let row = RatingSourceRowModel.from(status(.hardcover, connectionUsername: "simonhull"))
        #expect(row.connectionLine == "Using simonhull's Hardcover account")
        #expect(row.subtitle() == "Not fetched yet\nUsing simonhull's Hardcover account")
    }

    @Test func onlyHardcoverNamesAConnection() {
        let row = RatingSourceRowModel.from(status(.audible, connectionUsername: "simonhull"))
        #expect(row.connectionLine == nil)
        #expect(row.subtitle() == "Not fetched yet")
    }

    @Test func anUnavailableSourceKeepsItsSwitchState() {
        // The switch stays operable: switching off a source that cannot run is still meaningful.
        let row = RatingSourceRowModel.from(status(.hardcover, enabled: true, unavailable: .noConnection))
        #expect(row.enabled == true)
        #expect(row.unavailable == .noConnection)
    }

    // MARK: - Hardcover (#1542)

    @Test func readyModelMapsTheHardcoverSection() {
        let ready = AdminSettingsUiStateReady(
            serverName: "S",
            remoteUrl: "",
            holdNewBooksForReview: false,
            pushNotificationsEnabled: true,
            ratingSources: [],
            hardcoverSource: HardcoverSourceStatus(
                apiToken: HardcoverApiTokenStatusSaved(username: "simon", setAt: 1),
                metadataEnabled: true,
                metadataUnavailable: nil
            ),
            hardcoverTokenSave: HardcoverTokenSaveBusy.shared,
            isDirty: false,
            isSaving: false,
            error: nil
        )
        let model = AdminSettingsReadyModel.from(ready)
        #expect(model.hardcover?.token == .saved(username: "simon"))
        #expect(model.hardcover?.isBusy == true)
    }

    @Test func noHardcoverSectionUntilItLoads() {
        let ready = AdminSettingsUiStateReady(
            serverName: "S",
            remoteUrl: "",
            holdNewBooksForReview: false,
            pushNotificationsEnabled: true,
            ratingSources: [],
            hardcoverSource: nil,
            hardcoverTokenSave: HardcoverTokenSaveIdle.shared,
            isDirty: false,
            isSaving: false,
            error: nil
        )
        #expect(AdminSettingsReadyModel.from(ready).hardcover == nil)
    }

    // MARK: - Fixtures

    /// A `RatingSourceStatus` naming every field — Swift Export does not carry Kotlin's defaults.
    private func status(
        _ source: ExternalRatingSource,
        enabled: Bool = true,
        lastFetchedAt: Int64? = nil,
        lastError: String? = nil,
        pausedUntil: Int64? = nil,
        unavailable: RatingSourceUnavailable? = nil,
        connectionUsername: String? = nil
    ) -> RatingSourceStatus {
        RatingSourceStatus(
            source: source,
            enabled: enabled,
            lastFetchedAt: lastFetchedAt,
            lastError: lastError,
            pausedUntil: pausedUntil,
            unavailable: unavailable,
            connectionUsername: connectionUsername
        )
    }
}
