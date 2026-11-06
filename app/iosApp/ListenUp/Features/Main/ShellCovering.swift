import SwiftUI

/// What the library-readiness gate draws over (or instead of) the tab shell.
enum ShellCovering: Equatable {
    case none
    case launch
    case setup
    case populating
}

/// The readiness gate's decision: whether the tab shell is in the tree, and what covers it.
struct ShellCoveringLayout: Equatable {
    let showsShell: Bool
    let covering: ShellCovering

    /// Before the shell has ever mounted (a cold launch, a first-run admin) the gate screens stand in
    /// for it — there is no point building a shell for a setup wizard. Once it has mounted, it stays:
    /// a readiness re-check covers it instead of replacing it, so Now Playing, open sheets and scroll
    /// positions survive (Android lays its checking surface over a mounted shell the same way).
    static func layout(for phase: LibraryReadinessPhase, shellHasMounted: Bool) -> Self {
        let covering: ShellCovering
        switch phase {
        case .ready, .checkFailed: return Self(showsShell: true, covering: .none)
        case .checking: covering = .launch
        case .needsSetup: covering = .setup
        case .populating: covering = .populating
        }
        return Self(showsShell: shellHasMounted, covering: covering)
    }
}

/// Draws the tab shell and, over it, the gate screen the phase calls for. The shell is always the
/// first child of one `ZStack`, never a branch of a `switch`, so its identity is the same through
/// every phase change and its state is kept.
struct ShellCoveringHost<Shell: View, Cover: View>: View {
    let phase: LibraryReadinessPhase
    @ViewBuilder let shell: () -> Shell
    @ViewBuilder let cover: (ShellCovering) -> Cover

    @State private var shellHasMounted = false

    var body: some View {
        let layout = ShellCoveringLayout.layout(for: phase, shellHasMounted: shellHasMounted)
        let isCovered = layout.covering != .none
        ZStack {
            if layout.showsShell {
                shell()
                    .onAppear { shellHasMounted = true }
                    .accessibilityHidden(isCovered)
                    .allowsHitTesting(!isCovered)
            }
            if isCovered {
                cover(layout.covering)
                    .background(Color(.systemBackground).ignoresSafeArea())
            }
        }
    }
}
