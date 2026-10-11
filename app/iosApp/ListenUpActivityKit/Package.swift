// swift-tools-version: 6.2
import PackageDescription

let package = Package(
    name: "ListenUpActivityKit",
    platforms: [.iOS(.v26), .macOS(.v26)],
    products: [
        .library(name: "ListenUpActivityKit", targets: ["ListenUpActivityKit"])
    ],
    targets: [
        .target(name: "ListenUpActivityKit")
    ]
)
