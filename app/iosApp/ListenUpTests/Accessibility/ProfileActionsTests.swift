import SwiftUI
import Testing
@testable import ListenUp

/// The profile's Downloads row goes where Settings › Manage storage goes: the storage screen.
@MainActor
@Suite("Profile actions", .serialized)
struct ProfileActionsTests {
    private func host() async -> HostedView {
        await HostedView(
            NavigationStack {
                ScrollView { ProfileActions(isAdmin: false) }
                    .navigationDestination(for: StorageDestination.self) { _ in Text("Storage screen") }
            },
            size: CGSize(width: 393, height: 400)
        )
    }

    @Test func downloadsIsAButton() async throws {
        let hosted = await host()
        defer { hosted.close() }
        let downloads = try #require(hosted.stop(labelled: "Downloads"), "\(hosted.tree)")
        #expect(downloads.isButton, "\(hosted.tree)")
    }

    @Test func downloadsOpensTheStorageScreen() async throws {
        let hosted = await host()
        defer { hosted.close() }
        #expect(hosted.activate(labelled: "Downloads"), "\(hosted.tree)")
        await hosted.settle()
        #expect(hosted.stop(labelled: "Storage screen") != nil, "\(hosted.tree)")
    }
}
