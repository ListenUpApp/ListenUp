import Testing
import UIKit
@testable import ListenUp

/// The observer publishes the recent books as the app icon's quick actions, and clears them on
/// sign-out. The loader and the sink are injected, so no Koin and no `UIApplication` are involved.
@MainActor
@Suite("RecentBookShortcutsObserver")
struct RecentBookShortcutsObserverTests {
    private let bundleID = "com.example.listenup"

    private final class Sink {
        var published: [UIApplicationShortcutItem]?
    }

    private func makeObserver(sink: Sink, loading books: [RecentBook]) -> RecentBookShortcutsObserver {
        RecentBookShortcutsObserver(
            bundleIdentifier: bundleID,
            loadRecentBooks: { books },
            publish: { sink.published = $0 }
        )
    }

    @Test func refreshPublishesTheRecentBooksAsItems() async {
        let sink = Sink()
        let observer = makeObserver(
            sink: sink,
            loading: [RecentBook(id: "a", title: "A", author: "X"), RecentBook(id: "b", title: "B", author: "Y")]
        )

        await observer.refresh()

        #expect(sink.published?.map(\.localizedTitle) == ["A", "B"])
    }

    @Test func clearPublishesAnEmptyList() async {
        let sink = Sink()
        let observer = makeObserver(sink: sink, loading: [RecentBook(id: "a", title: "A", author: "X")])
        await observer.refresh()

        observer.clear()

        #expect(sink.published?.isEmpty == true)
    }

    @Test func refreshWithNoBooksPublishesAnEmptyList() async {
        let sink = Sink()
        let observer = makeObserver(sink: sink, loading: [])

        await observer.refresh()

        #expect(sink.published?.isEmpty == true)
    }
}
