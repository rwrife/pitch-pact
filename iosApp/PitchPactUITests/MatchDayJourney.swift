import XCTest

final class MatchDayJourney: XCTestCase {
    @MainActor func testLaunchCaptureRestartAndUndo() {
        let app = XCUIApplication()
        app.launchArguments = ["--m4-ui-test"]
        app.launch()
        XCTAssertTrue(app.staticTexts["officialScore"].waitForExistence(timeout: 20))
        for _ in 0..<4 where !app.buttons["Capture event"].isHittable { app.swipeUp() }
        app.buttons["Capture event"].tap()
        app.swipeDown()
        XCTAssertEqual(app.staticTexts["pendingScore"].label, "Pending score 1-0")
        XCTAssertEqual(app.staticTexts["officialScore"].label, "OFFICIAL 0-0")
        app.terminate(); app.launch()
        XCTAssertTrue(app.staticTexts["pendingScore"].waitForExistence(timeout: 20))
        XCTAssertEqual(app.staticTexts["pendingScore"].label, "Pending score 1-0")
        for _ in 0..<4 where !app.buttons["Undo uncommitted tail"].isHittable { app.swipeUp() }
        app.buttons["Undo uncommitted tail"].tap()
        XCTAssertEqual(app.staticTexts["pendingScore"].label, "Pending score 0-0")
    }
}
