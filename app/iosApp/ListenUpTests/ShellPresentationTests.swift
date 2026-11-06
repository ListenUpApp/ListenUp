import SwiftUI
import Testing
import UIKit
@testable import ListenUp

@MainActor
@Suite("The shell's presentations", .serialized)
struct ShellPresentationModelTests {
    @Test func presentingASheetThenDismissingClearsIt() {
        let shell = MainShellModel()

        shell.present(.bookEdit(bookId: "b1"))
        #expect(shell.presentedSheet == .bookEdit(bookId: "b1"))

        shell.dismissPresentation()
        #expect(shell.presentedSheet == nil)
    }

    @Test func presentingACoverIsIndependentOfTheSheet() {
        let shell = MainShellModel()
        let document = ReaderDocument(localPath: "/tmp/a.pdf", title: "A")

        shell.present(.chapterEditor(bookId: "b1"))
        shell.present(.documentReader(document))

        #expect(shell.presentedSheet == .chapterEditor(bookId: "b1"))
        #expect(shell.presentedCover == .documentReader(document))
    }

    @Test func retargetingReplacesThePageOnTopAndKeepsTheDepth() {
        let shell = MainShellModel()
        shell.selectedTab = .home
        shell.open(BookDestination(id: "b1"))
        shell.open(ContributorDestination(id: "old"))

        shell.retarget(to: ContributorDestination(id: "survivor"))

        #expect(shell.path(for: .home).count == 2)
    }

    @Test func retargetingAnEmptyStackLeavesItEmpty() {
        let shell = MainShellModel()

        shell.retarget(to: ContributorDestination(id: "survivor"))

        #expect(shell.path(for: shell.selectedTab).isEmpty)
    }
}

@MainActor
@Suite("A presentation above the identified tab view survives its rebuild", .serialized)
struct ShellPresentationSurvivalTests {
    @Observable
    @MainActor
    final class Flip {
        var flag = false
    }

    final class Token {}

    final class Recorder {
        var identities: [ObjectIdentifier] = []
    }

    struct Probe: View {
        let recorder: Recorder
        @State private var token = Token()

        var body: some View {
            Text("editor").onAppear { recorder.identities.append(ObjectIdentifier(token)) }
        }
    }

    /// `presentsAboveTabs` mirrors `MainTabView`: the sheet is attached outside the view whose identity flips.
    /// Without it, the sheet is attached inside that view — the shape every editor had before.
    struct Harness: View {
        @Bindable var shell: MainShellModel
        let flip: Flip
        let recorder: Recorder
        let presentsAboveTabs: Bool

        var body: some View {
            if presentsAboveTabs {
                tabs(presenting: false)
                    .sheet(item: $shell.presentedSheet) { _ in Probe(recorder: recorder) }
            } else {
                tabs(presenting: true)
            }
        }

        /// A tab view whose identity flips, like `MainTabView`'s under `SidebarFirstTabBar`.
        private func tabs(presenting: Bool) -> some View {
            TabView {
                SwiftUI.Tab("Home", systemImage: "house") {
                    NavigationStack {
                        if presenting {
                            Color.clear.sheet(item: $shell.presentedSheet) { _ in Probe(recorder: recorder) }
                        } else {
                            Color.clear
                        }
                    }
                }
            }
            .id(flip.flag)
        }
    }

    private func run(presentsAboveTabs: Bool) async -> (recorder: Recorder, hosted: HostedView) {
        let shell = MainShellModel()
        let flip = Flip()
        let recorder = Recorder()
        let hosted = await HostedView(
            Harness(shell: shell, flip: flip, recorder: recorder, presentsAboveTabs: presentsAboveTabs))
        shell.present(.editProfile)
        for _ in 0..<10 { await hosted.settle() }
        flip.flag.toggle()
        for _ in 0..<10 { await hosted.settle() }
        return (recorder, hosted)
    }

    @Test func theEditorIsTheSameInstanceAfterTheTabsAreRebuilt() async {
        let (recorder, hosted) = await run(presentsAboveTabs: true)
        defer { hosted.close() }

        #expect(!recorder.identities.isEmpty, "the sheet never appeared")
        #expect(Set(recorder.identities).count == 1, "the editor was rebuilt: \(recorder.identities.count) appearances")
    }

    /// The harness measures what it claims to: the old shape rebuilds the editor.
    @Test func anEditorPresentedInsideTheRebuiltViewIsRebuilt() async {
        let (recorder, hosted) = await run(presentsAboveTabs: false)
        defer { hosted.close() }

        #expect(Set(recorder.identities).count > 1)
    }
}
