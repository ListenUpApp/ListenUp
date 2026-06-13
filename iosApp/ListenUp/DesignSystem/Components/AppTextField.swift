import SwiftUI

/// The one canonical, surface-less text-field primitive for ListenUp. Every screen —
/// auth, edit forms, search — composes its fields from this. It owns the chrome a field
/// row needs (leading SF Symbol, secure eye-toggle, search clear button, inline error
/// caption, hairline separator, keyboard config) but renders **no background of its own**:
/// the caller wraps it in `AuthFieldGroup`, `FieldGroup`, or `.fieldCard()` to supply the
/// inset surface, so the same primitive reads correctly in every container.
///
/// Three flavours via `Kind`:
/// - `.secure` — password entry with an eye/eye-slash reveal toggle (defaults to a `lock` icon).
/// - `.search` — query entry with a trailing clear button shown only when non-empty
///   (defaults to a `magnifyingglass` icon).
/// - `.text` — plain entry (no default icon).
///
/// Layout adapts to `label`: with a `label` it's the edit-card column (caption above the
/// field); without one it's the inset-row layout (inline field) used by auth and search.
struct AppTextField: View {
    enum Kind { case text, secure, search }

    let placeholder: String
    @Binding var text: String
    var label: String?
    var icon: String?
    var kind: Kind = .text
    var error: String?
    var axis: Axis = .horizontal
    var isLast: Bool = true
    var keyboardType: UIKeyboardType = .default
    var textContentType: UITextContentType?
    var autocapitalization: TextInputAutocapitalization = .never
    var submitLabel: SubmitLabel?
    var onSubmit: () -> Void = {}

    @State private var isSecure = true
    @Environment(\.displayScale) private var displayScale
    private var hairline: CGFloat { 1 / max(displayScale, 1) }

    /// Search fields default the return key to `.search`; otherwise an explicit value wins,
    /// falling back to `.return`.
    private var effectiveSubmitLabel: SubmitLabel {
        Self.defaultsToSearchSubmitLabel(explicit: submitLabel, kind: kind) ? .search : (submitLabel ?? .return)
    }

    // MARK: - Pure layout helpers (unit-tested)

    /// The leading SF Symbol to render: an explicit `icon` always wins; otherwise the
    /// `Kind` supplies a sensible default (lock for secure, magnifyingglass for search).
    nonisolated static func leadingIcon(explicit: String?, kind: Kind) -> String? {
        if let explicit { return explicit }
        switch kind {
        case .secure: return "lock"
        case .search: return "magnifyingglass"
        case .text: return nil
        }
    }

    /// The trailing clear button appears only for a search field with text to clear.
    nonisolated static func showsClearButton(kind: Kind, text: String) -> Bool {
        kind == .search && !text.isEmpty
    }

    /// A stable a11y/UI-test identifier derived from the placeholder.
    nonisolated static func accessibilityIdentifier(placeholder: String) -> String {
        "\(placeholder.lowercased().replacingOccurrences(of: " ", with: "_"))_field"
    }

    /// Whether the keyboard return key should default to the `.search` label: true only
    /// for a search field with no explicit `submitLabel`. (`SubmitLabel` isn't `Equatable`,
    /// so the decision is exposed as a predicate the view and tests share.)
    nonisolated static func defaultsToSearchSubmitLabel(explicit: SubmitLabel?, kind: Kind) -> Bool {
        explicit == nil && kind == .search
    }

    // MARK: - Body

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let label {
                VStack(alignment: .leading, spacing: 4) {
                    labelView(label)
                    rowView
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
            } else {
                rowView
                    .frame(minHeight: 52)
                    .padding(.horizontal, 14)
            }

            if !isLast { separator }
            if let error { errorCaption(error) }
        }
    }

    // MARK: - Subviews

    private func labelView(_ label: String) -> some View {
        Text(label)
            .font(.caption)
            .foregroundStyle(Color.luLabel2)
    }

    private var rowView: some View {
        HStack(spacing: 12) {
            if let leading = Self.leadingIcon(explicit: icon, kind: kind) {
                Image(systemName: leading)
                    .font(.body)
                    .foregroundStyle(error != nil ? .red : Color.luLabel2)
                    .frame(width: 22)
            }
            control
            accessory
        }
    }

    private var control: some View {
        rawControl
            .font(.body)
            .foregroundStyle(.primary)
            .lineLimit(axis == .vertical ? 3 ... 8 : 1 ... 1)
            .keyboardType(keyboardType)
            .textContentType(textContentType)
            .textInputAutocapitalization(autocapitalization)
            .autocorrectionDisabled()
            .submitLabel(effectiveSubmitLabel)
            .onSubmit(onSubmit)
            .accessibilityLabel(placeholder)
            .accessibilityIdentifier(Self.accessibilityIdentifier(placeholder: placeholder))
    }

    @ViewBuilder
    private var rawControl: some View {
        switch kind {
        case .secure:
            if isSecure {
                SecureField(placeholder, text: $text)
            } else {
                TextField(placeholder, text: $text)
            }
        case .search, .text:
            TextField(placeholder, text: $text, axis: axis)
        }
    }

    @ViewBuilder
    private var accessory: some View {
        switch kind {
        case .secure:
            Button { isSecure.toggle() } label: {
                Image(systemName: isSecure ? "eye.slash" : "eye")
                    .foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(isSecure ? "Show password" : "Hide password")
            .accessibilityHint("Double tap to \(isSecure ? "reveal" : "hide") password")
        case .search:
            if Self.showsClearButton(kind: kind, text: text) {
                Button { text = "" } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
            }
        case .text:
            EmptyView()
        }
    }

    private var separator: some View {
        Rectangle()
            .fill(Color.luSeparator)
            .frame(height: hairline)
            .padding(.leading, Self.leadingIcon(explicit: icon, kind: kind) == nil ? 14 : 46)
    }

    private func errorCaption(_ message: String) -> some View {
        HStack(spacing: 4) {
            Image(systemName: "exclamationmark.circle.fill")
            Text(message)
        }
        .font(.caption)
        .foregroundStyle(.red)
        .padding(.horizontal, 14)
        .padding(.bottom, 8)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Error: \(message)")
        .accessibilityAddTraits(.isStaticText)
    }
}

#Preview("AppTextField") {
    @Previewable @State var email = ""
    @Previewable @State var password = "hunter2"
    @Previewable @State var name = "The Stormlight Archive"
    @Previewable @State var desc = ""
    @Previewable @State var query = "dune"

    return ScrollView {
        VStack(spacing: 16) {
            AuthFieldGroup {
                AppTextField(placeholder: "Email", text: $email, icon: "envelope", isLast: false)
                AppTextField(placeholder: "Password", text: $password, kind: .secure)
            }

            AppTextField(placeholder: "Name", text: $name, label: "Name")
                .fieldCard()

            AppTextField(
                placeholder: "Add a description",
                text: $desc,
                label: "Description",
                axis: .vertical
            )
            .fieldCard()

            AppTextField(placeholder: "Search", text: $query, kind: .search)
                .fieldCard()

            AppTextField(placeholder: "Email", text: $email, icon: "envelope", error: "That doesn't look right.")
                .fieldCard()
        }
        .padding()
    }
    .background(Color.luSurface)
}
