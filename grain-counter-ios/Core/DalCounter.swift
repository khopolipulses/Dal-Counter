import Foundation

/**
 * Pure-Swift grain counter: a line-by-line port of the Android app's DalCounter.java, so both apps give the
 * same count for the same photo. No UIKit, so it is unit-tested with `swift test` on any machine.
 *
 * Pipeline: paper region -> grain mask -> clean-up -> connected components -> distance transform
 * -> smoothed peaks (one peak per grain, splits touching pieces) -> small broken fragments added.
 */
public enum DalCounter {

    public final class Result {
        public var width = 0, height = 0
        /// Marker centres in image pixels, already in reading order (top-to-bottom, left-to-right).
        public var xs: [Float] = []
        public var ys: [Float] = []
        /// Typical grain radius in pixels.
        public var radius: Float = 12
        /// True for markers that sit in a touching/overlapping group and deserve a second look.
        public var uncertain: [Bool] = []
        /// Background found (plain sheet / tray). False = results are a best guess.
        public var backgroundFound = false
        /// How strongly grains stand out from the background (>=0.6 good, <0.3 poor).
        public var contrast: Float = 0
        /// Background noise (higher = grainier / darker photo).
        public var noise: Float = 0
        /// Mean background brightness 0..255.
        public var brightness: Float = 0
        /// Number of markers flagged uncertain.
        public var uncertainCount = 0
        /// Pieces of printed text, lines and symbols that were recognised and not counted.
        public var ignoredCount = 0

        public init() {}

        public var count: Int { xs.count }

        /// Height of one row of markers for reading-order numbering, in image pixels.
        public var band: Float { max(1, 50 * Float(max(width, height)) / DalCounter.refLongSide) }

        /// True when the photo is good enough that the count can be trusted.
        public var reliable: Bool { count > 0 && backgroundFound && contrast >= 0.35 && noise <= 0.08 }

        /// Tells the user that printed text / lines / symbols were skipped, or nil when there were none.
        public var ignoredNote: String? {
            if ignoredCount == 0 { return nil }
            return "Printed text, lines and symbols were ignored (\(ignoredCount)\(ignoredCount == 1 ? " piece)." : " pieces).")"
        }

        /// A problem with the photo itself (background, contrast, light), or nil when the photo is fine.
        public var photoTip: String? {
            guard let h = hint else { return nil }
            return uncertainCount > 0 && h.hasPrefix("\(uncertainCount) grains touch") ? nil : h
        }

        /// The single most useful tip for the user, or nil when everything looks fine.
        public var hint: String? {
            if count == 0 { return "No grains found. Use one plain background and good light." }
            if !backgroundFound { return "No plain background found. Put the grains on one plain sheet or tray." }
            if contrast < 0.35 {
                return "Grains look too similar to the background, so this count may be wrong. "
                    + "Use a plainer, contrasting background (dark for light grains, white for dark grains)."
            }
            if noise > 0.08 { return "The photo is dark or grainy. Use more light for a safer count." }
            if uncertainCount > 0 {
                return "\(uncertainCount) grains touch or overlap (shown in orange). Check them, "
                    + "or spread the grains out for an exact count."
            }
            return nil
        }
    }

    /// Thresholds were tuned on 2576 px photos; everything is scaled relative to image size.
    static let refLongSide: Float = 2576

    // Tunables (fractions of the typical grain radius), identical to the Android app.
    /// A peak is its own grain only if the neck to a higher neighbour is at least this fraction lower.
    public static let peakDynamics: Float = 0.15
    public static let peakMinHeight: Float = 0.3
    /// Blob area / typical grain area is rounded with this offset to get the expected grain count.
    public static let clusterRounding: Float = 0.25
    /// Blobs that would need more grains than this are left alone (not a sensible pile).
    static let maxClusterGrains = 150
    public static let blurSigma: Float = 1.0 // in pixels at refLongSide

    private struct Pt {
        var x: Float, y: Float
        var label: Int
        var uncertain: Bool
    }

    /// Counts the grains in a w*h image given as 0xAARRGGBB pixels (row by row).
    public static func count(argb: [UInt32], width w: Int, height h: Int) -> Result {
        let s = Float(max(w, h)) / refLongSide
        let n = w * h

        let seg = segment(argb, w, h, s)
        let opened = open(seg.fg, w, h, 1)
        var mask = opened
        fillHoles(&mask, w, h)

        var lab = [Int32](repeating: 0, count: n)
        let nLab = label(mask, w, h, &lab)
        let res = Result()
        res.width = w
        res.height = h
        res.backgroundFound = seg.paperFound
        res.contrast = seg.contrast
        res.noise = seg.noise
        res.brightness = seg.paperLum
        if nLab == 0 { return res }

        var area = [Double](repeating: 0, count: nLab + 1)
        var sx = [Double](repeating: 0, count: nLab + 1)
        var sy = [Double](repeating: 0, count: nLab + 1)
        for y in 0..<h {
            for x in 0..<w {
                let l = Int(lab[y * w + x])
                if l > 0 {
                    area[l] += 1
                    sx[l] += Double(x)
                    sy[l] += Double(y)
                }
            }
        }

        // Printed text, lines and symbols on the sheet are not grains: drop them before measuring grains.
        res.ignoredCount = removeMarks(argb, seg.score, opened, &mask, &lab, nLab, &area, w, h, s)

        // Typical single-grain area = median of the components that are not tiny crumbs.
        let all = area[1...nLab].filter { $0 > 0 }
        if all.isEmpty { return res }
        let med0 = median(all)
        let med = median(all.filter { $0 > 0.5 * med0 })
        let rad = Float((med / Double.pi).squareRoot())
        res.radius = rad
        let thr = rad * peakMinHeight

        // Smoothed distance transform: each grain gets exactly one bright peak, even when touching.
        let d = edt(mask, w, h)
        let dist = d.map { Float($0) }
        let sm = gauss(dist, w, h, max(0.7, Double(blurSigma * s)))

        // One peak per grain by "dynamics": a bump of the distance map is a separate grain only if the neck
        // between it and a higher bump is clearly narrower. Long single grains have no neck, so stay whole.
        var pts: [Pt] = []
        var hasPeak = [Bool](repeating: false, count: nLab + 1)
        var maskCount = 0
        var maxV: Float = 0
        for i in 0..<n where lab[i] > 0 {
            maskCount += 1
            if sm[i] > maxV { maxV = sm[i] }
        }
        let Q: Float = 16
        let levels = Int(maxV * Q) + 2
        var start = [Int](repeating: 0, count: levels + 1)
        for i in 0..<n where lab[i] > 0 { start[Int(sm[i] * Q)] += 1 }
        var acc0 = 0
        for L in stride(from: levels - 1, through: 0, by: -1) {
            let c0 = start[L]
            start[L] = acc0
            acc0 += c0
        }
        var order = [Int](repeating: 0, count: maskCount)
        for i in 0..<n where lab[i] > 0 {
            let L = Int(sm[i] * Q)
            order[start[L]] = i
            start[L] += 1
        }

        var parent = [Int](repeating: -1, count: n)
        var pv = [Float](repeating: 0, count: n)
        var pk = [Int](repeating: 0, count: n)
        var kept: [Int] = []
        let dx4 = [-1, 1, 0, 0]
        let dy4 = [0, 0, -1, 1]
        for t in 0..<maskCount {
            let i = order[t]
            let v = sm[i]
            let x = i % w, y = i / w
            var root = -1
            for dir in 0..<4 {
                let xx = x + dx4[dir], yy = y + dy4[dir]
                if xx < 0 || yy < 0 || xx >= w || yy >= h { continue }
                let j = yy * w + xx
                if parent[j] < 0 { continue }
                var rj = j
                while parent[rj] != rj {
                    parent[rj] = parent[parent[rj]]
                    rj = parent[rj]
                }
                if root < 0 {
                    root = rj
                } else if rj != root {
                    let hi = pv[root] >= pv[rj] ? root : rj
                    let lo = hi == root ? rj : root
                    if pv[lo] - v >= max(0.5, peakDynamics * pv[lo]) {
                        kept.append(pk[lo])
                    }
                    parent[lo] = hi
                    root = hi
                }
            }
            if root < 0 {
                parent[i] = i
                pv[i] = v
                pk[i] = i
            } else {
                parent[i] = root
            }
        }
        for t in 0..<maskCount {
            let i = order[t]
            if parent[i] == i { kept.append(pk[i]) }
        }
        for i in kept where sm[i] > thr {
            let l = Int(lab[i])
            hasPeak[l] = true
            pts.append(Pt(x: Float(i % w), y: Float(i / w), label: l, uncertain: false))
        }

        // Cluster correction: a blob clearly bigger than one grain hides more grains than the neck test saw.
        // Not when its outline is smooth and convex: that is one big grain (wheat among small seeds, a bean among
        // mustard), since touching grains always leave notches in the outline.
        var nPk = [Int](repeating: 0, count: nLab + 1)
        for q in pts { nPk[q.label] += 1 }
        var big = [Bool](repeating: false, count: nLab + 1)
        for l in 1...nLab {
            big[l] = nPk[l] > 0 && ((area[l] / med + Double(clusterRounding)).rounded(.down) > Double(nPk[l])
                || (nPk[l] == 1 && area[l] / med > 1.45))
        }
        let single = convexBlobs(lab, w, h, nLab, area, big)
        var need = [Int](repeating: 0, count: nLab + 1)
        var needTotal = 0
        for l in 1...nLab where nPk[l] > 0 && !single[l] {
            let expect = Int((area[l] / med + Double(clusterRounding)).rounded(.down))
            if expect > nPk[l] {
                need[l] = expect
                needTotal += Int(area[l])
            }
        }
        if needTotal > 0 {
            var cs = [Int](repeating: 0, count: nLab + 2)
            for l in 1...nLab { cs[l + 1] = cs[l] + (need[l] > 0 ? Int(area[l]) : 0) }
            var pix = [Int](repeating: 0, count: needTotal)
            var fp = cs
            for i in 0..<n {
                let l = Int(lab[i])
                if l > 0 && need[l] > 0 {
                    pix[fp[l]] = i
                    fp[l] += 1
                }
            }
            for l in 1...nLab where need[l] > 0 {
                let K = need[l]
                let from = cs[l], to = cs[l + 1]
                if K > maxClusterGrains { continue }                // a giant blob is not a countable pile
                let step = max(1, (to - from) / 3000)                // keeps the work bounded
                var ccx = [Double](repeating: 0, count: K), ccy = [Double](repeating: 0, count: K)
                var k0 = 0
                for q in stride(from: pts.count - 1, through: 0, by: -1) where pts[q].label == l {
                    if k0 < K {
                        ccx[k0] = Double(pts[q].x)
                        ccy[k0] = Double(pts[q].y)
                        k0 += 1
                    }
                    pts.remove(at: q)
                }
                for k in k0..<K {                                    // farthest-point seeding for the hidden grains
                    var bestD = -1.0
                    var bi = from
                    for t in stride(from: from, to: to, by: step) {
                        let i = pix[t]
                        var dmin = Double.greatestFiniteMagnitude
                        for c2 in 0..<k {
                            let ddx = Double(i % w) - ccx[c2], ddy = Double(i / w) - ccy[c2]
                            dmin = min(dmin, ddx * ddx + ddy * ddy)
                        }
                        if dmin > bestD { bestD = dmin; bi = i }
                    }
                    ccx[k] = Double(bi % w)
                    ccy[k] = Double(bi / w)
                }
                for _ in 0..<8 {                                     // Lloyd iterations
                    var sxk = [Double](repeating: 0, count: K), syk = [Double](repeating: 0, count: K)
                    var nk2 = [Int](repeating: 0, count: K)
                    for t in stride(from: from, to: to, by: step) {
                        let i = pix[t]
                        let px = Double(i % w), py = Double(i / w)
                        var bd = Double.greatestFiniteMagnitude
                        var bk = 0
                        for k in 0..<K {
                            let ddx = px - ccx[k], ddy = py - ccy[k], dd = ddx * ddx + ddy * ddy
                            if dd < bd { bd = dd; bk = k }
                        }
                        sxk[bk] += px; syk[bk] += py; nk2[bk] += 1
                    }
                    for k in 0..<K where nk2[k] > 0 {
                        ccx[k] = sxk[k] / Double(nk2[k])
                        ccy[k] = syk[k] / Double(nk2[k])
                    }
                }
                for k in 0..<K { pts.append(Pt(x: Float(ccx[k]), y: Float(ccy[k]), label: l, uncertain: true)) }
                hasPeak[l] = true
            }
        }

        // Small broken pieces that are too small to form a distance-transform peak still count.
        let minArea = Double(max(6, javaRound(25 * s * s)))
        for l in 1...nLab where !hasPeak[l] && area[l] >= minArea {
            pts.append(Pt(x: Float(sx[l] / area[l]), y: Float(sy[l] / area[l]), label: l, uncertain: false))
        }

        // Uncertain = member of a touching/overlapping group whose size does not add up cleanly.
        var finalPk = [Int](repeating: 0, count: nLab + 1)
        for q in pts { finalPk[q.label] += 1 }
        for qi in pts.indices {
            let l = pts[qi].label
            let ratio = area[l] / med
            let k = finalPk[l]
            pts[qi].uncertain = pts[qi].uncertain || (k >= 2 && ratio < 0.85 * Double(k)) || (k == 1 && ratio > 1.45 && !single[l])
        }
        // Odd ones out: a lone grain that looks like no other grain in the photo (size, thickness, colour) is
        // worth a look; real grains, even in a mix, come in groups that look alike.
        let odd = oddOnesOut(argb, lab, d, nLab, area, finalPk, w, h)
        for qi in pts.indices where odd[pts[qi].label] { pts[qi].uncertain = true }
        let band = max(1, 50 * s)
        pts = stableSorted(pts) { p, q in
            let rp = Int(p.y / band), rq = Int(q.y / band)
            if rp != rq { return rp < rq }
            return p.x < q.x
        }
        res.xs = pts.map { $0.x }
        res.ys = pts.map { $0.y }
        res.uncertain = pts.map { $0.uncertain }
        res.uncertainCount = res.uncertain.filter { $0 }.count
        return res
    }

    /// For every blob holding exactly one marker: true when fewer than a few other such blobs look like it
    /// (area within 1.6x, thickness within 1.4x, mean colour within 40). Skipped on very busy photos.
    private static func oddOnesOut(_ argb: [UInt32], _ lab: [Int32], _ dist: [Double], _ nLab: Int, _ area: [Double],
                                   _ markers: [Int], _ w: Int, _ h: Int) -> [Bool] {
        var odd = [Bool](repeating: false, count: nLab + 1)
        var ids: [Int] = []
        for l in 1...nLab where markers[l] == 1 && area[l] > 0 { ids.append(l) }
        let m = ids.count
        if m < 8 || m > 4000 { return odd }
        var thick = [Double](repeating: 0, count: nLab + 1), r = thick, g = thick, b = thick
        for i in 0..<(w * h) {
            let l = Int(lab[i])
            if l == 0 || markers[l] != 1 { continue }
            if dist[i] > thick[l] { thick[l] = dist[i] }
            let (pr, pg, pb) = rgb(argb[i])
            r[l] += Double(pr)
            g[l] += Double(pg)
            b[l] += Double(pb)
        }
        var la = [Double](repeating: 0, count: m), lt = la, cr = la, cg = la, cb = la
        for k in 0..<m {
            let l = ids[k]
            la[k] = log(area[l])
            lt[k] = log(max(0.5, thick[l]))
            cr[k] = r[l] / area[l]
            cg[k] = g[l] / area[l]
            cb[k] = b[l] / area[l]
        }
        let sa = log(1.6), st = log(1.4)
        let needed = max(2, Int(0.03 * Double(m)))
        for k in 0..<m {
            var alike = 0
            var j = 0
            while j < m && alike < needed {
                if j != k && abs(la[j] - la[k]) <= sa && abs(lt[j] - lt[k]) <= st {
                    let dr = cr[j] - cr[k], dg = cg[j] - cg[k], db = cb[j] - cb[k]
                    if dr * dr + dg * dg + db * db <= 40 * 40 { alike += 1 }
                }
                j += 1
            }
            if alike < needed { odd[ids[k]] = true }
        }
        return odd
    }

    /// Blobs at least this convex (area / convex hull area) are one grain, however big.
    public static let singleSolidity: Float = 0.9

    /// For the blobs marked in `check`: true when the blob is convex enough to be a single grain.
    private static func convexBlobs(_ lab: [Int32], _ w: Int, _ h: Int, _ nLab: Int, _ area: [Double],
                                    _ check: [Bool]) -> [Bool] {
        var single = [Bool](repeating: false, count: nLab + 1)
        if !check.contains(true) { return single }
        var minY = [Int](repeating: Int.max, count: nLab + 1), maxY = [Int](repeating: -1, count: nLab + 1)
        for i in 0..<(w * h) {
            let l = Int(lab[i])
            if l > 0 && check[l] {
                let y = i / w
                if y < minY[l] { minY[l] = y }
                if y > maxY[l] { maxY[l] = y }
            }
        }
        // Row extents of every checked blob, then the convex hull of the pixel corners.
        var rowMin = [[Int]](repeating: [], count: nLab + 1), rowMax = rowMin
        for l in 1...nLab where check[l] && maxY[l] >= 0 {
            rowMin[l] = [Int](repeating: Int.max, count: maxY[l] - minY[l] + 1)
            rowMax[l] = [Int](repeating: -1, count: maxY[l] - minY[l] + 1)
        }
        for i in 0..<(w * h) {
            let l = Int(lab[i])
            if l == 0 || rowMin[l].isEmpty { continue }
            let x = i % w, r = i / w - minY[l]
            if x < rowMin[l][r] { rowMin[l][r] = x }
            if x > rowMax[l][r] { rowMax[l][r] = x }
        }
        for l in 1...nLab where !rowMin[l].isEmpty {
            var pts: [(Int, Int)] = []
            for r in 0..<rowMin[l].count where rowMax[l][r] >= 0 {
                let y = minY[l] + r
                pts.append((rowMin[l][r], y))
                pts.append((rowMin[l][r], y + 1))
                pts.append((rowMax[l][r] + 1, y))
                pts.append((rowMax[l][r] + 1, y + 1))
            }
            let hull = hullArea(pts)
            single[l] = hull > 0 && area[l] / hull >= Double(singleSolidity)
        }
        return single
    }

    /// Area of the convex hull of integer points (Andrew's monotone chain).
    private static func hullArea(_ points: [(Int, Int)]) -> Double {
        let p = points.sorted { $0.0 != $1.0 ? $0.0 < $1.0 : $0.1 < $1.1 }
        let n = p.count
        if n < 3 { return 0 }
        func cross(_ o: (Int, Int), _ a: (Int, Int), _ b: (Int, Int)) -> Int {
            return (a.0 - o.0) * (b.1 - o.1) - (a.1 - o.1) * (b.0 - o.0)
        }
        var hull: [(Int, Int)] = []
        hull.reserveCapacity(2 * n)
        for i in 0..<n {
            while hull.count >= 2 && cross(hull[hull.count - 2], hull[hull.count - 1], p[i]) <= 0 { hull.removeLast() }
            hull.append(p[i])
        }
        let t = hull.count + 1
        for i in stride(from: n - 2, through: 0, by: -1) {
            while hull.count >= t && cross(hull[hull.count - 2], hull[hull.count - 1], p[i]) <= 0 { hull.removeLast() }
            hull.append(p[i])
        }
        var a = 0.0
        for i in 0..<(hull.count - 1) {
            a += Double(hull[i].0) * Double(hull[i + 1].1) - Double(hull[i + 1].0) * Double(hull[i].1)
        }
        return abs(a) / 2
    }

    // ---------------------------------------------------------------- printed text and symbols

    /// A mark's strokes are at most this fraction of the typical grain thickness.
    public static let markThin: Float = 0.5
    /// Thin pieces at least this long for their width (area / (pi * halfWidth^2)) are strokes, not broken grains.
    public static let markStroke: Float = 5
    /// Thin pieces with at least this much enclosed hole are letters like o, e, 0, 8 or drawn circles.
    public static let markHole: Float = 0.2
    /// ...and the inside of that hole looks like the background (difference score below this).
    public static let markHoleEmpty: Float = 0.2
    /// Thin specks smaller than this fraction of a grain are bits of print or dust (broken grains are bigger).
    public static let markSpeck: Float = 0.1

    /**
     * Finds printed text, lines and symbols (letters, digits, underlines, arrows, ticks, boxes, rulers...) and
     * removes them from the mask and the labels. Their strokes are much thinner than the grains in the same photo,
     * while a grain, a broken piece or a pile of touching grains is as thick as a grain. Small thin pieces next to
     * a mark (the rest of a word, i dots, full stops, hyphens) go with it.
     * Returns the number of pieces removed; their area is set to 0.
     */
    private static func removeMarks(_ argb: [UInt32], _ score: [Float], _ open: [Bool], _ mask: inout [Bool],
                                    _ lab: inout [Int32], _ nLab: Int, _ area: inout [Double],
                                    _ w: Int, _ h: Int, _ s: Float) -> Int {
        let n = w * h
        // Half-thickness of every piece, measured with its holes still open (an "o" is a thin ring, not a disc).
        let du = edt(open, w, h)
        var thick = [Double](repeating: 0, count: nLab + 1)
        var inkArea = [Double](repeating: 0, count: nLab + 1)
        var holeScore = [Double](repeating: 0, count: nLab + 1)
        for i in 0..<n {
            let l = Int(lab[i])
            if l > 0 && open[i] {
                inkArea[l] += 1
                if du[i] > thick[l] { thick[l] = du[i] }
            } else if l > 0 {
                holeScore[l] += Double(score[i])
            }
        }
        // Typical grain half-thickness: upper quartile of the pieces that are not crumbs, so that a page
        // with many letters still measures the grains.
        let med0 = median(Array(area[1...nLab]))
        var tc: [Double] = []
        for l in 1...nLab where area[l] > 0.5 * med0 { tc.append(thick[l]) }
        if tc.isEmpty { return 0 }
        tc.sort()
        let nc = tc.count
        let typical = tc[min(nc - 1, Int(0.75 * Double(nc)))]
        if typical < 2 { return 0 } // grains only a few pixels wide: strokes cannot be told apart
        let grainArea = Double.pi * typical * typical
        let hairline = Double(3 * s) // printed strokes are a few pixels wide; no grain or broken piece is that thin

        var thin = [Bool](repeating: false, count: nLab + 1)
        var mark = [Bool](repeating: false, count: nLab + 1)
        var sure = [Bool](repeating: false, count: nLab + 1) // clearly print (not just a speck): may pull its neighbours in
        var marks = 0
        for l in 1...nLab {
            thin[l] = inkArea[l] > 0 && thick[l] < Double(markThin) * typical
            if !thin[l] { continue }
            let stroke = inkArea[l] / (Double.pi * thick[l] * thick[l])
            let hole = 1 - inkArea[l] / area[l]
            // A printed ring (o, e, 0, a drawn circle) has plain background inside; a grain with a light centre does not.
            let emptyHole = hole > 0 && holeScore[l] / (area[l] - inkArea[l]) < Double(markHoleEmpty)
            sure[l] = stroke > Double(markStroke) || (hole > Double(markHole) && emptyHole) || thick[l] <= hairline
            // Tiny and nearly as thin as a hairline: a bit of print or dust (tiny seeds like mustard are thicker).
            let speck = area[l] < Double(markSpeck) * grainArea && thick[l] <= 1.5 * hairline
            if sure[l] || speck {
                mark[l] = true
                marks += 1
            }
        }
        if marks == 0 { return 0 }

        // The rest of the same text: small thin pieces close to a mark, spreading letter by letter along a word.
        // "Close" = bounding boxes less than 2.5 grain half-widths apart.
        var minX = [Int](repeating: Int.max, count: nLab + 1), maxX = [Int](repeating: -1, count: nLab + 1)
        var minY = [Int](repeating: Int.max, count: nLab + 1), maxY = [Int](repeating: -1, count: nLab + 1)
        for i in 0..<n {
            let l = Int(lab[i])
            if l == 0 { continue }
            let x = i % w, y = i / w
            if x < minX[l] { minX[l] = x }
            if x > maxX[l] { maxX[l] = x }
            if y < minY[l] { minY[l] = y }
            if y > maxY[l] { maxY[l] = y }
        }
        var cand: [Int] = []
        for l in 1...nLab where !mark[l] && thin[l] && area[l] < 0.35 * grainArea { cand.append(l) }
        var queue: [Int] = []
        for l in 1...nLab where sure[l] { queue.append(l) }
        let near = 2.5 * typical
        var q = 0
        while q < queue.count && !cand.isEmpty {
            let m = queue[q]
            var c = cand.count - 1
            while c >= 0 {
                let l = cand[c]
                let gx = Double(max(0, max(minX[l] - maxX[m], minX[m] - maxX[l])))
                let gy = Double(max(0, max(minY[l] - maxY[m], minY[m] - maxY[l])))
                if gx * gx + gy * gy <= near * near {
                    mark[l] = true
                    marks += 1
                    queue.append(l)
                    cand[c] = cand[cand.count - 1]   // same swap-remove as the Java version, so the order matches
                    cand.removeLast()
                }
                c -= 1
            }
            q += 1
        }
        let bounds = Bounds(minX: minX, maxX: maxX, minY: minY, maxY: maxY)
        marks += removeWatermark(argb, lab, &mark, area, bounds, nLab, w, h, grainArea, bottom: true)
        marks += removeWatermark(argb, lab, &mark, area, bounds, nLab, w, h, grainArea, bottom: false)
        for i in 0..<n {
            let l = Int(lab[i])
            if l > 0 && mark[l] {
                lab[i] = 0
                mask[i] = false
            }
        }
        for l in 1...nLab where mark[l] { area[l] = 0 }
        return marks
    }

    /// Camera watermarks sit within this fraction of the photo height from the top or bottom edge.
    private static let watermarkBand: Float = 0.16

    /// Bounding boxes of the labelled pieces.
    private struct Bounds {
        let minX: [Int], maxX: [Int], minY: [Int], maxY: [Int]
    }

    /**
     * Phone camera watermarks ("Shot on ...", "AI QUAD CAMERA", the phone model, a date stamp, a small brand logo)
     * are a line of text in a corner at the bottom (or top) of the photo. When marks form such a line, the pieces
     * inside the line's box that have the text's colour (bold letters) or a badge shape (a square logo) go too.
     * Grains of another colour next to the watermark are kept.
     * Returns the number of extra pieces removed.
     */
    private static func removeWatermark(_ argb: [UInt32], _ lab: [Int32], _ mark: inout [Bool], _ area: [Double],
                                        _ b: Bounds, _ nLab: Int, _ w: Int, _ h: Int, _ grainArea: Double,
                                        bottom: Bool) -> Int {
        let band = Double(watermarkBand) * Double(h)
        var line = [Bool](repeating: false, count: nLab + 1)
        var bx0 = Int.max, bx1 = -1, by0 = Int.max, by1 = -1
        var heights: [Double] = []
        for l in 1...nLab where mark[l] && b.maxX[l] >= 0 {
            let inBand = bottom ? Double(b.minY[l]) >= Double(h) - band : Double(b.maxY[l]) <= band
            if !inBand { continue }
            line[l] = true
            bx0 = min(bx0, b.minX[l])
            bx1 = max(bx1, b.maxX[l])
            by0 = min(by0, b.minY[l])
            by1 = max(by1, b.maxY[l])
            heights.append(Double(b.maxY[l] - b.minY[l] + 1))
        }
        if heights.count < 4 { return 0 }                                     // a few specks are not a line of text
        let corner = Double(bx0) < 0.12 * Double(w) || Double(bx1) > 0.88 * Double(w) // anchored to the left or right edge
        let edge = bottom ? Double(by1) > Double(h) - 0.1 * Double(h) : Double(by0) < 0.1 * Double(h) // and near top / bottom
        if !corner || !edge { return 0 }
        // Bold letters: tight box around the text. Logo badge: anywhere within one block height of it.
        let lineH = median(heights), blockH = Double(by1 - by0 + 1)
        let x0 = Double(bx0) - 1.5 * lineH, x1 = Double(bx1) + 1.5 * lineH
        let y0 = Double(by0) - 0.5 * lineH, y1 = Double(by1) + 0.5 * lineH
        let lx0 = Double(bx0) - blockH, lx1 = Double(bx1) + blockH, ly0 = Double(by0) - blockH, ly1 = Double(by1) + blockH
        var inBox = [Bool](repeating: false, count: nLab + 1), nearBox = inBox
        var nIn = 0
        for l in 1...nLab {
            if mark[l] || b.maxX[l] < 0 || area[l] > 3 * grainArea { continue }
            let (mnx, mxx, mny, mxy) = (Double(b.minX[l]), Double(b.maxX[l]), Double(b.minY[l]), Double(b.maxY[l]))
            inBox[l] = mnx >= x0 && mxx <= x1 && mny >= y0 && mxy <= y1
            nearBox[l] = mnx >= lx0 && mxx <= lx1 && mny >= ly0 && mxy <= ly1
            if nearBox[l] { nIn += 1 }
        }
        if nIn == 0 { return 0 }

        // Colour of the watermark text vs colour of each piece in the box.
        var tr = 0.0, tg = 0.0, tb = 0.0, tn = 0.0
        var cr = [Double](repeating: 0, count: nLab + 1), cg = cr, cb = cr
        for i in 0..<argb.count {
            let l = Int(lab[i])
            if l == 0 || !(line[l] || nearBox[l]) { continue }
            let (r, g, bl) = rgb(argb[i])
            if line[l] {
                tr += Double(r); tg += Double(g); tb += Double(bl); tn += 1
            } else {
                cr[l] += Double(r); cg[l] += Double(g); cb[l] += Double(bl)
            }
        }
        tr /= tn; tg /= tn; tb /= tn
        var removed = 0
        for l in 1...nLab where nearBox[l] {
            let dr = cr[l] / area[l] - tr, dg = cg[l] / area[l] - tg, db = cb[l] / area[l] - tb
            let textColour = inBox[l] && (dr * dr + dg * dg + db * db).squareRoot() < 60
            let boxFill = area[l] / (Double(b.maxX[l] - b.minX[l] + 1) * Double(b.maxY[l] - b.minY[l] + 1))
            let badge = boxFill > 0.85                                          // square / rectangular logo (grains fill <= ~0.82)
            if textColour || badge {
                mark[l] = true
                removed += 1
            }
        }
        return removed
    }

    /// Stable sort (like Java's Collections.sort), so equal keys keep their order on every platform.
    static func stableSorted<T>(_ a: [T], by less: (T, T) -> Bool) -> [T] {
        return a.enumerated().sorted { p, q in
            if less(p.element, q.element) { return true }
            if less(q.element, p.element) { return false }
            return p.offset < q.offset
        }.map { $0.element }
    }

    /// Java's Math.round(float): floor(x + 0.5).
    @inline(__always) static func javaRound(_ x: Float) -> Int {
        return Int((x + 0.5).rounded(.down))
    }

    // ---------------------------------------------------------------- adaptive segmentation

    private struct Seg {
        var fg: [Bool]           // grains: anything on the sheet that differs enough from the background
        var score: [Float] = []  // how different from the background each pixel is
        var paperFound = false   // a clear plain background was found
        var paperLum: Float = 0  // typical background brightness (0..255)
        var contrast: Float = 0  // how strongly grains stand out (1 = clearly, <0.25 = hard to see)
        var noise: Float = 0     // background noise level in score units
    }

    /// Local background colour/brightness maps on a coarse grid (they follow shading and colour casts).
    private struct Grid {
        var gw = 0, gh = 0, blk = 0
        var lum: [Float] = [], rn: [Float] = [], gn: [Float] = []
    }

    /// Below this grain contrast the difference map is smoothed before thresholding.
    private static let lowContrastSmoothing: Float = 0.35
    private static let chromaScale: Float = 0.25  // chromaticity distance that counts as "fully different"
    private static let lumScale: Float = 0.8      // relative brightness change that counts as "fully different"
    private static let faintLevel: Float = 0.25   // difference score that still makes a separate faint grain
    private static let lightSheet: Float = 150    // background brightness from which reflections are ignored
    private static let glareLevel: Float = 0.035  // mean extra brightness over a wide patch that marks a reflection

    /**
     * How much brighter than the sheet each part of the photo is, averaged over patches several grains wide
     * (on the coarse background grid). A bright grain is small and averages away; a reflection does not.
     */
    private static func glareMap(_ argb: [UInt32], _ w: Int, _ h: Int, _ g: Grid) -> [Float] {
        let gw = g.gw, gh = g.gh, blk = g.blk
        var excess = [Double](repeating: 0, count: gw * gh), cnt = excess
        for y in 0..<h {
            for x in 0..<w {
                let (r, gg, b) = rgb(argb[y * w + x])
                let lum: Float = 0.299 * Float(r) + 0.587 * Float(gg) + 0.114 * Float(b)
                let ratio = lum / max(8, sample(g.lum, gw, gh, blk, x, y))
                let cell = (y / blk) * gw + (x / blk)
                excess[cell] += Double(max(0, ratio - 1))
                cnt[cell] += 1
            }
        }
        let r = max(2, javaRound(Float(max(w, h)) / 1280 * 6)) // ~24 px at 1280 px
        let be = boxBlur(excess, gw, gh, r), bc = boxBlur(cnt, gw, gh, r)
        var out = [Float](repeating: 0, count: gw * gh)
        for i in 0..<out.count { out[i] = bc[i] > 0 ? Float(be[i] / bc[i]) : 0 }
        return out
    }

    @inline(__always) private static func rgb(_ p: UInt32) -> (Int, Int, Int) {
        return (Int((p >> 16) & 255), Int((p >> 8) & 255), Int(p & 255))
    }

    /**
     * Learns what the plain background looks like in each part of the photo and scores every pixel by how
     * different it is (colour OR brightness, darker OR brighter). Nothing about the grain is assumed, so any
     * grain colour, dark or light, on any plain background, under any lighting, is handled.
     */
    private static func segment(_ argb: [UInt32], _ w: Int, _ h: Int, _ s: Float) -> Seg {
        let n = w * h
        var out = Seg(fg: [Bool](repeating: false, count: n))

        // 1. The background is the biggest bright chromaticity cluster (r/(r+g+b), g/(r+g+b)).
        let B = 40
        var cnt = [Int](repeating: 0, count: B * B)
        var sumY = [Double](repeating: 0, count: B * B)
        var sr = [Double](repeating: 0, count: B * B)
        var sg = [Double](repeating: 0, count: B * B)
        var total = 0
        for i in stride(from: 0, to: n, by: 3) {
            let (r, g, b) = rgb(argb[i])
            let sum = r + g + b
            if sum < 60 { continue }
            let rn = Float(r) / Float(sum), gn = Float(g) / Float(sum)
            let k = min(B - 1, Int(rn * Float(B))) * B + min(B - 1, Int(gn * Float(B)))
            cnt[k] += 1
            sumY[k] += 0.299 * Double(r) + 0.587 * Double(g) + 0.114 * Double(b)
            sr[k] += Double(rn)
            sg[k] += Double(gn)
            total += 1
        }
        if total == 0 { return out }
        var best = -1
        var bestY = -1.0
        var biggest = 0
        for k in 0..<cnt.count {
            if cnt[k] > cnt[biggest] { biggest = k }
            if Double(cnt[k]) >= 0.06 * Double(total) {
                let y = sumY[k] / Double(cnt[k])
                if y > bestY { bestY = y; best = k }
            }
        }
        if best < 0 { best = biggest }
        var wsum = 0.0, cr = 0.0, cg = 0.0, ysum = 0.0
        for di in -1...1 {
            for dj in -1...1 {
                let bi = best / B + di, bj = best % B + dj
                if bi < 0 || bj < 0 || bi >= B || bj >= B { continue }
                let kk = bi * B + bj
                wsum += Double(cnt[kk]); cr += sr[kk]; cg += sg[kk]; ysum += sumY[kk]
            }
        }
        cr /= wsum; cg /= wsum
        out.paperLum = Float(ysum / wsum)

        // 2. Local background maps, refined once: first from the global colour, then from the first map.
        let g1 = buildGrid(argb, w, h, nil, Float(cr), Float(cg), out.paperLum)
        let g2 = buildGrid(argb, w, h, g1, Float(cr), Float(cg), out.paperLum)

        // 3. Difference score for every pixel (0 = looks like background, 1 = clearly something else).
        //    Reflections (flash, lamp, window on a glossy table) are wide patches brighter than the sheet; inside
        //    them being brighter does not count, only colour does (grains there are still darker or coloured).
        let glare: [Float]? = out.paperLum >= lightSheet ? glareMap(argb, w, h, g2) : nil
        var score = [Float](repeating: 0, count: n)
        var ratioArr = [Float](repeating: 0, count: n)
        for y in 0..<h {
            for x in 0..<w {
                let i = y * w + x
                let (r, g, b) = rgb(argb[i])
                let lum: Float = 0.299 * Float(r) + 0.587 * Float(g) + 0.114 * Float(b)
                let fl = max(8, sample(g2.lum, g2.gw, g2.gh, g2.blk, x, y))
                let ratio = lum / fl
                var reflection = false
                if ratio > 1, let gm = glare { reflection = sample(gm, g2.gw, g2.gh, g2.blk, x, y) > glareLevel }
                let dev = reflection ? 0 : min(2, abs(ratio - 1)) / lumScale
                var dc: Float = 0
                let sum = r + g + b
                if sum >= 45 {
                    let dx = Float(r) / Float(sum) - sample(g2.rn, g2.gw, g2.gh, g2.blk, x, y)
                    let dy = Float(g) / Float(sum) - sample(g2.gn, g2.gw, g2.gh, g2.blk, x, y)
                    dc = Float(Double(dx * dx + dy * dy).squareRoot()) / chromaScale
                }
                score[i] = Float(Double(dc * dc + dev * dev).squareRoot())
                ratioArr[i] = ratio
            }
        }

        // 4. Find the sheet: the biggest region that looks like background (row/column extents).
        var paperLike = [Bool](repeating: false, count: n)
        var sq = 0.0
        var sc = 0
        for i in 0..<n {
            paperLike[i] = score[i] < 0.2 && ratioArr[i] > 0.72 && ratioArr[i] < 1.5
            if score[i] < 0.2 && (i & 3) == 0 { sq += Double(score[i] * score[i]); sc += 1 }
        }
        out.noise = sc > 0 ? Float((sq / Double(sc)).squareRoot()) : 0.05
        let paper = open(paperLike, w, h, max(1, javaRound(3 * s)))
        var lab = [Int32](repeating: 0, count: n)
        let c = label(paper, w, h, &lab)
        var hull = [Bool](repeating: true, count: n)
        if c > 0 {
            var cn = [Int](repeating: 0, count: c + 1)
            for i in 0..<n where lab[i] > 0 { cn[Int(lab[i])] += 1 }
            var big = 1
            if c >= 2 { for k in 2...c where cn[k] > cn[big] { big = k } }
            let bigL = Int32(big)
            var minX = [Int](repeating: Int.max, count: h), maxX = [Int](repeating: -1, count: h)
            var minY = [Int](repeating: Int.max, count: w), maxY = [Int](repeating: -1, count: w)
            for y in 0..<h {
                for x in 0..<w where lab[y * w + x] == bigL {
                    if x < minX[y] { minX[y] = x }
                    if x > maxX[y] { maxX[y] = x }
                    if y < minY[x] { minY[x] = y }
                    if y > maxY[x] { maxY[x] = y }
                }
            }
            var hl = [Bool](repeating: false, count: n)
            var hc = 0
            for y in 0..<h {
                for x in 0..<w where x >= minX[y] && x <= maxX[y] && y >= minY[x] && y <= maxY[x] {
                    hl[y * w + x] = true
                    hc += 1
                }
            }
            if Double(hc) >= 0.2 * Double(n) {
                let e = Double(max(2, javaRound(6 * s)))
                let d = edt(hl, w, h)
                for i in 0..<n { hl[i] = d[i] > e }
                hull = hl
                out.paperFound = Double(cn[big]) >= 0.2 * Double(n)
            }
        }

        // 5. Grain threshold at half of the typical grain contrast: the true edge, so touching grains stay apart.
        //    Faint grains (low contrast) are denoised first by smoothing the difference map.
        var noise = out.noise
        for pass in 0..<2 {
            let tauLo = max(0.10, 3.5 * noise)
            var hist = [Int](repeating: 0, count: 151)
            var cand = 0
            for i in 0..<n where hull[i] && score[i] > tauLo {
                hist[min(150, Int(score[i] * 50))] += 1
                cand += 1
            }
            if cand < 40 {
                out.contrast = 0
                return out
            }
            let target = Int(Double(cand) * 0.6)
            var acc = 0, bin = 0
            while bin < 150 {
                acc += hist[bin]
                if acc >= target { break }
                bin += 1
            }
            out.contrast = min(3, (Float(bin) + 0.5) / 50)
            if pass == 0 && out.contrast < lowContrastSmoothing {
                score = gauss(score, w, h, max(1.0, 1.6 * Double(s)))   // faint grains: average out the noise
                var sq2 = 0.0
                var sc2 = 0
                for i in stride(from: 0, to: n, by: 4) where paperLike[i] {
                    sq2 += Double(score[i] * score[i])
                    sc2 += 1
                }
                noise = sc2 > 0 ? Float((sq2 / Double(sc2)).squareRoot()) : noise
                continue
            }
            out.score = score
            let thr = min(0.6, max(tauLo, 0.5 * out.contrast))
            for i in 0..<n { out.fg[i] = hull[i] && score[i] > thr }

            // Mixed grains: a pale kind next to a dark kind (chickpeas with rajma) may reach the threshold set by
            // the dark ones only at its rim, or not at all. A faint object that is mostly below the threshold is
            // kept whole at a lower level; objects that are mostly strong keep their sharp edge (touching grains).
            let low = max(tauLo, faintLevel)
            if low < thr {
                var faint = [Bool](repeating: false, count: n)
                for i in 0..<n { faint[i] = hull[i] && score[i] > low }
                var fl = [Int32](repeating: 0, count: n)
                let nf = label(faint, w, h, &fl)
                var strong = [Int](repeating: 0, count: nf + 1), all = strong
                for i in 0..<n where fl[i] > 0 {
                    all[Int(fl[i])] += 1
                    if out.fg[i] { strong[Int(fl[i])] += 1 }
                }
                for i in 0..<n where fl[i] > 0 && Double(strong[Int(fl[i])]) < 0.5 * Double(all[Int(fl[i])]) {
                    out.fg[i] = true
                }
            }
            break
        }
        return out
    }

    /**
     * Builds the local background maps. A pixel feeds the map only if it looks like background:
     * right colour and not far darker/brighter than the map says (pass 2) or than the global level (pass 1).
     */
    private static func buildGrid(_ argb: [UInt32], _ w: Int, _ h: Int, _ ref: Grid?,
                                  _ cr: Float, _ cg: Float, _ paperLum: Float) -> Grid {
        let blk = 4
        var g = Grid()
        g.blk = blk
        g.gw = (w + blk - 1) / blk
        g.gh = (h + blk - 1) / blk
        let gw = g.gw, gh = g.gh
        var nl = [Double](repeating: 0, count: gw * gh), nr = nl, ng = nl, den = nl
        for y in 0..<h {
            for x in 0..<w {
                let (r, gg, b) = rgb(argb[y * w + x])
                let sum = r + gg + b
                if sum < 45 { continue }
                let lum: Float = 0.299 * Float(r) + 0.587 * Float(gg) + 0.114 * Float(b)
                let rn = Float(r) / Float(sum), gn = Float(gg) / Float(sum)
                let rl: Float, rr: Float, rg: Float
                if let ref = ref {
                    rl = sample(ref.lum, gw, gh, blk, x, y)
                    rr = sample(ref.rn, gw, gh, blk, x, y)
                    rg = sample(ref.gn, gw, gh, blk, x, y)
                } else {
                    rl = paperLum; rr = cr; rg = cg
                }
                let dx = rn - rr, dy = gn - rg
                let ratio = lum / max(8, rl)
                if dx * dx + dy * dy < Float(0.05) * Float(0.05) && ratio > 0.6 && ratio < 1.6 {
                    let cell = (y / blk) * gw + (x / blk)
                    nl[cell] += Double(lum); nr[cell] += Double(rn); ng[cell] += Double(gn); den[cell] += 1
                }
            }
        }
        let rb = max(3, min(gw, gh) / 6)
        let bl = boxBlur(nl, gw, gh, rb), br = boxBlur(nr, gw, gh, rb), bg = boxBlur(ng, gw, gh, rb), bd = boxBlur(den, gw, gh, rb)
        g.lum = [Float](repeating: 0, count: gw * gh)
        g.rn = g.lum
        g.gn = g.lum
        for i in 0..<(gw * gh) {
            let ok = bd[i] > 40
            g.lum[i] = ok ? Float(bl[i] / bd[i]) : paperLum
            g.rn[i] = ok ? Float(br[i] / bd[i]) : cr
            g.gn[i] = ok ? Float(bg[i] / bd[i]) : cg
        }
        return g
    }

    @inline(__always)
    private static func sample(_ c: [Float], _ gw: Int, _ gh: Int, _ blk: Int, _ x: Int, _ y: Int) -> Float {
        let gy = min(Float(gh) - 1, max(0, (Float(y) + 0.5) / Float(blk) - 0.5))
        let gx = min(Float(gw) - 1, max(0, (Float(x) + 0.5) / Float(blk) - 0.5))
        let y0 = Int(gy), x0 = Int(gx)
        let y1 = min(gh - 1, y0 + 1), x1 = min(gw - 1, x0 + 1)
        let fy = gy - Float(y0), fx = gx - Float(x0)
        let top = c[y0 * gw + x0] * (1 - fx) + c[y0 * gw + x1] * fx
        let bot = c[y1 * gw + x0] * (1 - fx) + c[y1 * gw + x1] * fx
        return top * (1 - fy) + bot * fy
    }

    private static func boxBlur(_ a: [Double], _ w: Int, _ h: Int, _ r: Int) -> [Double] {
        var t = [Double](repeating: 0, count: w * h), o = t
        for y in 0..<h {
            var acc = 0.0
            for x in -r..<w {
                if x + r < w { acc += a[y * w + x + r] }
                if x - r - 1 >= 0 { acc -= a[y * w + x - r - 1] }
                if x >= 0 { t[y * w + x] = acc }
            }
        }
        for x in 0..<w {
            var acc = 0.0
            for y in -r..<h {
                if y + r < h { acc += t[(y + r) * w + x] }
                if y - r - 1 >= 0 { acc -= t[(y - r - 1) * w + x] }
                if y >= 0 { o[y * w + x] = acc }
            }
        }
        return o
    }

    // ---------------------------------------------------------------- morphology / labelling

    private static func open(_ a: [Bool], _ w: Int, _ h: Int, _ iterations: Int) -> [Bool] {
        var cur = a
        for _ in 0..<iterations { cur = erode(cur, w, h) }
        for _ in 0..<iterations { cur = dilate(cur, w, h) }
        return cur
    }

    private static func erode(_ a: [Bool], _ w: Int, _ h: Int) -> [Bool] {
        var o = [Bool](repeating: false, count: a.count)
        if w < 3 || h < 3 { return o }
        for y in 1..<(h - 1) {
            for x in 1..<(w - 1) {
                let i = y * w + x
                o[i] = a[i] && a[i - 1] && a[i + 1] && a[i - w] && a[i + w]
            }
        }
        return o
    }

    private static func dilate(_ a: [Bool], _ w: Int, _ h: Int) -> [Bool] {
        var o = [Bool](repeating: false, count: a.count)
        for y in 0..<h {
            for x in 0..<w {
                let i = y * w + x
                o[i] = a[i] || (x > 0 && a[i - 1]) || (x < w - 1 && a[i + 1])
                    || (y > 0 && a[i - w]) || (y < h - 1 && a[i + w])
            }
        }
        return o
    }

    /// Fills enclosed holes (e.g. the lighter centre of a lentil) in place.
    private static func fillHoles(_ a: inout [Bool], _ w: Int, _ h: Int) {
        let n = w * h
        var bg = [Bool](repeating: false, count: n)
        var stack: [Int] = []
        stack.reserveCapacity(1024)
        for x in 0..<w {
            let t = x, b = (h - 1) * w + x
            if !a[t] && !bg[t] { bg[t] = true; stack.append(t) }
            if !a[b] && !bg[b] { bg[b] = true; stack.append(b) }
        }
        for y in 0..<h {
            let l = y * w, r = y * w + w - 1
            if !a[l] && !bg[l] { bg[l] = true; stack.append(l) }
            if !a[r] && !bg[r] { bg[r] = true; stack.append(r) }
        }
        while let i = stack.popLast() {
            let x = i % w, y = i / w
            if x > 0 && !a[i - 1] && !bg[i - 1] { bg[i - 1] = true; stack.append(i - 1) }
            if x < w - 1 && !a[i + 1] && !bg[i + 1] { bg[i + 1] = true; stack.append(i + 1) }
            if y > 0 && !a[i - w] && !bg[i - w] { bg[i - w] = true; stack.append(i - w) }
            if y < h - 1 && !a[i + w] && !bg[i + w] { bg[i + w] = true; stack.append(i + w) }
        }
        for i in 0..<n where !a[i] && !bg[i] { a[i] = true }
    }

    /// 4-connected component labelling. Returns the number of components; labels start at 1.
    private static func label(_ a: [Bool], _ w: Int, _ h: Int, _ lab: inout [Int32]) -> Int {
        let n = w * h
        var stack: [Int] = []
        stack.reserveCapacity(1024)
        var count: Int32 = 0
        for start in 0..<n {
            if !a[start] || lab[start] != 0 { continue }
            count += 1
            stack.append(start)
            lab[start] = count
            while let i = stack.popLast() {
                let x = i % w, y = i / w
                if x > 0 && a[i - 1] && lab[i - 1] == 0 { lab[i - 1] = count; stack.append(i - 1) }
                if x < w - 1 && a[i + 1] && lab[i + 1] == 0 { lab[i + 1] = count; stack.append(i + 1) }
                if y > 0 && a[i - w] && lab[i - w] == 0 { lab[i - w] = count; stack.append(i - w) }
                if y < h - 1 && a[i + w] && lab[i + w] == 0 { lab[i + w] = count; stack.append(i + w) }
            }
        }
        return Int(count)
    }

    // ---------------------------------------------------------------- distance transform / blur

    private static let inf = 1e20

    /// Euclidean distance from every true pixel to the nearest false pixel (Felzenszwalb).
    private static func edt(_ a: [Bool], _ w: Int, _ h: Int) -> [Double] {
        var f = a.map { $0 ? inf : 0 }
        let m = max(w, h)
        var col = [Double](repeating: 0, count: m), out = col, z = [Double](repeating: 0, count: m + 1)
        var v = [Int](repeating: 0, count: m)
        for x in 0..<w {
            for y in 0..<h { col[y] = f[y * w + x] }
            dt1d(col, &out, h, &v, &z)
            for y in 0..<h { f[y * w + x] = out[y] }
        }
        for y in 0..<h {
            for x in 0..<w { col[x] = f[y * w + x] }
            dt1d(col, &out, w, &v, &z)
            for x in 0..<w { f[y * w + x] = out[x] }
        }
        for i in 0..<f.count { f[i] = f[i].squareRoot() }
        return f
    }

    private static func dt1d(_ f: [Double], _ d: inout [Double], _ n: Int, _ v: inout [Int], _ z: inout [Double]) {
        var k = 0
        v[0] = 0
        z[0] = -inf
        z[1] = inf
        if n > 1 {
            for q in 1..<n {
                var s: Double
                while true {
                    let p = v[k]
                    s = ((f[q] + Double(q * q)) - (f[p] + Double(p * p))) / (2.0 * Double(q) - 2.0 * Double(p))
                    if s <= z[k] { k -= 1 } else { break }
                }
                k += 1
                v[k] = q
                z[k] = s
                z[k + 1] = inf
            }
        }
        k = 0
        for q in 0..<n {
            while z[k + 1] < Double(q) { k += 1 }
            let p = v[k]
            d[q] = Double((q - p) * (q - p)) + f[p]
        }
    }

    private static func gauss(_ src: [Float], _ w: Int, _ h: Int, _ sigma: Double) -> [Float] {
        let r = Int(4 * sigma + 0.5)
        var k = [Float](repeating: 0, count: 2 * r + 1)
        var sum = 0.0
        for i in -r...r {
            let v = exp(-0.5 * Double(i * i) / (sigma * sigma))
            k[i + r] = Float(v)
            sum += v
        }
        for i in 0..<k.count { k[i] /= Float(sum) }

        var tmp = [Float](repeating: 0, count: w * h)
        var out = tmp
        for y in 0..<h {
            let row = y * w
            for x in 0..<w {
                var acc: Float = 0
                for t in -r...r {
                    var xx = x + t
                    if xx < 0 { xx = -xx - 1 } else if xx >= w { xx = 2 * w - xx - 1 }
                    xx = min(max(xx, 0), w - 1)
                    acc += k[t + r] * src[row + xx]
                }
                tmp[row + x] = acc
            }
        }
        for y in 0..<h {
            for x in 0..<w {
                var acc: Float = 0
                for t in -r...r {
                    var yy = y + t
                    if yy < 0 { yy = -yy - 1 } else if yy >= h { yy = 2 * h - yy - 1 }
                    yy = min(max(yy, 0), h - 1)
                    acc += k[t + r] * tmp[yy * w + x]
                }
                out[y * w + x] = acc
            }
        }
        return out
    }

    // ---------------------------------------------------------------- small helpers

    private static func median(_ a: [Double]) -> Double {
        if a.isEmpty { return 1 }
        let c = a.sorted()
        let m = c.count / 2
        return c.count % 2 == 1 ? c[m] : 0.5 * (c[m - 1] + c[m])
    }
}
