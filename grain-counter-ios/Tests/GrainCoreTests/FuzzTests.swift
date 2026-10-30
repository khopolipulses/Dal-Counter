import Foundation
import XCTest
@testable import GrainCore

/**
 * Fuzz and property tests, mirroring grain-counter/test/Fuzz.java call for call: the same seeded SplitMix64 and
 * integer-only drawing give the very same pixels, so the per-case digests written to $FUZZ_OUT must equal the
 * Java ones (CI compares the two files). Every case must finish with markers inside the image.
 */
final class FuzzTests: XCTestCase {
    private var state: UInt64 = 0

    private func next() -> UInt64 {
        state = state &+ 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }

    private func below(_ n: Int) -> Int { Int(next() % UInt64(n)) }

    private func rgb(_ r: Int, _ g: Int, _ b: Int) -> UInt32 {
        return 0xFF00_0000 | UInt32(r & 255) << 16 | UInt32(g & 255) << 8 | UInt32(b & 255)
    }

    private static let sizes = [(1, 1), (1, 1280), (1280, 1), (2, 2), (3, 700), (700, 3), (5, 5), (64, 48), (1280, 960), (960, 1280)]

    private func image(_ kase: Int) -> ([UInt32], Int, Int) {
        let w: Int, h: Int
        if kase < FuzzTests.sizes.count {
            (w, h) = FuzzTests.sizes[kase]
        } else {
            w = 1 + below(420)
            h = 1 + below(420)
        }
        let bg = rgb(150 + below(106), 150 + below(106), 150 + below(106))
        let kind = below(8)
        var px = [UInt32](repeating: bg, count: w * h)
        switch kind {
        case 0:
            let c = below(2) == 0 ? rgb(0, 0, 0) : rgb(below(256), below(256), below(256))
            for i in 0..<px.count { px[i] = c }
        case 1:
            for i in 0..<px.count { px[i] = rgb(below(256), below(256), below(256)) }
        case 2:
            let cell = 1 + below(40), dark = rgb(below(120), below(120), below(120))
            for y in 0..<h { for x in 0..<w where ((x / cell) + (y / cell)) % 2 == 0 { px[y * w + x] = dark } }
        default:
            let n = below(60), col = rgb(below(200), below(160), below(120))
            for _ in 0..<n {
                let cx = below(w), cy = below(h), r = 1 + below(max(1, min(w, h) / 6 + 1))
                for y in max(0, cy - r)...min(h - 1, cy + r) {
                    for x in max(0, cx - r)...min(w - 1, cx + r) where (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r {
                        px[y * w + x] = col
                    }
                }
            }
            let strokes = below(10)
            for _ in 0..<strokes {
                let x0 = below(w), y0 = below(h), len = below(200), dir = below(4)
                for t in 0..<len {
                    let x = x0 + (dir == 0 || dir == 2 ? t : 0), y = y0 + (dir >= 1 ? t : 0)
                    if x < w && y < h { px[y * w + x] = rgb(20, 20, 25) }
                }
            }
            let glare = below(3)
            for _ in 0..<glare {
                let cx = below(w), cy = below(h), r = 5 + below(80)
                for y in max(0, cy - r)...min(h - 1, cy + r) {
                    for x in max(0, cx - r)...min(w - 1, cx + r) {
                        let d2 = (x - cx) * (x - cx) + (y - cy) * (y - cy)
                        if d2 <= r * r {
                            let p = px[y * w + x], lift = 60 * (r * r - d2) / (r * r)
                            px[y * w + x] = rgb(min(255, Int((p >> 16) & 255) + lift), min(255, Int((p >> 8) & 255) + lift),
                                                min(255, Int(p & 255) + lift))
                        }
                    }
                }
            }
        }
        return (px, w, h)
    }

    private func fnv(_ h: UInt64, _ v: Int64) -> UInt64 {
        var h = h
        let u = UInt64(bitPattern: v)
        for i in 0..<8 {
            h ^= (u >> (8 * UInt64(i))) & 255
            h = h &* 0x100_0000_01B3
        }
        return h
    }

    private func bits(_ f: Float) -> Int64 { Int64(Int32(bitPattern: f.bitPattern)) }

    func testFuzzImages() throws {
        let cases = Int(ProcessInfo.processInfo.environment["FUZZ_CASES"] ?? "") ?? 300
        state = 12345
        var lines: [String] = []
        for k in 0..<cases {
            let (px, w, h) = image(k)
            let r = DalCounter.count(argb: px, width: w, height: h)
            let n = r.count
            XCTAssertTrue(r.xs.count == n && r.ys.count == n && r.uncertain.count == n, "case \(k) array sizes")
            XCTAssertLessThanOrEqual(n, w * h, "case \(k)")
            for i in 0..<n {
                XCTAssertTrue(r.xs[i] >= 0 && r.xs[i] < Float(w) && r.ys[i] >= 0 && r.ys[i] < Float(h), "case \(k) marker \(i) outside")
            }
            XCTAssertEqual(r.uncertain.filter { $0 }.count, r.uncertainCount, "case \(k)")
            XCTAssertTrue(r.radius > 0 && r.radius.isFinite, "case \(k) radius")
            _ = r.hint
            _ = r.photoTip
            var d: UInt64 = 0xcbf2_9ce4_8422_2325
            d = fnv(d, Int64(n))
            d = fnv(d, Int64(r.uncertainCount))
            d = fnv(d, Int64(r.ignoredCount))
            d = fnv(d, bits(r.radius))
            for i in 0..<n {
                d = fnv(d, bits(r.xs[i]))
                d = fnv(d, bits(r.ys[i]))
                d = fnv(d, r.uncertain[i] ? 1 : 0)
            }
            lines.append("\(k) \(w)x\(h) \(n) \(String(d, radix: 16))")
        }
        if let path = ProcessInfo.processInfo.environment["FUZZ_OUT"] {
            try (lines.joined(separator: "\n") + "\n").write(toFile: path, atomically: true, encoding: .utf8)
        }
    }

    /// Random add / remove / confirm / undo; undo must restore the exported state exactly.
    func testMarkerEditsUndoExactly() {
        state = 777
        for round in 0..<200 {
            let m = Markers()
            let n0 = below(30)
            var xs: [Float] = [], ys: [Float] = [], u: [Bool] = []
            for _ in 0..<n0 {
                xs.append(Float(below(1000)))
                ys.append(Float(below(1000)))
                u.append(below(3) == 0)
            }
            m.set(xs: xs, ys: ys, uncertain: u, band: Float(1 + below(80)))
            var history: [[Float]] = []
            for _ in 0..<120 {
                let before = m.export()
                let op = below(5)
                var changed = false
                if op == 0 {
                    m.add(x: Float(below(1000)), y: Float(below(1000)))
                    changed = true
                } else if op == 1 && m.count > 0 {
                    m.remove(at: below(m.count))
                    changed = true
                } else if op == 2 && m.count > 0 {
                    let i = below(m.count)
                    changed = m[i].kind != .confirmed
                    m.confirm(at: i)
                } else if op == 3 {
                    let could = m.canUndo
                    let did = m.undo()
                    XCTAssertEqual(could, did)
                    if did { XCTAssertEqual(history.removeLast(), m.export(), "undo restores the previous state (round \(round))") }
                    continue
                } else if op == 4 && m.count > 0 {
                    let kind = Markers.Kind(rawValue: below(4))!, from = below(m.count + 3) - 1, i = m.next(kind, from: from)
                    XCTAssertTrue(i == -1 || m[i].kind == kind)
                    if i == -1 { for j in 0..<m.count { XCTAssertNotEqual(m[j].kind, kind) } }
                    continue
                }
                if changed { history.append(before) }
            }
        }
    }
}
