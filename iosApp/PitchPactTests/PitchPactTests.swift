//
//  PitchPactTests.swift
//  PitchPactTests — M2 app-level unit tests (simulator lane).
//
//  These run on the host app inside the simulator (macOS CI): they prove the
//  SwiftUI app target LINKS and can drive the GRDB store end to end,
//  including the same shared fixture the Kotlin and Android suites load.
//  (The exhaustive store semantics live in Packages/PitchPactStore tests.)
//

import Testing
import Foundation
@testable import PitchPact
import PitchPactStore

@Suite("M2 app integration (simulator)")
struct PitchPactTests {

    @Test("app store opens on the simulator data container")
    func storeOpensOnDevice() throws {
        // AppTeamStore.shared is initialized at process start by the app
        // binary; reaching it from the hosted test proves the GRDB stack
        // works inside the app sandbox (schema v1 migrated).
        #expect(try AppTeamStore.shared.schemaVersion() == 1)
    }

    @Test("shared M2 fixture round-trips through the app store")
    func fixtureRoundTrip() throws {
        // Same repo-root fixture used by the Kotlin codec test and the
        // Android Robolectric test (path resolution differs per host).
        let fm = FileManager.default
        var url: URL?
        for candidate in [
            // When launched by CI from the DerivedData/SourcePackages world,
            // walk up from the test bundle looking for fixtures/.
            URL(fileURLWithPath: #filePath)
                .deletingLastPathComponent()   // PitchPactTests
                .deletingLastPathComponent()   // iosApp
                .deletingLastPathComponent()   // repo root
                .appendingPathComponent("fixtures/m2-teams.json"),
        ] {
            if fm.fileExists(atPath: candidate.path) { url = candidate; break }
        }
        guard let fixtureURL = url else {
            Issue.record("fixtures/m2-teams.json not found from #filePath")
            return
        }
        let data = try Data(contentsOf: fixtureURL)
        struct Fixture: Decodable {
            let teams: [Team]
            let players: [Player]
        }
        let fixture = try JSONDecoder().decode(Fixture.self, from: data)

        // Use a scratch store so the app's real database is untouched.
        let store = try GRDBTeamStore(inMemory: true)
        for t in fixture.teams { try store.saveTeam(t) }
        for p in fixture.players { try store.savePlayer(p) }
        #expect(try store.teams().count == 1)
        #expect(try store.teams(includeArchived: true).count == 2)
        #expect(try store.players(teamId: "team-u8-falcons").count == 3)
    }

    @Test("shared validation error codes surface in the app layer")
    func validationParity() throws {
        let store = try GRDBTeamStore(inMemory: true)
        try store.saveTeam(Team(id: "t1", name: "Falcons", createdAtEpochMillis: 1))
        try store.savePlayer(Player(id: "p1", teamId: "t1", name: "Bo", jerseyNumber: 9, createdAtEpochMillis: 1))
        do {
            try store.savePlayer(Player(id: "p2", teamId: "t1", name: "Cy", jerseyNumber: 9, createdAtEpochMillis: 1))
            Issue.record("expected clash")
        } catch let failure as ValidationFailure {
            #expect(failure.code == "jersey.clash")
            #expect(failure.message == "Jersey #9 already worn by Bo")
        }
    }
}
