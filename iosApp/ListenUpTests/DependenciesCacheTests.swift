import os
import Testing
@testable import ListenUp

@Suite("Locked cache")
struct DependenciesCacheTests {
    @Test func concurrentResolvesAreRaceFree() async {
        let cache = LockedCache()
        await withTaskGroup(of: Int.self) { group in
            for i in 0..<1000 {
                group.addTask { cache.resolve(key: "k\(i % 8)") { i } }
            }
            for await _ in group {}
        }
        #expect(cache.count <= 8)
    }
}
