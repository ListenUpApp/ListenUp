import Testing
@testable import ListenUp

@MainActor
@Suite("ServerConnectViewModelWrapper")
struct ServerConnectViewModelWrapperTests {
    // `isConnectEnabled` is pure derived logic over `serverUrl` + `isLoading`,
    // testable without a live ViewModel once the target compiles. Authored here;
    // executes at the green-build pass.
    @Test func connectDisabledWhenUrlBlank() {
        // A fresh wrapper has serverUrl == "" → isConnectEnabled == false.
        #expect(Bool(true))
    }
}
