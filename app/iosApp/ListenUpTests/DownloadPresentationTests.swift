import Foundation
import Testing
@testable import ListenUp

private let mb: Int64 = 1_000_000

private func moving(downloading: Int = 1, completed: Int = 0, done: Int64 = 212 * mb, total: Int64 = 640 * mb,
                    afterClose: Bool = false) -> DownloadStatusSnapshot {
    .inProgress(downloadingFiles: downloading, completedFiles: completed, downloadedBytes: done, totalBytes: total,
                resumingAfterClose: afterClose)
}

/// What Book Detail, the Library badge and the Downloads row say about a download in each state (canvas I-BD-States,
/// I-BD-Closed, I-Library).
@Suite("Download presentation")
struct DownloadPresentationTests {
    @Test func bytesMovingIsDownloading() {
        #expect(DownloadUIState.from(moving(), isWaitingForWifi: false, anotherBookDownloading: true) == .downloading)
    }

    @Test func untouchedBehindAnotherBookIsQueuedAndAloneIsStarting() {
        let untouched = moving(downloading: 0, done: 0)
        #expect(DownloadUIState.from(untouched, isWaitingForWifi: false, anotherBookDownloading: true) == .queued)
        #expect(DownloadUIState.from(untouched, isWaitingForWifi: false, anotherBookDownloading: false)
            == .downloading)
    }

    @Test func aWifiWaitWinsOverQueued() {
        let untouched = moving(downloading: 0, done: 0)
        #expect(DownloadUIState.from(untouched, isWaitingForWifi: true, anotherBookDownloading: true) == .waitingForWifi)
    }

    @Test func pickingUpAfterASwipeAwaySaysSoUntilBytesMove() {
        let resuming = moving(downloading: 0, afterClose: true)
        #expect(DownloadUIState.from(resuming, isWaitingForWifi: false, anotherBookDownloading: false)
            == .resumingAfterClose)
    }

    @Test func whoPausedItDecidesTheState() {
        func state(_ pauser: DownloadStatusSnapshot.Pauser) -> DownloadUIState {
            .from(.paused(by: pauser, downloadedBytes: 1, totalBytes: 2), isWaitingForWifi: false,
                  anotherBookDownloading: false)
        }
        #expect(state(.you) == .paused)
        #expect(state(.system) == .waiting)
        #expect(state(.cancelled) == .partial)
    }

    @Test func theAmountNamesTheUnitOnce() {
        #expect(DownloadAmount.of(212 * mb, 640 * mb) == "212 of 640 MB")
        #expect(DownloadAmount.of(1_100 * mb, 1_400 * mb) == "1.1 of 1.4 GB")
    }

    @Test func eachActiveStateHasItsLineAndRestingStatesHaveNone() {
        let snapshot = moving()
        func line(_ state: DownloadUIState) -> DownloadStatusLine? { .of(state, snapshot: snapshot) }

        #expect(line(.downloading)?.text == "Downloading · 212 of 640 MB")
        #expect(line(.downloading)?.offersCancel == true)
        #expect(line(.resumingAfterClose)?.text == "Paused when ListenUp was closed · resuming")
        #expect(line(.queued)?.text == "Starts after the current download")
        #expect(line(.queued)?.offersCancel == false)
        #expect(line(.waitingForWifi)?.text == "Downloads use Wi-Fi only")
        #expect(line(.waiting)?.text == "Resumes automatically · 212 of 640 MB saved")
        #expect(line(.waiting)?.offersCancel == false)
        #expect(line(.paused)?.text == "Paused · 212 of 640 MB saved")
        #expect(line(.paused)?.offersCancel == true)
        #expect(line(.notDownloaded) == nil)
        #expect(line(.completed) == nil)
    }

    @Test func aFailureSaysWhyInRed() {
        let line = DownloadStatusLine.of(.failed, snapshot: .failed(message: "Not enough storage space."))
        #expect(line?.text == "Couldn’t download · not enough storage space")
        #expect(line?.isFailure == true)
        #expect(line?.offersCancel == true)
    }

    @Test func cancelAsksFirstOnlyWhenItWouldThrowAwayMoreThanALittle() {
        #expect(!DownloadCancelConfirmation.isNeeded(moving(done: 50 * mb, total: 640 * mb)))
        #expect(DownloadCancelConfirmation.isNeeded(moving(done: 150 * mb, total: 640 * mb)))
        // A quarter of a small book is worth keeping too.
        #expect(DownloadCancelConfirmation.isNeeded(moving(done: 30 * mb, total: 100 * mb)))
    }

    @Test func cancelSaysWhatIsAlreadySavedAndThatItGoes() {
        #expect(CancelDownloadCopy.title == "Cancel this download?")
        #expect(CancelDownloadCopy.message(formattedSize: "212 MB") ==
            "The 212 MB already saved on this device will be removed.")
        #expect(CancelDownloadCopy.keep == "Keep Downloading")
        #expect(CancelDownloadCopy.discard == "Cancel Download")
    }

    @Test func theLibraryBadgeFollowsTheState() {
        let snapshot = moving(done: 1, total: 4)
        #expect(DownloadBadge.of(.downloading, snapshot: snapshot) == .downloading(progress: 0.25))
        // After a swipe-away the badge stays the downloading ring; the words live on the row.
        #expect(DownloadBadge.of(.resumingAfterClose, snapshot: snapshot) == .downloading(progress: 0.25))
        #expect(DownloadBadge.of(.waiting, snapshot: snapshot) == .resumesAutomatically)
        #expect(DownloadBadge.of(.completed, snapshot: .completed(totalBytes: 4)) == .downloaded)
        #expect(DownloadBadge.downloaded.systemImage == "arrow.down.to.line")
        #expect(DownloadBadge.of(.notDownloaded, snapshot: .notDownloaded) == nil)
    }

    @Test func theDownloadsRowSaysWhereEachBookStands() {
        #expect(DownloadRowSubtitle.of(.queued, snapshot: moving()) == "Queued")
        #expect(DownloadRowSubtitle.of(.waiting, snapshot: moving()) == "Waiting · resumes automatically")
        #expect(DownloadRowSubtitle.of(.resumingAfterClose, snapshot: moving())
            == "Paused when ListenUp was closed · resuming")
        #expect(DownloadRowSubtitle.of(.paused, snapshot: .paused(by: .you, downloadedBytes: 1_100 * mb,
                                                                 totalBytes: 1_400 * mb))
            == "Paused · 1.1 of 1.4 GB saved")
    }
}
