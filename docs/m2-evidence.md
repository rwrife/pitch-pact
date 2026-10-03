# M2 evidence (teams, rosters, uniforms)

Honest split between what was actually executed on the executor host (Linux,
arm64) and what only CI can prove. Nothing here is fabricated; absent items
are explicitly pending.

## Verified on the executor host (real executions, 2026-10-01)

| Evidence | Command | Result |
|---|---|---|
| Shared KMP validation + privacy tests | `docker run ghcr.io/cirruslabs/android-sdk:36 ./gradlew :shared:jvmTest` (same image as CI) | BUILD SUCCESSFUL (incl. new `M2ValidationTest`, `M2PrivacyTest`, `StoreFixtureCodecTest`) |
| iOS store logic on real SQLite (GRDB) | `docker run carekit-swift-sqlite:latest swift test` in `Packages/PitchPactStore` (repo mounted) | 10/10 tests pass, incl. cross-platform fixture round-trip, cascade preview + confirmed cascade, FK guardian cleanup, byte-identical validation wording vs Kotlin |
| Workflow lint | `rhysd/actionlint` on both workflow files | clean |
| pbxproj integrity | referenced vs defined object-ID diff | 32/32, zero missing, zero conflict markers; `TARGETED_DEVICE_FAMILY = 1` x6, bundle id x4, SDKROOT x2 |

## NOT verified on the host (disclosed gaps, CI closes them)

- **Android Room/Robolectric tests** (`:androidApp:testDebugUnitTest`):
  compilation fails on the host before the tests can run with
  `AAPT2 aapt2-8.11.2-12782657-linux Daemon #1: Daemon startup failed` —
  Google ships aapt2 for x86_64 only and the host is arm64
  (see fleet skill `github-pr-workflow`: "arm64 dev host cannot run Android
  resource builds"). The ubuntu CI lane runs these for real.
- **iOS app build + hosted `PitchPactTests` (simulator)**: requires the exact
  Xcode 26.0.1/17A400 pin — macOS CI lane only. Swift package tests above are
  logic evidence, NOT iOS build evidence.
- **Android assembleDebug**: same arm64/aapt2 host gap; CI lane builds it.
- No device runs, no TestFlight, no Play tracks — M8 scope.

## Known accepted risk

`Packages/PitchPactStore` mirrors the Kotlin DTOs/validation verbatim (same
JSON keys, same error codes, pinned by matching literals in both suites).
The Kotlin module remains the product truth; M4 should evaluate generating
the Swift side from the KMP export instead of hand-mirroring.
