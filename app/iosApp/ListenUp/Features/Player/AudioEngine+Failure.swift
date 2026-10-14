import AVFoundation

extension AudioEngine {
    /// The item's failure, typed once (spec §4.6): network domains are a dropped connection, AVFoundation's
    /// format and decoder codes are an unsupported format, anything else could not start.
    nonisolated static func failure(for error: NSError?) -> EngineFailure {
        guard let error else { return .couldNotStart(detail: nil) }
        let detail = "\(error.domain) \(error.code)"
        if error.domain == NSURLErrorDomain { return .connectionLost(detail: detail) }
        let formatCodes: Set<Int> = [
            AVError.Code.fileFormatNotRecognized.rawValue,
            AVError.Code.decoderNotFound.rawValue,
            AVError.Code.decodeFailed.rawValue,
            AVError.Code.undecodableMediaData.rawValue
        ]
        if error.domain == AVFoundationErrorDomain, formatCodes.contains(error.code) {
            return .unsupportedFormat(detail: detail)
        }
        return .couldNotStart(detail: detail)
    }
}
