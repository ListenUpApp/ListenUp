import SwiftUI

/// Book Detail's Hardcover section, under Readers: the Hardcover book this one is matched to and
/// where it stands, with Change Match and Remove Match; or Needs a Match, with Find on Hardcover.
/// Nothing at all when hidden, as Readers is when it has nothing to say.
///
/// The section heading follows `BookReadersSection`'s, so the two read as siblings. Change Match is
/// the filled action and Remove Match a plain destructive one beside it; Needs a Match has a
/// single thing to do, so Find on Hardcover is prominent. HIG, Buttons: "use a more prominent button
/// style for that option and a less prominent style for the remaining ones". Remove Match confirms
/// first (the caller's dialog): it parks the book's syncing until a new match is picked.
///
/// Sync with Hardcover (#1541) heads the card in every state; kept off, the card holds only it, with a
/// footer saying what that means. A book never matched gets the card with only the Toggle, on.
struct BookHardcoverSection: View {
    let phase: BookHardcoverPhase
    let onFindMatch: () -> Void
    let onRemoveMatch: () -> Void
    /// Sync with Hardcover flipped: off keeps the book off Hardcover, on syncs it again.
    let onSetSynced: (Bool) -> Void
    /// Switching it off would take something visible out of ListenUp: the caller asks first, with this message.
    let onConfirmKeepOff: (String) -> Void

    var body: some View {
        switch phase {
        case .hidden:
            EmptyView()
        case .unmatched:
            VStack(alignment: .leading, spacing: Spacing.s) {
                header(String(localized: "hardcover.book_row_title"))
                card { syncToggle(keepOffMessage: nil) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        case .needsMatch:
            VStack(alignment: .leading, spacing: Spacing.s) {
                header(String(localized: "hardcover.book_row_title"))
                card {
                    VStack(alignment: .leading, spacing: Spacing.s) {
                        syncToggle(keepOffMessage: nil)
                        Divider()
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
                            .controlSize(.large)
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
                        syncToggle(keepOffMessage: model.keepOffMessage)
                        Divider()
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
        case .keptOff(let isResuming):
            VStack(alignment: .leading, spacing: Spacing.s) {
                header(String(localized: "hardcover.book_row_title"))
                card { syncToggle(keepOffMessage: nil) }
                // A footer under the toggle, as the HIG places explanatory text for one.
                if !isResuming {
                    Text(String(localized: "hardcover.kept_off_line"))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, Spacing.m)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    /// Sync with Hardcover, the section's first row (#1541). HIG, Toggles: a toggle "lets people choose between a pair
    /// of opposing states". Switching off goes to the caller's confirmation when `keepOffMessage` says something
    /// visible would leave; the binding still reads the phase, so the Toggle stays on behind the dialog.
    private func syncToggle(keepOffMessage: String?) -> some View {
        Toggle(
            String(localized: "hardcover.keep_off_switch"),
            isOn: Binding(
                get: { phase.isSyncOn },
                set: { isOn in
                    if !isOn, let keepOffMessage {
                        onConfirmKeepOff(keepOffMessage)
                    } else {
                        onSetSynced(isOn)
                    }
                }
            )
        )
        .font(.body.weight(.semibold))
    }

    /// Change Match and Remove Match. `fitted` holds each label on one line, so the side-by-side
    /// arrangement only fits when both do; stacked, a label may wrap rather than run off the card.
    ///
    /// Both reach 44 points tall (HIG, Accessibility: "at least 44x44 pt"), and both labels clear 4.5:1 on
    /// the card. The tint on its own tinted fill was 3.27:1, so Change Match is the filled capsule with the
    /// on-brand label; system red on the card was 3.20:1, so Remove Match keeps a red glyph and sets its
    /// words in the primary colour — the glyph and the destructive role still mark it.
    @ViewBuilder
    private func linkedActions(fitted: Bool) -> some View {
        Button(String(localized: "hardcover.book_row_change_match").titleStyled, action: onFindMatch)
            .buttonStyle(.borderedProminent)
            .buttonBorderShape(.capsule)
            .controlSize(.large)
            .onBrandFillLabel()
            .fixedSize(horizontal: fitted, vertical: false)
        Button(role: .destructive, action: onRemoveMatch) {
            HStack(spacing: Spacing.xs) {
                Image(systemName: "minus.circle")
                    .foregroundStyle(.red)
                    .accessibilityHidden(true)
                Text(String(localized: "hardcover.match_remove").titleStyled)
                    .foregroundStyle(Color.primary)
            }
            .frame(minHeight: TapTarget.minimum)
            .contentShape(Rectangle())
        }
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
                onRemoveMatch: {},
                onSetSynced: { _ in },
                onConfirmKeepOff: { _ in }
            )
            BookHardcoverSection(
                phase: .needsMatch,
                onFindMatch: {}, onRemoveMatch: {}, onSetSynced: { _ in }, onConfirmKeepOff: { _ in }
            )
            BookHardcoverSection(
                phase: .keptOff(isResuming: false),
                onFindMatch: {}, onRemoveMatch: {}, onSetSynced: { _ in }, onConfirmKeepOff: { _ in }
            )
            BookHardcoverSection(
                phase: .unmatched, onFindMatch: {}, onRemoveMatch: {}, onSetSynced: { _ in }, onConfirmKeepOff: { _ in }
            )
        }
        .padding()
    }
}
