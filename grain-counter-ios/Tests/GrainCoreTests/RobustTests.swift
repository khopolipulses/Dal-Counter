import XCTest
@testable import GrainCore

/// Degenerate-input stress test (same cases as the Android Robust test): nothing may crash or return nonsense.
final class RobustTests: XCTestCase {
    private func rgb(_ r: Int, _ g: Int, _ b: Int) -> UInt32 {
        func c(_ v: Int) -> UInt32 { UInt32(max(0, min(255, v))) }
        return 0xFF00_0000 | c(r) << 16 | c(g) << 8 | c(b)
    }

    private func run(_ name: String, _ w: Int, _ h: Int, _ maxCount: Int, _ px: (Int, Int) -> UInt32) {
        var a = [UInt32](repeating: 0, count: w * h)
        for y in 0..<h { for x in 0..<w { a[y * w + x] = 0xFF00_0000 | px(x, y) } }
        let r = DalCounter.count(argb: a, width: w, height: h)
        XCTAssertLessThanOrEqual(r.count, maxCount, name)
        XCTAssertTrue(r.xs.count == r.ys.count && r.xs.count == r.uncertain.count, name)
        for i in 0..<r.count {
            XCTAssertTrue(r.xs[i] >= 0 && r.xs[i] < Float(w) && r.ys[i] >= 0 && r.ys[i] < Float(h), "\(name): marker inside image")
        }
    }

    func testDegenerateInputs() {
        var rnd = SystemRandomNumberGenerator()
        run("1x1 white", 1, 1, 0) { _, _ in self.rgb(255, 255, 255) }
        run("2x2 mixed", 2, 2, 4) { x, y in self.rgb(x * 200, y * 200, 50) }
        run("7x5 gray", 7, 5, 0) { _, _ in self.rgb(120, 120, 120) }
        run("all white", 1600, 900, 0) { _, _ in self.rgb(255, 255, 255) }
        run("all black", 1600, 900, 0) { _, _ in 0 }
        run("flat orange", 1600, 900, 5) { _, _ in self.rgb(240, 140, 50) }
        run("white noise", 1600, 900, 5000) { _, _ in
            self.rgb(Int.random(in: 0..<256, using: &rnd), Int.random(in: 0..<256, using: &rnd), Int.random(in: 0..<256, using: &rnd))
        }
        run("gradient", 1600, 900, 200) { x, y in self.rgb(x * 255 / 1600, y * 255 / 900, 128) }
        run("half white half dark", 1600, 900, 200) { x, _ in x < 800 ? self.rgb(230, 230, 230) : self.rgb(20, 20, 20) }
        run("one big orange disc", 1600, 900, 3) { x, y in
            (x - 800) * (x - 800) + (y - 450) * (y - 450) < 90000 ? self.rgb(240, 140, 50) : self.rgb(225, 225, 228)
        }
        run("thin line on white", 1600, 900, 3) { _, y in abs(y - 450) < 2 ? self.rgb(0, 0, 0) : self.rgb(225, 225, 228) }
        run("chequerboard", 1600, 900, 700) { x, y in ((x / 40 + y / 40) & 1) == 0 ? self.rgb(230, 230, 230) : self.rgb(200, 90, 40) }
        run("portrait white", 900, 1600, 0) { _, _ in self.rgb(235, 235, 235) }
        run("panorama", 3200, 400, 0) { _, _ in self.rgb(235, 235, 235) }
    }

    /// A clean synthetic photo: separate dark discs on a light sheet must be counted exactly.
    func testSeparateDiscsCountedExactly() {
        let w = 1280, h = 960
        var centres: [(Int, Int)] = []
        for row in 0..<5 { for col in 0..<7 { centres.append((120 + col * 170, 120 + row * 170)) } }
        let r2 = 30 * 30
        var a = [UInt32](repeating: rgb(235, 232, 225), count: w * h)
        for (cx, cy) in centres {
            for y in (cy - 31)...(cy + 31) {
                for x in (cx - 31)...(cx + 31) where (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r2 {
                    a[y * w + x] = rgb(200, 110, 40)
                }
            }
        }
        let r = DalCounter.count(argb: a, width: w, height: h)
        XCTAssertEqual(r.count, centres.count)
        XCTAssertEqual(r.uncertainCount, 0)
        XCTAssertNil(r.hint)
    }
}
