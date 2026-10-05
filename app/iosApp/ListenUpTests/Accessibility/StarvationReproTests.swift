import SwiftUI
import Testing
@testable import ListenUp

/// Diagnostic only: saturates the cooperative pool the way the CI runner's startup flood does, then hosts the
/// Readers section and logs what the harness sees.
@MainActor
@Suite("Starvation repro")
struct StarvationReproTests {
    @Test func readersUnderAStarvedPool() async throws {
        let readers = [
            BookReaderRow(id: "u1", displayName: "Rig Reader", initials: "RR", isYou: true, progressPercent: nil,
                          lastFinished: Date(timeIntervalSince1970: 1_462_000_000), lastFinishedOnHardcover: true),
            BookReaderRow(id: "u3", displayName: "Lena Ortiz", initials: "LO", isYou: false, progressPercent: nil,
                          lastFinished: Date(timeIntervalSince1970: 1_727_000_000), halfStars: 8,
                          note: "Better on a second listen.")
        ]
        let seconds = Double(ProcessInfo.processInfo.environment["STARVE_SECONDS"] ?? "15") ?? 15
        let spinners = Int(ProcessInfo.processInfo.environment["STARVE_SPINNERS"] ?? "64") ?? 64
        let until = Date().addingTimeInterval(seconds)
        for _ in 0..<spinners {
            Task.detached(priority: .userInitiated) {
                var x = 0
                while Date() < until { x &+= 1 }
                _ = x
            }
        }
        let hosted = await HostedView(NavigationStack { ScrollView { BookReadersSection(readers: readers).padding() } })
        defer { hosted.close() }
        let rows = hosted.stops.filter { $0.label.contains("Rig Reader") || $0.label.contains("Lena Ortiz") }
        #expect(rows.count == 2, "\(hosted.tree)")
    }
}
