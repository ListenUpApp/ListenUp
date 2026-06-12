import SwiftUI
@preconcurrency import Shared
import UIKit

/// Full-screen audiobook player on a soft cover-tint wash.
///
/// Layout:
/// - A linear tint wash over `systemBackground` (light/dark adaptive, fades by mid-screen)
/// - Header: system-fill chevron-down · "Chapter N of M" · ellipsis menu
/// - Centered cover art
/// - Leading-aligned title block (title / chapter / narrator)
/// - Tint-accented chapter scrubber + thin overall-book progress bar
/// - Transport (prev-ch · back-10 · play/pause · fwd-30 · next-ch)
/// - Secondary row: Speed · Sleep · Chapters · AirPlay
///
/// The accent is a legibility-clamped tint derived from the cover (coral until it
/// resolves; coral on any failure — never stranded).
struct FullScreenPlayerView: View {
    let observer: PlayerCoordinator
    @Binding var isPresented: Bool

    @State private var showSpeedPicker: Bool = false
    @State private var showChapterList: Bool = false
    @State private var showSleepTimer: Bool = false
    @State private var tint: Color = .listenUpOrange

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(spacing: 0) {
            header

            Spacer(minLength: 12)

            // Cover art — centered
            BookCoverImage(
                coverPath: observer.coverPath,
                blurHash: observer.coverBlurHash
            )
            .frame(width: 286, height: 286)
            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .shadow(color: .black.opacity(0.25), radius: 16, x: 0, y: 8)

            Spacer()
                .frame(height: 32)

            titleBlock

            Spacer().frame(height: 22)

            // Chapter-scoped progress — isolated so its per-frame position reads
            // don't re-evaluate the rest of the player.
            ChapterScrubberSection(observer: observer, tint: tint)
                .padding(.horizontal, 26)

            Spacer().frame(height: 12)

            // Overall book progress bar (thin)
            overallProgressBar
                .padding(.horizontal, 26)

            Spacer(minLength: 20)

            transport
                .padding(.horizontal, 30)

            Spacer(minLength: 20)

            secondaryControls
                .padding(.horizontal, 26)

            Spacer().frame(height: 24)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(
            LinearGradient(
                colors: [tint.opacity(0.18), Color(.systemBackground)],
                startPoint: .top,
                endPoint: .center
            )
            .ignoresSafeArea()
        )
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.4), value: tint)
        .task(id: observer.coverPath) { resolveTint() }
        .statusBarHidden(false)
        .sheet(isPresented: $showSpeedPicker) {
            SpeedPickerSheet(
                currentSpeed: observer.playbackSpeed,
                onSpeedSelected: { speed in
                    observer.setSpeed(speed)
                    showSpeedPicker = false
                }
            )
            .presentationDetents([.medium])
            .presentationDragIndicator(.visible)
            .presentationBackground(.regularMaterial)
        }
        .sheet(isPresented: $showSleepTimer) {
            SleepTimerSheet(
                observer: observer,
                onDismiss: { showSleepTimer = false }
            )
            .presentationDetents([.medium])
            .presentationDragIndicator(.visible)
            .presentationBackground(.regularMaterial)
        }
        .sheet(isPresented: $showChapterList) {
            ChapterListSheet(
                observer: observer,
                onDismiss: { showChapterList = false }
            )
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
            .presentationBackground(.regularMaterial)
        }
    }

    // MARK: - Header

    private var header: some View {
        HStack {
            Button(action: { isPresented = false }) {
                Image(systemName: "chevron.down")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(.primary)
                    .frame(width: 36, height: 36)
                    .background(Color(.tertiarySystemFill), in: Circle())
            }

            Spacer()

            if observer.totalChapters > 0 {
                Text(String(
                    format: String(localized: "player.chapter_of"),
                    "\(observer.chapterIndex + 1)",
                    "\(observer.totalChapters)"
                ))
                .font(.footnote)
                .foregroundStyle(.secondary)
            }

            Spacer()

            Menu {
                // Placeholder — wired in a later task.
                Button(action: {}) {
                    Label(String(localized: "player.go_to_book"), systemImage: "book")
                }
                .disabled(true)
            } label: {
                Image(systemName: "ellipsis")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(.primary)
                    .frame(width: 36, height: 36)
                    .background(Color(.tertiarySystemFill), in: Circle())
            }
        }
        .padding(.horizontal, 18)
    }

    // MARK: - Title block

    private var titleBlock: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(observer.bookTitle)
                .font(.title2.bold())
                .foregroundStyle(.primary)
                .lineLimit(1)

            Text("Ch. \(observer.chapterIndex + 1) · \(observer.chapterTitle ?? "")")
                .font(.callout)
                .foregroundStyle(.secondary)
                .lineLimit(1)

            Text(String(format: String(localized: "books.detail_narrated_by_value"), observer.authorName))
                .font(.footnote)
                .foregroundStyle(.tertiary)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 26)
    }

    // MARK: - Overall progress

    private var overallProgressBar: some View {
        VStack(spacing: 4) {
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule()
                        .fill(Color(.systemFill))
                        .frame(height: 3)
                    Capsule()
                        .fill(tint)
                        .frame(width: geo.size.width * CGFloat(observer.displayBookProgress), height: 3)
                }
            }
            .frame(height: 3)

            HStack {
                Text(formatTime(observer.displayBookPositionMs))
                    .font(.system(size: 10))
                    .foregroundStyle(.tertiary)
                    .monospacedDigit()
                Spacer()
                Text(formatTime(observer.bookDurationMs))
                    .font(.system(size: 10))
                    .foregroundStyle(.tertiary)
                    .monospacedDigit()
            }
        }
    }

    // MARK: - Transport

    private var transport: some View {
        HStack(spacing: 0) {
            // Previous chapter
            Button {
                if observer.chapterIndex > 0 {
                    observer.selectChapter(index: observer.chapterIndex - 1)
                }
            } label: {
                Image(systemName: "backward.end.fill")
                    .font(.title3)
                    .foregroundStyle(observer.chapterIndex > 0 ? .primary : .tertiary)
                    .frame(width: 44, height: 44)
            }
            .disabled(observer.chapterIndex <= 0)

            Spacer()

            // Skip back
            Button { observer.skipBackward(seconds: 10) } label: {
                Image(systemName: "gobackward.10")
                    .font(.title2)
                    .foregroundStyle(.primary)
                    .frame(width: 44, height: 44)
            }

            Spacer()

            // Play/Pause
            Button {
                observer.togglePlayback()
            } label: {
                ZStack {
                    Circle()
                        .fill(tint)
                        .frame(width: 76, height: 76)
                        .shadow(color: tint.opacity(0.45), radius: 12, x: 0, y: 8)
                    Image(systemName: observer.isPlaying ? "pause.fill" : "play.fill")
                        .font(.title)
                        .foregroundStyle(.white)
                }
            }

            Spacer()

            // Skip forward
            Button { observer.skipForward(seconds: 30) } label: {
                Image(systemName: "goforward.30")
                    .font(.title2)
                    .foregroundStyle(.primary)
                    .frame(width: 44, height: 44)
            }

            Spacer()

            // Next chapter
            Button {
                if observer.chapterIndex < observer.totalChapters - 1 {
                    observer.selectChapter(index: observer.chapterIndex + 1)
                }
            } label: {
                Image(systemName: "forward.end.fill")
                    .font(.title3)
                    .foregroundStyle(observer.chapterIndex < observer.totalChapters - 1 ? .primary : .tertiary)
                    .frame(width: 44, height: 44)
            }
            .disabled(observer.chapterIndex >= observer.totalChapters - 1)
        }
    }

    // MARK: - Secondary controls

    private var secondaryControls: some View {
        HStack(alignment: .top, spacing: 6) {
            // Speed
            controlItem(label: String(localized: "player.speed")) {
                Button(action: { showSpeedPicker = true }) {
                    Text(formatSpeed(observer.playbackSpeed))
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.primary)
                        .padding(.horizontal, 12)
                        .frame(height: 28)
                        .background(Color(.tertiarySystemFill), in: Capsule())
                }
            }

            // Sleep
            controlItem(label: String(localized: "player.sleep")) {
                Button(action: { showSleepTimer = true }) {
                    Image(systemName: observer.sleepTimerActive ? "moon.zzz.fill" : "moon.zzz")
                        .font(.title3)
                        .foregroundStyle(observer.sleepTimerActive ? tint : .secondary)
                        .frame(height: 28)
                }
            }

            // Chapters
            controlItem(label: String(localized: "player.chapters")) {
                Button(action: { showChapterList = true }) {
                    Image(systemName: "list.bullet")
                        .font(.title3)
                        .foregroundStyle(.secondary)
                        .frame(height: 28)
                }
            }

            // AirPlay
            controlItem(label: String(localized: "player.airplay")) {
                RoutePickerView(tint: Color(.secondaryLabel), activeTint: tint)
                    .frame(width: 28, height: 28)
            }
        }
    }

    private func controlItem(label: String, @ViewBuilder _ control: () -> some View) -> some View {
        VStack(spacing: 5) {
            control()
            Text(label)
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: - Tint

    /// Resolves the cover accent. Coral until extraction lands; coral on any failure
    /// (never stranded). Keyed on `coverPath` so re-entry never re-flickers.
    private func resolveTint() {
        guard let coverPath = observer.coverPath else { return }
        if let cached = CoverTintExtractor.shared.cached(bookId: coverPath) {
            tint = cached.color
            return
        }
        Task {
            if let resolved = await CoverTintExtractor.shared.resolve(bookId: coverPath, coverPath: coverPath) {
                tint = resolved.color
            }
        }
    }

    // MARK: - Helpers

    private func formatTime(_ ms: Int64) -> String {
        let totalSeconds = ms / 1000
        let hours = totalSeconds / 3600
        let minutes = (totalSeconds % 3600) / 60
        let seconds = totalSeconds % 60

        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            return String(format: "%d:%02d", minutes, seconds)
        }
    }

    private func formatTimeRemaining(_ ms: Int64) -> String {
        let totalSeconds = ms / 1000
        let hours = totalSeconds / 3600
        let minutes = (totalSeconds % 3600) / 60
        let seconds = totalSeconds % 60

        if hours > 0 {
            return String(format: "-%d:%02d:%02d", hours, minutes, seconds)
        } else {
            return String(format: "-%d:%02d", minutes, seconds)
        }
    }

    private func formatSpeed(_ speed: Float) -> String {
        if speed == Float(Int(speed)) {
            return "\(Int(speed))x"
        } else {
            return String(format: "%.2gx", speed)
        }
    }
}

// MARK: - Chapter Scrubber

/// The chapter slider + elapsed/remaining labels. Extracted from
/// `FullScreenPlayerView` so the per-frame position reads that drive the moving
/// thumb re-evaluate only this small view — not the whole player and its blurred
/// cover background, which now re-evaluate at most ~1×/sec.
private struct ChapterScrubberSection: View {
    let observer: PlayerCoordinator
    let tint: Color

    @State private var sliderPosition: Double = 0
    @State private var isDraggingSlider: Bool = false

    var body: some View {
        VStack(spacing: 8) {
            Slider(
                value: $sliderPosition,
                in: 0...max(Double(observer.chapterDurationMs), 1),
                onEditingChanged: { editing in
                    isDraggingSlider = editing
                    if !editing {
                        // Seek relative to chapter start
                        if let info = observer.currentChapterInfoForSeeking {
                            let absolutePosition = Int64(info.startMs) + Int64(sliderPosition)
                            observer.seekTo(positionMs: absolutePosition)
                        }
                    }
                }
            )
            .tint(tint)

            HStack {
                let elapsed = isDraggingSlider ? Int64(sliderPosition) : observer.chapterPositionMs
                Text(formatTime(elapsed))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
                Spacer()
                Text("-" + formatTime(observer.chapterDurationMs - elapsed))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
            }
        }
        .onChange(of: observer.chapterPositionMs) { _, newValue in
            if !isDraggingSlider {
                sliderPosition = Double(newValue)
            }
        }
        .onAppear {
            sliderPosition = Double(observer.chapterPositionMs)
        }
    }

    private func formatTime(_ ms: Int64) -> String {
        let totalSeconds = max(0, ms / 1000)
        let hours = totalSeconds / 3600
        let minutes = (totalSeconds % 3600) / 60
        let seconds = totalSeconds % 60
        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            return String(format: "%d:%02d", minutes, seconds)
        }
    }
}

// MARK: - Preview

#Preview {
    Color.blue
        .ignoresSafeArea()
        .sheet(isPresented: .constant(true)) {
            Text("Full screen player preview requires observer")
        }
}
