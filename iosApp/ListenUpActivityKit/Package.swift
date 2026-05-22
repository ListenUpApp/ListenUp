// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "ListenUpActivityKit",
    platforms: [.iOS(.v18)],
    products: [
        .library(name: "ListenUpActivityKit", targets: ["ListenUpActivityKit"]),
    ],
    targets: [
        .target(name: "ListenUpActivityKit"),
    ]
)
