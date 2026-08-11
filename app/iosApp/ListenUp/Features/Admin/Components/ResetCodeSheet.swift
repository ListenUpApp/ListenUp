import SwiftUI

/// The one-time password-reset code, shown after an admin approves a request.
///
/// The code appears here and nowhere else — never in a list, never re-fetchable — so the sheet
/// is presented with `interactiveDismissDisabled()` and only the **Done** button (which calls
/// `dismissResetCode()`) releases it. The instruction copy tells the admin to convey the code
/// out of band, never back through the app.
struct ResetCodeSheet: View {
    let code: String
    let recipientName: String?
    let onCopy: () -> Void
    let onDone: () -> Void

    private var recipient: String {
        recipientName ?? String(localized: "admin.reset_code_recipient_fallback")
    }

    var body: some View {
        VStack(spacing: 24) {
            Image(systemName: "key.horizontal.fill")
                .font(.system(size: 40))
                .foregroundStyle(Color.luTint)
                .accessibilityHidden(true)
                .padding(.top, 36)

            Text(String(format: String(localized: "admin.reset_code_title"), recipient))
                .font(.title3.weight(.semibold))
                .multilineTextAlignment(.center)

            Text(code)
                .font(.system(.largeTitle, design: .monospaced).weight(.bold))
                .kerning(2)
                .padding(.horizontal, 24)
                .padding(.vertical, 14)
                .background(RoundedRectangle(cornerRadius: 14).fill(Color.luFill))
                .textSelection(.enabled)

            Text(String(format: String(localized: "admin.reset_code_instruction"), recipient))
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 28)

            Spacer(minLength: 0)

            VStack(spacing: 10) {
                Button(action: onCopy) {
                    Label(String(localized: "common.copy"), systemImage: "doc.on.doc")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .controlSize(.large)

                Button(action: onDone) {
                    Text(String(localized: "admin.reset_code_done"))
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(Color.luTint)
                .controlSize(.large)
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 20)
        }
        .presentationDetents([.medium])
    }
}

#Preview("Reset code") {
    Color.clear.sheet(isPresented: .constant(true)) {
        ResetCodeSheet(code: "ABCD-2345", recipientName: "Alex", onCopy: {}, onDone: {})
    }
}
