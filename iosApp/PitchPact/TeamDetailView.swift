//
//  TeamDetailView.swift
//  PitchPact — M2 team detail: roster CRUD (jersey uniqueness via shared
//  validation), per-opponent uniform requirements, guardian contacts.
//  Guardian rows are private: the copy below states that explicitly, and no
//  guardian field ever leaves the device (privacy contract).
//

import SwiftUI
import PitchPactStore

struct TeamDetailView: View {
    let teamId: String

    @State private var players: [Player] = []
    @State private var uniforms: [UniformRequirement] = []
    @State private var showAddPlayer = false
    @State private var showAddUniform = false
    @State private var deletePlayerTarget: Player?
    @State private var guardianSheetPlayer: Player?

    var body: some View {
        List {
            Section("Roster") {
                ForEach(players) { player in
                    playerRow(player)
                }
                if players.isEmpty {
                    Text("No players yet.")
                        .foregroundStyle(.secondary)
                        .accessibilityIdentifier("app.team.noPlayers")
                }
                Button("Add player") { showAddPlayer = true }
                    .accessibilityIdentifier("app.team.addPlayer")
            }

            Section("Uniform requirements") {
                ForEach(uniforms) { uniform in
                    uniformRow(uniform)
                }
                Button("Add uniform requirement") { showAddUniform = true }
                    .accessibilityIdentifier("app.team.addUniform")
            }
        }
        .navigationTitle(teamName)
        .onAppear(perform: reloadSafely)
        .sheet(isPresented: $showAddPlayer) {
            PlayerForm(teamId: teamId) { reloadSafely() }
        }
        .sheet(isPresented: $showAddUniform) {
            UniformForm(teamId: teamId) { reloadSafely() }
        }
        .sheet(item: $guardianSheetPlayer) { player in
            GuardianForm(player: player) { reloadSafely() }
        }
        .confirmationDialog(
            "Remove \(deletePlayerTarget?.name ?? "")?",
            isPresented: Binding(
                get: { deletePlayerTarget != nil },
                set: { if !$0 { deletePlayerTarget = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("Remove", role: .destructive) {
                if let player = deletePlayerTarget {
                    try? AppTeamStore.shared.deletePlayer(id: player.id)
                    deletePlayerTarget = nil
                    reloadSafely()
                }
            }
            .accessibilityIdentifier("app.team.confirmRemovePlayer")
            Button("Cancel", role: .cancel) { deletePlayerTarget = nil }
        } message: {
            Text("This also removes their guardian contact(s).")
        }
    }

    @ViewBuilder
    private func playerRow(_ player: Player) -> some View {
        let guardians = (try? AppTeamStore.shared.guardianContacts(playerId: player.id)) ?? []
        VStack(alignment: .leading, spacing: 4) {
            Text("#\(player.jerseyNumber)  \(player.name)")
                .font(.body)
            ForEach(guardians) { g in
                Text(guardianSummary(g))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            HStack(spacing: 12) {
                Button("Guardians") { guardianSheetPlayer = player }
                    .accessibilityIdentifier("app.team.guardians.\(player.id)")
                Button("Remove", role: .destructive) { deletePlayerTarget = player }
                    .accessibilityIdentifier("app.team.removePlayer.\(player.id)")
            }
            .buttonStyle(.borderless)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Player \(player.name), jersey \(player.jerseyNumber)")
    }

    @ViewBuilder
    private func uniformRow(_ uniform: UniformRequirement) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("vs \(uniform.opponentName)").font(.body)
            Text(uniformSummary(uniform))
                .font(.footnote)
                .foregroundStyle(.secondary)
            if !uniform.equipmentChecklist.isEmpty {
                Text("Kit: \(uniform.equipmentChecklist.joined(separator: ", "))")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Button("Remove", role: .destructive) {
                try? AppTeamStore.shared.deleteUniformRequirement(id: uniform.id)
                reloadSafely()
            }
            .buttonStyle(.borderless)
            .accessibilityIdentifier("app.team.removeUniform.\(uniform.id)")
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Uniform versus \(uniform.opponentName)")
    }

    private func guardianSummary(_ g: GuardianContact) -> String {
        var parts = [g.guardianName]
        if !g.phone.isEmpty { parts.append(g.phone) }
        if !g.email.isEmpty { parts.append(g.email) }
        return parts.joined(separator: " · ")
    }

    private func uniformSummary(_ u: UniformRequirement) -> String {
        var text = "Home \(u.homeKit.primary.rawValue)"
        if let alt = u.homeKit.alternate { text += "/\(alt.rawValue)" }
        text += " · Away \(u.awayKit.primary.rawValue)"
        if let keeper = u.keeperKit { text += " · Keeper \(keeper.primary.rawValue)" }
        return text
    }

    private var teamName: String {
        (try? AppTeamStore.shared.team(id: teamId))?.name ?? "Team"
    }

    private func reloadSafely() {
        players = (try? AppTeamStore.shared.players(teamId: teamId)) ?? []
        uniforms = (try? AppTeamStore.shared.uniformRequirements(teamId: teamId)) ?? []
    }
}

// MARK: - Forms (validation errors render inline, verbatim from shared rules)

struct PlayerForm: View {
    @Environment(\.dismiss) private var dismiss
    let teamId: String
    @State private var name = ""
    @State private var jersey = ""
    @State private var error: String?
    let onSave: () -> Void

    var body: some View {
        NavigationStack {
            Form {
                TextField("Player name", text: $name)
                    .accessibilityIdentifier("app.player.nameField")
                TextField("Jersey number (1–99)", text: $jersey)
                    .keyboardType(.numberPad)
                    .accessibilityIdentifier("app.player.jerseyField")
                if let error {
                    Text(error)
                        .font(.footnote)
                        .foregroundStyle(.red)
                        .accessibilityIdentifier("app.player.error")
                }
            }
            .navigationTitle("Add player")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save", action: save)
                        .accessibilityIdentifier("app.player.save")
                }
            }
        }
    }

    private func save() {
        do {
            try AppTeamStore.shared.savePlayer(Player(
                id: UUID().uuidString,
                teamId: teamId,
                name: name,
                jerseyNumber: Int(jersey) ?? -1,
                createdAtEpochMillis: Int64(Date().timeIntervalSince1970 * 1000)
            ))
            onSave()
            dismiss()
        } catch let failure as ValidationFailure {
            error = failure.message
        } catch let failure {
            error = failure.localizedDescription
        }
    }
}

struct UniformForm: View {
    @Environment(\.dismiss) private var dismiss
    let teamId: String
    /// Sentinel raw value for "no keeper kit" in the optional Picker.
    private static let noneSentinel = "__none__"
    @State private var opponent = ""
    @State private var home: KitColor = .white
    @State private var away: KitColor = .blue
    @State private var keeper: KitColor?
    @State private var error: String?
    let onSave: () -> Void

    var body: some View {
        NavigationStack {
            Form {
                TextField("Opponent", text: $opponent)
                    .accessibilityIdentifier("app.uniform.opponentField")
                Picker("Home kit", selection: $home) {
                    ForEach(KitColor.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                }
                .accessibilityIdentifier("app.uniform.homeKit")
                Picker("Away kit", selection: $away) {
                    ForEach(KitColor.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                }
                .accessibilityIdentifier("app.uniform.awayKit")
                Picker("Keeper kit (optional)", selection: Binding(
                    get: { keeper.map(\.rawValue) ?? Self.noneSentinel },
                    set: { keeper = KitColor(rawValue: $0) }
                )) {
                    Text("none").tag(Self.noneSentinel)
                    ForEach(KitColor.allCases, id: \.rawValue) { color in
                        Text(color.rawValue).tag(color.rawValue)
                    }
                }
                .accessibilityIdentifier("app.uniform.keeperKit")
                if let error {
                    Text(error).font(.footnote).foregroundStyle(.red)
                        .accessibilityIdentifier("app.uniform.error")
                }
            }
            .navigationTitle("Uniform requirement")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save", action: save)
                        .accessibilityIdentifier("app.uniform.save")
                }
            }
        }
    }

    private func save() {
        do {
            try AppTeamStore.shared.saveUniformRequirement(UniformRequirement(
                id: UUID().uuidString,
                teamId: teamId,
                opponentName: opponent,
                homeKit: Kit(primary: home),
                awayKit: Kit(primary: away),
                keeperKit: keeper.map { Kit(primary: $0) },
                equipmentChecklist: ["shinguards"]
            ))
            onSave()
            dismiss()
        } catch let failure as ValidationFailure {
            error = failure.message
        } catch let failure {
            error = failure.localizedDescription
        }
    }
}

struct GuardianForm: View {
    @Environment(\.dismiss) private var dismiss
    let player: Player
    @State private var name = ""
    @State private var phone = ""
    @State private var email = ""
    @State private var error: String?
    @State private var existing: [GuardianContact] = []
    let onSave: () -> Void

    var body: some View {
        NavigationStack {
            Form {
                Section("Existing") {
                    ForEach(existing) { g in
                        HStack {
                            Text("\(g.guardianName) \(g.phone) \(g.email)".trimmingCharacters(in: .whitespaces))
                            Spacer()
                            Button("Remove", role: .destructive) {
                                try? AppTeamStore.shared.deleteGuardianContact(id: g.id)
                                existing.removeAll { $0.id == g.id }
                                onSave()
                            }
                            .buttonStyle(.borderless)
                            .accessibilityIdentifier("app.guardian.remove.\(g.id)")
                        }
                    }
                    if existing.isEmpty {
                        Text("None yet.").foregroundStyle(.secondary)
                    }
                }
                Section("Add guardian") {
                    TextField("Guardian name", text: $name)
                        .accessibilityIdentifier("app.guardian.nameField")
                    TextField("Phone", text: $phone)
                        .keyboardType(.phonePad)
                        .accessibilityIdentifier("app.guardian.phoneField")
                    TextField("Email", text: $email)
                        .keyboardType(.emailAddress)
                        .autocorrectionDisabled()
                        .accessibilityIdentifier("app.guardian.emailField")
                    Text("Guardian contacts stay on this device — never shared or broadcast.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .accessibilityIdentifier("app.guardian.privacyNote")
                    if let error {
                        Text(error).font(.footnote).foregroundStyle(.red)
                            .accessibilityIdentifier("app.guardian.error")
                    }
                }
            }
            .navigationTitle("Guardians — \(player.name)")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add", action: add)
                        .accessibilityIdentifier("app.guardian.add")
                }
            }
            .onAppear {
                existing = (try? AppTeamStore.shared.guardianContacts(playerId: player.id)) ?? []
            }
        }
    }

    private func add() {
        do {
            try AppTeamStore.shared.saveGuardianContact(GuardianContact(
                id: UUID().uuidString,
                playerId: player.id,
                guardianName: name,
                phone: phone,
                email: email
            ))
            name = ""
            phone = ""
            email = ""
            error = nil
            existing = (try? AppTeamStore.shared.guardianContacts(playerId: player.id)) ?? []
            onSave()
        } catch let failure as ValidationFailure {
            error = failure.message
        } catch let failure {
            error = failure.localizedDescription
        }
    }
}
