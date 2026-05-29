import ActivityKit
import AppIntents
import ListenUpActivityKit
import SwiftUI
import WidgetKit

// MARK: - Brand colour

private extension Color {
    static let listenUpOrange = Color(red: 1.0, green: 0.42, blue: 0.29) // #FF6B4A
}

// MARK: - Cover image

/// The book cover for the Live Activity. Reads the thumbnail the app wrote into
/// the App Group container; falls back to a branded placeholder.
private struct ActivityCover: View {
    let bookId: String
    var cornerRadius: CGFloat = 8

    private static let appGroupID = "group.com.calypsan.listenup.client"

    private var coverImage: UIImage? {
        guard let container = FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: Self.appGroupID) else { return nil }
        let url = container
            .appendingPathComponent("LiveActivityCovers", isDirectory: true)
            .appendingPathComponent("\(bookId).jpg")
        return UIImage(contentsOfFile: url.path)
    }

    var body: some View {
        Group {
            if let coverImage {
                Image(uiImage: coverImage).resizable().aspectRatio(contentMode: .fill)
            } else {
                ZStack {
                    Color.listenUpOrange.opacity(0.3)
                    Image(systemName: "book.closed.fill").foregroundStyle(Color.listenUpOrange)
                }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
    }
}

// MARK: - Control buttons

/// The three interactive controls — skip back, play/pause, skip forward.
private struct ControlButtons: View {
    let isPlaying: Bool

    var body: some View {
        HStack(spacing: 28) {
            Button(intent: SkipBackwardIntent()) {
                Image(systemName: "gobackward.15")
            }
            Button(intent: TogglePlaybackIntent()) {
                Image(systemName: isPlaying ? "pause.fill" : "play.fill")
            }
            Button(intent: SkipForwardIntent()) {
                Image(systemName: "goforward.15")
            }
        }
        .font(.title3)
        .foregroundStyle(.white)
        .buttonStyle(.plain)
    }
}

// MARK: - Lock-screen banner

/// The lock-screen / banner presentation. Earns its place beside the system Now
/// Playing control by showing chapter-level detail and ListenUp branding.
private struct LockScreenBanner: View {
    let context: ActivityViewContext<AudiobookActivityAttributes>

    var body: some View {
        VStack(spacing: 12) {
            HStack(spacing: 12) {
                ActivityCover(bookId: context.attributes.bookId)
                    .frame(width: 52, height: 52)

                VStack(alignment: .leading, spacing: 3) {
                    Text(context.attributes.bookTitle)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                    Text(context.state.chapterTitle)
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.7))
                        .lineLimit(1)
                    ProgressView(value: context.state.bookProgress)
                        .tint(Color.listenUpOrange)
                }
                Spacer(minLength: 0)
            }

            HStack {
                Text(context.state.elapsedDescription)
                Spacer()
                ControlButtons(isPlaying: context.state.isPlaying)
                Spacer()
                Text(context.state.remainingDescription)
            }
            .font(.caption2)
            .foregroundStyle(.white.opacity(0.7))
        }
        .padding(16)
        .activityBackgroundTint(Color.black.opacity(0.85))
    }
}

// MARK: - Progress ring (compact / minimal Dynamic Island)

private struct ProgressRing: View {
    let progress: Double
    var lineWidth: CGFloat = 2

    var body: some View {
        ZStack {
            Circle().stroke(Color.white.opacity(0.2), lineWidth: lineWidth)
            Circle()
                .trim(from: 0, to: progress)
                .stroke(Color.listenUpOrange, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                .rotationEffect(.degrees(-90))
        }
    }
}

// MARK: - Widget

struct AudiobookLiveActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: AudiobookActivityAttributes.self) { context in
            LockScreenBanner(context: context)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    ActivityCover(bookId: context.attributes.bookId)
                        .frame(width: 44, height: 44)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Text(context.state.remainingDescription)
                        .font(.caption2)
                        .foregroundStyle(.white.opacity(0.7))
                }
                DynamicIslandExpandedRegion(.center) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(context.attributes.bookTitle)
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.white)
                            .lineLimit(1)
                        Text(context.state.chapterTitle)
                            .font(.caption2)
                            .foregroundStyle(.white.opacity(0.7))
                            .lineLimit(1)
                    }
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(spacing: 8) {
                        ControlButtons(isPlaying: context.state.isPlaying)
                        ProgressView(value: context.state.chapterProgress)
                            .tint(Color.listenUpOrange)
                    }
                }
            } compactLeading: {
                Image(systemName: context.state.isPlaying ? "waveform" : "pause.fill")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(Color.listenUpOrange)
            } compactTrailing: {
                ProgressRing(progress: context.state.bookProgress)
                    .frame(width: 16, height: 16)
            } minimal: {
                ProgressRing(progress: context.state.bookProgress)
                    .frame(width: 16, height: 16)
            }
        }
    }
}
