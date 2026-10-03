import SwiftUI
import Shared

/// Five stars filled in halves from `halfStars` (2...10; 0 draws five empty stars).
///
/// Read-only when built with `init(halfStars:starSize:)`: small, for reader rows and summaries,
/// and read by VoiceOver as one element ("3.5 out of 5 stars"). Built with `onChange`, it's an
/// input: tap or drag across the stars to pick a half-star rating, with a selection tick for every
/// half crossed. VoiceOver hears a static "Rating" label, the value, and can swipe up or down to
/// step one half. Built with `onCommit` too, it saves as you go: `onChange` previews each half a
/// drag crosses, and `onCommit` receives the rating you settled on. Every count it shows or speaks goes through `ListenerRatingLimits.starsLabel`, so
/// iOS says what Android and web say.
struct RatingStarsView: View {
    let halfStars: Int
    let starSize: CGFloat
    private let onChange: ((Int) -> Void)?
    private let onCommit: ((Int) -> Void)?
    private let onCancel: (() -> Void)?

    @Environment(\.layoutDirection) private var layoutDirection
    /// The input's frame in global coordinates. Global space is never mirrored, so the touch maths
    /// can apply the RTL flip itself instead of trusting how the local space is laid out.
    @State private var globalFrame: CGRect = .zero

    /// Read-only stars.
    init(halfStars: Int, starSize: CGFloat = 13) {
        self.halfStars = halfStars
        self.starSize = starSize
        self.onChange = nil
        self.onCommit = nil
        self.onCancel = nil
    }

    /// Input stars; `onChange` receives the picked rating (2...10).
    init(halfStars: Int, starSize: CGFloat = 34, onChange: @escaping (Int) -> Void) {
        self.halfStars = halfStars
        self.starSize = starSize
        self.onChange = onChange
        self.onCommit = nil
        self.onCancel = nil
    }

    /// Input stars that save as you go: `onChange` previews every half a drag crosses, and `onCommit`
    /// receives the rating you settled on — where your finger lifts, or each VoiceOver adjustment.
    /// A mostly vertical swipe is someone scrolling the page past the stars, not rating: it saves
    /// nothing and calls `onCancel`, so the preview goes back to the saved rating.
    init(
        halfStars: Int,
        starSize: CGFloat,
        onChange: @escaping (Int) -> Void,
        onCommit: @escaping (Int) -> Void,
        onCancel: @escaping () -> Void
    ) {
        self.halfStars = halfStars
        self.starSize = starSize
        self.onChange = onChange
        self.onCommit = onCommit
        self.onCancel = onCancel
    }

    var body: some View {
        if let onChange {
            input(onChange)
        } else {
            stars(spacing: 1)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(Self.starsA11y(halfStars: halfStars))
        }
    }

    // MARK: - Drawing

    /// Read-only stars sit in a line of text, so they scale with it (HIG, Typography: icons that
    /// carry information stay easy to see at larger sizes). The input keeps its size: its touch
    /// maths divides a fixed width into five slots, and the sheet it lives in gives it the room.
    @ViewBuilder
    private func starGlyph(_ image: Image) -> some View {
        if onChange == nil {
            image.scaledFont(size: starSize, relativeTo: .footnote)
        } else {
            image.font(.system(size: starSize)) // decorative fixed size: the input's five fixed slots
        }
    }

    private func stars(spacing: CGFloat) -> some View {
        HStack(spacing: spacing) {
            ForEach(0..<Self.starCount, id: \.self) { index in
                let symbol = Self.symbol(halfStars: halfStars, index: index)
                starGlyph(Image(systemName: symbol))
                    .foregroundStyle(symbol == Self.emptySymbol ? Color.secondary : Color.listenUpOrange)
                    // Five equal slots, so a slot is exactly a fifth of the input's width — never narrower
                    // than Apple's 44 pt touch target.
                    .frame(width: onChange == nil ? nil : max(starSize * 1.3, Self.minInputHeight))
            }
        }
    }

    private func input(_ onChange: @escaping (Int) -> Void) -> some View {
        stars(spacing: 0)
            .frame(minHeight: Self.minInputHeight)
            .contentShape(Rectangle())
            .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { globalFrame = $0 }
            .gesture(
                DragGesture(minimumDistance: 0, coordinateSpace: .global)
                    .onChanged { value in
                        if onCommit != nil, Self.isScrollAttempt(value.translation) {
                            onCancel?()
                            return
                        }
                        let picked = pick(at: value.location.x)
                        if picked != halfStars { onChange(picked) }
                    }
                    .onEnded { value in
                        guard let onCommit else { return }
                        if Self.isScrollAttempt(value.translation) {
                            onCancel?()
                        } else {
                            onCommit(pick(at: value.location.x))
                        }
                    }
            )
            .haptic(.selectionTick, trigger: halfStars)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(String(localized: "rating.stars_label"))
            .accessibilityValue(Self.spokenValue(halfStars: halfStars))
            .accessibilityAdjustableAction { direction in
                let next: Int
                switch direction {
                case .increment: next = Self.stepped(halfStars, by: 1)
                case .decrement: next = Self.stepped(halfStars, by: -1)
                @unknown default: return
                }
                onChange(next)
                // No finger lifts under VoiceOver, so each step is the rating you settled on.
                onCommit?(next)
            }
    }

    private func pick(at globalX: CGFloat) -> Int {
        Self.halfStars(
            forX: globalX - globalFrame.minX,
            width: globalFrame.width,
            isRightToLeft: layoutDirection == .rightToLeft
        )
    }

    // MARK: - Pure helpers (unit-tested)

    nonisolated static let starCount = 5
    /// Apple's minimum touch target.
    static let minInputHeight: CGFloat = 44
    private static let emptySymbol = "star"

    nonisolated static var minHalfStars: Int { Int(ListenerRatingLimits.shared.MIN_HALF_STARS) }
    nonisolated static var maxHalfStars: Int { Int(ListenerRatingLimits.shared.MAX_HALF_STARS) }

    /// The number of stars every platform says for `halfStars` ("4", "3.5"); an average rounds to
    /// the nearest half.
    nonisolated static func starsLabel(_ halfStars: Double) -> String {
        ListenerRatingLimits.shared.starsLabel(halfStars: halfStars)
    }

    /// "3.5 out of 5 stars".
    nonisolated static func starsA11y(halfStars: Int) -> String {
        String(format: String(localized: "rating.stars_a11y"), starsLabel(Double(halfStars)))
    }

    /// The input's VoiceOver value: the stars, or "Not rated" before one is chosen.
    nonisolated static func spokenValue(halfStars: Int) -> String {
        halfStars < minHalfStars ? String(localized: "rating.stars_unrated") : starsA11y(halfStars: halfStars)
    }

    /// The SF Symbol for star `index` (0-based) of a `halfStars` rating.
    nonisolated static func symbol(halfStars: Int, index: Int) -> String {
        let starEnd = (index + 1) * 2
        if halfStars >= starEnd { return "star.fill" }
        if halfStars == starEnd - 1 { return "star.leadinghalf.filled" }
        return "star"
    }

    /// The rating a touch at `x` sets on five equal stars spanning `width`. The start half of a star
    /// counts half of it, the end half counts it whole; the start is the right edge in RTL. Clamped
    /// to one...five stars, so a touch at the very start still rates the book.
    nonisolated static func halfStars(forX x: CGFloat, width: CGFloat, isRightToLeft: Bool = false) -> Int {
        guard width > 0 else { return minHalfStars }
        let fromStart = isRightToLeft ? width - x : x
        let halves = Int((fromStart / (width / CGFloat(starCount)) * 2).rounded(.up))
        return min(max(halves, minHalfStars), maxHalfStars)
    }

    /// Whether a drag of `translation` is someone scrolling past the stars rather than rating: it has
    /// travelled further up or down than across, beyond a tap's wobble.
    nonisolated static func isScrollAttempt(_ translation: CGSize) -> Bool {
        abs(translation.height) > 12 && abs(translation.height) > abs(translation.width)
    }

    /// One VoiceOver step of `delta` halves. The first step up from unrated lands on one star; a
    /// step down from unrated stays unrated.
    nonisolated static func stepped(_ halfStars: Int, by delta: Int) -> Int {
        if halfStars < minHalfStars { return delta > 0 ? minHalfStars : halfStars }
        return min(max(halfStars + delta, minHalfStars), maxHalfStars)
    }
}

// MARK: - Preview

#Preview("RatingStarsView") {
    @Previewable @State var picked = 0
    VStack(spacing: 20) {
        RatingStarsView(halfStars: 7)
        RatingStarsView(halfStars: picked) { picked = $0 }
    }
    .padding()
}
