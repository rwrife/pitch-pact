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
@preconcurrency import PitchPactShared

private typealias Team = PitchPactStore.Team
private typealias Player = PitchPactStore.Player

@Suite("M2 app integration (simulator)")
struct PitchPactTests {

    @Test("app store opens on the simulator data container")
    func storeOpensOnDevice() throws {
        // AppTeamStore.shared is initialized at process start by the app
        // binary; reaching it from the hosted test proves the GRDB stack
        // works inside the app sandbox (current v2 schema migrated).
        #expect(try AppTeamStore.shared.schemaVersion() == 3)
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
        struct M2Payload: Decodable {
            let teams: [PitchPactStore.Team]
            let players: [PitchPactStore.Player]
        }
        let fixture = try JSONDecoder().decode(M2Payload.self, from: data)

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

    @MainActor
    @Test("pending standings cross the KMP-to-Swift bridge without becoming zero-filled")
    func pendingStandingsBridge() throws {
        let fixture = PitchPactStore.Fixture(id: "f1", title: "Pending", homeTeamId: "a", awayTeamId: "b",
            startEpochMillis: 1, resultStatus: .PENDING)
        let json = String(decoding: try JSONEncoder().encode([fixture]), as: UTF8.self)
        let wire = SchedulingFacade().standings(teamIds: ["a", "b"], fixturesJson: json)
        let standing = try JSONDecoder().decode([PitchPactStore.Standing].self, from: Data(wire.utf8))
        #expect(standing.count == 2)
        #expect(standing.allSatisfy { $0.pendingFixtures == 1 && $0.played == 0 })
    }

    @MainActor
    @Test("consensus facade bridge drives scoreboard, pending rows and record ledger")
    func consensusFacadeBridge() throws {
        let rules = ConsensusFacade()
        #expect(rules.sideFor(firstTeamId: "teamA", secondTeamId: "teamB", myTeamId: "teamA") == "HOME")
        #expect(rules.sideFor(firstTeamId: "teamA", secondTeamId: "teamB", myTeamId: "teamB") == "AWAY")

        let submitBody = try rules.submitGoal(
            matchId: "match-1",
            side: "HOME",
            changeId: "change-1",
            eventId: "event-1",
            epoch: 1_700_000_000_000,
            jersey: 9,
            idempotencyKey: "idem-1"
        )
        #expect(submitBody.contains("change-1"))
        #expect(submitBody.contains("event-1"))

        // Stable wire fixture exercises the native bridge; server integration owns
        // live two-party authorization and the adversarial contested-half run.
        let sampleSnapshot = """
        {
          "matchId": "m1", "events": [],
          "officialScore": {"matchId": "m1", "homeScore": 1, "awayScore": 0},
          "pendingChanges": [],
          "decisions": [
            {
              "change": {"changeId": "c1", "matchId": "m1", "submittedBy": "home", "ledgerEventId": "e1", "action": "add"},
              "event": {"type": "event", "eventId": "e1", "side": "home", "recordedAtEpochMillis": 1000,
                        "payload": {"type": "goal", "side": "home", "jerseyNumber": 9}},
              "state": "accepted", "actor": "away", "submittedAt": 1000, "resolvedAt": 1100
            }
          ],
          "finalWhistles": ["home", "away"], "official": true
        }
        """

        let scoreboard = try rules.scoreboard(snapshotJson: sampleSnapshot)
        #expect(scoreboard.contains("OFFICIAL 1-0"))
        #expect(scoreboard.contains("FINAL (official)"))
        #expect(scoreboard.contains("PENDING REVIEW: 0"))

        let ledger = try rules.recordLedger(snapshotJson: sampleSnapshot)
        #expect(ledger.contains("ACCEPTED"))
        #expect(ledger.contains("goal #9 (HOME)"))
        #expect(ledger.contains("resolved by AWAY"))

        let pendingSnapshot = """
        {
          "matchId": "m1", "events": [],
          "officialScore": {"matchId": "m1", "homeScore": 0, "awayScore": 0},
          "pendingChanges": [{"changeId": "c2", "submittedBy": "home", "state": "pending"}],
          "decisions": [
            {
              "change": {"changeId": "c2", "matchId": "m1", "submittedBy": "home", "ledgerEventId": "e2", "action": "add"},
              "event": {"type": "event", "eventId": "e2", "side": "home", "recordedAtEpochMillis": 2000,
                        "payload": {"type": "goal", "side": "home", "jerseyNumber": 10}},
              "state": "pending", "actor": "home", "submittedAt": 2000
            }
          ],
          "finalWhistles": [], "official": false
        }
        """

        let homeRows = try rules.pendingRows(snapshotJson: pendingSnapshot, mySide: "HOME")
        #expect(homeRows.count == 1)
        #expect(homeRows[0].contains("Awaiting opponent"))
        #expect(homeRows[0].hasSuffix("|withdraw"))

        let awayRows = try rules.pendingRows(snapshotJson: pendingSnapshot, mySide: "AWAY")
        #expect(awayRows.count == 1)
        #expect(awayRows[0].contains("Review:"))
        #expect(awayRows[0].hasSuffix("|review"))
    }
}
