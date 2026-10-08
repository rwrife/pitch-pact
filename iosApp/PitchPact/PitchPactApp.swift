//
//  PitchPactApp.swift
//  PitchPact — SwiftUI iOS app (iPhone-only per toolchain.json).
//
//  M2: team directory + team detail CRUD over the GRDB store. The Swift
//  layer computes nothing — validation rules mirror `shared`/Validate and
//  all wire/business truth lives in the KMP module.
//

import SwiftUI

@main
struct PitchPactApp: App {
    var body: some Scene {
        WindowGroup {
            NavigationStack {
                if ProcessInfo.processInfo.arguments.contains("--m4-ui-test") {
                    MatchDayView(fixtureId: "m4-ui-fixture")
                } else { TeamsView() }
            }
            .accessibilityIdentifier("app.root")
        }
    }
}

#Preview {
    NavigationStack {
        TeamsView()
    }
}
