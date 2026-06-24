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
    var namespace: Namespace.ID
    var onCollapse: () -> Void
    /// Collapse the player and navigate to the current book's detail screen.
    var onViewDetails: () -> Void = {}

    /// Live drag translation as the user swipes the header down (downward only).
    /// Attached to the header strip alone so the body's chapter `Slider` and any
    /// scrolling stay fully interactive — the dismiss drag never covers them.
    var onDragChanged: (CGFloat) -> Void = { _ in }
    /// Drag release: the overlay decides commit-to-dismiss vs. spring-back from
    /// the final translation and predicted-end fling.
    var onDragEnded: (_ translation: CGFloat, _ predictedEndTranslation: CGFloat) -> Void = { _, _ in }

    @State private var showSpeedPicker: Bool = false
    @State private var showChapterList: Bool = false
    @State private var showSleepTimer: Bool = false
    @State private var tint: Color = .listenUpOrange

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.horizontalSizeClass) private var hSize

    var body: some View {
        layout
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
        .task(id: observer.currentBookId) { resolveTint() }
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
        }
        .sheet(isPresented: $showSleepTimer) {
            SleepTimerSheet(
                observer: observer,
                onDismiss: { showSleepTimer = false }
            )
            .presentationDetents([.medium])
            .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $showChapterList) {
            ChapterListSheet(
                observer: observer,
                onDismiss: { showChapterList = false }
            )
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
        }
        .fullScreenCover(item: Binding(
            get: { observer.documentToOpen },
            set: { observer.documentToOpen = $0 }
        )) { doc in
            DocumentReaderView(document: doc, onDone: { observer.documentToOpen = nil })
        }
    }

    // MARK: - Layout

    /// Size-class-driven layout. Compact (iPhone) keeps the single stacked column
    /// with the chapter *sheet*; regular (iPad / landscape) splits into the player
    /// column plus an always-visible inline "Up Next" chapters pane.
    @ViewBuilder
    private var layout: some View {
        if hSize == .regular {
            regularLayout
        } else {
            compactLayout
        }
    }

    /// iPhone: the single stacked player column. The Chapters control opens the
    /// modal `ChapterListSheet` (there's no room for an inline pane).
    private var compactLayout: some View {
        playerColumn(showChaptersControl: true)
    }

    /// iPad: a centered player column beside the inline "Up Next" chapters pane.
    /// The pane replaces the chapter sheet, so the column's Chapters control is
    /// hidden here.
    private var regularLayout: some View {
        HStack(spacing: 0) {
            playerColumn(showChaptersControl: false)
                .frame(maxWidth: 620)
                .frame(maxWidth: .infinity)

            NowPlayingUpNextPanel(observer: observer, tint: tint)
        }
    }

    /// The shared player stack — header, cover, title, scrubber, transport, and
    /// secondary controls. Used by both layouts so the matched-geometry cover and
    /// the dismiss gesture live in exactly one place. `showChaptersControl` hides
    /// the Chapters button on iPad, where the inline pane is the primary surface.
    private func playerColumn(showChaptersControl: Bool) -> some View {
        VStack(spacing: 0) {
            header

            Spacer(minLength: 12)

            // Cover art — centered
            BookCoverImage(
                bookId: observer.currentBookId,
                coverPath: observer.coverPath,
                blurHash: observer.coverBlurHash
            )
            .frame(width: 286, height: 286)
            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .shadow(color: .black.opacity(0.25), radius: 16, x: 0, y: 8)
            .matchedGeometryEffect(id: PlayerMorph.coverID, in: namespace)

            Spacer()
                .frame(height: 32)

            titleBlock

            Spacer().frame(height: 22)

            // Chapter-scoped progress — isolated so its per-frame position reads
            // don't re-evaluate the rest of the player.
            ChapterScrubberSection(observer: observer, tint: tint)
                .padding(.horizontal, 26)

            Spacer(minLength: 20)

            transport
                .padding(.horizontal, 30)

            Spacer(minLength: 20)

            secondaryControls(showChaptersControl: showChaptersControl)
                .padding(.horizontal, 26)

            Spacer().frame(height: 24)
        }
    }

    // MARK: - Header

    private var header: some View {
        HStack {
            Button(action: onCollapse) {
                Image(systemName: "chevron.down")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(.primary)
                    .frame(width: 36, height: 36)
                    .background(Color(.tertiarySystemFill), in: Circle())
            }
            .accessibilityLabel(String(localized: "player.collapse"))

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
                Button(action: onViewDetails) {
                    Label(String(localized: "player.go_to_book"), systemImage: "book")
                }
                if observer.firstPdfDocId != nil {
                    Button(action: { observer.openCurrentBookPdf() }) {
                        Label(String(localized: "player.open_pdf"), systemImage: "doc.richtext")
                    }
                }
            } label: {
                Image(systemName: "ellipsis")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(.primary)
                    .frame(width: 36, height: 36)
                    .background(Color(.tertiarySystemFill), in: Circle())
            }
            .accessibilityLabel(String(localized: "player.more_options"))
        }
        .padding(.horizontal, 18)
        // Interactive swipe-down-to-dismiss lives on the header strip only. The
        // chevron/menu `Button`s still get their taps (the drag only fires past
        // its minimum distance); the body's chapter `Slider` is untouched.
        .contentShape(Rectangle())
        .simultaneousGesture(
            DragGesture(minimumDistance: 10)
                .onChanged { value in onDragChanged(value.translation.height) }
                .onEnded { value in
                    onDragEnded(value.translation.height, value.predictedEndTranslation.height)
                }
        )
    }

    // MARK: - Title block

    private var titleBlock: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(observer.bookTitle)
                .font(.title2.bold())
                .foregroundStyle(.primary)
                .lineLimit(1)

            // The chapter's own title; fall back to "Chapter N" only when genuinely untitled.
            // No "Ch. N · " prefix — the title is often itself "Chapter N", producing confusing
            // duplicates like "Ch. 3 · Chapter 1" when front-matter offsets the numbering.
            Text(observer.chapterTitle.flatMap { $0.isEmpty ? nil : $0 }
                ?? "Chapter \(observer.chapterIndex + 1)")
                .font(.callout)
                .foregroundStyle(.secondary)
                .lineLimit(1)

            if !observer.narratorName.isEmpty {
                Text(String(format: String(localized: "book.detail_narrated_by_value"), observer.narratorName))
                    .font(.footnote)
                    .foregroundStyle(.tertiary)
                    .lineLimit(1)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 26)
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
            .accessibilityLabel(String(localized: "player.previous_chapter"))

            Spacer()

            // Skip back
            Button { observer.skipBackward(seconds: 10) } label: {
                Image(systemName: "gobackward.10")
                    .font(.title2)
                    .foregroundStyle(.primary)
                    .frame(width: 44, height: 44)
            }
            .accessibilityLabel(String(format: String(localized: "player.skip_backward"), "10"))

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
            .accessibilityLabel(String(localized: observer.isPlaying ? "player.pause" : "player.play"))

            Spacer()

            // Skip forward
            Button { observer.skipForward(seconds: 30) } label: {
                Image(systemName: "goforward.30")
                    .font(.title2)
                    .foregroundStyle(.primary)
                    .frame(width: 44, height: 44)
            }
            .accessibilityLabel(String(format: String(localized: "player.skip_forward"), "30"))

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
            .accessibilityLabel(String(localized: "player.next_chapter"))
        }
    }

    // MARK: - Secondary controls

    private func secondaryControls(showChaptersControl: Bool) -> some View {
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
                        .foregroundStyle(observer.sleepTimerActive ? tint : .primary)
                        .frame(height: 28)
                }
            }

            // Chapters — hidden on iPad, where the inline "Up Next" pane replaces it.
            if showChaptersControl {
                controlItem(label: String(localized: "player.chapters")) {
                    Button(action: { showChapterList = true }) {
                        Image(systemName: "list.bullet")
                            .font(.title3)
                            .foregroundStyle(.primary)
                            .frame(height: 28)
                    }
                }
            }

            // AirPlay — self-voicing route picker; keep it as its own interactive element.
            controlItem(label: String(localized: "player.airplay"), combineForVoiceOver: false) {
                RoutePickerView(tint: Color(.label), activeTint: tint)
                    .frame(width: 28, height: 28)
            }
        }
    }

    /// One secondary-row control with its caption. `combineForVoiceOver` merges the
    /// control and its visible caption into one VoiceOver element ("Speed, 1×" once,
    /// not the raw symbol name plus a duplicate). Off for AirPlay's self-voicing picker.
    private func controlItem(
        label: String,
        combineForVoiceOver: Bool = true,
        @ViewBuilder _ control: () -> some View
    ) -> some View {
        VStack(spacing: 5) {
            control()
            Text(label)
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: combineForVoiceOver ? .combine : .contain)
    }

    // MARK: - Tint

    /// Resolves the cover accent. Coral until extraction lands; coral on any failure
    /// (never stranded). Keyed on the book id so the cache entry is shared with Book
    /// Detail (placeholder `coverPath`s never collide); falls back to `coverPath` only
    /// when the id is unknown.
    private func resolveTint() {
        guard let cacheKey = observer.currentBookId ?? observer.coverPath else { return }
        if let cached = CoverTintExtractor.shared.cached(bookId: cacheKey) {
            tint = cached.color
            return
        }
        Task {
            if let resolved = await CoverTintExtractor.shared.resolve(bookId: cacheKey, coverPath: observer.coverPath) {
                tint = resolved.color
            }
        }
    }

    // MARK: - Helpers

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
