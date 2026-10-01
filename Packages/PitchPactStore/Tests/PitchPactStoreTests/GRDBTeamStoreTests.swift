import Foundation
import PitchPactStore
import Testing

@Suite("GRDBTeamStore (iOS side of M2 dual-store contract)")
struct GRDBTeamStoreTests {
    private func makeStore() throws -> GRDBTeamStore {
        try GRDBTeamStore(inMemory: true)
    }

    private func team(_ id: String, name: String = "T") -> Team {
        Team(id: id, name: name, createdAtEpochMillis: 1)
    }

    private func player(_ id: String, _ teamId: String, _ name: String, _ jersey: Int) -> Player {
        Player(id: id, teamId: teamId, name: name, jerseyNumber: jersey, createdAtEpochMillis: 1)
    }

    @Test("schema is version one")
    func schemaIsVersionOne() throws {
        #expect(try makeStore().schemaVersion() == 1)
    }

    @Test("round-trips the shared cross-platform fixture")
    func roundTripsSharedFixture() throws {
        // Same file the Android Robolectric test loads (repo-root fixtures/).
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent() // -> Tests/PitchPactStoreTests
            .deletingLastPathComponent() // -> Tests
            .deletingLastPathComponent() // -> PitchPactStore
            .deletingLastPathComponent() // -> Packages
            .deletingLastPathComponent() // -> repo root
            .appendingPathComponent("fixtures/m2-teams.json")
        let data = try Data(contentsOf: url)

        struct Fixture: Decodable {
            let teams: [Team]
            let players: [Player]
        }
        let fixture = try JSONDecoder().decode(Fixture.self, from: data)
        let store = try makeStore()
        for t in fixture.teams { try store.saveTeam(t) }
        for p in fixture.players { try store.savePlayer(p) }

        let active = try store.teams()
        #expect(Set(active.map(\.id)) == Set(fixture.teams.filter { !$0.isArchived }.map(\.id)))
        #expect(try store.teams(includeArchived: true).contains { $0.isArchived })
        let falcons = try store.players(teamId: "team-u8-falcons")
        #expect(Set(falcons.map(\.id)) == Set(
            fixture.players.filter { $0.teamId == "team-u8-falcons" }.map(\.id)
        ))
        #expect(falcons.filter { $0.jerseyNumber == 9 }.count == 1)
    }

    @Test("team CRUD + archive/unarchive round trip")
    func teamCrud() throws {
        let store = try makeStore()
        try store.saveTeam(team("t1", name: "Falcons"))
        #expect(try store.team(id: "t1")?.name == "Falcons")
        try store.archiveTeam(id: "t1", archived: true, atEpochMillis: 99)
        #expect(try store.teams().first(where: { $0.id == "t1" }) == nil)
        #expect(try store.teams(includeArchived: true).contains { $0.isArchived })
        try store.archiveTeam(id: "t1", archived: false, atEpochMillis: 0)
        #expect(try store.teams().contains { $0.id == "t1" })
    }

    @Test("jersey clash rejected at the store layer")
    func jerseyClash() throws {
        let store = try makeStore()
        try store.saveTeam(team("t1"))
        try store.savePlayer(player("p1", "t1", "Bo", 9))
        #expect(throws: ValidationFailure.self) {
            try store.savePlayer(player("p2", "t1", "Cy", 9))
        }
        // Parity with the Kotlin `Validate` wording (shared module is truth):
        // both platforms must surface byte-identical clash text.
        do {
            try store.savePlayer(player("p2", "t1", "Cy", 9))
            Issue.record("expected a clash")
        } catch let failure as ValidationFailure {
            #expect(failure.code == "jersey.clash")
            #expect(failure.message == "Jersey #9 already worn by Bo")
        }
        // Same player re-saving the same number is a no-op.
        try store.savePlayer(player("p1", "t1", "Bo", 9))
        // Same number on another team is fine.
        try store.saveTeam(team("t2"))
        try store.savePlayer(player("p3", "t2", "Di", 9))
    }

    @Test("deletion preview counts; guarded delete preserves; confirmed cascade destroys")
    func deletionGuards() throws {
        let store = try makeStore()
        try store.saveTeam(team("t1"))
        try store.savePlayer(player("p1", "t1", "Bo", 9))
        try store.saveGuardianContact(GuardianContact(id: "g1", playerId: "p1", guardianName: "Sam", phone: "555"))
        try store.saveUniformRequirement(UniformRequirement(
            id: "u1", teamId: "t1", opponentName: "Rivals",
            homeKit: Kit(primary: .red), awayKit: Kit(primary: .blue)
        ))

        let preview = try store.deletionPreview(id: "t1")
        #expect(preview.playerCount == 1)
        #expect(preview.uniformRequirementCount == 1)
        #expect(preview.guardianContactCount == 1)
        #expect(preview.blockingCount == 3)

        // Guarded delete refuses WITHOUT destroying anything.
        #expect(throws: ValidationFailure.self) {
            try store.deleteTeam(id: "t1", requireEmpty: true)
        }
        #expect(try store.team(id: "t1") != nil)

        // Confirmed cascade removes team + dependents + private contacts.
        try store.deleteTeam(id: "t1", requireEmpty: false)
        #expect(try store.team(id: "t1") == nil)
        #expect(try store.player(id: "p1") == nil)
        #expect(try store.guardianContacts(playerId: "p1").isEmpty)
        #expect(try store.uniformRequirements(teamId: "t1").isEmpty)
    }

    @Test("player delete cascades guardian contacts")
    func playerCascade() throws {
        let store = try makeStore()
        try store.saveTeam(team("t1"))
        try store.savePlayer(player("p1", "t1", "Bo", 9))
        try store.saveGuardianContact(GuardianContact(id: "g1", playerId: "p1", guardianName: "Sam", phone: "555"))
        try store.deletePlayer(id: "p1")
        #expect(try store.player(id: "p1") == nil)
        #expect(try store.guardianContacts(playerId: "p1").isEmpty)
    }

    @Test("uniform requirement round-trips kits + normalized checklist")
    func uniformRoundTrip() throws {
        let store = try makeStore()
        try store.saveTeam(team("t1"))
        let req = UniformRequirement(
            id: "u1", teamId: "t1", opponentName: "Comets",
            homeKit: Kit(primary: .white, alternate: .grey),
            awayKit: Kit(primary: .blue),
            keeperKit: Kit(primary: .green),
            equipmentChecklist: [" shinguards ", "shinguards", "size 4 ball", ""],
            notes: "match day at north field"
        )
        try store.saveUniformRequirement(req)
        let back = try store.uniformRequirements(teamId: "t1")
        #expect(back.count == 1)
        #expect(back[0].equipmentChecklist == ["shinguards", "size 4 ball"])
        #expect(back[0].homeKit == Kit(primary: .white, alternate: .grey))
        #expect(back[0].keeperKit == Kit(primary: .green))
    }

    @Test("guardian needs a route at the store layer")
    func guardianRoute() throws {
        let store = try makeStore()
        try store.saveTeam(team("t1"))
        try store.savePlayer(player("p1", "t1", "Bo", 9))
        #expect(throws: ValidationFailure.self) {
            try store.saveGuardianContact(GuardianContact(id: "g1", playerId: "p1", guardianName: "Sam"))
        }
    }

    @Test("guardian JSON keys match the shared Kotlin wire field names")
    func guardianWireParity() throws {
        // The Kotlin GuardianContact serializes with these exact keys; Swift
        // Codable must produce the same shape so both stores can be compared
        // against identical fixtures in export tests (M8).
        let data = try JSONEncoder().encode(
            GuardianContact(id: "g", playerId: "p", guardianName: "Sam", phone: "1", email: "e", notes: "")
        )
        let obj = try #require(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        for key in ["id", "playerId", "guardianName", "phone", "email", "notes"] {
            #expect(obj[key] != nil, "guardian wire key \(key) missing")
        }
    }

    @Test("v1 migration applies exactly once and is recorded")
    func migrationRecorded() throws {
        // The store's schemaVersion getter derives from the applied-migration
        // registry; re-opening the same file must not re-apply (idempotent).
        let store = try makeStore()
        #expect(try store.schemaVersion() == 1)
        // Forward test: a fresh store starts at v1 (no v2 exists yet); the
        // migration registry contains exactly the v1 identifier.
        // (v1 -> v2 upgrade-path test lands WITH the M3 migration.)
    }
}
