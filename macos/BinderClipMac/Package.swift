// swift-tools-version: 5.10
import PackageDescription

let package = Package(
    name: "binderclip-mac",
    defaultLocalization: "en",
    platforms: [.macOS(.v13)],
    products: [
        .executable(name: "BinderClip", targets: ["BinderClip"])
    ],
    dependencies: [
        .package(url: "https://github.com/sparkle-project/Sparkle", from: "2.6.0")
    ],
    targets: [
        .executableTarget(
            name: "BinderClip",
            dependencies: [
                .product(name: "Sparkle", package: "Sparkle")
            ],
            path: ".",
            exclude: ["Tests"],
            resources: [.process("Resources")]
        ),
        .testTarget(
            name: "BinderClipTests",
            dependencies: ["BinderClip"],
            path: "Tests/Direct"
        )
    ]
)
