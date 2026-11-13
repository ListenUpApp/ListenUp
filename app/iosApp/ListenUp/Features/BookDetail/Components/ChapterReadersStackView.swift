import SwiftUI

/// The readers on one place in the chapter list: up to three 24 pt avatars overlapping by 6, or two beside a "+N"
/// disc, each ringed in the row's colour so the overlap reads as separate faces, as the hero's social line stacks them.
/// Someone listening right now gets a still coral ring set 2 pt off their avatar, with a breathing glow behind it.
///
/// Decorative and not tappable: the row it sits in says the same thing to VoiceOver as its value.
struct ChapterReadersStackView: View {
    let stack: ChapterReaderStack
    /// The row's background, which the ring between overlapping avatars matches.
    var ringColor = Color(.secondarySystemGroupedBackground)

    static let avatarSize: CGFloat = 24
    private static let overlap: CGFloat = 6

    var body: some View {
        HStack(spacing: -Self.overlap) {
            // The first face sits on top, as the boards stack them.
            ForEach(Array(stack.faces.enumerated()), id: \.element.id) { index, face in
                ChapterReaderAvatar(face: face, ringColor: ringColor)
                    .zIndex(Double(stack.faces.count - index + 1))
            }
            if stack.overflowCount > 0 {
                overflowDisc
            }
        }
        .accessibilityHidden(true)
    }

    private var overflowDisc: some View {
        Circle()
            .fill(Color(.systemGray5))
            .frame(width: Self.avatarSize, height: Self.avatarSize)
            .overlay {
                Text(verbatim: "+\(stack.overflowCount.formatted())")
                    // Sized to the fixed circle, like the avatars' initials.
                    .font(.system(size: 10, weight: .semibold)) // decorative fixed size
                    // Not `.secondary`: on "All N Chapters" that would take the button's coral.
                    .foregroundStyle(Color(.secondaryLabel))
                    .minimumScaleFactor(0.7)
                    .lineLimit(1)
            }
            .background { Circle().fill(ringColor).padding(-2) }
    }
}

/// One face in the stack. The live ring fades in and out over 0.2 s as someone starts or stops listening.
private struct ChapterReaderAvatar: View {
    let face: ChapterReaderFaceRow
    let ringColor: Color

    var body: some View {
        UserAvatarView(userId: face.id, fallbackName: face.displayName, size: ChapterReadersStackView.avatarSize)
            // A 2 pt ring in the row's colour: on a live face it is also the gap before the coral ring.
            .background { Circle().fill(ringColor).padding(-2) }
            .background {
                if face.isLive {
                    BreathingGlowView()
                        .transition(.opacity)
                }
            }
            .overlay {
                Circle()
                    .strokeBorder(Color.listenUpOrange, lineWidth: 2)
                    .padding(-4)
                    .opacity(face.isLive ? 1 : 0)
            }
            .animation(.easeInOut(duration: 0.2), value: face.isLive)
    }
}

/// The glow behind a live avatar. It is decoration: the coral ring already says "listening now", so with Reduce
/// Motion on it holds still rather than breathing (HIG, Motion: make motion optional).
struct BreathingGlowView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        if reduceMotion {
            glow(BreathingGlow.still)
        } else {
            TimelineView(.animation) { context in
                glow(BreathingGlow.frame(at: context.date))
            }
        }
    }

    private func glow(_ frame: BreathingGlow.Frame) -> some View {
        Circle()
            .fill(Color.listenUpOrange)
            .scaleEffect(frame.scale)
            .opacity(frame.opacity)
    }
}

/// The breath: a 2 s cycle of 1.6 s swelling from scale 1.0 to 1.7 while fading from 0.45 to nothing on the
/// emphasized-decelerate curve, then a 0.4 s rest. Every glow reads its phase off the same clock, the time since the
/// reference date, so however many are on screen and whenever each appeared, they breathe in step.
enum BreathingGlow {
    struct Frame: Equatable {
        let scale: Double
        let opacity: Double
    }

    static let cycle: TimeInterval = 2.0
    static let breath: TimeInterval = 1.6
    static let restingScale = 1.7
    /// Reduce Motion's glow: held at a third of the way out, faint.
    static let still = Frame(scale: 1.35, opacity: 0.22)

    private static let startOpacity = 0.45
    /// M3 emphasized decelerate, `cubic-bezier(0.05, 0.7, 0.1, 1)`: a quick swell that settles out.
    private static let curve = UnitCurve.bezier(
        startControlPoint: UnitPoint(x: 0.05, y: 0.7),
        endControlPoint: UnitPoint(x: 0.1, y: 1)
    )

    /// The glow at `date`, on the shared clock.
    static func frame(at date: Date) -> Frame {
        frame(atPhase: date.timeIntervalSinceReferenceDate)
    }

    /// The glow `seconds` into the shared clock; whole cycles fall away.
    static func frame(atPhase seconds: TimeInterval) -> Frame {
        let phase = seconds.truncatingRemainder(dividingBy: cycle)
        let into = phase < 0 ? phase + cycle : phase
        guard into < breath else { return Frame(scale: restingScale, opacity: 0) }
        let progress = curve.value(at: into / breath)
        return Frame(scale: 1 + (restingScale - 1) * progress, opacity: startOpacity * (1 - progress))
    }
}

// MARK: - Preview

#Preview("Chapter readers") {
    VStack(alignment: .trailing, spacing: 24) {
        ChapterReadersStackView(stack: ChapterReaderStack(
            place: .thisChapter,
            faces: [ChapterReaderFaceRow(id: "j", displayName: "Jonah", isLive: false)],
            overflowCount: 0, listeningNow: nil, notListening: nil
        ))
        ChapterReadersStackView(stack: ChapterReaderStack(
            place: .thisChapter,
            faces: [ChapterReaderFaceRow(id: "m", displayName: "Maya", isLive: true),
                    ChapterReaderFaceRow(id: "t", displayName: "Tom", isLive: false)],
            overflowCount: 0, listeningNow: nil, notListening: nil
        ))
        ChapterReadersStackView(stack: ChapterReaderStack(
            place: .thisChapter,
            faces: [ChapterReaderFaceRow(id: "p", displayName: "Priya", isLive: false),
                    ChapterReaderFaceRow(id: "t", displayName: "Tom", isLive: false)],
            overflowCount: 2, listeningNow: nil, notListening: nil
        ))
    }
    .padding(Spacing.xl)
    .background(Color(.secondarySystemGroupedBackground))
}
