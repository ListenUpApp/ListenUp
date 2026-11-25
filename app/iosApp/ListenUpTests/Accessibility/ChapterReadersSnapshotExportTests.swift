import SwiftUI
import Testing
import UIKit
@testable import ListenUp

/// Renders the hero's social line and chapter rows carrying reader stacks to PNGs, so the overlapped initials can be
/// looked at, at the default text size and at the largest accessibility size. Off unless LU_SNAPSHOT_DIR is set (xcodebuild passes
/// TEST_RUNNER_LU_SNAPSHOT_DIR through), so it never runs in CI. Test target only: no app launch, no audio.
@Suite("Chapter readers review snapshots", .serialized,
       .enabled(if: ProcessInfo.processInfo.environment["LU_SNAPSHOT_DIR"] != nil))
struct ChapterReadersSnapshotExportTests {
    private static let maya = ChapterReaderFaceRow(id: "snap-maya", displayName: "Maya Wren", isLive: false)
    private static let tom = ChapterReaderFaceRow(id: "snap-tom", displayName: "Tom Okafor", isLive: false)
    private static let priya = ChapterReaderFaceRow(id: "snap-priya", displayName: "Priya Shah", isLive: true)
    private static let walt = ChapterReaderFaceRow(id: "snap-walt", displayName: "Walt Moss", isLive: false)

    private static let stacks: [String: ChapterReaderStack] = [
        "c1": stack([maya]),
        "c2": stack([maya, tom, walt]),
        "c3": stack([priya, walt, tom]),
        "c4": stack([walt, maya], overflow: 3)
    ]

    private static func stack(_ faces: [ChapterReaderFaceRow], overflow: Int = 0) -> ChapterReaderStack {
        ChapterReaderStack(place: .thisChapter, faces: faces, overflowCount: overflow, listeningNow: nil,
                           notListening: nil)
    }

    @Test(arguments: [DynamicTypeSize.large, .accessibility3])
    func export(_ dynamicTypeSize: DynamicTypeSize) async throws {
        let dir = URL(fileURLWithPath: try #require(ProcessInfo.processInfo.environment["LU_SNAPSHOT_DIR"]))
        let rows = (0..<5).map {
            BookChapterRow(id: "c\($0)", title: "Chapter \($0 + 1)", duration: "10:00", isCurrent: false,
                           startMs: Int64($0) * 600_000)
        }
        let readers = ChapterReadersLookup(onChapter: { Self.stacks[$0] }, beyond: { _ in nil })
        let hero = HeroSocialLineView(
            line: ReadersSocialLine(listening: ["Dan Reyes", "Hal Burke"], finished: ["Walt Moss"],
                                    avatarUserIds: ["snap-dan", "snap-hal", "snap-walt"], othersCount: 0),
            ringColor: Color(.systemGroupedBackground), onOpenReaders: {})
        let section = VStack(spacing: 16) {
            hero
            BookChapterRowsSection(chapters: rows, preview: rows, canPlay: true, readers: readers,
                                   initiallyExpanded: true, onPlayFromChapter: { _ in })
        }
            .padding()
            .frame(maxHeight: .infinity, alignment: .top)
            .background(Color(.systemGroupedBackground))
        let hosted = await HostedView(section, size: CGSize(width: 393, height: 852), dynamicTypeSize: dynamicTypeSize)
        defer { hosted.close() }
        let image = UIGraphicsImageRenderer(bounds: hosted.window.bounds).image { _ in
            hosted.window.drawHierarchy(in: hosted.window.bounds, afterScreenUpdates: true)
        }
        let name = "chapter-readers-\(dynamicTypeSize == .large ? "default" : "ax3").png"
        try #require(image.pngData()).write(to: dir.appendingPathComponent(name))
    }
}
