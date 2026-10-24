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
}
