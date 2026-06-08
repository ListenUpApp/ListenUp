import SwiftUI

/// iOS inset-grouped container — an opaque rounded surface holding one or more field
/// rows with hairline separators. The iOS-forms hallmark; concentric inside `AuthCard`.
struct AuthFieldGroup<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        VStack(spacing: 0) { content }
            .background(
                RoundedRectangle(cornerRadius: AuthMetrics.fieldGroupCornerRadius, style: .continuous)
                    .fill(Color(.tertiarySystemGroupedBackground))
            )
            .clipShape(RoundedRectangle(cornerRadius: AuthMetrics.fieldGroupCornerRadius, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: AuthMetrics.fieldGroupCornerRadius, style: .continuous)
                    .strokeBorder(Color.primary.opacity(0.06), lineWidth: 0.5)
            )
    }
}

/// A single editable row inside an `AuthFieldGroup`: leading SF Symbol, the text field,
/// and a hairline bottom separator unless it's the last row.
struct AuthFieldRow: View {
    var icon: String?
    var placeholder: String
    @Binding var text: String
    var error: String?
    var isLast: Bool = false
    var keyboardType: UIKeyboardType = .default
    var textContentType: UITextContentType?
    var autocapitalization: TextInputAutocapitalization = .never
    var onSubmit: () -> Void = {}

    var body: some View {
        AuthRowContainer(icon: icon, isLast: isLast, hasError: error != nil) {
            TextField(placeholder, text: $text)
                .keyboardType(keyboardType)
                .textContentType(textContentType)
                .textInputAutocapitalization(autocapitalization)
                .autocorrectionDisabled()
                .onSubmit(onSubmit)
                .accessibilityLabel(placeholder)
                .accessibilityHint(placeholder)
                .accessibilityIdentifier("\(placeholder.lowercased().replacingOccurrences(of: " ", with: "_"))_field")
        } accessory: { EmptyView() }
        .authRowError(error)
    }
}

/// A secure (password) row with an eye/eye-slash reveal toggle.
struct AuthSecureFieldRow: View {
    var icon: String? = "lock"
    var placeholder: String
    @Binding var text: String
    var error: String?
    var isLast: Bool = false
    var textContentType: UITextContentType?
    var onSubmit: () -> Void = {}

    @State private var isSecure = true

    var body: some View {
        AuthRowContainer(icon: icon, isLast: isLast, hasError: error != nil) {
            Group {
                if isSecure {
                    SecureField(placeholder, text: $text)
                } else {
                    TextField(placeholder, text: $text)
                }
            }
            .textContentType(textContentType)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .onSubmit(onSubmit)
            .accessibilityLabel(placeholder)
        } accessory: {
            Button { isSecure.toggle() } label: {
                Image(systemName: isSecure ? "eye.slash" : "eye")
                    .foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(isSecure ? "Show password" : "Hide password")
            .accessibilityHint("Double tap to \(isSecure ? "reveal" : "hide") password")
        }
        .authRowError(error)
    }
}

// MARK: - Shared row chrome

/// Lays out a field row's icon + content + accessory + separator. Internal to the kit.
private struct AuthRowContainer<Content: View, Accessory: View>: View {
    var icon: String?
    var isLast: Bool
    var hasError: Bool
    @ViewBuilder var content: Content
    @ViewBuilder var accessory: Accessory

    var body: some View {
        HStack(spacing: 12) {
            if let icon {
                Image(systemName: icon)
                    .font(.body)
                    .foregroundStyle(hasError ? Color.red : .secondary)
                    .frame(width: 22)
            }
            content
                .font(.body)
                .foregroundStyle(.primary)
            accessory
        }
        .frame(minHeight: 52)
        .padding(.horizontal, 14)
        .overlay(alignment: .bottom) {
            if !isLast {
                Rectangle()
                    .fill(Color.primary.opacity(0.10))
                    .frame(height: 0.5)
                    .padding(.leading, icon == nil ? 14 : 46)
            }
        }
    }
}

private extension View {
    /// Appends an inline error caption beneath a field row when `message` is non-nil.
    @ViewBuilder
    func authRowError(_ message: String?) -> some View {
        if let message {
            VStack(alignment: .leading, spacing: 0) {
                self
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
        } else {
            self
        }
    }
}
