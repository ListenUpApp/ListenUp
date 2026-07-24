import SwiftUI

/// Text view with "Read more" / "Show less" toggle.
///
/// Collapses long text to a specified line limit with an expandable toggle button.
///
/// Usage:
/// ```swift
/// ExpandableText(
///     title: "Synopsis",
///     text: book.description,
///     lineLimit: 4
/// )
/// ```
struct ExpandableText: View {
    let title: String?
    let text: String
    var lineLimit: Int = 4
    var minimumLengthForToggle: Int = 150

    @State private var isExpanded = false

    /// Full initializer
    init(title: String? = nil, text: String, lineLimit: Int = 4, minimumLengthForToggle: Int = 150) {
        self.title = title
        self.text = text
        self.lineLimit = lineLimit
        self.minimumLengthForToggle = minimumLengthForToggle
    }

    /// Bottom alpha-fade for the clamped (collapsed) description, matching the Android preview.
    /// A no-op opaque mask when expanded or when the text is too short to be truncated.
    @ViewBuilder
    private var descriptionMask: some View {
        if !isExpanded && text.count > minimumLengthForToggle {
            LinearGradient(
                stops: [
                    .init(color: .black, location: 0),
                    .init(color: .black, location: 0.8),
                    .init(color: .clear, location: 1.0)
                ],
                startPoint: .top,
                endPoint: .bottom
            )
        } else {
            Color.black
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let title {
                Text(title)
                    .font(.headline)
            }

            Text(AttributedString.fromBookMarkdown(text))
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .lineLimit(isExpanded ? nil : lineLimit)
                .mask { descriptionMask }

            if text.count > minimumLengthForToggle {
                Button(isExpanded ? "Show less" : "Read more") {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        isExpanded.toggle()
                    }
                }
                .font(.subheadline.weight(.medium))
                .foregroundStyle(Color.listenUpOrange)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - Preview

#Preview("Long Text") {
    ExpandableText(
        title: "Synopsis",
        text: "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor "
            + "incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud "
            + "exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure "
            + "dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur."
    )
    .padding()
}

#Preview("Short Text - No Toggle") {
    ExpandableText(
        title: "About",
        text: "A short description that doesn't need a toggle."
    )
    .padding()
}

#Preview("No Title") {
    ExpandableText(
        text: "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor "
            + "incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud "
            + "exercitation ullamco laboris."
    )
    .padding()
}
