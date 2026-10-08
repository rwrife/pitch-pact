//
//  TeamStoreFactory.swift
//  PitchPact — resolves the GRDB store (repo root Packages/PitchPactStore).
//
//  The iOS target links the local PitchPactStore package directly (not
//  through the KMP framework): business rules still live in Kotlin `shared`
//  for anything that crosses the wire; the store is a platform concern per
//  the M2 plan (GRDB on iOS, Room on Android, same contract).
//

import Foundation
import PitchPactStore

enum TeamStoreFactory {
    static func open() -> GRDBTeamStore {
        do {
            let dir = try FileManager.default.url(
                for: .applicationSupportDirectory,
                in: .userDomainMask,
                appropriateFor: nil,
                create: true
            )
            return try GRDBTeamStore(url: dir.appendingPathComponent("pitchpact.db"))
        } catch {
            // Never make successful-looking scoring writes to a volatile fallback.
            // Preserve the database for recovery and stop startup visibly.
            fatalError("PitchPact durable store unavailable: \(error)")
        }
    }
}

/// Process-wide store. Single-threaded by contract: every access happens on
/// the main actor (SwiftUI views / hosted tests), matching the shared
/// TeamStore contract. `nonisolated(unsafe)` documents that choice; GRDB's
/// DatabaseQueue additionally serializes internally.
final class AppTeamStore {
    nonisolated(unsafe) static let shared: GRDBTeamStore = TeamStoreFactory.open()
}
