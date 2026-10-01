import SwiftUI
import Shared

/// The Hardcover screen while connected: who you are, the offer to send earlier books when there is one,
/// how sync stands, the books that need a match,
/// what ListenUp shares and what comes back, then Disconnect.
///
/// One grouped `Form`, a section per subject with the system's headers and footers. HIG, Lists and
/// tables: "the grouped style uses headers, footers, and additional space to separate groups of data".
struct HardcoverConnectedPhase: View {
    let model: HardcoverConnectedModel
    let onSyncNow: () -> Void
    let onSetShareMode: (HardcoverShareMode) -> Void
    let onSendHistory: () -> Void
    let onDismissHistory: () -> Void
    let onFindMatch: (String) -> Void
    let onDisconnect: () -> Void

    var body: some View {
        ScrollViewReader { proxy in
            Form {
                identitySection
                HardcoverHistorySection(
                    history: model.history,
                    onSend: onSendHistory,
                    onDismiss: onDismissHistory,
                    onShowNeedsMatch: { withAnimation { proxy.scrollTo(Self.needsMatchID, anchor: .top) } }
                )
                syncSection
                needsMatchSection
                    .id(Self.needsMatchID)
                whatIsSharedSection
                Section {
                    HardcoverStatementRow(
                        systemImage: "arrow.down.to.line",
                        text: String(localized: "hardcover.comes_back_line"),
                        tint: Color.secondary
                    )
                    HardcoverStatementRow(
                        systemImage: "bookmark",
                        text: String(localized: "hardcover.comes_back_want_to_read"),
                        tint: Color.secondary
                    )
                } header: {
                    Text(String(localized: "hardcover.what_comes_back"))
                } footer: {
                    Text(String(localized: "hardcover.comes_back_never_listening"))
                }
                disconnectSection
            }
            .readableListWidth(720)
            .onChange(of: model.sync) { _, sync in
                // The notice appears beneath the row the user just pressed; say it as well as show it.
                if case .syncNowFailed(let notice) = sync {
                    AccessibilityNotification.Announcement(notice).post()
                }
            }
        }
    }

    /// Where Done's "need a match" row scrolls to.
    private static let needsMatchID = "hardcover.needs-match"

    // MARK: - Identity

    private var identitySection: some View {
        Section {
            HStack(spacing: 14) {
                Circle()
                    .fill(Color.luTint)
                    .frame(width: 56, height: 56)
                    .overlay {
                        Text(model.username.prefix(1).uppercased())
                            .font(.title2.weight(.semibold))
                            .foregroundStyle(Color.luOnTint)
                    }
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(model.username)
                        .font(.headline)
                    Label(String(localized: "hardcover.connected"), systemImage: "checkmark")
                        .font(.subheadline)
                        .foregroundStyle(.green)
                    Text(String(format: String(localized: "hardcover.connected_since"), sinceText))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                .accessibilityElement(children: .combine)
            }
            .padding(.vertical, Spacing.xxs)
        }
    }

    private var sinceText: String {
        model.since.formatted(date: .long, time: .omitted)
    }

    // MARK: - Sync

    /// When it last synced, with Sync Now; a sync in progress; or a stuck push or pull in words, with
    /// Try Again. A Sync Now that didn't finish leaves the row as it was — pressing it again is the
    /// retry — and says so in the footer.
    @ViewBuilder
    private var syncSection: some View {
        Section {
            switch model.sync {
            case .idle, .syncNowFailed:
                lastSyncedRow
                syncNowButton(isEnabled: true)
            case .syncing:
                HStack(spacing: Spacing.s) {
                    ProgressView()
                    Text(String(localized: "hardcover.syncing"))
                }
                .accessibilityElement(children: .combine)
                syncNowButton(isEnabled: false)
            case .stalled(let words):
                stalledRow(words)
            }
            if case .available(let books) = model.history {
                HardcoverEarlierBooksRow(books: books, onSend: onSendHistory)
            }
        } header: {
            Text(String(localized: "hardcover.sync_section"))
        } footer: {
            if case .syncNowFailed(let notice) = model.sync {
                Label {
                    Text(notice)
                } icon: {
                    Image(systemName: "exclamationmark.triangle")
                        .foregroundStyle(.red)
                }
            }
        }
    }

    /// "Last synced 3 minutes ago", re-read every minute so "just now" ages honestly.
    private var lastSyncedRow: some View {
        TimelineView(.periodic(from: .now, by: 60)) { context in
            Label(
                HardcoverSettingsObserver.lastSyncedText(model.lastSyncedAt, now: context.date),
                systemImage: "clock"
            )
        }
    }

    private func syncNowButton(isEnabled: Bool) -> some View {
        Button(action: onSyncNow) {
            Label(String(localized: "hardcover.sync_now").titleStyled, systemImage: "arrow.triangle.2.circlepath")
                // Dimmed while a sync runs: a disabled Form button otherwise keeps a full-strength label.
                .foregroundStyle(isEnabled ? Color.luTint : Color.secondary)
        }
        .disabled(!isEnabled)
    }

    /// A push or pull that has stopped getting through. HIG, Progress indicators: "If a process stalls
    /// for some reason, provide feedback that helps people understand the problem and what they can
    /// do about it." The sentence says what is stuck; Try Again is the one prominent action (HIG,
    /// Buttons: "use a button that has a prominent visual style for the most likely action").
    private func stalledRow(_ words: String) -> some View {
        Label {
            VStack(alignment: .leading, spacing: Spacing.xs) {
                Text(words)
                TimelineView(.periodic(from: .now, by: 60)) { context in
                    Text(HardcoverSettingsObserver.lastSyncedText(model.lastSyncedAt, now: context.date))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Button(String(localized: "hardcover.try_again").titleStyled, action: onSyncNow)
                    .buttonStyle(.borderedProminent)
                    .buttonBorderShape(.capsule)
                    .onBrandFillLabel()
                    .padding(.top, Spacing.xxs)
            }
        } icon: {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(Color.luWarning)
        }
        .padding(.vertical, Spacing.xxs)
    }

    // MARK: - Needs a match

    /// The books ListenUp couldn't match, each opening Find on Hardcover; one quiet line once the
    /// server has said there are none; nothing at all before it has answered.
    @ViewBuilder
    private var needsMatchSection: some View {
        if !model.booksToMatch.isEmpty {
            Section {
                ForEach(model.booksToMatch) { book in
                    Button { onFindMatch(book.id) } label: {
                        BookToMatchRowView(book: book)
                    }
                }
            } header: {
                Text(String(localized: "hardcover.needs_match_section"))
            } footer: {
                Text(String(localized: "hardcover.needs_match_detail"))
            }
        } else if model.isMatchListKnown {
            Section(String(localized: "hardcover.needs_match_section")) {
                HardcoverStatementRow(
                    systemImage: "checkmark.circle",
                    text: String(localized: "hardcover.needs_match_none"),
                    tint: Color.secondary
                )
            }
        }
    }

    // MARK: - What ListenUp shares

    /// Update Hardcover, then what the chosen mode sends. A menu-style picker is a pop-up button — HIG,
    /// Pop-up buttons: "Use a pop-up button to present a flat list of mutually exclusive options or
    /// states", with "an introductory label … giving context to the options"; the rows beneath are the
    /// "explanatory text below the list" that says what each choice does. In a form it shows its label
    /// and the current value, and VoiceOver reads both. Disabled while a choice saves.
    private var whatIsSharedSection: some View {
        Section(String(localized: "hardcover.what_is_shared")) {
            Picker(
                String(localized: "hardcover.share_mode_label").titleStyled,
                selection: Binding(get: { model.shareMode }, set: { onSetShareMode($0) })
            ) {
                ForEach(Array(HardcoverShareMode.allCases), id: \.self) { mode in
                    Text(HardcoverSettingsObserver.shareModeLabel(mode)).tag(mode)
                }
            }
            .pickerStyle(.menu)
            .disabled(model.isSavingShareMode)
            .haptic(.selectionTick, trigger: model.shareMode)
            ForEach(HardcoverSettingsObserver.sharedLines(for: model.shareMode)) { line in
                HardcoverStatementRow(systemImage: line.systemImage, text: line.text, tint: Color.secondary)
                    .foregroundStyle(line.isQuiet ? Color.secondary : Color.primary)
            }
        }
    }

    // MARK: - Disconnect

    private var disconnectSection: some View {
        Section {
            Button(role: .destructive, action: onDisconnect) {
                HStack {
                    Spacer()
                    if model.isDisconnecting {
                        ProgressView()
                    } else {
                        Text(String(localized: "hardcover.disconnect"))
                    }
                    Spacer()
                }
            }
            .disabled(model.isDisconnecting)
        }
    }
}

/// A book that needs a match: its cover, title and author, and "Find on Hardcover" in the tint so
/// the row reads as the action it is.
private struct BookToMatchRowView: View {
    let book: HardcoverBookToMatchRow

    var body: some View {
        HStack(spacing: Spacing.s) {
            BookCoverImage(bookId: book.id, coverPath: book.coverPath, coverHash: book.coverHash)
                .frame(width: 40, height: 60)
                .clipShape(RoundedRectangle(cornerRadius: Radius.xs))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 1) {
                Text(book.title)
                    .font(.body)
                    .foregroundStyle(Color.primary)
                if !book.authorNames.isEmpty {
                    Text(book.authorNames)
                        .font(.subheadline)
                        .foregroundStyle(Color.secondary)
                }
                Text(String(localized: "hardcover.find_on_hardcover"))
                    .font(.subheadline)
                    .foregroundStyle(Color.luTint)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .accessibilityElement(children: .combine)
    }
}
