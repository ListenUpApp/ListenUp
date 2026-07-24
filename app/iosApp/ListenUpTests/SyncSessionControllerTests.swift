import Testing
@testable import ListenUp

@MainActor
struct SyncSessionControllerTests {
    @Test func connectAndResumeRunsBothActions() async {
        var connectCount = 0
        var resumeCount = 0
        let controller = SyncSessionController(
            connectRealtime: { connectCount += 1 },
            resumeDownloads: { resumeCount += 1 }
        )

        await controller.connectAndResume()

        #expect(connectCount == 1)
        #expect(resumeCount == 1)
    }

    @Test func connectRunsBeforeResume() async {
        var order: [String] = []
        let controller = SyncSessionController(
            connectRealtime: { order.append("connect") },
            resumeDownloads: { order.append("resume") }
        )

        await controller.connectAndResume()

        #expect(order == ["connect", "resume"])
    }
}
