import Foundation
@preconcurrency import Shared

extension Data {
    /// Bridges to a Kotlin `ByteArray` via a single bulk `memcpy` crossing — fast for
    /// multi-MB images (no per-byte bridge round-trips).
    func toKotlinByteArray() -> ExportedKotlinPackages.kotlin.ByteArray {
        ExportedKotlinPackages.com.calypsan.listenup.client.util.byteArrayFromNSData(data: self as NSData)
    }
}
