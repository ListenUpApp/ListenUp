import Testing
@testable import ListenUp
@preconcurrency import Shared

/// The CarPlay Library wrapper OWNS its ViewModel and closes it on disconnect (#1192). If it
/// borrowed the phone shell's memoized `Dependencies.shared.libraryViewModel` instead, a CarPlay
/// disconnect would cancel that shared instance's `viewModelScope` out from under the phone's
/// Library screen — visible as a permanently frozen library after unplugging from a car. Guards
/// that CarPlay always gets its own fresh instance from the Koin `factory` binding.
@Suite("CarPlay library wrapper ownership")
@MainActor
struct CarPlayLibraryWrapperTests {
    @Test func carPlayGetsAFreshViewModelNotTheSharedOne() {
        let shared = Dependencies.shared.libraryViewModel
        let carPlayVM = Dependencies.shared.makeLibraryViewModel()
        #expect(carPlayVM !== shared)
        #expect(Dependencies.shared.makeLibraryViewModel() !== carPlayVM)
    }
}
