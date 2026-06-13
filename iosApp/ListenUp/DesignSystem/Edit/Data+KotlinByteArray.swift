import Foundation
@preconcurrency import Shared

extension Data {
    /// Bridges to a Kotlin `ByteArray` (exported as `KotlinByteArray`) via a single bulk
    /// `memcpy` crossing — fast for multi-MB images (no per-byte bridge round-trips).
    func toKotlinByteArray() -> KotlinByteArray {
        NSDataByteArrayKt.byteArrayFromNSData(data: self as NSData)
    }
}
