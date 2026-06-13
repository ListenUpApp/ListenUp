import Foundation
@preconcurrency import Shared

extension Data {
    /// Bridges to a Kotlin `ByteArray` (exported as `KotlinByteArray`) for VM image uploads.
    /// Kotlin `Byte` is signed (`Int8`); reinterpret each unsigned byte's bit pattern.
    func toKotlinByteArray() -> KotlinByteArray {
        let array = KotlinByteArray(size: Int32(count))
        for (index, byte) in enumerated() {
            array.set(index: Int32(index), value: Int8(bitPattern: byte))
        }
        return array
    }
}
