import SwiftUI

/// Text input field with Liquid Glass aesthetic.
///
/// Features:
/// - Ultra-thin material background for glass effect
/// - Subtle border for definition
/// - Label and error state support
/// - Keyboard configuration
/// - Accessible by default
///
/// Usage:
/// ```swift
/// GlassTextField(
///     label: "Server URL",
///     placeholder: "example.com",
///     text: $viewModel.serverUrl,
///     error: viewModel.error
/// )
/// ```
struct GlassTextField: View {
    let label: String
    let placeholder: String
    @Binding var text: String
    var error: String?
    var keyboardType: UIKeyboardType
    var textContentType: UITextContentType?
    var autocapitalization: TextInputAutocapitalization
    var onSubmit: () -> Void

    init(
        label: String,
        placeholder: String,
        text: Binding<String>,
        error: String? = nil,
        keyboardType: UIKeyboardType = .default,
        textContentType: UITextContentType? = nil,
        autocapitalization: TextInputAutocapitalization = .never,
        onSubmit: @escaping () -> Void = {}
    ) {
        self.label = label
        self.placeholder = placeholder
        self._text = text
        self.error = error
        self.keyboardType = keyboardType
        self.textContentType = textContentType
        self.autocapitalization = autocapitalization
        self.onSubmit = onSubmit
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            // Label
            Text(label)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.primary)

            // Text field with glass background
            TextField(placeholder, text: $text)
                .padding(16)
                .foregroundStyle(.primary)
                .background {
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(.ultraThinMaterial)
                        .overlay {
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .strokeBorder(
                                    error != nil ? Color.red.opacity(0.5) : Color.primary.opacity(0.1),
                                    lineWidth: 1
                                )
                        }
                }
                .keyboardType(keyboardType)
                .textContentType(textContentType)
                .textInputAutocapitalization(autocapitalization)
                .autocorrectionDisabled()
                .onSubmit(onSubmit)
                // Accessibility
                .accessibilityLabel(label)
                .accessibilityHint(placeholder)
                .accessibilityIdentifier("\(label.lowercased().replacingOccurrences(of: " ", with: "_"))_field")

            // Error message
            if let error {
                HStack(spacing: 4) {
                    Image(systemName: "exclamationmark.circle.fill")
                        .font(.caption)
                    Text(error)
                        .font(.caption)
                }
                .foregroundStyle(.red)
                .accessibilityElement(children: .combine)
                .accessibilityLabel("Error: \(error)")
                .accessibilityAddTraits(.isStaticText)
            }
        }
    }
}

// MARK: - Secure Field Variant

/// Secure text input field for passwords with Liquid Glass aesthetic.
struct GlassSecureField: View {
    let label: String
    let placeholder: String
    @Binding var text: String
    var error: String?
    var onSubmit: () -> Void

    @State private var isSecure: Bool = true

    init(
        label: String,
        placeholder: String,
        text: Binding<String>,
        error: String? = nil,
        onSubmit: @escaping () -> Void = {}
    ) {
        self.label = label
        self.placeholder = placeholder
        self._text = text
        self.error = error
        self.onSubmit = onSubmit
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            // Label
            Text(label)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.primary)

            // Secure field with glass background and toggle
            HStack {
                Group {
                    if isSecure {
                        SecureField(placeholder, text: $text)
                    } else {
                        TextField(placeholder, text: $text)
                    }
                }
                .foregroundStyle(.primary)
                .accessibilityLabel(label)
                .accessibilityHint(placeholder)

                Button {
                    isSecure.toggle()
                } label: {
                    Image(systemName: isSecure ? "eye.slash" : "eye")
                        .foregroundStyle(.secondary)
                }
                .accessibilityLabel(isSecure ? "Show password" : "Hide password")
                .accessibilityHint("Double tap to \(isSecure ? "reveal" : "hide") password")
            }
            .padding(16)
            .background {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(.ultraThinMaterial)
                    .overlay {
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .strokeBorder(
                                error != nil ? Color.red.opacity(0.5) : Color.primary.opacity(0.1),
                                lineWidth: 1
                            )
                    }
            }
            .onSubmit(onSubmit)

            // Error message
            if let error {
                HStack(spacing: 4) {
                    Image(systemName: "exclamationmark.circle.fill")
                        .font(.caption)
                    Text(error)
                        .font(.caption)
                }
                .foregroundStyle(.red)
                .accessibilityElement(children: .combine)
                .accessibilityLabel("Error: \(error)")
                .accessibilityAddTraits(.isStaticText)
            }
        }
    }
}

// MARK: - Previews

#Preview("Text Field - Default") {
    @Previewable @State var text = ""

    VStack(spacing: 20) {
        GlassTextField(
            label: "Server URL",
            placeholder: "example.com or 10.0.2.2:8080",
            text: $text
        )

        GlassTextField(
            label: "Username",
            placeholder: "Enter your username",
            text: $text
        )
    }
    .padding()
}

#Preview("Text Field - With Error") {
    @Previewable @State var text = "invalid url"

    GlassTextField(
        label: "Server URL",
        placeholder: "example.com",
        text: $text,
        error: "Invalid URL format"
    )
    .padding()
}

#Preview("Secure Field") {
    @Previewable @State var password = ""

    VStack(spacing: 20) {
        GlassSecureField(
            label: "Password",
            placeholder: "Enter your password",
            text: $password
        )

        GlassSecureField(
            label: "Password",
            placeholder: "Enter your password",
            text: $password,
            error: "Password must be at least 8 characters"
        )
    }
    .padding()
}

#Preview("Text Fields - In Context") {
    @Previewable @State var serverUrl = ""
    @Previewable @State var username = ""
    @Previewable @State var password = ""

    ZStack {
        // Gradient background
        LinearGradient(
            colors: [
                Color(hex: "8B3A3A"),
                Color(hex: "E8704A")
            ],
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
        .ignoresSafeArea()

        GlassCard {
            VStack(spacing: 24) {
                Text("Login")
                    .font(.largeTitle.bold())

                GlassTextField(
                    label: "Server URL",
                    placeholder: "example.com",
                    text: $serverUrl,
                    keyboardType: .URL,
                    textContentType: .URL
                )

                GlassTextField(
                    label: "Username",
                    placeholder: "Enter your username",
                    text: $username,
                    textContentType: .username
                )

                GlassSecureField(
                    label: "Password",
                    placeholder: "Enter your password",
                    text: $password
                )

                Spacer()

                ListenUpButton(title: "Login") {
                    print("Login tapped")
                }
            }
            .frame(height: 500)
        }
        .padding()
    }
}
