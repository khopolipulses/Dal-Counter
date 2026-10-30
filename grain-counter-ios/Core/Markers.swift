import Foundation

/**
 * The editable list of counted grains (port of the Android app's Markers.java). No UIKit, so it is unit-tested
 * with `swift test`. Keeps reading order (top-to-bottom, left-to-right), supports tap-to-add / tap-to-remove and undo.
 */
public final class Markers {
    public enum Kind: Int {
        case auto = 0       // found by the engine, looks certain
        case uncertain = 1  // found by the engine inside a touching/overlapping group
        case manual = 2     // added by the user
        case confirmed = 3  // found by the engine and checked by the user
    }

    public final class Marker {
        public let x: Float, y: Float
        public let kind: Kind

        init(_ x: Float, _ y: Float, _ kind: Kind) {
            self.x = x
            self.y = y
            self.kind = kind
        }
    }

    /// One undoable edit: an add, a remove, or a marker replaced by another (confirmed).
    private struct Op {
        let added: Bool
        let marker: Marker
        var replaced: Marker? = nil
    }

    private var list: [Marker] = []
    private var undoStack: [Op] = []
    private var band: Float = 25

    public init() {}

    /// Replaces everything with the engine's result.
    public func set(xs: [Float], ys: [Float], uncertain: [Bool]?, band: Float) {
        list.removeAll()
        undoStack.removeAll()
        self.band = max(1, band)
        for i in 0..<min(xs.count, ys.count) {
            let u = uncertain.map { i < $0.count && $0[i] } ?? false
            list.append(Marker(xs[i], ys[i], u ? .uncertain : .auto))
        }
        sort()
    }

    public var count: Int { list.count }

    public subscript(i: Int) -> Marker { list[i] }

    /// Current position of this marker (positions change when markers are added or removed).
    public func index(of m: Marker) -> Int? {
        return list.firstIndex { $0 === m }
    }

    public func count(of kind: Kind) -> Int {
        return list.reduce(0) { $0 + ($1.kind == kind ? 1 : 0) }
    }

    /// Index of the marker nearest to (x, y) within tol, or -1.
    public func hit(x: Float, y: Float, tolerance tol: Float) -> Int {
        var best = -1
        var bestD = tol * tol
        for (i, m) in list.enumerated() {
            let dx = m.x - x, dy = m.y - y
            let d2 = dx * dx + dy * dy
            if d2 < bestD {
                bestD = d2
                best = i
            }
        }
        return best
    }

    public func add(x: Float, y: Float) {
        let m = Marker(x, y, .manual)
        list.append(m)
        undoStack.append(Op(added: true, marker: m))
        sort()
    }

    public func remove(at index: Int) {
        let m = list.remove(at: index)
        undoStack.append(Op(added: false, marker: m))
    }

    /// Marks a marker as checked by the user (it is a real grain).
    public func confirm(at index: Int) {
        let old = list[index]
        if old.kind == .confirmed { return }
        let m = Marker(old.x, old.y, .confirmed)
        list[index] = m
        undoStack.append(Op(added: true, marker: m, replaced: old))
    }

    /// Index of the first marker of this kind at or after `from` (wrapping around), or -1.
    public func next(_ kind: Kind, from: Int) -> Int {
        let n = list.count
        for k in 0..<n {
            let i = ((from + k) % n + n) % n
            if list[i].kind == kind { return i }
        }
        return -1
    }

    public var canUndo: Bool { !undoStack.isEmpty }

    /// Reverts the last add or remove. Returns false if there was nothing to undo.
    @discardableResult
    public func undo() -> Bool {
        guard let op = undoStack.popLast() else { return false }
        if let old = op.replaced {
            if let i = list.firstIndex(where: { $0 === op.marker }) { list[i] = old }
        } else if op.added {
            list.removeAll { $0 === op.marker }
        } else {
            list.append(op.marker)
            sort()
        }
        return true
    }

    /// Flat (x, y, kind) triples, for saving state.
    public func export() -> [Float] {
        var out: [Float] = []
        out.reserveCapacity(list.count * 3)
        for m in list { out += [m.x, m.y, Float(m.kind.rawValue)] }
        return out
    }

    public func restore(_ data: [Float]?, band: Float) {
        list.removeAll()
        undoStack.removeAll()
        self.band = max(1, band)
        if let data = data {
            var i = 0
            while i + 2 < data.count {
                list.append(Marker(data[i], data[i + 1], Kind(rawValue: Int(data[i + 2])) ?? .auto))
                i += 3
            }
        }
        sort()
    }

    private func sort() {
        let b = band
        list = DalCounter.stableSorted(list) { p, q in
            let rp = Int(p.y / b), rq = Int(q.y / b)
            if rp != rq { return rp < rq }
            return p.x != q.x ? p.x < q.x : p.y < q.y // full order: numbers never swap after an undo
        }
    }
}
