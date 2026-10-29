import XCTest
@testable import GrainCore

/// The logic behind tap-to-add / tap-to-remove / undo (same cases as the Android MarkersTest).
final class MarkersTests: XCTestCase {
    func testOrderHitAddRemoveUndo() {
        let m = Markers()
        // three markers out of order; two share a row band (band=50)
        m.set(xs: [300, 100, 200], ys: [210, 20, 30], uncertain: [false, true, false], band: 50)
        XCTAssertEqual(m.count, 3, "set() keeps all markers")
        XCTAssertTrue(m[0].x == 100 && m[1].x == 200 && m[2].x == 300, "reading order: rows first, then left-to-right")
        XCTAssertTrue(m[0].kind == .uncertain && m[1].kind == .auto, "uncertain flags follow their markers when sorted")
        XCTAssertEqual(m.count(of: .uncertain), 1)
        XCTAssertFalse(m.canUndo, "nothing to undo initially")

        XCTAssertEqual(m.hit(x: 101, y: 22, tolerance: 5), 0, "hit finds the nearest marker inside tolerance")
        XCTAssertEqual(m.hit(x: 150, y: 150, tolerance: 5), -1, "hit returns -1 when nothing is near")
        XCTAssertEqual(m.hit(x: 110, y: 20, tolerance: 5), -1, "hit respects the tolerance radius")

        m.add(x: 150, y: 25)
        XCTAssertTrue(m.count == 4 && m[1].x == 150 && m[1].kind == .manual, "add() inserts a manual marker in reading order")
        m.remove(at: m.hit(x: 300, y: 210, tolerance: 5))
        XCTAssertEqual(m.count, 3, "remove()")
        XCTAssertTrue(m.canUndo, "can undo after edits")

        XCTAssertTrue(m.undo() && m.count == 4, "undo restores the removed marker")
        XCTAssertTrue(m[3].x == 300 && m[3].y == 210, "restored marker is back in its place")
        XCTAssertTrue(m.undo() && m.count == 3 && m.count(of: .manual) == 0, "undo removes the added marker")
        XCTAssertFalse(m.undo(), "undo on an empty stack returns false")

        // export / restore
        m.add(x: 500, y: 500)
        let data = m.export()
        let r = Markers()
        r.restore(data, band: 50)
        XCTAssertEqual(r.count, m.count, "restore() brings back every marker")
        for i in 0..<m.count {
            XCTAssertTrue(r[i].x == m[i].x && r[i].y == m[i].y && r[i].kind == m[i].kind, "restored marker \(i) is identical")
        }
        XCTAssertFalse(r.canUndo, "undo history is not restored (by design)")
    }

    func testRobustness() {
        let e = Markers()
        e.set(xs: [], ys: [], uncertain: [], band: 50)
        XCTAssertTrue(e.count == 0 && e.hit(x: 1, y: 1, tolerance: 10) == -1 && !e.undo(), "empty list is safe")
        e.restore(nil, band: 50)
        XCTAssertEqual(e.count, 0, "restore(nil) is safe")
        e.restore([1, 2], band: 50)
        XCTAssertEqual(e.count, 0, "truncated data is ignored, not crashed on")
        e.set(xs: [5], ys: [5], uncertain: nil, band: 0)
        XCTAssertEqual(e.count, 1, "nil uncertain array and zero band are safe")
    }

    func testReviewConfirmAndNext() {
        let v = Markers()
        v.set(xs: [10, 20, 30], ys: [5, 5, 5], uncertain: [true, false, true], band: 50)
        XCTAssertTrue(v.next(.uncertain, from: 0) == 0 && v.next(.uncertain, from: 1) == 2, "next() finds doubtful markers in order")
        v.confirm(at: 0)
        XCTAssertTrue(v[0].kind == .confirmed && v.count == 3, "confirm() keeps the marker and marks it checked")
        XCTAssertTrue(v.next(.uncertain, from: 0) == 2 && v.next(.uncertain, from: 3) == 2, "next() skips checked ones and wraps")
        XCTAssertTrue(v.undo() && v[0].kind == .uncertain, "undo reverts a confirmation")
        v.confirm(at: 0)
        v.confirm(at: 2)
        XCTAssertEqual(v.next(.uncertain, from: 0), -1, "next() returns -1 when everything is checked")
        let vr = Markers()
        vr.restore(v.export(), band: 50)
        XCTAssertEqual(vr[2].kind, .confirmed, "checked state survives export/restore")
    }

    func testManyEditsKeepUndoOrder() {
        let b = Markers()
        b.set(xs: [], ys: [], uncertain: nil, band: 50)
        for i in 0..<100 { b.add(x: Float(i * 10), y: Float((i % 7) * 60)) }
        for _ in 0..<40 { b.remove(at: 0) }
        let left = b.count
        for _ in 0..<40 { b.undo() }
        XCTAssertTrue(left == 60 && b.count == 100, "100 adds, 40 removes, 40 undos -> back to 100")
    }
}
