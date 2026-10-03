// swift-tools-version: 6.2
import PackageDescription

// M2 iOS store: GRDB-backed TeamStore mirroring the shared KMP contract
// (shared/src/commonMain/.../store/TeamStore.kt). Business rules are
// duplicated from `shared`'s Validate object verbatim — the Kotlin side is
// the product truth; parity is pinned by matching error-code strings in
// both platforms' tests.
let package = Package(
    name: "PitchPactStore",
    platforms: [
        .iOS(.v26),
        .macOS(.v14),
    ],
    products: [
        .library(name: "PitchPactStore", targets: ["PitchPactStore"]),
    ],
    dependencies: [
        .package(url: "https://github.com/groue/GRDB.swift.git", from: "7.0.0"),
    ],
    targets: [
        .target(
            name: "PitchPactStore",
            dependencies: [.product(name: "GRDB", package: "GRDB.swift")]
        ),
        .testTarget(
            name: "PitchPactStoreTests",
            dependencies: ["PitchPactStore"]
        ),
    ]
)
