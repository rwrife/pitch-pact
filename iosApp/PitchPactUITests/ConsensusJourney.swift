import XCTest

final class ConsensusJourney: XCTestCase {
    /// A tap schedules state on the main actor and can land on a merged label
    /// region without firing the action. Retry until the awaited consequence
    /// actually appears instead of racing (or silently losing) the render.
    private func tapUntil(_ control: XCUIElement, timeout: TimeInterval = 20,
                          _ settled: () -> Bool, file: StaticString = #filePath, line: UInt = #line) {
        let deadline = Date().addingTimeInterval(timeout)
        var attempt = 0
        while Date() < deadline {
            if settled() { return }
            // Merged Form rows can surface the identifier on the row frame or
            // on the nested label; rotate through every candidate until the
            // action actually fires (asserting the consequence, not the tap).
            switch attempt % 2 {
            case 0: control.tap()
            default: control.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
            }
            attempt += 1
            usleep(200_000)
        }
        XCTAssertTrue(settled(), "tap on \(control.identifier) never produced the awaited state", file: file, line: line)
    }
    private func waitLabel(_ element: XCUIElement, _ expected: String, timeout: TimeInterval = 20,
                           file: StaticString = #filePath, line: UInt = #line) {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if element.label == expected { return }
            usleep(200_000)
        }
        XCTAssertEqual(element.label, expected, file: file, line: line)
    }
    private func waitLabelContains(_ element: XCUIElement, _ needle: String, timeout: TimeInterval = 20,
                                   file: StaticString = #filePath, line: UInt = #line) {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if element.label.contains(needle) { return }
            usleep(200_000)
        }
        XCTAssertTrue(element.label.contains(needle), "label was \(element.label)", file: file, line: line)
    }
    private func waitExists(_ element: XCUIElement, timeout: TimeInterval = 20,
                            file: StaticString = #filePath, line: UInt = #line) {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if element.exists { return }
            usleep(200_000)
        }
        XCTAssertTrue(element.exists, file: file, line: line)
    }

    @MainActor func testTwoSidelineSubmitAcceptLoop() {
        let app = XCUIApplication()
        app.launchArguments = ["--m7-ui-test"]
        app.launch()
        XCTAssertTrue(app.staticTexts["consensus.official"].waitForExistence(timeout: 20))
        waitLabelContains(app.staticTexts["consensus.official"], "OFFICIAL 0-0")

        let official = app.staticTexts["consensus.official"]
        let pendingCount = app.staticTexts["consensus.pendingCount"]
        let submit = app.buttons["consensus.submit"]
        for _ in 0..<4 where !submit.isHittable { app.swipeUp() }
        XCTAssertTrue(submit.isHittable)
        tapUntil(submit) { pendingCount.label == "PENDING REVIEW 1" }
        waitExists(app.buttons["consensus.withdraw"])
        waitLabelContains(official, "OFFICIAL 0-0")

        let switchSide = app.buttons["consensus.switch"]
        for _ in 0..<4 where !switchSide.isHittable { app.swipeDown() }
        XCTAssertTrue(switchSide.isHittable)
        let sideText = app.staticTexts["consensus.side"]
        tapUntil(switchSide) { sideText.label == "You are the away sideline" }
        waitLabelContains(app.staticTexts["consensus.pending"], "Review:")
        waitLabelContains(official, "OFFICIAL 0-0")

        let accept = app.buttons["consensus.accept"]
        for _ in 0..<4 where !accept.isHittable { app.swipeUp() }
        XCTAssertTrue(accept.isHittable)
        tapUntil(accept) { pendingCount.label == "PENDING REVIEW 0" }
        waitLabelContains(official, "OFFICIAL 1-0")
        waitLabelContains(app.staticTexts["consensus.ledger"], "ACCEPTED")
        waitLabelContains(app.staticTexts["consensus.ledger"], "resolved by AWAY")
    }

    /// Exercises the production networked `ConsensusSection` (embedded in `RegistryView`)
    /// against an environment-supplied local Ktor server. When the runner did not
    /// supply server parameters, this test skips safely so basic builds continue.
    @MainActor func testProductionNetworkedScorerFlow() throws {
        let env = ProcessInfo.processInfo.environment
        guard let endpoint = env["PITCH_TEST_ENDPOINT"], !endpoint.isEmpty,
              let link = env["PITCH_TEST_LINK"], !link.isEmpty,
              let home = env["PITCH_TEST_HOME"], !home.isEmpty,
              let away = env["PITCH_TEST_AWAY"], !away.isEmpty,
              let homeKey = env["PITCH_TEST_HOME_KEY"], !homeKey.isEmpty,
              let awayKey = env["PITCH_TEST_AWAY_KEY"], !awayKey.isEmpty else {
            if env["PITCH_REQUIRE_NETWORK_TEST"] == "1" {
                XCTFail("Production scorer network test parameters not supplied")
            }
            throw XCTSkip("Production scorer network test parameters not supplied")
        }

        let matchId = "net-match-\(UUID().uuidString.prefix(8))"

        // Step 1: Home scorer launches, enters match + jersey, submits goal over real HTTP.
        let homeApp = XCUIApplication()
        homeApp.launchArguments = ["--m7-network-ui-test"]
        homeApp.launchEnvironment = [
            "PITCH_TEST_MATCH": matchId,
            "PITCH_TEST_ENDPOINT": endpoint,
            "PITCH_TEST_LINK": link,
            "PITCH_TEST_HOME": home,
            "PITCH_TEST_AWAY": away,
            "PITCH_TEST_TEAM": home,
            "PITCH_TEST_KEY": homeKey,
        ]
        homeApp.launch()

        let matchField = homeApp.textFields["consensus.matchId"]
        XCTAssertTrue(matchField.waitForExistence(timeout: 20))
        XCTAssertEqual(matchField.value as? String, matchId)
        XCTAssertEqual(homeApp.textFields["consensus.jersey"].value as? String, "9")

        let submit = homeApp.buttons["consensus.submit"]
        for _ in 0..<4 where !submit.isHittable { homeApp.swipeUp() }
        XCTAssertTrue(submit.isHittable)
        tapUntil(submit) { homeApp.staticTexts["consensus.scoreboard"].label.contains("PENDING REVIEW: 1") }
        waitLabelContains(homeApp.staticTexts["consensus.scoreboard"], "OFFICIAL 0-0")
        homeApp.terminate()

        // Step 2: Away scorer launches against same server, views pending review, accepts it.
        let awayApp = XCUIApplication()
        awayApp.launchArguments = ["--m7-network-ui-test"]
        awayApp.launchEnvironment = [
            "PITCH_TEST_MATCH": matchId,
            "PITCH_TEST_ENDPOINT": endpoint,
            "PITCH_TEST_LINK": link,
            "PITCH_TEST_HOME": home,
            "PITCH_TEST_AWAY": away,
            "PITCH_TEST_TEAM": away,
            "PITCH_TEST_KEY": awayKey,
        ]
        awayApp.launch()

        let awayMatch = awayApp.textFields["consensus.matchId"]
        XCTAssertTrue(awayMatch.waitForExistence(timeout: 20))
        XCTAssertEqual(awayMatch.value as? String, matchId)

        let refresh = awayApp.buttons["Refresh official score"]
        for _ in 0..<4 where !refresh.isHittable { awayApp.swipeUp() }
        XCTAssertTrue(refresh.isHittable)
        tapUntil(refresh) { awayApp.buttons["consensus.accept"].exists }

        let accept = awayApp.buttons["consensus.accept"]
        for _ in 0..<4 where !accept.isHittable { awayApp.swipeUp() }
        XCTAssertTrue(accept.isHittable)
        tapUntil(accept) { awayApp.staticTexts["consensus.scoreboard"].label.contains("OFFICIAL 1-0") }

        waitLabelContains(awayApp.staticTexts["consensus.ledger"], "ACCEPTED")
        waitLabelContains(awayApp.staticTexts["consensus.ledger"], "resolved by AWAY")
        awayApp.terminate()
    }
}
