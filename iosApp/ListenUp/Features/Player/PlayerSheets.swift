import SwiftUI
@preconcurrency import Shared

// MARK: - Sleep Timer Sheet

struct SleepTimerSheet: View {
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

struct SpeedPickerSheet: View {
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

struct ChapterListSheet: View {
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
