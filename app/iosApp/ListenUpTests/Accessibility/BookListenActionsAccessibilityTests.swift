import SwiftUI
import Testing
@testable import ListenUp

/// Book Detail's actions, read the way VoiceOver meets them: Download names its size (spec N2), the primary is a
/// live button unless the book is Unavailable Offline (N8), and Add to Shelf is a visible button.
@MainActor
@Suite("Book Detail listen actions accessibility", .serialized)
struct BookListenActionsAccessibilityTests {
    private let size: Int64 = 1_288_490_189

    private func actions(kind: BookPrimaryActionKind, download: DownloadUIState = .notDownloaded) async -> HostedView {
        await HostedView(
            NavigationStack {
                ScrollView {
                    BookListenActions(
                        label: BookPrimaryActionLabel(kind: kind, isPreparing: false),
                        downloadState: download,
                        downloadProgress: 0,
                        downloadSizeBytes: size,
                        canDownload: true,
                        onPrimary: {}, onDownload: {}, onCancelDownload: {}, onDeleteDownload: {}, onAddToShelf: {}
                    )
                    .padding()
                }
            })
    }

    @Test func downloadIsNamedByItsSize() async throws {
        let hosted = await actions(kind: .resume)
        defer { hosted.close() }
        let download = try #require(hosted.stops(labelContaining: StorageFormat.byteSize(size)).first, "\(hosted.tree)")
        #expect(download.isButton)
    }

    @Test func resumeIsAnEnabledButton() async throws {
        let hosted = await actions(kind: .resume)
        defer { hosted.close() }
        let resume = try #require(hosted.stop(labelled: String(localized: "book.detail_resume_ios")), "\(hosted.tree)")
        #expect(resume.isButton)
        #expect(!resume.traits.contains(.notEnabled))
    }

    @Test func listenAgainIsAnEnabledButton() async throws {
        let hosted = await actions(kind: .listenAgain, download: .completed)
        defer { hosted.close() }
        let again = try #require(hosted.stop(labelled: String(localized: "book.detail_listen_again_ios")),
                                 "\(hosted.tree)")
        #expect(!again.traits.contains(.notEnabled))
    }

    @Test func unavailableOfflineIsTheOnlyDisabledPrimary() async throws {
        let hosted = await actions(kind: .unavailableOffline)
        defer { hosted.close() }
        let offline = try #require(hosted.stop(labelled: String(localized: "book.detail_unavailable_offline")),
                                   "\(hosted.tree)")
        #expect(offline.traits.contains(.notEnabled))
    }

    @Test func addToShelfIsAVisibleButton() async throws {
        let hosted = await actions(kind: .play)
        defer { hosted.close() }
        let shelf = try #require(hosted.stop(labelled: String(localized: "book.detail_add_to_shelf_ios")),
                                 "\(hosted.tree)")
        #expect(shelf.isButton)
    }
}
