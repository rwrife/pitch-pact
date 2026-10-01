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
                TeamsView()
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
