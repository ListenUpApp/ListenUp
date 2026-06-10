import SwiftUI
@preconcurrency import Shared
import UIKit

/// Full-screen audiobook player with blurred cover art background
/// and Liquid Glass controls card.
///
/// Layout:
/// - Blurred cover art background (full-bleed)
/// - Top bar: dismiss chevron + ellipsis menu
/// - Centered cover art at 65% screen width
/// - Chapter info below cover
/// - Bottom-anchored glass controls card with slider, transport, and actions
struct FullScreenPlayerView: View {
    let observer: PlayerCoordinator
    @Binding var isPresented: Bool

    @State private var showSpeedPicker: Bool = false
    @State private var showChapterList: Bool = false
    @State private var showSleepTimer: Bool = false

    var body: some View {
        VStack(spacing: 0) {
            // Top bar
            HStack {
                Button(action: { isPresented = false }) {
                    Image(systemName: "chevron.down")
                        .font(.title3.weight(.medium))
                        .foregroundStyle(.white)
                        .frame(width: 44, height: 44)
                }
                Spacer()
            }
            .padding(.horizontal, 8)

            Spacer()

            // Cover art — centered
            BookCoverImage(
                coverPath: observer.coverPath,
                blurHash: observer.coverBlurHash
            )
            .frame(width: 250, height: 250)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .shadow(color: .black.opacity(0.3), radius: 12, x: 0, y: 6)

            Spacer()
                .frame(height: 20)

            // Chapter info
            VStack(spacing: 6) {
                if observer.totalChapters > 0 {
                    Text("Chapter \(observer.chapterIndex + 1) of \(observer.totalChapters)")
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.7))
                }
                Text(observer.chapterTitle ?? observer.bookTitle)
                    .font(.title3.bold())
                    .foregroundStyle(.white)
                    .multilineTextAlignment(.center)
                Text(observer.authorName)
                    .font(.subheadline)
                    .foregroundStyle(.gray)
            }
            .padding(.horizontal, 24)

            Spacer()

            // Controls
            VStack(spacing: 20) {
                // Chapter-scoped progress — isolated so its per-frame position reads
                // don't re-evaluate the rest of the player (incl. the blur background).
                ChapterScrubberSection(observer: observer)

                // Overall book progress bar (thin)
                VStack(spacing: 4) {
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            Capsule()
                                .fill(Color.white.opacity(0.15))
                                .frame(height: 3)
                            Capsule()
                                .fill(Color.listenUpOrange.opacity(0.6))
                                .frame(width: geo.size.width * CGFloat(observer.displayBookProgress), height: 3)
                        }
                    }
                    .frame(height: 3)

                    HStack {
                        Text(formatTime(observer.displayBookPositionMs))
                            .font(.system(size: 10))
                            .foregroundStyle(.gray.opacity(0.6))
                            .monospacedDigit()
                        Spacer()
                        Text(formatTime(observer.bookDurationMs))
                            .font(.system(size: 10))
                            .foregroundStyle(.gray.opacity(0.6))
                            .monospacedDigit()
                    }
                }

                // Transport
                HStack(spacing: 0) {
                    // Previous chapter
                    Button {
                        if observer.chapterIndex > 0 {
                            observer.selectChapter(index: observer.chapterIndex - 1)
                        }
                    } label: {
                        Image(systemName: "backward.end.fill")
                            .font(.body)
                            .foregroundStyle(observer.chapterIndex > 0 ? .white : .gray.opacity(0.4))
                            .frame(width: 44, height: 44)
                    }
                    .disabled(observer.chapterIndex <= 0)

                    Spacer()

                    // Skip back
                    Button { observer.skipBackward(seconds: 10) } label: {
                        Image(systemName: "gobackward.10")
                            .font(.title2)
                            .foregroundStyle(.white)
                            .frame(width: 44, height: 44)
                    }

                    Spacer()

                    // Play/Pause
                    Button {
                        observer.togglePlayback()
                    } label: {
                        ZStack {
                            Circle()
                                .fill(.white)
                                .frame(width: 64, height: 64)
                            Image(systemName: observer.isPlaying ? "pause.fill" : "play.fill")
                                .font(.title)
                                .foregroundStyle(Color.listenUpOrange)
                        }
                    }

                    Spacer()

                    // Skip forward
                    Button { observer.skipForward(seconds: 30) } label: {
                        Image(systemName: "goforward.30")
                            .font(.title2)
                            .foregroundStyle(.white)
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
                            .font(.body)
                            .foregroundStyle(observer.chapterIndex < observer.totalChapters - 1 ? .white : .gray.opacity(0.4))
                            .frame(width: 44, height: 44)
                    }
                    .disabled(observer.chapterIndex >= observer.totalChapters - 1)
                }
                .padding(.horizontal, 8)

                // Bottom actions
                HStack(spacing: 32) {
                    Button(action: { showSleepTimer = true }) {
                        Image(systemName: observer.sleepTimerActive ? "moon.zzz.fill" : "moon.zzz")
                            .foregroundStyle(observer.sleepTimerActive ? Color.listenUpOrange : .gray)
                    }
                    Button(action: { showSpeedPicker = true }) {
                        Text(formatSpeed(observer.playbackSpeed))
                            .foregroundStyle(.gray)
                    }
                    Button(action: { showChapterList = true }) {
                        Image(systemName: "list.bullet")
                            .foregroundStyle(.gray)
                    }
                }
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 40)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background {
            ZStack {
                BookCoverImage(
                    coverPath: observer.coverPath,
                    blurHash: observer.coverBlurHash
                )
                .blur(radius: 50)
                .scaleEffect(1.2)
                .ignoresSafeArea()

                Color.black.opacity(0.4)
                    .ignoresSafeArea()
            }
        }
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

            HStack {
                let elapsed = isDraggingSlider ? Int64(sliderPosition) : observer.chapterPositionMs
                Text(formatTime(elapsed))
                    .font(.caption)
                    .foregroundStyle(.gray)
                    .monospacedDigit()
                Spacer()
                Text("-" + formatTime(observer.chapterDurationMs - elapsed))
                    .font(.caption)
                    .foregroundStyle(.gray)
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

// MARK: - Sleep Timer Sheet

private struct SleepTimerSheet: View {
    let observer: PlayerCoordinator
    let onDismiss: () -> Void

    private let durations = [15, 30, 45, 60, 120]

    var body: some View {
        NavigationStack {
            List {
                if observer.sleepTimerActive {
                    Section {
                        HStack {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(String(localized: "player.timer_active"))
                                    .font(.subheadline.bold())
                                Text(observer.sleepTimerLabel)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Button(String(localized: "common.cancel")) {
                                observer.cancelSleepTimer()
                                onDismiss()
                            }
                            .foregroundStyle(.red)
                        }
                    }
                }

                Section(String(localized: "player.duration")) {
                    ForEach(durations, id: \.self) { minutes in
                        Button(action: {
                            observer.setSleepTimer(minutes: minutes)
                            onDismiss()
                        }) {
                            HStack {
                                Text(formatDuration(minutes))
                                    .foregroundStyle(.primary)
                                Spacer()
                            }
                        }
                    }
                }

                Section {
                    Button(action: {
                        observer.setSleepTimerEndOfChapter()
                        onDismiss()
                    }) {
                        HStack {
                            Text(String(localized: "player.end_of_chapter"))
                                .foregroundStyle(.primary)
                            Spacer()
                        }
                    }
                }
            }
            .navigationTitle(String(localized: "player.sleep_timer"))
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    private func formatDuration(_ minutes: Int) -> String {
        if minutes < 60 { return "\(minutes) minutes" }
        if minutes == 60 { return "1 hour" }
        return "\(minutes / 60) hours"
    }
}

// MARK: - Speed Picker Sheet

private struct SpeedPickerSheet: View {
    let currentSpeed: Float
    let onSpeedSelected: (Float) -> Void

    private let speeds: [Float] = [0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0]

    var body: some View {
        NavigationStack {
            List {
                ForEach(speeds, id: \.self) { speed in
                    Button(action: { onSpeedSelected(speed) }) {
                        HStack {
                            Text(formatSpeed(speed))
                                .foregroundStyle(.primary)

                            Spacer()

                            if abs(speed - currentSpeed) < 0.01 {
                                Image(systemName: "checkmark")
                                    .foregroundStyle(Color.listenUpOrange)
                                    .fontWeight(.semibold)
                            }
                        }
                    }
                }
            }
            .navigationTitle(String(localized: "player.playback_speed"))
            .navigationBarTitleDisplayMode(.inline)
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

// MARK: - Chapter List Sheet

private struct ChapterListSheet: View {
    let observer: PlayerCoordinator
    let onDismiss: () -> Void

    static func formatMs(_ ms: Int64) -> String {
        let totalSeconds = ms / 1000
        let hours = totalSeconds / 3600
        let minutes = (totalSeconds % 3600) / 60
        let seconds = totalSeconds % 60
        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, seconds)
        }
        return String(format: "%d:%02d", minutes, seconds)
    }

    var body: some View {
        NavigationStack {
            List {
                ForEach(0..<observer.totalChapters, id: \.self) { index in
                    Button(action: {
                        observer.selectChapter(index: index)
                        onDismiss()
                    }) {
                        HStack(spacing: 12) {
                            // Chapter number
                            Text("\(index + 1)")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .frame(width: 24)

                            // Chapter title + duration
                            VStack(alignment: .leading, spacing: 2) {
                                Text(observer.chapterTitleForIndex(index) ?? "Chapter \(index + 1)")
                                    .font(.subheadline)
                                    .foregroundStyle(index == observer.chapterIndex ? Color.listenUpOrange : .primary)
                                    .lineLimit(2)
                                if index < observer.chapters.count {
                                    let durationMs = observer.chapters[index].duration
                                    Text(Self.formatMs(durationMs))
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }

                            Spacer()

                            // Now playing indicator
                            if index == observer.chapterIndex {
                                Image(systemName: observer.isPlaying ? "speaker.wave.2.fill" : "speaker.fill")
                                    .font(.caption)
                                    .foregroundStyle(Color.listenUpOrange)
                            }
                        }
                    }
                }
            }
            .navigationTitle(String(localized: "player.chapters"))
            .navigationBarTitleDisplayMode(.inline)
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
