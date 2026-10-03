import SwiftUI

/// Admin → Hardcover (#1542): the write-only API token, where to get one, and the "Hardcover metadata"
/// switch, as one Form section under Rating sources (HIG, Lists and tables). The token is typed into this
/// view's own `@State` — never the ViewModel, never saved state — handed to the observer once on Save,
/// and cleared the moment Hardcover accepts it. Remove is destructive, so it asks first.
struct HardcoverSourceSection: View {
    let model: HardcoverSourceModel
    let onSave: (String) -> Void
    let onRemove: () -> Void
    let onMetadataEnabledChange: (Bool) -> Void
    let onClearError: () -> Void

    @State private var draft = ""
    @State private var isReplacing = false
    @State private var isConfirmingRemove = false

    /// What VoiceOver hears as the token is checked: that checking began (Save retitles and disables, while
    /// focus stays put), then how it ended — the refusal, a rejection, or the saved line. HIG, Feedback:
    /// communicate the result of an action; HIG, VoiceOver: say what changes without a focus change.
    static func announcement(from old: HardcoverSourceModel, to new: HardcoverSourceModel) -> String? {
        if new.isBusy, !old.isBusy { return String(localized: "admin.hardcover_token_checking") }
        if let refusal = new.refusal, refusal != old.refusal { return refusal }
        if case .rejected = new.token, old.token != new.token {
            return String(localized: "admin.hardcover_token_rejected")
        }
        if let saved = new.savedLine, old.isBusy, !new.isBusy { return saved }
        return nil
    }

    var body: some View {
        Section {
            tokenRows
            ToggleRow(
                systemImage: "sparkles",
                title: String(localized: "admin.hardcover_metadata_title"),
                subtitle: model.metadataSubtitle,
                isOn: Binding(get: { model.metadataEnabled }, set: { onMetadataEnabledChange($0) })
            )
        } header: {
            Text(String(localized: "rating.source_hardcover"))
        } footer: {
            VStack(alignment: .leading, spacing: Spacing.xs) {
                Text(String(localized: "admin.hardcover_hint"))
                if let url = HardcoverSourceModel.apiPage {
                    Link(String(localized: "admin.hardcover_token_get"), destination: url)
                }
            }
        }
        .onChange(of: model.token) { _, token in
            if case .saved = token {
                draft = ""
                isReplacing = false
            }
        }
        .onChange(of: model) { old, new in
            if let announcement = Self.announcement(from: old, to: new) {
                AccessibilityNotification.Announcement(announcement).post()
            }
        }
        .confirmationDialog(
            String(localized: "admin.hardcover_token_remove_title"),
            isPresented: $isConfirmingRemove,
            titleVisibility: .visible
        ) {
            Button(String(localized: "common.remove"), role: .destructive) { onRemove() }
            Button(String(localized: "common.cancel"), role: .cancel) {}
        } message: {
            Text(String(localized: "admin.hardcover_token_remove_body"))
        }
    }

    @ViewBuilder
    private var tokenRows: some View {
        if let savedLine = model.savedLine, !isReplacing {
            LabeledContent(String(localized: "admin.hardcover_token_label"), value: savedLine)
            // Named for what they act on, so they make sense out of context (the rotor, Voice Control);
            // Voice Control still answers to the word on screen. HIG, Accessibility.
            Button(String(localized: "admin.hardcover_token_replace")) { isReplacing = true }
                .accessibilityLabel(String(localized: "admin.hardcover_token_replace_label"))
                .accessibilityInputLabels([
                    String(localized: "admin.hardcover_token_replace"),
                    String(localized: "admin.hardcover_token_replace_label")
                ])
            Button(String(localized: "common.remove"), role: .destructive) { isConfirmingRemove = true }
                .disabled(model.isBusy)
                .accessibilityLabel(String(localized: "admin.hardcover_token_remove_label"))
                .accessibilityInputLabels([
                    String(localized: "common.remove"),
                    String(localized: "admin.hardcover_token_remove_label")
                ])
        } else {
            if case .rejected = model.token {
                // The glyph red, the sentence primary: system red on white is 3.57:1 (HIG, Accessibility).
                Label {
                    Text(String(localized: "admin.hardcover_token_rejected"))
                        .foregroundStyle(Color.primary)
                } icon: {
                    Image(systemName: "exclamationmark.triangle")
                        .foregroundStyle(.red)
                }
            }
            AppTextField(
                placeholder: String(localized: "admin.hardcover_token_placeholder"),
                text: $draft,
                entry: .secret,
                label: String(localized: "admin.hardcover_token_label"),
                icon: "key",
                kind: .secure,
                error: model.refusal,
                submitLabel: .done,
                onSubmit: save
            )
            .onChange(of: draft) {
                if model.refusal != nil { onClearError() }
            }
            Button(
                model.isBusy
                    ? String(localized: "admin.hardcover_token_checking")
                    : String(localized: "admin.hardcover_token_save"),
                action: save
            )
            .disabled(model.isBusy || draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
    }

    private func save() {
        let token = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !token.isEmpty, !model.isBusy else { return }
        onSave(token)
    }
}
