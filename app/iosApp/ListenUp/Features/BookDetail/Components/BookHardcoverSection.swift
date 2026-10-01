import SwiftUI

/// Book Detail's Hardcover section, under Readers: the Hardcover book this one is matched to and
/// where it stands, with Change Match and Remove Match; or Needs a Match, with Find on Hardcover.
/// Nothing at all when hidden, as Readers is when it has nothing to say.
///
/// The section heading follows `BookReadersSection`'s, so the two read as siblings. Change Match is
/// the quiet bordered action and Remove Match a plain destructive one beside it; Needs a Match has a
/// single thing to do, so Find on Hardcover is prominent. HIG, Buttons: "use a more prominent button
/// style for that option and a less prominent style for the remaining ones". Remove Match confirms
/// first (the caller's dialog): it parks the book's syncing until a new match is picked.
struct BookHardcoverSection: View {
    let phase: BookHardcoverPhase
    let onFindMatch: () -> Void
    let onRemoveMatch: () -> Void

    var body: some View {
        switch phase {
        case .hidden:
            EmptyView()
        case .needsMatch:
            VStack(alignment: .leading, spacing: Spacing.s) {
                header(String(localized: "hardcover.book_row_title"))
                card {
                    VStack(alignment: .leading, spacing: Spacing.s) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(String(localized: "hardcover.book_row_needs_match"))
                                .font(.body.weight(.semibold))
                            Text(String(localized: "hardcover.book_row_needs_match_detail"))
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                        .accessibilityElement(children: .combine)
                        Button(String(localized: "hardcover.find_on_hardcover").titleStyled, action: onFindMatch)
                            .buttonStyle(.borderedProminent)
                            .buttonBorderShape(.capsule)
                            .onBrandFillLabel()
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        case .linked(let model):
            VStack(alignment: .leading, spacing: Spacing.s) {
                header(String(localized: "hardcover.book_row_on_hardcover"))
                card {
                    VStack(alignment: .leading, spacing: Spacing.s) {
                        linkedSummary(model)
                        Divider()
                        // Side by side while they fit; stacked at the large accessibility sizes, so
                        // neither label ever breaks mid-word.
                        ViewThatFits(in: .horizontal) {
                            HStack(spacing: Spacing.m) { linkedActions(fitted: true) }
                            VStack(alignment: .leading, spacing: Spacing.s) { linkedActions(fitted: false) }
                        }
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    /// Change Match and Remove Match. `fitted` holds each label on one line, so the side-by-side
    /// arrangement only fits when both do; stacked, a label may wrap rather than run off the card.
    @ViewBuilder
    private func linkedActions(fitted: Bool) -> some View {
        Button(String(localized: "hardcover.book_row_change_match").titleStyled, action: onFindMatch)
            .buttonStyle(.bordered)
            .buttonBorderShape(.capsule)
            .fixedSize(horizontal: fitted, vertical: false)
        Button(String(localized: "hardcover.match_remove").titleStyled, role: .destructive, action: onRemoveMatch)
            .buttonStyle(.borderless)
            .fixedSize(horizontal: fitted, vertical: false)
    }

    private func linkedSummary(_ model: BookHardcoverLinkedModel) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(model.title)
                .font(.body.weight(.semibold))
            if let byline = model.byline {
                Text(byline)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            if model.chosenByYou {
                Text(String(localized: "hardcover.book_row_chosen_by_you"))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            Label {
                Text(model.status.text)
            } icon: {
                Image(systemName: model.status.systemImage)
                    .foregroundStyle(tint(for: model.status.tone))
            }
            .font(.subheadline)
            .foregroundStyle(model.status.tone == .caution ? Color.luWarning : Color.secondary)
            .padding(.top, Spacing.xxs)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    private func tint(for tone: BookHardcoverStatus.Tone) -> Color {
        switch tone {
        case .settled: .green
        case .quiet: .secondary
        case .caution: .luWarning
        }
    }

    private func header(_ title: String) -> some View {
        Text(title)
            .font(.headline)
            .accessibilityAddTraits(.isHeader)
    }

    private func card<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        content()
            .padding(Spacing.m)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: Radius.l))
    }
}

// MARK: - Preview

#Preview("Hardcover section") {
    ScrollView {
        VStack(spacing: Spacing.xl) {
            BookHardcoverSection(
                phase: .linked(BookHardcoverLinkedModel(
                    title: "Project Hail Mary",
                    byline: "Andy Weir · 2021",
                    chosenByYou: true,
                    status: BookHardcoverObserver.status(for: .removedOnHardcover)
                )),
                onFindMatch: {},
                onRemoveMatch: {}
            )
            BookHardcoverSection(phase: .needsMatch, onFindMatch: {}, onRemoveMatch: {})
        }
        .padding()
    }
}
