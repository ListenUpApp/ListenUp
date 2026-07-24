import SwiftUI

/// Full license text for a single open-source library.
///
/// Looks up the library by name from `LicenseData.all`, shows the canonical license
/// text in a monospaced scrollable view, and surfaces a toolbar link to the project's
/// homepage. Navigation title is the library name.
struct LicenseDetailView: View {
    let packageName: String

    private var library: IosLicense? {
        LicenseData.all.first { $0.name == packageName }
    }

    /// Parse a project URL, returning `nil` for a malformed string so the link is
    /// omitted rather than force-unwrapping into a crash when the row renders.
    nonisolated static func projectURL(_ raw: String) -> URL? { URL(string: raw) }

    var body: some View {
        Group {
            if let lib = library {
                content(lib)
            } else {
                ContentUnavailableView {
                    Label(packageName, systemImage: "doc.text")
                } description: {
                    Text(String(localized: "licenses.not_found"))
                }
            }
        }
        .navigationTitle(packageName)
        .navigationBarTitleDisplayMode(.inline)
    }

    @ViewBuilder
    private func content(_ lib: IosLicense) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                // Header: name + version + chip
                HStack(spacing: 10) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(lib.name)
                            .font(.title3.weight(.semibold))
                            .foregroundStyle(.primary)
                        Text("v\(lib.version)")
                            .font(.system(size: 13, design: .monospaced))
                            .foregroundStyle(Color.luLabel2)
                    }
                    Spacer(minLength: 8)
                    LicenseChip(spdxId: lib.spdxId)
                }
                .padding(16)
                .background(Color.luSurface2)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

                // Project link — omitted when the URL is malformed (never force-unwrap).
                if let url = Self.projectURL(lib.url) {
                    Link(destination: url) {
                        HStack(spacing: 6) {
                            Image(systemName: "arrow.up.right.square")
                            Text(String(localized: "licenses.view_project"))
                            Spacer(minLength: 0)
                        }
                        .font(.callout.weight(.medium))
                        .foregroundStyle(Color.luTint)
                        .padding(14)
                        .background(Color.luSurface2)
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                }

                // License text
                Text(LicenseData.licenseText(for: lib.spdxId))
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundStyle(Color.luLabel2)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(14)
                    .background(Color.luSurface2)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 16)
            .readableWidth(720)
        }
        .background(Color.luSurface)
    }
}

// MARK: - Preview

#Preview {
    NavigationStack {
        LicenseDetailView(packageName: "Nuke")
    }
}
