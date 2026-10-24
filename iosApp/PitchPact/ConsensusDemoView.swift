//
//  ConsensusDemoView.swift
//  PitchPact — M7 offline two-sideline scorer journey (XCUITest surface).
//
//  Drives the SAME shared ConsensusBook rules the server runs (submit →
//  pending → opposing accept → official) with no transport, so the submit/
//  accept loop is provable on-device. Production scoring uses the linked
//  server panel in RegistryView; this view is the launch-argument demo for
//  the native journey. All wording and rules come from the KMP facade.
//

import SwiftUI
@preconcurrency import PitchPactShared

@MainActor struct ConsensusDemoView: View {
    @State private var book = "[]"
    @State private var side = "HOME"
    @State private var jersey = "9"
    @State private var snapshot = ""
    @State private var message = ""
    private let rules = ConsensusFacade()
    private var otherSide: String { side == "HOME" ? "AWAY" : "HOME" }

    var body: some View {
        Form {
            Section("Consensus scoring") {
                Text("You are the \(side.lowercased()) sideline").accessibilityIdentifier("consensus.side")
                TextField("Scorer jersey #", text: $jersey)
                    .keyboardType(.numberPad).accessibilityIdentifier("consensus.jersey")
                Button("Submit goal for opponent review") { submit() }
                    .contentShape(Rectangle()).accessibilityIdentifier("consensus.submit")
                Button("Switch to \(otherSide.lowercased()) sideline") {
                    side = otherSide; refresh()
                }.contentShape(Rectangle()).accessibilityIdentifier("consensus.switch")
            }
            Section("Scoreboard") {
                Text(scoreline).accessibilityIdentifier("consensus.official")
                Text("PENDING REVIEW \(pendingRows.count)").accessibilityIdentifier("consensus.pendingCount")
                ForEach(pendingRows, id: \.self) { row in
                    let parts = row.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
                    if parts.count == 3 {
                        Text(parts[1]).accessibilityIdentifier("consensus.pending")
                        if parts[2] == "review" {
                            Button("Accept") { review(parts[0], "accept") }.contentShape(Rectangle()).accessibilityIdentifier("consensus.accept")
                            Button("Reject") { review(parts[0], "reject") }.accessibilityIdentifier("consensus.reject")
                        } else {
                            Button("Withdraw") { review(parts[0], "withdraw") }.accessibilityIdentifier("consensus.withdraw")
                        }
                    }
                }
            }
            Section("Record ledger") {
                Text(ledger).accessibilityIdentifier("consensus.ledger")
            }
            if !message.isEmpty { Text(message).foregroundStyle(.orange).accessibilityIdentifier("consensus.error") }
        }
        .onAppear { refresh() }
    }

    // The submit button doubles as keyboard dismissal: tapping it while the
    // number pad owns focus resigns the field before the action runs.
    private func submit() {
        do {
            book = try rules.demoSubmit(bookJson: book, matchId: "demo", side: side,
                changeId: "c-\(UUID().uuidString.prefix(8))", eventId: "e-\(UUID().uuidString.prefix(8))",
                epoch: Int64(Date().timeIntervalSince1970 * 1000), jersey: Int32(jersey) ?? -1,
                now: Int64(Date().timeIntervalSince1970 * 1000))
            message = ""; refresh()
        } catch { message = "Submit refused: \(error.localizedDescription)" }
    }

    private func review(_ changeId: String, _ action: String) {
        do {
            book = try rules.demoReview(bookJson: book, changeId: changeId, actor: side,
                action: action, now: Int64(Date().timeIntervalSince1970 * 1000))
            message = ""; refresh()
        } catch { message = "Review refused: \(error.localizedDescription)" }
    }

    private func refresh() {
        snapshot = (try? rules.demoProject(bookJson: book, whistlesJson: "[]")) ?? ""
    }
    // Never call the facade on an empty snapshot: Kotlin throws there and a
    // thrown Kotlin error is fatal across the ObjC bridge even under try?.
    private var scoreline: String {
        snapshot.isEmpty ? "OFFICIAL 0-0 · live" : ((try? rules.scoreboard(snapshotJson: snapshot).components(separatedBy: "\n").first) ?? "OFFICIAL — —")
    }
    private var pendingRows: [String] { snapshot.isEmpty ? [] : ((try? rules.pendingRows(snapshotJson: snapshot, mySide: side)) ?? []) }
    private var ledger: String { snapshot.isEmpty ? "No consensus records" : ((try? rules.recordLedger(snapshotJson: snapshot)) ?? "") }
}
