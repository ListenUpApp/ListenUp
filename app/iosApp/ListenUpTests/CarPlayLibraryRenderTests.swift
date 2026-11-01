import CarPlay
import Foundation
import Testing
import UIKit
@testable import ListenUp

/// The Library list appears at once; its covers are decoded behind it, off the main actor.
@Suite("CarPlay Library rendering")
@MainActor
struct CarPlayLibraryRenderTests {
    /// Holds every decode back until `open()`, so a test can look at the list before any cover exists.
    private actor DecodeGate {
        private var isOpen = false
        private var waiters: [CheckedContinuation<Void, Never>] = []

        func wait() async {
            if isOpen { return }
            await withCheckedContinuation { waiters.append($0) }
        }

        func open() {
            isOpen = true
            waiters.forEach { $0.resume() }
            waiters.removeAll()
        }
    }

    private static func books(_ ids: [String]) -> [BookRow] {
        ids.map {
            BookRow(id: $0, title: $0, authorNames: "Author", hasDocuments: false, coverPath: "/covers/\($0).jpg")
        }
    }

    private static func swatch() -> UIImage {
        UIGraphicsImageRenderer(size: CGSize(width: 4, height: 4)).image { context in
            UIColor.systemTeal.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 4, height: 4))
        }
    }

    private static func items(of delegate: CarPlaySceneDelegate) -> [CPListItem] {
        delegate.libraryTemplate.sections.flatMap(\.items).compactMap { $0 as? CPListItem }
    }

    /// Spins the main actor until `condition` holds; false if it never does.
    private static func eventually(_ condition: () -> Bool) async -> Bool {
        for _ in 0..<200 where !condition() {
            try? await Task.sleep(for: .milliseconds(10))
        }
        return condition()
    }

    @Test func theListIsPushedBeforeAnyCoverIsDecoded() async {
        let gate = DecodeGate()
        let delegate = CarPlaySceneDelegate()
        delegate.decodeArtwork = { _, _ in
            await gate.wait()
            return await MainActor.run { Self.swatch() }
        }

        delegate.renderLibrary(Self.books(["a", "b", "c"]))

        let items = Self.items(of: delegate)
        #expect(items.count == 3)
        #expect(items.allSatisfy { $0.image == nil })

        await gate.open()
        let filled = await Self.eventually { items.allSatisfy { $0.image != nil } }
        #expect(filled)
    }

    @Test func aRerenderCancelsTheCoversStillPendingForTheOldList() async {
        let gate = DecodeGate()
        let delegate = CarPlaySceneDelegate()
        delegate.decodeArtwork = { _, _ in
            await gate.wait()
            return await MainActor.run { Self.swatch() }
        }

        delegate.renderLibrary(Self.books(["a", "b"]))
        let stale = Self.items(of: delegate)
        delegate.renderLibrary(Self.books(["c", "d"]))
        let current = Self.items(of: delegate)

        await gate.open()
        let filled = await Self.eventually { current.allSatisfy { $0.image != nil } }
        #expect(filled)
        #expect(stale.allSatisfy { $0.image == nil })
    }
}
