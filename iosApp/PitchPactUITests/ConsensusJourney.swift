import XCTest

final class ConsensusJourney: XCTestCase {
    @MainActor func testTwoSidelineSubmitAcceptLoop() {
        let app = XCUIApplication()
        app.launchArguments = ["--m7-ui-test"]
        app.launch()
        XCTAssertTrue(app.staticTexts["consensus.official"].waitForExistence(timeout: 20))
        XCTAssertTrue(app.staticTexts["consensus.official"].label.contains("OFFICIAL 0-0"))

        let submit = app.buttons["consensus.submit"]
        for _ in 0..<4 where !submit.isHittable { app.swipeUp() }
        XCTAssertTrue(submit.isHittable)
        submit.tap()
        XCTAssertTrue(app.staticTexts["consensus.official"].label.contains("OFFICIAL 0-0"))
        XCTAssertEqual(app.staticTexts["consensus.pendingCount"].label, "PENDING REVIEW 1")
        XCTAssertTrue(app.buttons["consensus.withdraw"].exists)

        let switchSide = app.buttons["consensus.switch"]
        for _ in 0..<4 where !switchSide.isHittable { app.swipeDown() }
        XCTAssertTrue(switchSide.isHittable)
        switchSide.tap()
        XCTAssertEqual(app.staticTexts["consensus.side"].label, "You are the away sideline")
        XCTAssertTrue(app.staticTexts["consensus.pending"].label.contains("Review:"))
        XCTAssertTrue(app.staticTexts["consensus.official"].label.contains("OFFICIAL 0-0"))

        let accept = app.buttons["consensus.accept"]
        for _ in 0..<4 where !accept.isHittable { app.swipeUp() }
        XCTAssertTrue(accept.isHittable)
        accept.tap()
        XCTAssertEqual(app.staticTexts["consensus.official"].label, "OFFICIAL 1-0")
        XCTAssertEqual(app.staticTexts["consensus.pendingCount"].label, "PENDING REVIEW 0")
        XCTAssertTrue(app.staticTexts["consensus.ledger"].label.contains("ACCEPTED"))
        XCTAssertTrue(app.staticTexts["consensus.ledger"].label.contains("resolved by AWAY"))
    }
}
