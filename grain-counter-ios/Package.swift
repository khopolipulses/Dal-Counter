// swift-tools-version:5.7
// Only the counting engine and the marker list: lets `swift test` run them on macOS or Linux without Xcode.
// The iOS app itself is built from project.yml (XcodeGen), which compiles the same Core/ files into the app.
import PackageDescription

let package = Package(
    name: "GrainCore",
    products: [.library(name: "GrainCore", targets: ["GrainCore"])],
    targets: [
        .target(name: "GrainCore", path: "Core"),
        .testTarget(name: "GrainCoreTests", dependencies: ["GrainCore"], path: "Tests/GrainCoreTests"),
    ]
)
