import SwiftUI

/// The hero's social line (spec §2.10): up to three overlapping avatars and one sentence, read as one element, that
/// brings the Readers section into view. The avatars are hidden from VoiceOver: the sentence names the readers.
struct HeroSocialLineView: View {
    let line: ReadersSocialLine
    let ringColor: Color
    let onOpenReaders: () -> Void

    var body: some View {
        Button(action: onOpenReaders) {
            HStack(spacing: 10) {
                HStack(spacing: -8) {
                    ForEach(Array(zip(line.avatarUserIds.prefix(3), line.avatarNames.prefix(3))), id: \.0) { id, name in
                        UserAvatarView(userId: id, fallbackName: name, size: 28)
                            .overlay(Circle().stroke(ringColor, lineWidth: 2))
                    }
                }
                .accessibilityHidden(true)
                Text(Self.attributed(line))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Self.sentence(line))
        .accessibilityAddTraits(.isButton)
        .accessibilityHint(String(localized: "book.detail_social_hint"))
    }

    /// "Maya and Priya are listening · Tom finished it": two names a clause, each clause counting its own overflow.
    static func sentence(_ line: ReadersSocialLine) -> String {
        let listening = listeningClause(line.listening)
        let finished = finishedClause(line.finished)
        switch (listening, finished) {
        case let (listening?, finished?):
            return String(format: String(localized: "book.detail_social_join"), listening, finished)
        case let (listening?, nil):
            return listening
        case let (nil, finished?):
            return finished
        default:
            return ""
        }
    }

    // One switch per clause, over literal keys: `verifySwiftStringKeys` only sees a key written inside
    // `String(localized:)`, so a helper taking the key as a variable would hide these from the gate.
    private static func listeningClause(_ names: [String]) -> String? {
        switch names.count {
        case 0: nil
        case 1: String(format: String(localized: "book.detail_social_listening_one"), names[0])
        case 2: String(format: String(localized: "book.detail_social_listening_two"), names[0], names[1])
        case 3: String(format: String(localized: "book.detail_social_listening_many_one"), names[0], names[1])
        default:
            String(format: String(localized: "book.detail_social_listening_many"), names[0], names[1], names.count - 2)
        }
    }

    private static func finishedClause(_ names: [String]) -> String? {
        switch names.count {
        case 0: nil
        case 1: String(format: String(localized: "book.detail_social_finished_one"), names[0])
        case 2: String(format: String(localized: "book.detail_social_finished_two"), names[0], names[1])
        case 3: String(format: String(localized: "book.detail_social_finished_many_one"), names[0], names[1])
        default:
            String(format: String(localized: "book.detail_social_finished_many"), names[0], names[1], names.count - 2)
        }
    }

    /// The sentence with each named reader in the primary colour and semibold.
    private static func attributed(_ line: ReadersSocialLine) -> AttributedString {
        var text = AttributedString(sentence(line))
        var searchFrom = text.startIndex
        for name in line.listening.prefix(2) + line.finished.prefix(2) {
            guard let range = text[searchFrom...].range(of: name) else { continue }
            text[range].font = .subheadline.weight(.semibold)
            text[range].foregroundColor = .primary
            searchFrom = range.upperBound
        }
        return text
    }
}

// MARK: - Preview

#Preview("Social line") {
    HeroSocialLineView(
        line: ReadersSocialLine(listening: ["Maya", "Priya"], finished: ["Tom"], avatarUserIds: ["m", "p", "t"],
                                othersCount: 0),
        ringColor: Color(.secondarySystemBackground),
        onOpenReaders: {}
    )
    .padding()
}
