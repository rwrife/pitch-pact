//
//  TeamsView.swift
//  PitchPact — M2 team directory (create / archive / delete with cascade
//  preview). All validation wording comes from the store's shared validation
//  (mirrors Kotlin `Validate`).
//

import SwiftUI
import UserNotifications
import PitchPactStore
import UniformTypeIdentifiers
@preconcurrency import PitchPactShared

private struct BackupDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }
    var text: String
    init(text: String) { self.text = text }
    init(configuration: ReadConfiguration) throws {
        guard let text = String(data: configuration.file.regularFileContents ?? Data(), encoding: .utf8) else {
            throw CocoaError(.fileReadCorruptFile)
        }
        self.text = text
    }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: Data(text.utf8))
    }
}

struct TeamsView: View {
    @State private var teams: [Team] = []
    @State private var showArchived = false
    @State private var showCreate = false
    @State private var newTeamName = ""
    @State private var createError: String?
    @State private var deleteTarget: Team?
    @State private var deletePreview: TeamDeletionPreview?
    @State private var backupDocument: BackupDocument?
    @State private var exportingBackup = false
    @State private var importingBackup = false
    @State private var incomingBackup: String?
    @State private var incomingCounts: String?
    @State private var dataError: String?
    @State private var csv: String?

    var body: some View {
        List {
            Section("Private data") {
                Button("Save backup JSON") {
                    do {
                        let raw = try AppTeamStore.shared.backupJSON()
                        try validateBackup(raw)
                        backupDocument = BackupDocument(text: raw)
                        exportingBackup = true
                    } catch { dataError = error.localizedDescription }
                }
                Button("Restore backup JSON") { importingBackup = true }
                ForEach(["schedule", "results", "availability"], id: \.self) { kind in
                    Button("Share \(kind) CSV") {
                        do {
                            let raw = try AppTeamStore.shared.backupJSON()
                            try validateBackup(raw)
                            let result = BackupFacade().export(json: raw, kind: kind)
                            guard !result.hasPrefix("ERROR:") else {
                                throw ValidationFailure("backup.csv", result)
                            }
                            csv = result
                        } catch { dataError = error.localizedDescription }
                    }
                }
                if let csv { ShareLink("Share CSV", item: csv) }
            }
            NavigationLink("Games & tournaments") { ScheduleView() }
            NavigationLink("Find & link teams") { RegistryView() }.accessibilityIdentifier("app.teams.registry")
            NavigationLink("Watch live game") { SpectatorView() }.accessibilityIdentifier("app.teams.watchLive")
            Toggle("Show archived", isOn: $showArchived)
                .accessibilityIdentifier("app.teams.showArchived")
                .onChange(of: showArchived) { _, _ in reload() }

            ForEach(teams) { team in
                NavigationLink {
                    TeamDetailView(teamId: team.id)
                } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(team.name)
                            .font(.headline)
                        Text(subtitle(for: team))
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                        HStack(spacing: 12) {
                            Button(team.isArchived ? "Unarchive" : "Archive") {
                                try? AppTeamStore.shared.archiveTeam(
                                    id: team.id,
                                    archived: !team.isArchived,
                                    atEpochMillis: Int64(Date().timeIntervalSince1970 * 1000)
                                )
                                reload()
                            }
                            .accessibilityIdentifier("app.teams.archive.\(team.id)")
                            Button("Delete", role: .destructive) {
                                deleteTarget = team
                                deletePreview = try? AppTeamStore.shared.deletionPreview(id: team.id)
                            }
                            .accessibilityIdentifier("app.teams.delete.\(team.id)")
                        }
                        .buttonStyle(.borderless)
                    }
                    .padding(.vertical, 4)
                    .accessibilityElement(children: .contain)
                    .accessibilityLabel("Team \(team.name)")
                }
                .accessibilityIdentifier("app.teams.row.\(team.id)")
            }

            if teams.isEmpty {
                Text("No teams yet. Tap + to create one.")
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier("app.teams.empty")
            }
        }
        .navigationTitle("Teams")
        .toolbar {
            Button {
                showCreate = true
            } label: {
                Image(systemName: "plus")
            }
            .accessibilityIdentifier("app.teams.create")
            .accessibilityLabel("Create team")
        }
        .onAppear { reload() }
        .sheet(isPresented: $showCreate) {
            NavigationStack {
                Form {
                    TextField("Team name", text: $newTeamName)
                        .accessibilityIdentifier("app.teams.nameField")
                    if let createError {
                        Text(createError)
                            .font(.footnote)
                            .foregroundStyle(.red)
                            .accessibilityIdentifier("app.teams.createError")
                    }
                }
                .scrollDismissesKeyboard(.interactively)
                .navigationTitle("New team")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { showCreate = false }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Save", action: saveTeam)
                            .accessibilityIdentifier("app.teams.saveTeam")
                    }
                }
            }
        }
        .confirmationDialog(
            deleteTitle,
            isPresented: Binding(
                get: { deleteTarget != nil },
                set: { if !$0 { deleteTarget = nil } }
            ),
            titleVisibility: .visible,
            presenting: deletePreview
        ) { preview in
            Button("Delete everything", role: .destructive) {
                if let team = deleteTarget {
                    do {
                        let ids = try AppTeamStore.shared.fixtures().filter { $0.homeTeamId == team.id || $0.awayTeamId == team.id }.map(\.id)
                        try AppTeamStore.shared.deleteTeam(id: team.id, requireEmpty: false)
                        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: ids)
                        deleteTarget = nil; reload()
                    } catch { createError = error.localizedDescription; deleteTarget = nil }
                }
            }
            .accessibilityIdentifier("app.teams.confirmDelete")
            Button("Cancel", role: .cancel) { deleteTarget = nil }
        } message: { preview in
            Text(preview.blockingCount == 0
                 ? "This team has no players, uniforms, guardian contacts, or fixtures. Deleting removes it permanently."
                 : "This deletes the team AND \(preview.playerCount) players, \(preview.uniformRequirementCount) uniform requirements, \(preview.guardianContactCount) guardian contacts, \(preview.fixtureCount) fixtures and their match ledgers. This cannot be undone.")
        }
        .fileExporter(isPresented: $exportingBackup, document: backupDocument, contentType: .json, defaultFilename: "pitch-pact-backup.json") { _ in
            backupDocument = nil
        }
        .fileImporter(isPresented: $importingBackup, allowedContentTypes: [.json]) { result in
            do {
                let url = try result.get()
                guard url.startAccessingSecurityScopedResource() else {
                    throw CocoaError(.fileReadNoPermission)
                }
                defer { url.stopAccessingSecurityScopedResource() }
                let text = try String(contentsOf: url, encoding: .utf8)
                try validateBackup(text)
                incomingBackup = text
                incomingCounts = BackupFacade().preview(json: text)
            } catch { dataError = error.localizedDescription }
        }
        .confirmationDialog(
            "Replace local data?",
            isPresented: Binding(
                get: { incomingBackup != nil },
                set: { if !$0 { incomingBackup = nil; incomingCounts = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("Replace", role: .destructive) {
                guard let incoming = incomingBackup else { return }
                do {
                    try AppTeamStore.shared.replaceBackup(incoming, validate: validateBackup)
                    reload()
                } catch { dataError = error.localizedDescription }
                incomingBackup = nil; incomingCounts = nil
            }
            Button("Cancel", role: .cancel) { incomingBackup = nil; incomingCounts = nil }
        } message: {
            Text("Preview: \(incomingCounts ?? "counts unavailable"). Incoming records will overwrite local data completely. Cancel changes nothing.")
        }
        .alert("Data operation failed", isPresented: Binding(
            get: { dataError != nil },
            set: { if !$0 { dataError = nil } }
        )) {
            Button("OK", role: .cancel) { dataError = nil }
        } message: { Text(dataError ?? "") }
    }

    private func validateBackup(_ json: String) throws {
        let result = BackupFacade().preview(json: json)
        guard result.hasPrefix("OK:") else {
            throw ValidationFailure("backup.preview.failed", result)
        }
    }

    private var deleteTitle: String {
        "Delete \(deleteTarget?.name ?? "team")?"
    }

    private func saveTeam() {
        do {
            try AppTeamStore.shared.saveTeam(Team(
                id: UUID().uuidString,
                name: newTeamName,
                createdAtEpochMillis: Int64(Date().timeIntervalSince1970 * 1000)
            ))
            showCreate = false
            newTeamName = ""
            createError = nil
            reload()
        } catch let failure as ValidationFailure {
            createError = failure.message
        } catch {
            createError = error.localizedDescription
        }
    }

    private func subtitle(for team: Team) -> String {
        let count = (try? AppTeamStore.shared.players(teamId: team.id).count) ?? 0
        return team.isArchived ? "\(count) players · archived" : "\(count) players"
    }

    private func reload() {
        teams = (try? AppTeamStore.shared.teams(includeArchived: showArchived)) ?? []
    }
}
