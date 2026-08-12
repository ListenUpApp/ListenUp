import Foundation

/// Hex-encodes an APNs device token for hand-off to the shared push registrar.
///
/// A truncated or malformed encoding here registers cleanly, gets reported invalid by the
/// relay, and the token is deleted forever — a silent total-failure mode with no crash and no
/// error to surface. Pulled out as a pure function so that failure mode has a test.
func hexEncodedAPNsToken(_ data: Data) -> String {
    data.map { String(format: "%02x", $0) }.joined()
}
