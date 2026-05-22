import Testing
@testable import ListenUp

@Suite("DiscoveredServerItem")
struct DiscoveredServerItemTests {
    @Test func hostPortFormats() {
        let item = DiscoveredServerItem(
            id: "s1", name: "Home", host: "192.168.1.10", port: 8080,
            version: "1.0", isOnline: true
        )
        #expect(item.hostPort == "192.168.1.10:8080")
    }

    @Test func equalityIsByValue() {
        let a = DiscoveredServerItem(id: "s1", name: "Home", host: "h", port: 1, version: "v", isOnline: true)
        let b = DiscoveredServerItem(id: "s1", name: "Home", host: "h", port: 1, version: "v", isOnline: true)
        #expect(a == b)
    }
}
