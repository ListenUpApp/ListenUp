import Foundation
import Testing
@testable import ListenUp

/// What the book-detail download control says and does in each state.
///
/// Before this, only "waiting for Wi-Fi" had a label — VoiceOver read "checkmark" over a control
/// whose tap deleted the download.
@Suite("Download control")
struct DownloadControlTests {
    @Test func notDownloadedOffersDownload() {
        let control = DownloadControl(state: .notDownloaded, progress: 0)
        #expect(control.accessibilityLabel == String(localized: "book.detail_download"))
        #expect(control.accessibilityValue == nil)
        #expect(control.tapAction == .download)
    }

    @Test func queuedOffersCancelAndSaysItIsQueued() {
        let control = DownloadControl(state: .queued, progress: 0)
        #expect(control.accessibilityLabel == String(localized: "book.detail_cancel_download"))
        #expect(control.accessibilityValue == String(localized: "book.detail_queued"))
        #expect(control.tapAction == .cancel)
    }

    @Test func downloadingOffersCancelAndReadsThePercent() {
        let control = DownloadControl(state: .downloading, progress: 0.65)
        #expect(control.accessibilityLabel == String(localized: "book.detail_cancel_download"))
        #expect(control.accessibilityValue == 0.65.formatted(.percent.precision(.fractionLength(0))))
        #expect(control.tapAction == .cancel)
    }

    @Test func waitingForWifiOffersCancelAndSaysWhy() {
        let control = DownloadControl(state: .waitingForWifi, progress: 0.2)
        #expect(control.accessibilityLabel == String(localized: "book.detail_cancel_download"))
        #expect(control.accessibilityValue == String(localized: "book.detail_waiting_for_wifi"))
        #expect(control.tapAction == .cancel)
    }

    /// The bug this pins: a completed download used to delete on a single tap. Removal now lives
    /// in a menu, behind a confirmation — a tap only opens the menu.
    @Test func downloadedHasNoOneTapAction() {
        let control = DownloadControl(state: .completed, progress: 1)
        #expect(control.accessibilityLabel == String(localized: "book.detail_downloaded"))
        #expect(control.accessibilityValue == nil)
        #expect(control.tapAction == .openMenu)
    }

    @Test func failedOffersRetryAndSaysItFailed() {
        let control = DownloadControl(state: .failed, progress: 0.3)
        #expect(control.accessibilityLabel == String(localized: "book.detail_retry_download"))
        #expect(control.accessibilityValue == String(localized: "book.detail_download_failed_a11y"))
        #expect(control.tapAction == .download)
    }

    @Test func partialOffersRetryAndSaysItIsIncomplete() {
        let control = DownloadControl(state: .partial, progress: 0.3)
        #expect(control.accessibilityLabel == String(localized: "book.detail_retry_download"))
        #expect(control.accessibilityValue == String(localized: "book.detail_download_partial_a11y"))
        #expect(control.tapAction == .download)
    }

    /// Progress past the ends (a stale or overshooting report) never reads as more than 100%.
    @Test func thePercentIsClamped() {
        let control = DownloadControl(state: .downloading, progress: 1.4)
        #expect(control.accessibilityValue == 1.0.formatted(.percent.precision(.fractionLength(0))))
    }
}
