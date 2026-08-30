// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "AirRelayKit",
    platforms: [.macOS(.v14)],
    products: [
        .library(name: "AirRelayKit", targets: ["AirRelayKit"]),
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-certificates.git", from: "1.0.0"),
    ],
    targets: [
        .target(
            name: "AirRelayKit",
            dependencies: [
                .product(name: "X509", package: "swift-certificates"),
            ],
            path: "Sources/AirRelayKit"
        ),
        .testTarget(
            name: "AirRelayKitTests",
            dependencies: ["AirRelayKit"],
            path: "Tests/AirRelayKitTests"
        ),
    ]
)
