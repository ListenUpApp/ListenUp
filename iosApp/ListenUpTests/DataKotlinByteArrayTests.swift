import Foundation
import Testing
@preconcurrency import Shared
@testable import ListenUp

@Suite("Data+KotlinByteArray")
struct DataKotlinByteArrayTests {
    @Test func roundTripsBytesIncludingSignBoundary() {
        let data = Data([0x00, 0x7F, 0x80, 0xFF, 0x42])
        let kba = data.toKotlinByteArray()
        #expect(kba.size == 5)
        var back = Data()
        for i in 0 ..< kba.size {
            back.append(UInt8(bitPattern: kba.get(index: i)))
        }
        #expect(back == data)
    }

    @Test func emptyDataYieldsEmptyArray() {
        #expect(Data().toKotlinByteArray().size == 0)
    }
}
