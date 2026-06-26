import SwiftUI

/// The "What Others Are Listening To" section: a section title and a horizontally scrolling
/// rail of people-and-their-current-book cards. One card per person (deduped upstream in
/// `CurrentlyListeningObserver`). Card width is width-driven by the caller so the rail reads
/// larger on iPad. Renders loading / ready / error from the observer's phase.
struct CurrentlyListeningSection: View {
    let phase: CurrentlyListeningPhase
    let cardWidth: CGFloat
    /// Horizontal inset so the rail's first/last cards align with the screen's content margin.
    let horizontalInset: CGFloat
    /// Screen-wide multi-select; `nil` disables selection for this rail.
    var selection: BookSelectionObserver?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(String(localized: "discover.what_others_are_listening_to"))
                .font(.title2.bold())
                .padding(.horizontal, horizontalInset)

            content
        }
    }

    @ViewBuilder
    private var content: some View {
        switch phase {
        case .loading:
            ProgressView()
                .frame(maxWidth: .infinity)
                .padding(.vertical, 24)
        case .ready(let rows):
            if rows.isEmpty {
                message(String(localized: "discover.no_one_listening_right_now"))
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 16) {
                        ForEach(rows) { row in
                            CurrentlyListeningCard(row: row, width: cardWidth, selection: selection)
                        }
                    }
                    .padding(.horizontal, horizontalInset)
                }
            }
        case .error:
            message(String(localized: "discover.no_one_listening_right_now"))
        }
    }

    private func message(_ text: String) -> some View {
        Text(text)
            .font(.subheadline)
            .foregroundStyle(.secondary)
            .padding(.horizontal, horizontalInset)
            .padding(.vertical, 12)
    }
}
