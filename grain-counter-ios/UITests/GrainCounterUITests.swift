import XCTest

/**
 * End-to-end UI tests on the simulator: home, counting a photo, guided review, history, dark mode, rotation, and
 * Apple's accessibility audit on every screen. Screenshots are attached to the result and, when SCREENSHOT_DIR is
 * set (TEST_RUNNER_SCREENSHOT_DIR on the xcodebuild command line), written there as PNG files.
 */
final class GrainCounterUITests: XCTestCase {

    override func setUp() {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
    }

    private func snap(_ name: String) {
        let shot = XCUIScreen.main.screenshot()
        let a = XCTAttachment(screenshot: shot)
        a.name = name
        a.lifetime = .keepAlways
        add(a)
        if let dir = ProcessInfo.processInfo.environment["SCREENSHOT_DIR"] {
            try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
            try? shot.pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent(name + ".png"))
        }
    }

    private func photo(_ name: String) -> String {
        return Bundle(for: GrainCounterUITests.self).path(forResource: name, ofType: "jpg")!
    }

    private func launch(image: String? = nil, dark: Bool = false) -> XCUIApplication {
        let app = XCUIApplication()
        if let image = image { app.launchArguments += ["-uitest-image", photo(image)] }
        if dark { app.launchArguments.append("-uitest-dark") }
        app.launch()
        return app
    }

    /// Waits for the count to be shown and returns it.
    private func waitForCount(_ app: XCUIApplication) -> Int {
        let label = app.staticTexts.matching(NSPredicate(format: "label MATCHES '^[0-9]+ grains?$'")).firstMatch
        XCTAssertTrue(label.waitForExistence(timeout: 30), "the count never appeared")
        let p = NSPredicate(format: "label != '–'")
        expectation(for: p, evaluatedWith: label)
        waitForExpectations(timeout: 10)
        sleep(2) // the number counts up
        return Int(label.label.split(separator: " ")[0]) ?? -1
    }

    private func audit(_ app: XCUIApplication) throws {
        if #available(iOS 17.0, *) {
            try app.performAccessibilityAudit(for: [.sufficientElementDescription, .hitRegion, .trait]) { issue in
                // The photo canvas is one image element by design; its markers are drawn, not separate controls.
                issue.element?.elementType == .image
            }
        }
    }

    func testHomeScreen() throws {
        let app = launch()
        XCTAssertTrue(app.buttons["Take Photo"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["Choose from Photos"].exists)
        sleep(1)
        snap("01_home")
        try audit(app)
    }

    func testCountReviewAndHistory() throws {
        let app = launch(image: "lentils_with_print")
        let n = waitForCount(app)
        XCTAssertTrue((95...110).contains(n), "lentils_with_print counted \(n) (100 grains)")
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS 'print marks ignored'")).firstMatch.exists,
                      "printed marks not reported as ignored")
        snap("02_result")
        try audit(app)

        let review = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Review '")).firstMatch
        XCTAssertTrue(review.exists && review.isEnabled, "nothing to review")
        review.tap()
        let yes = app.buttons["Yes, a grain"]
        XCTAssertTrue(yes.waitForExistence(timeout: 5))
        sleep(1)
        snap("03_review")
        try audit(app)
        var answered = 0
        while yes.exists && yes.isHittable && answered < 40 {
            yes.tap()
            answered += 1
            usleep(700_000)
        }
        let checked = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Checked by you'")).firstMatch
        XCTAssertTrue(checked.waitForExistence(timeout: 5), "status is not 'Checked by you' after review")
        snap("04_review_done")

        app.buttons["Back"].tap()
        XCTAssertTrue(app.buttons["Take Photo"].waitForExistence(timeout: 5), "Back did not return home")
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label ENDSWITH 'grains'")).firstMatch
                        .waitForExistence(timeout: 5),
                      "the count is not in Recent")
        snap("05_home_recent")

        app.buttons["History"].tap()
        XCTAssertTrue(app.navigationBars["History"].waitForExistence(timeout: 5))
        sleep(1)
        snap("06_history")
        try audit(app)
        app.cells.firstMatch.tap()
        sleep(2)
        snap("07_picture")
        try audit(app)
    }

    func testDarkModeMixedGrainsWithGlare() throws {
        let app = launch(image: "mixed_with_glare", dark: true)
        let n = waitForCount(app)
        XCTAssertEqual(n, 60, "mixed grains with glare: 60 grains")
        snap("08_dark_result")
        app.buttons["Back"].tap()
        XCTAssertTrue(app.buttons["Take Photo"].waitForExistence(timeout: 5))
        sleep(1)
        snap("09_dark_home")
        try audit(app)
    }

    func testRotation() throws {
        let app = launch(image: "lentils_with_print")
        _ = waitForCount(app)
        XCUIDevice.shared.orientation = .landscapeLeft
        sleep(2)
        snap("10_landscape_result")
        XCTAssertTrue(app.buttons["Back"].isHittable, "controls hidden in landscape")
        XCUIDevice.shared.orientation = .portrait
    }
}
