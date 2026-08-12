import SwiftUI

/// The pieces the forgot-password flow needs and nothing else in the app does yet.
///
/// A self-hosted server has no mail transport, so a person approves the request and reads the
/// requester a code out of band. Every view here exists to make that premise visible: what will
/// happen, where the request has got to, and how much room for error is left.
///
/// No admin is ever named. Several people may hold the role, and the waiting state is the one a
/// request for an unrecognised address also reaches — naming someone there would separate real
/// accounts from unknown ones at a glance.

// MARK: - Status mark

/// A tinted disc behind an SF Symbol, for the full-screen terminal states.
///
/// The shipped screens used a bare 44pt glyph, which reads as an icon dropped on a page rather
/// than the subject of it. A disc gives a terminal state the presence it needs, and the tint
/// carries the outcome before any copy is read.
struct ForgotPasswordMark: View {
    enum Tone { case waiting, bad, good }

    let symbol: String
    var tone: Tone = .waiting

    @ScaledMetric(relativeTo: .largeTitle) private var diameter: CGFloat = 104

    private var color: Color {
        switch tone {
        case .waiting: Color.listenUpOrange
        case .bad: .red
        case .good: .green
        }
    }

    var body: some View {
        Circle()
            .fill(color.opacity(0.13))
            .frame(width: diameter, height: diameter)
            .overlay {
                Image(systemName: symbol)
                    .font(.system(size: diameter * 0.42, weight: .medium)) // decorative fixed size
                    .foregroundStyle(color)
            }
            .accessibilityHidden(true)
    }
}

// MARK: - How this works

/// The three steps, stated before the request is sent.
///
/// The single biggest gap in the shipped flow: without this you wait at a screen with no
/// explanation and are then asked for a code that was never mentioned. Carries no identity — this
/// screen is reachable by anyone signed out, and no account has been matched to the typed address.
struct ForgotPasswordHowItWorks: View {
    let steps: [String]

    var body: some View {
        VStack(alignment: .leading, spacing: 11) {
            Text(String(localized: "auth.forgot_password_how_it_works"))
                .font(.caption.weight(.semibold))
                .textCase(.uppercase)
                .foregroundStyle(.secondary)
                .padding(.bottom, 2)

            ForEach(Array(steps.enumerated()), id: \.offset) { index, step in
                HStack(alignment: .firstTextBaseline, spacing: 11) {
                    Text("\(index + 1)")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(Color.listenUpOrange)
                        .frame(width: 21, height: 21)
                        .background(Color.listenUpOrange.opacity(0.14), in: .circle)
                    Text(step)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 0)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(.background.secondary, in: .rect(cornerRadius: 16))
    }
}

// MARK: - Code entry

/// The reset code as six separate boxes.
///
/// Someone is reading this code aloud to the requester, so the characters are grouped rather than
/// run together in one field — easier to key, and far easier to re-check against what you were
/// just told. One real field sits behind the boxes so there is a single cursor, one keyboard
/// session and working paste.
///
/// Input is folded to the alphabet the server's own `normalize()` accepts — upper-cased, anything
/// outside `[0-9A-Z]` dropped — so a spoken-and-typed `K4M9-TQ` arrives as the code the admin read
/// out. A separator where the dash goes is the likeliest mistranscription, and rejecting it would
/// fail exactly the person this flow exists to rescue.
struct ForgotPasswordCodeField: View {
    @Binding var code: String
    var isError: Bool = false

    @FocusState private var isFocused: Bool
    @ScaledMetric(relativeTo: .title2) private var boxWidth: CGFloat = 44
    @ScaledMetric(relativeTo: .title2) private var boxHeight: CGFloat = 56

    static let length = 6

    private var characters: [Character?] {
        let typed = Array(code)
        return (0..<Self.length).map { $0 < typed.count ? typed[$0] : nil }
    }

    var body: some View {
        ZStack {
            // The real field, invisible but focusable — the boxes are its presentation.
            TextField("", text: $code)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
                .keyboardType(.asciiCapable)
                .textContentType(.oneTimeCode)
                .focused($isFocused)
                .opacity(0.001)
                .onChange(of: code) { _, newValue in
                    let folded = newValue.uppercased().filter { $0.isASCII && ($0.isNumber || $0.isLetter) }
                    let clipped = String(folded.prefix(Self.length))
                    if clipped != newValue { code = clipped }
                }

            HStack(spacing: 8) {
                ForEach(0..<Self.length, id: \.self) { index in
                    if index == Self.length / 2 {
                        Capsule()
                            .fill(.tertiary)
                            .frame(width: 11, height: 2)
                    }
                    box(at: index)
                }
            }
            .allowsHitTesting(false)
        }
        .contentShape(.rect)
        .onTapGesture { isFocused = true }
        .accessibilityElement()
        .accessibilityLabel(String(localized: "invite.enter_code"))
        .accessibilityValue(code.isEmpty ? "" : code.map(String.init).joined(separator: " "))
        .accessibilityAddTraits(.isKeyboardKey)
    }

    private func box(at index: Int) -> some View {
        let character = characters[index]
        let isNext = isFocused && index == code.count
        let accent: Color = isError ? .red : Color.listenUpOrange
        return RoundedRectangle(cornerRadius: 12)
            .fill(.background.secondary)
            .frame(width: boxWidth, height: boxHeight)
            .overlay {
                RoundedRectangle(cornerRadius: 12)
                    .strokeBorder(character != nil || isNext ? accent : .clear, lineWidth: 2)
            }
            .overlay {
                if let character {
                    Text(String(character))
                        .font(.title2.weight(.semibold))
                        .foregroundStyle(isError ? .red : .primary)
                }
            }
    }
}

// MARK: - Attempts

/// The remaining attempt budget, stated only once it is worth stating.
///
/// The shipped screen painted every count red, which spends the alarm before it means anything —
/// by the time one attempt is left there is nothing louder to say. A comfortable budget says
/// nothing, a shrinking one is a plain note, and only the last one is an error worth explaining.
struct ForgotPasswordAttempts: View {
    let remaining: Int?

    /// Below this the budget is comfortable enough not to be worth mentioning.
    static let worthMentioning = 4

    var body: some View {
        if let remaining, remaining < Self.worthMentioning {
            let isLast = remaining <= 1
            HStack(alignment: .firstTextBaseline, spacing: 7) {
                Image(systemName: isLast ? "exclamationmark.triangle.fill" : "info.circle")
                    .font(.footnote.weight(.semibold))
                Text(
                    isLast
                        ? String(localized: "auth.forgot_password_attempts_one")
                        : String(format: String(localized: "auth.forgot_password_attempts"), remaining)
                )
                .font(.footnote.weight(.semibold))
                .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }
            .foregroundStyle(isLast ? .red : .orange)
        }
    }
}

// MARK: - Timeline

/// Where the request has got to, in a flow that otherwise gives no sense of its length.
struct ForgotPasswordTimeline: View {
    /// Index of the step currently being waited on.
    let activeStep: Int

    private var steps: [String] {
        [
            String(localized: "auth.forgot_password_step_sent"),
            String(localized: "auth.forgot_password_step_approve"),
            String(localized: "auth.forgot_password_step_set"),
        ]
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(steps.enumerated()), id: \.offset) { index, step in
                HStack(alignment: .top, spacing: 12) {
                    VStack(spacing: 0) {
                        marker(for: index)
                        if index < steps.count - 1 {
                            Rectangle()
                                .fill(index < activeStep ? Color.listenUpOrange : Color.secondary.opacity(0.25))
                                .frame(width: 2, height: 22)
                        }
                    }
                    Text(step)
                        .font(.footnote.weight(index == activeStep ? .semibold : .regular))
                        .foregroundStyle(colour(for: index))
                        .padding(.bottom, 12)
                    Spacer(minLength: 0)
                }
            }
        }
        .accessibilityElement(children: .combine)
    }

    private func marker(for index: Int) -> some View {
        ZStack {
            Circle()
                .fill(
                    index < activeStep
                        ? AnyShapeStyle(Color.listenUpOrange)
                        : index == activeStep
                            ? AnyShapeStyle(Color.listenUpOrange.opacity(0.16))
                            : AnyShapeStyle(Color.secondary.opacity(0.18))
                )
                .frame(width: 20, height: 20)
            if index < activeStep {
                Image(systemName: "checkmark")
                    .font(.system(size: 11, weight: .bold)) // decorative fixed size
                    .foregroundStyle(.white)
            } else {
                Circle()
                    .fill(index == activeStep ? Color.listenUpOrange : Color.secondary.opacity(0.5))
                    .frame(width: 6, height: 6)
            }
        }
    }

    private func colour(for index: Int) -> HierarchicalShapeStyle {
        index == activeStep ? .primary : index < activeStep ? .secondary : .tertiary
    }
}
