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

    /// Printed lines, rings, ticks, a ruler and hairline specks on the sheet are ignored; the grains still count.
    func testPrintedMarksAreIgnored() {
        let w = 1280, h = 960
        var a = [UInt32](repeating: rgb(235, 232, 225), count: w * h)
        let ink = rgb(25, 25, 30)
        func dot(_ x: Int, _ y: Int, _ r: Int, _ c: UInt32) {
            for yy in max(0, y - r)...min(h - 1, y + r) {
                for xx in max(0, x - r)...min(w - 1, x + r) where (xx - x) * (xx - x) + (yy - y) * (yy - y) <= r * r {
                    a[yy * w + xx] = c
                }
            }
        }
        func line(_ x0: Int, _ y0: Int, _ x1: Int, _ y1: Int, width: Int = 1) {
            let steps = max(abs(x1 - x0), abs(y1 - y0))
            for k in 0...steps { dot(x0 + (x1 - x0) * k / steps, y0 + (y1 - y0) * k / steps, width, ink) }
        }
        var grains = 0
        for row in 0..<4 { for col in 0..<6 { dot(140 + col * 190, 140 + row * 220, 26, rgb(200, 110, 40)); grains += 1 } }
        line(60, 40, 600, 60)                                    // underline
        line(700, 900, 1200, 930, width: 2)                      // thicker rule
        for k in 0...10 { line(100 + k * 30, 930, 100 + k * 30, k % 5 == 0 ? 900 : 915) } // ruler ticks
        line(100, 930, 400, 930)
        for t in 0..<360 {                                       // drawn circle (ring)
            let x = 1230 + Int(30 * cos(Double(t) * .pi / 180)), y = 500 + Int(30 * sin(Double(t) * .pi / 180))
            dot(x, y, 1, ink)
        }
        line(1180, 300, 1200, 320); line(1200, 320, 1240, 270)   // tick mark
        for k in 0..<12 { dot(250 + k * 9, 250, 1, ink) }        // a row of full stops next to the grains
        let r = DalCounter.count(argb: a, width: w, height: h)
        XCTAssertEqual(r.count, grains)
        XCTAssertGreaterThan(r.ignoredCount, 0)
        XCTAssertNotNil(r.ignoredNote)
    }

    /// A wide, grainy flash reflection on a white sheet is not counted; grains inside it still are.
    func testReflectionsAreIgnored() {
        let w = 1280, h = 960
        var gen = SplitMix(seed: 7)
        var a = [UInt32](repeating: 0, count: w * h)
        for y in 0..<h {
            for x in 0..<w {
                let d2 = Double((x - 400) * (x - 400) + (y - 480) * (y - 480)) / (170.0 * 170.0)
                let glow = exp(-d2 * 1.2) * (0.35 + 0.65 * gen.unit())       // mottled, like a glossy table
                let v = min(255, Int(200 + 70 * glow))
                a[y * w + x] = rgb(v, v, v + 2)
            }
        }
        var grains = 0
        for row in 0..<4 {
            for col in 0..<6 {
                let cx = 150 + col * 200, cy = 140 + row * 230
                for y in (cy - 22)...(cy + 22) {
                    for x in (cx - 22)...(cx + 22) where (x - cx) * (x - cx) + (y - cy) * (y - cy) <= 22 * 22 {
                        a[y * w + x] = rgb(170, 150, 125)                     // pale wheat: low contrast
                    }
                }
                grains += 1
            }
        }
        let r = DalCounter.count(argb: a, width: w, height: h)
        XCTAssertEqual(r.count, grains)
    }

    /// Mixed grains: long single grains among small round seeds are not split up or flagged.
    func testMixedSizesAreNotSplit() {
        let w = 1280, h = 960
        var a = [UInt32](repeating: rgb(225, 225, 228), count: w * h)
        var grains = 0
        for row in 0..<4 {
            for col in 0..<7 {
                let cx = 110 + col * 175, cy = 130 + row * 220
                let long = (row * 7 + col) % 4 == 0                           // wheat-like ellipse among small seeds
                let ra = long ? 30.0 : 10.0, rb = long ? 13.0 : 10.0
                for y in (cy - 32)...(cy + 32) {
                    for x in (cx - 32)...(cx + 32) {
                        let u = Double(x - cx) / ra, v = Double(y - cy) / rb
                        if u * u + v * v <= 1 { a[y * w + x] = long ? rgb(150, 105, 60) : rgb(50, 35, 30) }
                    }
                }
                grains += 1
            }
        }
        let r = DalCounter.count(argb: a, width: w, height: h)
        XCTAssertEqual(r.count, grains)
        XCTAssertEqual(r.uncertainCount, 0)
    }
}

/// Small deterministic random numbers for test images.
struct SplitMix {
    var state: UInt64
    init(seed: UInt64) { state = seed }
    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
    mutating func unit() -> Double { Double(next() >> 11) / Double(1 << 53) }
}
