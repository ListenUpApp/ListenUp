import Foundation
import Testing
@testable import ListenUp

/// Pins [hexEncodedAPNsToken], the pure hex encoder AppDelegate hands the raw APNs device token
/// to. A truncated or malformed encoding here would register cleanly, get reported invalid by
/// the relay, and delete the token forever — a silent total-failure mode this test exists to
/// catch (see PushHexToken.swift).
@Suite("APNs hex token encoding")
struct PushTokenEncodingTests {
    @Test func emptyDataEncodesToAnEmptyString() {
        #expect(hexEncodedAPNsToken(Data()).isEmpty)
    }

    @Test func encodesEachByteAsTwoLowercaseHexDigits() {
        #expect(hexEncodedAPNsToken(Data([0x00, 0x0F, 0xA0, 0xFF])) == "000fa0ff")
    }

    @Test func lengthIsExactlyTwiceTheByteCount() {
        let data = Data((0..<32).map { UInt8($0) })
        #expect(hexEncodedAPNsToken(data).count == data.count * 2)
    }

    @Test func everyCharacterIsALowercaseHexDigit() {
        let data = Data((0...255).map { UInt8($0) })
        let hex = hexEncodedAPNsToken(data)
        #expect(hex.allSatisfy { "0123456789abcdef".contains($0) })
    }
}
