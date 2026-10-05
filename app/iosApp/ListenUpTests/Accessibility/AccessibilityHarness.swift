import Darwin
import MachO
import SwiftUI
import Testing
import UIKit
@testable import ListenUp

extension Trait where Self == ConditionTrait {
    /// Skips the harness suites on the CI runner only: the in-process accessibility tree and pixel capture
    /// pass and fail on the same Xcode there, test after test. They still run on a Mac at the desk. CI hands `CI` to the test process as `TEST_RUNNER_CI` (see ci.yml).
    static var flakyOnCI: Self {
        .disabled(
            if: false,
            "Flaky on the CI runner; tracked in https://github.com/ListenUpApp/ListenUp/issues/1578"
        )
    }
}

/// One stop VoiceOver would make, read back from a hosted view's accessibility tree.
struct AccessibilityStop: CustomStringConvertible {
    let label: String
    let value: String
    let hint: String
    let traits: UIAccessibilityTraits
    let inputLabels: [String]
    /// Window coordinates, in points.
    let frame: CGRect

    var isSelected: Bool { traits.contains(.selected) }
    var isButton: Bool { traits.contains(.button) }
    var isHeader: Bool { traits.contains(.header) }

    var description: String {
        "\"\(label)\" value=\"\(value)\" traits=\(traits.rawValue) frame=\(frame.integral)"
    }
}

/// Hosts a SwiftUI view in a real window, at a chosen Dynamic Type size, and reads back what VoiceOver
/// would find there: the stops, their labels, values, traits and frames. It also captures what was
/// drawn, so a test can measure the contrast of the text it can see.
///
/// SwiftUI builds its accessibility tree only while an assistive technology asks for it, so the harness
/// switches on the same automation mode XCUITest uses (`_AXSSetAutomationEnabled`, test target only — the
/// app never links it). The tree it returns is the one `XCUIApplication` would query: combined rows are
/// one stop, hidden images are absent, and a symbol's default label ("Selected" for `checkmark`) shows up
/// exactly where VoiceOver would speak it.
@MainActor
final class HostedView {
    let window: UIWindow
    private let fit: (CGSize) -> CGSize
    private let host: UIViewController
    private let started = Date()
    private let ordinal: Int
    private let liveAtStart: Int
    static var created = 0
    static var live = 0

    init<Content: View>(
        _ content: Content,
        size: CGSize = CGSize(width: 393, height: 852),
        dynamicTypeSize: DynamicTypeSize = .large
    ) async {
        Self.enableAccessibilityTree()
        await Self.awaitAccessibilityReady()
        let root = content
            .environment(\.dynamicTypeSize, dynamicTypeSize)
            .environment(HapticsSettings())
        let host = UIHostingController(rootView: root)
        host.overrideUserInterfaceStyle = .light
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first else {
            preconditionFailure("The test host has no window scene to host a view in.")
        }
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(origin: .zero, size: size)
        window.overrideUserInterfaceStyle = .light
        window.rootViewController = host
        window.makeKeyAndVisible()
        self.window = window
        self.host = host
        Self.created += 1
        Self.live += 1
        self.ordinal = Self.created
        self.liveAtStart = Self.live
        self.fit = { host.sizeThatFits(in: $0) }
        await settle()
    }

    /// Lets SwiftUI lay out, render, and publish its accessibility nodes.
    func settle() async {
        Self.installIdleObserver()
        let ticker = FrameTicker()
        let idleBefore = Self.idleTurns
        let t0 = Date()
        for _ in 0..<4 {
            window.layoutIfNeeded()
            try? await Task.sleep(for: .milliseconds(60))
        }
        let settleMs = Int(Date().timeIntervalSince(t0) * 1000)
        let idle0 = Self.idleTurns - idleBefore
        let ticks0 = ticker.ticks
        let stops0 = stops.count
        let pix0 = pixelHash()
        // Step F: force every hosting view in the window to lay out now, and commit.
        func force(_ view: UIView) {
            view.setNeedsLayout()
            view.layoutIfNeeded()
            for subview in view.subviews { force(subview) }
        }
        force(window)
        CATransaction.flush()
        let stopsF = stops.count
        let pixF = pixelHash()
        // Timeline: poll for 6 s, logging every change in the tree.
        var timeline: [String] = []
        var last = stops.map(\.description).joined(separator: "|")
        var lastCount = stops0
        let p0 = Date()
        var worst = 0
        while Date().timeIntervalSince(p0) < 6 {
            let before = Date()
            try? await Task.sleep(for: .milliseconds(25))
            worst = max(worst, Int(Date().timeIntervalSince(before) * 1000) - 25)
            window.layoutIfNeeded()
            let now = stops
            let key = now.map(\.description).joined(separator: "|")
            if key != last {
                timeline.append("+\(Int(Date().timeIntervalSince(p0) * 1000))ms:\(lastCount)->\(now.count)")
                last = key
                lastCount = now.count
            }
        }
        let stopsD = lastCount, waitD = worst, stopsI = lastCount, waitI = timeline.count
        let pixD = timeline.joined(separator: ","), pixI = "t\(Int(CACurrentMediaTime() * 1000))"
        ticker.stop()
        NSLog("A11YV3 ordinal=%d live=%d settleMs=%d idle0=%d ticks0=%d stops0=%d stopsF=%d stopsD=%d(waitD=%dms) stopsI=%d(waitI=%dms) pix0=%@ pixF=%@ pixD=%@ pixI=%@",
              ordinal, Self.live, settleMs, idle0, ticks0, stops0, stopsF, stopsD, waitD, stopsI, waitI,
              pix0, pixF, pixD, pixI)
    }

    private func pixelHash() -> String {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let image = UIGraphicsImageRenderer(bounds: window.bounds, format: format).image { context in
            window.layer.render(in: context.cgContext)
        }
        guard let data = image.cgImage?.dataProvider?.data as Data? else { return "nil" }
        var hash: UInt64 = 1469598103934665603
        for byte in data { hash = (hash ^ UInt64(byte)) &* 1099511628211 }
        return String(hash % 1_000_000)
    }

    static var idleTurns = 0
    private static var idleObserver: CFRunLoopObserver?

    private static func installIdleObserver() {
        guard idleObserver == nil else { return }
        let observer = CFRunLoopObserverCreateWithHandler(nil, CFRunLoopActivity.beforeWaiting.rawValue, true, Int.max) { _, _ in
            MainActor.assumeIsolated { HostedView.idleTurns += 1 }
        }
        CFRunLoopAddObserver(CFRunLoopGetMain(), observer, .commonModes)
        idleObserver = observer
    }

    /// Every stop, in tree order.
    var stops: [AccessibilityStop] {
        var found: [AccessibilityStop] = []
        var seen = Set<ObjectIdentifier>()
        collect(window, into: &found, seen: &seen)
        return found
    }

    func stops(labelled label: String) -> [AccessibilityStop] {
        stops.filter { $0.label == label }
    }

    func stop(labelled label: String) -> AccessibilityStop? {
        stops.first { $0.label == label }
    }

    func stops(labelContaining fragment: String) -> [AccessibilityStop] {
        stops.filter { $0.label.contains(fragment) }
    }

    /// The tree as text, for an assertion message that shows what was there instead.
    var tree: String {
        stops.map(\.description).joined(separator: "\n") + "\n--diag--\n" + diagnostics
    }

    var diagnostics: String {
        var out: [String] = []
        let scene = window.windowScene
        out.append("ordinal=\(ordinal) liveAtStart=\(liveAtStart) liveNow=\(Self.live) elapsed=\(Int(Date().timeIntervalSince(started) * 1000))ms")
        out.append("appState=\(UIApplication.shared.applicationState.rawValue) sceneState=\(scene.map { String($0.activationState.rawValue) } ?? "nil") scenes=\(UIApplication.shared.connectedScenes.count)")
        out.append("isKey=\(window.isKeyWindow) hidden=\(window.isHidden) bounds=\(window.bounds) automation=\(Self.automationEnabled())")
        for w in scene?.windows ?? [] {
            out.append("  win \(type(of: w)) key=\(w.isKeyWindow) hidden=\(w.isHidden) level=\(w.windowLevel.rawValue) root=\(w.rootViewController.map { String(describing: type(of: $0)) } ?? "nil") presented=\(w.rootViewController?.presentedViewController.map { String(describing: type(of: $0)) } ?? "nil")")
        }
        func vcs(_ vc: UIViewController, _ depth: Int) {
            out.append(String(repeating: "  ", count: depth) + "vc \(type(of: vc)) loaded=\(vc.isViewLoaded) inWindow=\(vc.viewIfLoaded?.window != nil) frame=\(vc.viewIfLoaded?.frame ?? .zero) subviews=\(vc.viewIfLoaded?.subviews.count ?? -1)")
            for child in vc.children { vcs(child, depth + 1) }
        }
        vcs(host, 0)
        func views(_ v: UIView, _ depth: Int) {
            guard depth < 7 else { return }
            out.append(String(repeating: " ", count: depth) + "v \(type(of: v)) \(v.frame.integral) h=\(v.isHidden) a=\(v.alpha) ax=\(v.isAccessibilityElement) axCount=\(v.accessibilityElementCount())")
            for s in v.subviews { views(s, depth + 1) }
        }
        views(window, 0)
        return out.joined(separator: "\n")
    }

    private static func automationEnabled() -> String {
        guard let handle = dlopen("/usr/lib/libAccessibility.dylib", RTLD_NOW), let sym = dlsym(handle, "_AXSAutomationEnabled") else { return "?" }
        typealias Get = @convention(c) () -> Bool
        return String(unsafeBitCast(sym, to: Get.self)())
    }

    /// The SwiftUI-measured height of the hosted content at the window's width.
    func fittingHeight() -> CGFloat {
        fit(CGSize(width: window.bounds.width, height: .greatestFiniteMagnitude)).height
    }

    func close() {
        let summary = diagnostics.split(separator: "\n").prefix(3).joined(separator: " | ")
        NSLog("A11YDIAG stops=%d %@", stops.count, summary)
        Self.live -= 1
        window.isHidden = true
        window.rootViewController = nil
    }

    private func collect(_ object: NSObject, into found: inout [AccessibilityStop], seen: inout Set<ObjectIdentifier>) {
        guard seen.insert(ObjectIdentifier(object)).inserted else { return }
        if let view = object as? UIView, view.isHidden || view.alpha == 0 { return }
        if object.accessibilityElementsHidden { return }
        if object.isAccessibilityElement {
            found.append(AccessibilityStop(
                label: object.accessibilityLabel ?? "",
                value: object.accessibilityValue ?? "",
                hint: object.accessibilityHint ?? "",
                traits: object.accessibilityTraits,
                inputLabels: object.accessibilityUserInputLabels ?? [],
                frame: object.accessibilityFrame
            ))
            return
        }
        let children = accessibilityChildren(of: object)
        if !children.isEmpty {
            for child in children { collect(child, into: &found, seen: &seen) }
        } else if let view = object as? UIView {
            for subview in view.subviews { collect(subview, into: &found, seen: &seen) }
        }
    }

    private func accessibilityChildren(of object: NSObject) -> [NSObject] {
        if let elements = object.accessibilityElements as? [NSObject], !elements.isEmpty { return elements }
        let count = object.accessibilityElementCount()
        guard count != NSNotFound, count > 0 else { return [] }
        return (0..<count).compactMap { object.accessibilityElement(at: $0) as? NSObject }
    }

    private static var isTreeEnabled = false
    private static var readiness: Task<Void, Never>?

    /// Diagnostic: hosts a probe and waits until SwiftUI publishes it as an accessibility element.
    static func awaitAccessibilityReady() async {
        if readiness == nil {
            readiness = Task { @MainActor in
                guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first else { return }
                let probe = UIWindow(windowScene: scene)
                probe.frame = CGRect(x: 0, y: 0, width: 200, height: 100)
                probe.rootViewController = UIHostingController(rootView: Text(verbatim: "harness-probe"))
                probe.makeKeyAndVisible()
                let start = Date()
                var polls = 0
                var worstOvershoot = 0
                var found = false
                while Date().timeIntervalSince(start) < 60 {
                    probe.layoutIfNeeded()
                    if probeFound(in: probe) { found = true; break }
                    polls += 1
                    let before = Date()
                    try? await Task.sleep(for: .milliseconds(20))
                    worstOvershoot = max(worstOvershoot, Int(Date().timeIntervalSince(before) * 1000) - 20)
                }
                NSLog("A11YPROBE found=%d ms=%d polls=%d worstOvershootMs=%d", found ? 1 : 0,
                      Int(Date().timeIntervalSince(start) * 1000), polls, worstOvershoot)
                probe.isHidden = true
                probe.rootViewController = nil
            }
        }
        await readiness?.value
    }

    private static func probeFound(in object: NSObject) -> Bool {
        if object.isAccessibilityElement { return object.accessibilityLabel == "harness-probe" }
        if let elements = object.accessibilityElements as? [NSObject], !elements.isEmpty {
            return elements.contains { probeFound(in: $0) }
        }
        let count = object.accessibilityElementCount()
        if count != NSNotFound, count > 0 {
            return (0..<count).contains { (object.accessibilityElement(at: $0) as? NSObject).map(probeFound) ?? false }
        }
        if let view = object as? UIView { return view.subviews.contains { probeFound(in: $0) } }
        return false
    }

    /// Turns on accessibility automation for this test process, as XCUITest does for an app under test.
    private static func enableAccessibilityTree() {
        guard !isTreeEnabled, let handle = dlopen("/usr/lib/libAccessibility.dylib", RTLD_NOW) else { return }
        typealias SetEnabled = @convention(c) (Bool) -> Void
        for name in ["_AXSSetAutomationEnabled", "_AXSApplicationAccessibilitySetEnabled"] {
            if let symbol = dlsym(handle, name) {
                unsafeBitCast(symbol, to: SetEnabled.self)(true)
            }
        }
        isTreeEnabled = true
        NSLog("A11YENABLE t=%d", Int(CACurrentMediaTime() * 1000))
        _dyld_register_func_for_add_image { header, _ in
            guard let header else { return }
            var info = Dl_info()
            guard dladdr(header, &info) != 0, let name = info.dli_fname else { return }
            let path = String(cString: name)
            if path.contains("ccessibility") || path.contains("axbundle") {
                NSLog("A11YDYLD t=%d %@", Int(CACurrentMediaTime() * 1000), path)
            }
        }
    }
}

@MainActor
final class FrameTicker: NSObject {
    private(set) var ticks = 0
    private var link: CADisplayLink?

    override init() {
        super.init()
        let link = CADisplayLink(target: self, selector: #selector(tick))
        link.add(to: .main, forMode: .common)
        self.link = link
    }

    @objc private func tick() { ticks += 1 }

    func stop() { link?.invalidate() }
}

// MARK: - Drawn contrast

/// The contrast of what was actually drawn, measured from the window's own pixels with the WCAG formula
/// `SRGBColor` implements — the measurement the audit took from screenshots, made repeatable.
@MainActor
struct DrawnContrast {
    private let pixels: [UInt8]
    private let width: Int
    private let height: Int
    private let scale: CGFloat

    init(_ hosted: HostedView) {
        let window = hosted.window
        let format = UIGraphicsImageRendererFormat(for: window.traitCollection)
        format.opaque = true
        let image = UIGraphicsImageRenderer(bounds: window.bounds, format: format).image { context in
            window.layer.render(in: context.cgContext)
        }
        let cgImage = image.cgImage!
        width = cgImage.width
        height = cgImage.height
        scale = CGFloat(cgImage.width) / window.bounds.width
        var buffer = [UInt8](repeating: 0, count: width * height * 4)
        buffer.withUnsafeMutableBytes { bytes in
            let context = CGContext(
                data: bytes.baseAddress, width: cgImage.width, height: cgImage.height, bitsPerComponent: 8,
                bytesPerRow: cgImage.width * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!,
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            )
            context?.draw(cgImage, in: CGRect(x: 0, y: 0, width: cgImage.width, height: cgImage.height))
        }
        pixels = buffer
    }

    /// Each line of text inside `rect` (window points), top to bottom, as the strongest contrast any of its
    /// pixels reaches against the region's background — the most common colour in it. Antialiased edges
    /// only ever lower a pixel's contrast, so a glyph's stem carries the colour the text was set in.
    func textLines(in rect: CGRect) -> [Double] {
        let region = pixelRect(rect)
        guard region.width > 0, region.height > 0 else { return [] }
        let background = dominantColour(in: region)
        var rowContrast: [Double] = []
        for y in region.minY..<region.maxY {
            var strongest = 1.0
            for x in region.minX..<region.maxX {
                strongest = max(strongest, colour(x, y).contrastRatio(against: background))
            }
            rowContrast.append(strongest)
        }
        // A row belongs to a line when anything in it stands off the background; blank rows separate lines.
        var lines: [Double] = []
        var current: Double?
        for value in rowContrast {
            if value > 1.25 {
                current = max(current ?? 1, value)
            } else if let line = current {
                lines.append(line)
                current = nil
            }
        }
        if let line = current { lines.append(line) }
        return lines
    }

    /// The strongest contrast anything inside `rect` reaches against the region's background.
    func strongestInk(in rect: CGRect) -> Double {
        textLines(in: rect).max() ?? 1
    }

    /// A region of the capture, in pixels.
    private struct PixelRegion {
        let minX: Int, minY: Int, maxX: Int, maxY: Int
        var width: Int { max(0, maxX - minX) }
        var height: Int { max(0, maxY - minY) }
    }

    private func pixelRect(_ rect: CGRect) -> PixelRegion {
        PixelRegion(
            minX: max(0, Int((rect.minX * scale).rounded())),
            minY: max(0, Int((rect.minY * scale).rounded())),
            maxX: min(width, Int((rect.maxX * scale).rounded())),
            maxY: min(height, Int((rect.maxY * scale).rounded()))
        )
    }

    private func colour(_ x: Int, _ y: Int) -> SRGBColor {
        let offset = (y * width + x) * 4
        return SRGBColor(
            red: Double(pixels[offset]) / 255,
            green: Double(pixels[offset + 1]) / 255,
            blue: Double(pixels[offset + 2]) / 255
        )
    }

    private func dominantColour(in region: PixelRegion) -> SRGBColor {
        var counts: [UInt32: Int] = [:]
        for y in region.minY..<region.maxY {
            for x in region.minX..<region.maxX {
                let offset = (y * width + x) * 4
                let key = UInt32(pixels[offset]) << 16 | UInt32(pixels[offset + 1]) << 8 | UInt32(pixels[offset + 2])
                counts[key, default: 0] += 1
            }
        }
        let key = counts.max { $0.value < $1.value }?.key ?? 0xFFFFFF
        return SRGBColor(
            red: Double((key >> 16) & 0xFF) / 255,
            green: Double((key >> 8) & 0xFF) / 255,
            blue: Double(key & 0xFF) / 255
        )
    }
}
