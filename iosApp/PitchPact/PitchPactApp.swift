//
//  PitchPactApp.swift
//  PitchPact — SwiftUI iOS app (iPhone-only per toolchain.json).
//
//  M1 proof: renders state derived from the shared KMP framework. The Swift
//  layer computes nothing — all business rules live in `shared/`.
//

import SwiftUI
// Kotlin/Native exports its classes as @unchecked Sendable; @preconcurrency
// keeps the Swift 6 checker honest without inventing isolation on our side.
@preconcurrency import PitchPactShared

@main
struct PitchPactApp: App {
    var body: some Scene {
        WindowGroup {
            MatchStateView()
        }
    }
}

struct MatchStateView: View {
    // KMP state is touched here — View bodies run on the main actor, which is
    // the single-threaded-by-contract calling discipline for shared code.
    var body: some View {
        let summary = KmpFacade.shared.proofSummary()
        let protocolVersion = KmpFacade.shared.protocolVersion()
        VStack(spacing: 12) {
            Text("Pitch Pact")
                .font(.largeTitle.bold())
            Text("protocol \(protocolVersion)")
                .font(.footnote)
                .foregroundStyle(.secondary)
            Text(summary)
                .multilineTextAlignment(.center)
                .padding(.horizontal)
            Text("Consensus scoring: official score only after the opposing side accepts.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .padding(.horizontal)
        }
        .padding()
        .accessibilityIdentifier("app.matchState.root")
    }
}

#Preview {
    MatchStateView()
}
