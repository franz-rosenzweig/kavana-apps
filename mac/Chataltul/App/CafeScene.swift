import AppKit
import SwiftUI

// The Cat Café in 3D, drawn natively. It follows the web's cafe3d.js: a floating room seen from above at 60
// degrees (the camera is orthographic; the floor is squashed by K and a tile of height rises by KZ on screen),
// with the things you own placed by that file's own table, the cats that came home sitting on them, and the two
// back walls. It spins in quarter turns when you let go. What is left out: the cats walking about, the arrival
// drop and its sparkles (the room is a picture of a day, not a toy).

private let K: CGFloat = 0.5
private let KZ: CGFloat = 0.866
private let QUARTER = CGFloat.pi / 2

struct CafeCatData {
    var key: String
    var id: String
    var src: String
    var napSrc: String?
    var kitten: Bool
    var title: String
    var sub: String
    var poses: [String: String]?
}

struct CafeData {
    var size = "day"
    var theme = "classic"
    var light = "day"
    var dim = false
    var things: [String] = []
    var views: [String: (side: String?, back: String?)] = [:]
    var cats: [CafeCatData] = []
    var gone: [String] = []
    var seed = 7
}

// ---------------------------------------------------------------- the paintings, trimmed to their painted part

final class CafeArt {
    struct Piece { var image: Image; var aspect: CGFloat }   // aspect: height over width of the painted part
    private static var cache: [String: Piece?] = [:]

    static func piece(_ path: String) -> Piece? {
        if let c = cache[path] { return c }
        var out: Piece?
        if let ns = Art.image(path), let cg = ns.cgImage(forProposedRect: nil, context: nil, hints: nil) {
            let w = cg.width, h = cg.height
            var box = CGRect(x: 0, y: 0, width: w, height: h)
            if let data = cg.dataProvider?.data, let ptr = CFDataGetBytePtr(data), cg.bitsPerPixel == 32 {
                let bpr = cg.bytesPerRow
                var x0 = w, y0 = h, x1 = 0, y1 = 0
                // the alpha byte is the last (premultiplied last) or the first, depending on how the file decoded
                let first = cg.alphaInfo == .premultipliedFirst || cg.alphaInfo == .first || cg.alphaInfo == .noneSkipFirst
                for y in stride(from: 0, to: h, by: 2) {
                    for x in stride(from: 0, to: w, by: 2) {
                        let a = ptr[y * bpr + x * 4 + (first ? 0 : 3)]
                        if a > 30 { x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y) }
                    }
                }
                if x1 > x0, y1 > y0 { box = CGRect(x: x0, y: y0, width: min(w - x0, x1 - x0 + 2), height: min(h - y0, y1 - y0 + 2)) }
            }
            if let cropped = cg.cropping(to: box) {
                out = Piece(image: Image(decorative: cropped, scale: 1), aspect: CGFloat(cropped.height) / CGFloat(max(1, cropped.width)))
            }
        }
        cache[path] = out
        return out
    }
}

// ---------------------------------------------------------------- the scene

private struct Theme3D {
    var wall: (Color, Color), dots: Color, skirt: Color, skirtLine: Color, floor: (Color, Color), board: Color, slab: Color, trim: Color
}

private func c(_ hex: UInt32, _ a: Double = 1) -> Color { Color(hex: hex, alpha: a) }

private let THEMES: [String: Theme3D] = [
    "classic": Theme3D(wall: (c(0xFDF5E8), c(0xF5E4CB)), dots: c(0xC49668, 0.32), skirt: c(0xECD2AE), skirtLine: c(0xD9B386), floor: (c(0xE8BD85), c(0xDBA56B)), board: c(0x7C4A22, 0.17), slab: c(0xB9845A), trim: c(0xF6DCB6)),
    "mint": Theme3D(wall: (c(0xE8F6EE), c(0xD3EBDE)), dots: c(0x3C8C64, 0.24), skirt: c(0xCFE3D6), skirtLine: c(0xA9C9B5), floor: (c(0xEAD4AB), c(0xDCC191)), board: c(0x785528, 0.15), slab: c(0xA48A66), trim: c(0xF1E5C9)),
    "sakura": Theme3D(wall: (c(0xFDE9F0), c(0xF8D5E2)), dots: c(0xE1789B, 0.36), skirt: c(0xF3C9D6), skirtLine: c(0xE3A2B7), floor: (c(0xF2DDC9), c(0xE5C9B0)), board: c(0x965A46, 0.13), slab: c(0xC99C88), trim: c(0xFBE8DD)),
    "library": Theme3D(wall: (c(0x31604C), c(0x284F3E)), dots: c(0xFFE6B4, 0.16), skirt: c(0x5B3B26), skirtLine: c(0x462C1C), floor: (c(0x7C5438), c(0x684430)), board: c(0x000000, 0.22), slab: c(0x4D3322), trim: c(0x8C6748)),
    "garden": Theme3D(wall: (c(0xEAF7FF), c(0xCFE9FB)), dots: c(0xFFFFFF, 0.85), skirt: c(0x8FCF7A), skirtLine: c(0x76B862), floor: (c(0x9FD480), c(0x86C266)), board: c(0x3C7828, 0.16), slab: c(0x7B9A5A), trim: c(0xD8EFC6)),
    "night": Theme3D(wall: (c(0x232849), c(0x2D3257)), dots: c(0xFFF0B4, 0.6), skirt: c(0x3A3550), skirtLine: c(0x4C4568), floor: (c(0x4F3D2E), c(0x403126)), board: c(0x000000, 0.28), slab: c(0x2E2622), trim: c(0x5D4F43)),
]

private struct Place { var u: CGFloat, v: CGFloat, w: CGFloat, fp: [CGFloat]? = nil, perch: CGFloat? = nil, light: CGFloat? = nil, pri: Int }
private let PLACE: [String: Place] = [
    "cattree": Place(u: 0.12, v: 0.12, w: 1.05, perch: 0.93, pri: 1), "cushion": Place(u: 0.47, v: 0.5, w: 0.95, perch: 0.2, pri: 1),
    "box": Place(u: 0.8, v: 0.8, w: 1.0, perch: 0.48, pri: 1), "armchair": Place(u: 0.15, v: 0.8, w: 1.3, fp: [1.05, 1.2], perch: 0.42, pri: 1),
    "counter": Place(u: 0.6, v: 0.12, w: 2.05, fp: [2.1, 1.1], perch: 0.6, pri: 2), "fireplace": Place(u: 0.1, v: 0.48, w: 1.35, fp: [0.9, 1.45], light: 0.35, pri: 2),
    "bookshelf": Place(u: 0.9, v: 0.1, w: 0.95, perch: 0.97, pri: 3), "lamp": Place(u: 0.09, v: 0.3, w: 0.72, light: 0.9, pri: 4),
    "plant": Place(u: 0.9, v: 0.48, w: 0.84, pri: 5), "fishbowl": Place(u: 0.9, v: 0.3, w: 0.64, pri: 6), "yarnbasket": Place(u: 0.43, v: 0.88, w: 0.72, pri: 6),
    "jackolantern": Place(u: 0.64, v: 0.34, w: 0.82, light: 0.6, pri: 7), "cauldron": Place(u: 0.3, v: 0.66, w: 0.8, light: 0.5, pri: 7),
    "snowman": Place(u: 0.72, v: 0.56, w: 0.85, pri: 7), "candles": Place(u: 0.88, v: 0.66, w: 0.42, light: 0.7, pri: 8),
    "sled": Place(u: 0.56, v: 0.9, w: 1.1, fp: [1.15, 0.8], pri: 8), "snowglobe": Place(u: 0.32, v: 0.3, w: 0.5, pri: 9),
]
private let WALLS: [String: (edge: Character, u: CGFloat, v: CGFloat, w: CGFloat)] = ["window": ("N", 0.33, 0.3, 1.5), "clock": ("W", 0.68, 0.42, 0.62)]
private let SOFT: Set<String> = ["cushion", "box", "armchair"]
private let EDGES: [(Character, (Int, Int), (Int, Int))] = [("N", (0, 0), (1, 0)), ("W", (0, 0), (0, 1)), ("E", (1, 0), (1, 1)), ("S", (0, 1), (1, 1))]
private let SIZES: [String: (n: Int, fits: Int)] = ["day": (4, 8), "week": (6, 22), "month": (8, 38), "big": (10, 60)]
private let ORDER = ["day", "week", "month", "big"]
private let POSE_W: [String: CGFloat] = ["walk": 0.98, "loaf": 0.82, "stretch": 1.05, "wash": 0.8, "back": 0.78]

private func rng(_ seed: Int) -> () -> Double {
    var s = UInt32(truncatingIfNeeded: seed == 0 ? 1 : seed)
    return { s ^= s << 13; s ^= s >> 17; s ^= s << 5; return Double(s) / 4294967296 }
}
private func hash(_ str: String) -> Int { str.unicodeScalars.reduce(7) { Int(UInt32(truncatingIfNeeded: $0 &* 31 &+ Int($1.value))) } }
private func clamp(_ x: CGFloat, _ a: CGFloat, _ b: CGFloat) -> CGFloat { max(a, min(b, x)) }

private final class Sprite {
    var id = "", key = "", src = ""
    var w: CGFloat = 1, x: CGFloat = 0, y: CGFloat = 0, z: CGFloat = 0
    var fp: [CGFloat]?, perch: CGFloat?, light: CGFloat?, pri = 0
    var seat: Sprite?
    var isCat = false, nap = false, flip = false, kitten = false
    var phase: Double = 0
    var title = "", sub = ""
    var rect: CGRect?
    var depth: CGFloat = 0
}

private struct WallItem { var id: String?; var edge: Character; var u: CGFloat; var v: CGFloat; var w: CGFloat; var door = false }
private struct Paw { var x: CGFloat, y: CGFloat, a: CGFloat, side: Int }

final class CafeScene {
    fileprivate(set) var n = 6
    private var center: CGFloat { CGFloat(n) / 2 }
    private var theme = "classic", light = "day", dim = false
    private var things: [Sprite] = []
    fileprivate var cats: [Sprite] = []
    private var walls: [WallItem] = []
    private var rug: [CGFloat]?
    private var paws: [Paw] = []
    private var views: [String: (side: String?, back: String?)] = [:]
    private var sig = ""
    private let wallH: CGFloat = 2.25, depth: CGFloat = 0.5, rim: CGFloat = 0.14

    // the camera
    var angle: CGFloat = .pi / 4
    var goal: CGFloat = .pi / 4
    var spinV: CGFloat = 0
    var dragging = false
    private var S: CGFloat = 0, ox: CGFloat = 0, oy: CGFloat = 0, W: CGFloat = 1, H: CGFloat = 1
    private var ca: CGFloat = 1, sa: CGFloat = 0
    var time: Double = 0
    var catCount: Int { cats.count }
    var onTip: ((String, String, CGPoint)?) -> Void = { _ in }

    // ---------------- what to draw

    func show(_ data: CafeData) {
        let signature = "\(data.size)|\(data.things)|\(data.cats.map { $0.key + $0.src })|\(data.gone)|\(data.seed)"
        theme = THEMES[data.theme] != nil ? data.theme : "classic"
        light = data.light; dim = data.dim; views = data.views
        if signature == sig { return }
        sig = signature
        var i = max(0, ORDER.firstIndex(of: data.size) ?? 0)
        while i < ORDER.count - 1, data.cats.count > SIZES[ORDER[i]]!.fits { i += 1 }
        n = SIZES[ORDER[i]]!.n
        let r = rng(data.seed)
        layout(data.things)
        cats = []; paws = []
        let seats = things.filter { $0.perch != nil }.sorted { $0.pri < $1.pri }
        var posers = 0
        for (k, cat) in data.cats.enumerated() {
            if k < seats.count {
                let seat = seats[k]
                addCat(cat, seat: seat, x: 0, y: 0, nap: cat.napSrc != nil && SOFT.contains(seat.id) && r() < 0.55)
                continue
            }
            guard let spot = freeSpot(r) else { continue }
            let m = addCat(cat, seat: nil, x: spot.0, y: spot.1, nap: cat.napSrc != nil && !cat.kitten && r() < 0.3)
            // a cat with pose paintings stands in one of them
            if let poses = cat.poses, posers < 3, !cat.kitten {
                posers += 1
                let pick = ["loaf", "wash", "stretch", "back"][Int(r() * 4) % 4]
                if let p = poses[pick] { m.src = p; m.w = POSE_W[pick] ?? 0.8; m.nap = pick == "loaf" }
            }
        }
        // the kittens that wandered off left paw prints
        for key in data.gone.prefix(6) {
            let pr = rng(hash(key))
            if let s = freeSpot(pr) {
                let a = CGFloat(pr()) * .pi * 2
                trail(s.0, s.1, clamp(s.0 + cos(a) * 1.3, 0.3, CGFloat(n) - 0.3), clamp(s.1 + sin(a) * 1.3, 0.3, CGFloat(n) - 0.3))
            }
        }
    }

    private func layout(_ ids: [String]) {
        things = []; walls = []; rug = nil
        let own = Set(ids)
        let nn = CGFloat(n)
        if own.contains("rug") { let w = nn * 0.42, h = nn * 0.32; rug = [nn / 2 - w / 2 + nn * 0.04, nn / 2 - h / 2 + nn * 0.06, w, h] }
        for (id, w) in WALLS where own.contains(id) { walls.append(WallItem(id: id, edge: w.edge, u: w.u * nn, v: w.v, w: w.w)) }
        if own.contains("batgarland") { for e in ["N", "W"] { walls.append(WallItem(id: "batgarland", edge: Character(e), u: nn / 2, v: 0.05, w: min(nn - 0.6, 4.2))) } }
        walls.append(WallItem(id: nil, edge: "E", u: nn * 0.55, v: 0, w: 0, door: true))
        let sill = own.contains("snowglobe") && own.contains("window")
        if sill, let win = walls.first(where: { $0.id == "window" }) { walls.append(WallItem(id: "snowglobe", edge: "N", u: win.u - 0.42, v: win.v + 0.98, w: 0.36)) }
        let list = PLACE.filter { own.contains($0.key) && !($0.key == "snowglobe" && sill) }.sorted { $0.value.pri != $1.value.pri ? $0.value.pri < $1.value.pri : $0.key < $1.key }
        let big: CGFloat = n >= 6 ? 1 : 0.86
        for (id, p) in list {
            let t = Sprite()
            t.id = id; t.src = "cafe/\(id)-1.webp"; t.w = p.w * big; t.fp = p.fp.map { $0.map { $0 * big } }; t.perch = p.perch; t.light = p.light; t.pri = p.pri
            guard let spot = spotNear(p.u * nn, p.v * nn, t) else { continue }
            t.x = spot.0; t.y = spot.1
            things.append(t)
        }
    }

    private func foot(_ t: Sprite) -> (CGFloat, CGFloat) { t.fp.map { ($0[0], $0[1]) } ?? (t.w * 0.9, t.w * 0.9) }

    private func spotNear(_ x: CGFloat, _ y: CGFloat, _ t: Sprite) -> (CGFloat, CGFloat)? {
        let nn = CGFloat(n), (fw, fd) = foot(t)
        func ok(_ px: CGFloat, _ py: CGFloat) -> Bool {
            if !(px - fw / 2 >= 0.08 && px + fw / 2 <= nn - 0.08 && py - fd / 2 >= 0.08 && py + fd / 2 <= nn - 0.08) { return false }
            for o in things { let (ow, od) = foot(o); if abs(px - o.x) < (fw + ow) / 2 + 0.05 && abs(py - o.y) < (fd + od) / 2 + 0.05 { return false } }
            if let rg = rug, t.id != "rug", px > rg[0] + 0.3, px < rg[0] + rg[2] - 0.3, py > rg[1] + 0.3, py < rg[1] + rg[3] - 0.3, !SOFT.contains(t.id), t.id != "yarnbasket" { return false }
            return true
        }
        let cx = clamp(x, fw / 2 + 0.08, nn - fw / 2 - 0.08), cy = clamp(y, fd / 2 + 0.08, nn - fd / 2 - 0.08)
        if ok(cx, cy) { return (cx, cy) }
        var rad: CGFloat = 0.25
        while rad < nn * 0.6 {
            for k in 0..<16 {
                let a = CGFloat(k) / 16 * .pi * 2
                let px = cx + cos(a) * rad, py = cy + sin(a) * rad
                if ok(px, py) { return (px, py) }
            }
            rad += 0.25
        }
        return nil
    }

    private func occupied(_ x: CGFloat, _ y: CGFloat, pad: CGFloat = 0.55) -> Bool {
        for t in things { let (fw, fd) = foot(t); if abs(x - t.x) < fw / 2 + pad * 0.6 && abs(y - t.y) < fd / 2 + pad * 0.6 { return true } }
        for c in cats where c.seat == nil { if hypot(c.x - x, c.y - y) < pad * 1.3 { return true } }
        return false
    }

    private func freeSpot(_ r: () -> Double) -> (CGFloat, CGFloat)? {
        let nn = CGFloat(n)
        for k in 0..<120 {
            let x = 0.6 + CGFloat(r()) * (nn - 1.2), y = 0.6 + CGFloat(r()) * (nn - 1.2)
            if !occupied(x, y, pad: k > 90 ? 0.4 : 0.55) { return (x, y) }
        }
        return nil
    }

    @discardableResult
    private func addCat(_ d: CafeCatData, seat: Sprite?, x: CGFloat, y: CGFloat, nap: Bool) -> Sprite {
        let asleep = nap && d.napSrc != nil && !d.kitten
        let s = Sprite()
        s.isCat = true; s.key = d.key; s.id = d.id; s.title = d.title; s.sub = d.sub
        s.src = asleep ? d.napSrc! : d.src; s.nap = asleep; s.kitten = d.kitten
        s.w = d.kitten ? 0.66 : asleep ? 0.9 : 0.8
        s.seat = seat
        s.x = seat.map { $0.x + 0.04 } ?? x; s.y = seat.map { $0.y + 0.06 } ?? y
        s.phase = Double(hash(d.key) % 600) / 100
        s.flip = hash(d.key) % 2 == 1
        if let seat { s.w = min(s.w, seat.w * (asleep ? 0.86 : 0.76)) }
        cats.append(s)
        return s
    }

    private func trail(_ x0: CGFloat, _ y0: CGFloat, _ x1: CGFloat, _ y1: CGFloat) {
        let m = max(3, Int((hypot(x1 - x0, y1 - y0) / 0.32).rounded()))
        let ang = atan2(y1 - y0, x1 - x0)
        for i in 0...m { let t = CGFloat(i) / CGFloat(m); paws.append(Paw(x: x0 + (x1 - x0) * t, y: y0 + (y1 - y0) * t, a: ang, side: i % 2)) }
    }

    private func heightZ(_ s: Sprite) -> CGFloat {
        guard let p = CafeArt.piece(s.src) else { return s.w / KZ }
        return (s.w * p.aspect) / KZ
    }

    // ---------------- the camera

    private var lastTick: Date?
    func step(date: Date, size: CGSize) {
        let dt = min(0.05, date.timeIntervalSince(lastTick ?? date))
        lastTick = date
        time += dt
        W = size.width; H = size.height
        if !dragging {
            let d = goal - angle
            spinV += (d * 70 - spinV * 14) * CGFloat(dt)
            angle += spinV * CGFloat(dt)
            if abs(d) < 1e-4, abs(spinV) < 1e-3 { angle = goal; spinV = 0 }
        }
        ca = cos(angle); sa = sin(angle)
        // fit the room to the canvas
        let r = (CGFloat(n) / 2 + rim) * 2.0.squareRoot()
        let x0 = -r, x1 = r, y0 = -r * K - wallH * KZ - 0.15, y1 = r * K + (depth + 0.35) * KZ
        let m: CGFloat = 12
        S = min((W - 2 * m) / (x1 - x0), (H - 2 * m) / (y1 - y0))
        ox = W / 2 - ((x0 + x1) / 2) * S
        oy = H / 2 - ((y0 + y1) / 2) * S
    }

    var moving: Bool { abs(goal - angle) > 1e-4 || abs(spinV) > 1e-3 || dragging }

    func spin(_ by: Int) { goal += CGFloat(by) * QUARTER }
    func snap() { goal = (((angle - .pi / 4) / QUARTER).rounded()) * QUARTER + .pi / 4 }

    private func P(_ x: CGFloat, _ y: CGFloat, _ z: CGFloat = 0) -> (p: CGPoint, d: CGFloat) {
        let dx = x - center, dy = y - center
        let rx = dx * ca - dy * sa, ry = dx * sa + dy * ca
        return (CGPoint(x: ox + rx * S, y: oy + ry * S * K - z * S * KZ), ry)
    }
    private func pt(_ x: CGFloat, _ y: CGFloat, _ z: CGFloat = 0) -> CGPoint { P(x, y, z).p }
    private func quarter() -> Int { ((Int(((angle - .pi / 4) / QUARTER).rounded()) % 4) + 4) % 4 }

    // ---------------- drawing

    private func shade(_ color: Color, _ f: CGFloat) -> Color {
        let ns = NSColor(color).usingColorSpace(.sRGB) ?? .gray
        func s(_ v: CGFloat) -> Double { Double(f < 0 ? v * (1 + f) : v + (1 - v) * f) }
        return Color(.sRGB, red: s(ns.redComponent), green: s(ns.greenComponent), blue: s(ns.blueComponent), opacity: 1)
    }

    private func poly(_ ctx: GraphicsContext, _ pts: [CGPoint], fill: Color?, stroke: Color? = nil, lw: CGFloat = 1) {
        var p = Path(); p.move(to: pts[0]); for q in pts.dropFirst() { p.addLine(to: q) }; p.closeSubpath()
        if let f = fill { ctx.fill(p, with: .color(f)) }
        if let s = stroke { ctx.stroke(p, with: .color(s), lineWidth: lw) }
    }

    /// A matrix that lays flat drawing (units of tiles) on a plane of the room.
    private func plane(_ o: CGPoint, _ u: CGPoint, _ v: CGPoint) -> CGAffineTransform {
        CGAffineTransform(a: u.x - o.x, b: u.y - o.y, c: v.x - o.x, d: v.y - o.y, tx: o.x, ty: o.y)
    }

    func draw(_ ctx: GraphicsContext, size: CGSize) {
        let T = THEMES[theme]!
        let nn = CGFloat(n), R = rim, D = depth, H = wallH
        // the shadow under the floating café
        let c0 = pt(nn / 2, nn / 2, -D - 0.2)
        var sh = ctx
        sh.translateBy(x: c0.x, y: c0.y); sh.scaleBy(x: 1, y: 0.42)
        sh.fill(Path(ellipseIn: CGRect(x: -nn * 0.78 * S, y: -nn * 0.78 * S, width: nn * 1.56 * S, height: nn * 1.56 * S)),
                with: .radialGradient(Gradient(colors: [Color(hex: 0x281E14, alpha: 0.22), Color(hex: 0x281E14, alpha: 0)]), center: .zero, startRadius: 0, endRadius: nn * 0.78 * S))
        // the base: the sides facing us, then the rim on top
        let corners: [(CGFloat, CGFloat)] = [(-R, -R), (nn + R, -R), (nn + R, nn + R), (-R, nn + R)]
        for i in 0..<4 {
            let a = corners[i], b = corners[(i + 1) % 4]
            if P((a.0 + b.0) / 2, (a.1 + b.1) / 2).d <= 0 { continue }
            let pts = [pt(a.0, a.1, 0), pt(b.0, b.1, 0), pt(b.0, b.1, -D), pt(a.0, a.1, -D)]
            let facing = abs(a.0 == b.0 ? ca : sa)
            poly(ctx, pts, fill: shade(T.slab, -0.12 * (1 - facing)))
            for row in 1..<3 { let z = -D * CGFloat(row) / 3; var l = Path(); l.move(to: pt(a.0, a.1, z)); l.addLine(to: pt(b.0, b.1, z)); ctx.stroke(l, with: .color(Color(hex: 0x3C1E0A, alpha: 0.16)), lineWidth: 1) }
            let L = nn + 2 * R
            for row in 0..<3 { for k in 0...Int(L * 1.6) {
                let t = (CGFloat(k) + CGFloat(row % 2) * 0.5) / (L * 1.6); if t <= 0 || t >= 1 { continue }
                let x = a.0 + (b.0 - a.0) * t, y = a.1 + (b.1 - a.1) * t
                var l = Path(); l.move(to: pt(x, y, -D * CGFloat(row) / 3)); l.addLine(to: pt(x, y, -D * CGFloat(row + 1) / 3)); ctx.stroke(l, with: .color(Color(hex: 0x3C1E0A, alpha: 0.16)), lineWidth: 1)
            } }
        }
        poly(ctx, corners.map { pt($0.0, $0.1, 0) }, fill: T.trim)
        // the floor, in boards
        for j in 0..<n { for i in 0..<n { poly(ctx, [pt(CGFloat(i), CGFloat(j)), pt(CGFloat(i + 1), CGFloat(j)), pt(CGFloat(i + 1), CGFloat(j + 1)), pt(CGFloat(i), CGFloat(j + 1))], fill: (i + j) % 2 == 1 ? T.floor.0 : T.floor.1) } }
        for k in 0...(n * 2) { var l = Path(); l.move(to: pt(0, CGFloat(k) / 2)); l.addLine(to: pt(nn, CGFloat(k) / 2)); ctx.stroke(l, with: .color(T.board), lineWidth: 1) }
        // the rug, laid flat
        if let rg = rug, let rp = CafeArt.piece("cafe/rug-1.webp") {
            var l = ctx
            l.concatenate(plane(pt(rg[0], rg[1]), pt(rg[0] + 1, rg[1]), pt(rg[0], rg[1] + 1)))
            l.opacity = 0.95
            l.draw(rp.image, in: CGRect(x: 0, y: 0, width: rg[2], height: rg[3]))
        }
        // paw prints
        for p in paws {
            var l = ctx
            l.concatenate(plane(pt(p.x, p.y), pt(p.x + 1, p.y), pt(p.x, p.y + 1)))
            l.rotate(by: .radians(Double(p.a)))
            l.translateBy(x: 0, y: p.side == 1 ? 0.07 : -0.07)
            let col = Color(hex: 0x6E4628, alpha: 0.4)
            l.fill(Path(ellipseIn: CGRect(x: -0.06, y: -0.05, width: 0.12, height: 0.1)), with: .color(col))
            for (tx, ty) in [(0.07, -0.045), (0.09, 0.0), (0.07, 0.045)] { l.fill(Path(ellipseIn: CGRect(x: tx - 0.022, y: ty - 0.022, width: 0.044, height: 0.044)), with: .color(col)) }
        }
        // the two walls at the back (the front two are cut away, like a dollhouse)
        var back: [(edge: Character, a: (CGFloat, CGFloat), b: (CGFloat, CGFloat), d: CGFloat)] = []
        for (edge, A, B) in EDGES {
            let a = (CGFloat(A.0) * nn, CGFloat(A.1) * nn), b = (CGFloat(B.0) * nn, CGFloat(B.1) * nn)
            let m = P((a.0 + b.0) / 2, (a.1 + b.1) / 2)
            if m.d < -0.01 * nn { back.append((edge, a, b, m.d)) }
        }
        back.sort { $0.d < $1.d }
        for w in back { drawWall(ctx, w.edge, w.a, w.b, T) }
        for w in back {
            let out: (CGFloat, CGFloat) = w.edge == "N" ? (0, -R) : w.edge == "S" ? (0, R) : w.edge == "W" ? (-R, 0) : (R, 0)
            let A = w.a, B = w.b
            poly(ctx, [pt(A.0, A.1, H), pt(B.0, B.1, H), pt(B.0 + out.0, B.1 + out.1, H), pt(A.0 + out.0, A.1 + out.1, H)], fill: T.trim, stroke: Color(hex: 0x5A3C1E, alpha: 0.18))
            for e in [A, B] {
                if P(e.0, e.1).d < -0.4 * nn { continue }
                poly(ctx, [pt(e.0, e.1, 0), pt(e.0 + out.0, e.1 + out.1, 0), pt(e.0 + out.0, e.1 + out.1, H), pt(e.0, e.1, H)], fill: shade(T.trim, -0.08))
            }
        }
        // everything standing, back to front
        let q = quarter()
        let all = things + cats
        for s in all { s.depth = P(s.x, s.y).d + (s.seat != nil ? 0.08 : 0) + (s.isCat ? 0.01 : 0) }
        for s in all.sorted(by: { $0.depth < $1.depth }) {
            if s.seat != nil, let seat = s.seat { s.z = heightZ(seat) * (seat.perch ?? 0) }
            if s.isCat { sprite(ctx, s, src: s.src, flip: s.flip) }
            else { let v = thingSrc(s, q); sprite(ctx, s, src: v.src, flip: v.flip) }
        }
        // low walls along the front edges, so the room has a lip
        let lip: CGFloat = 0.2
        for (edge, A, B) in EDGES {
            let a = (CGFloat(A.0) * nn, CGFloat(A.1) * nn), b = (CGFloat(B.0) * nn, CGFloat(B.1) * nn)
            if P((a.0 + b.0) / 2, (a.1 + b.1) / 2).d < -0.01 * nn { continue }
            let out: (CGFloat, CGFloat) = edge == "N" ? (0, -R) : edge == "S" ? (0, R) : edge == "W" ? (-R, 0) : (R, 0)
            let fa = (a.0 + out.0, a.1 + out.1), fb = (b.0 + out.0, b.1 + out.1)
            poly(ctx, [pt(fa.0, fa.1, 0), pt(fb.0, fb.1, 0), pt(fb.0, fb.1, lip), pt(fa.0, fa.1, lip)], fill: shade(T.skirt, -0.06))
            poly(ctx, [pt(a.0, a.1, lip), pt(b.0, b.1, lip), pt(fb.0, fb.1, lip), pt(fa.0, fa.1, lip)], fill: T.trim, stroke: Color(hex: 0x5A3C1E, alpha: 0.15))
        }
        drawLight(ctx, size)
    }

    private func thingSrc(_ t: Sprite, _ q: Int) -> (src: String, flip: Bool) {
        guard let vw = views[t.id], q != 0 else { return (t.src, false) }
        if q == 2, let b = vw.back { return (b, false) }
        if q == 2, let s = vw.side { return (s, true) }
        if (q == 1 || q == 3), let s = vw.side { return (s, q == 3) }
        return (t.src, q == 3)
    }

    private func sprite(_ ctx: GraphicsContext, _ s: Sprite, src: String, flip: Bool) {
        guard let piece = CafeArt.piece(src) else { s.rect = nil; return }
        let p = pt(s.x, s.y, s.z)
        var w = s.w * S, h = w * piece.aspect
        if s.isCat {   // breathing: a little taller, a little narrower
            let b = CGFloat(sin((time + s.phase) * .pi * 2 / (s.nap ? 5.2 : 3.6)))
            h *= 1 + 0.024 * b; w *= 1 - 0.008 * b
        }
        let sp = pt(s.x, s.y, s.seat != nil ? s.z : 0)
        ctx.fill(Path(ellipseIn: CGRect(x: sp.x - w * 0.4, y: sp.y - w * 0.4 * K, width: w * 0.8, height: w * 0.8 * K)), with: .color(Color(hex: 0x462808, alpha: s.isCat ? 0.18 : 0.14)))
        var l = ctx
        l.translateBy(x: p.x, y: p.y)
        if flip { l.scaleBy(x: -1, y: 1) }
        l.draw(piece.image, in: CGRect(x: -w / 2, y: -h, width: w, height: h))
        s.rect = CGRect(x: p.x - w / 2, y: p.y - h, width: w, height: h)
    }

    private func drawWall(_ ctx: GraphicsContext, _ edge: Character, _ a0: (CGFloat, CGFloat), _ b0: (CGFloat, CGFloat), _ T: Theme3D) {
        var A = a0, B = b0, flip = false
        if pt(B.0, B.1).x < pt(A.0, A.1).x { swap(&A, &B); flip = true }
        let L = CGFloat(n), H = wallH
        let o = pt(A.0, A.1, H), e = pt(B.0, B.1, H)
        var l = ctx
        l.concatenate(CGAffineTransform(a: (e.x - o.x) / L, b: (e.y - o.y) / L, c: 0, d: S * KZ, tx: o.x, ty: o.y))
        l.fill(Path(CGRect(x: 0, y: 0, width: L, height: H)), with: .linearGradient(Gradient(colors: [T.wall.0, T.wall.1]), startPoint: .zero, endPoint: CGPoint(x: 0, y: H)))
        let lit = edge == "N" || edge == "S" ? abs(sa) : abs(ca)
        l.fill(Path(CGRect(x: 0, y: 0, width: L, height: H)), with: .color(Color(hex: 0x503214, alpha: Double(0.1 * (1 - lit)))))
        var row = 0, yy: CGFloat = 0.22
        while yy < H - 0.4 {
            var xx: CGFloat = 0.15 + CGFloat(row % 2) * 0.15
            while xx < L { l.fill(Path(ellipseIn: CGRect(x: xx - 0.022, y: yy - 0.022, width: 0.044, height: 0.044)), with: .color(T.dots)); xx += 0.3 }
            row += 1; yy += 0.3
        }
        l.fill(Path(CGRect(x: 0, y: H - 0.22, width: L, height: 0.22)), with: .color(T.skirt))
        l.fill(Path(CGRect(x: 0, y: H - 0.24, width: L, height: 0.04)), with: .color(T.skirtLine))
        for it in walls where it.edge == edge {
            let uu = flip ? L - it.u : it.u
            if it.door { drawDoor(l, uu, H); continue }
            guard let id = it.id, let pc = CafeArt.piece("cafe/\(id)-1.webp") else { continue }
            l.draw(pc.image, in: CGRect(x: uu - it.w / 2, y: it.v, width: it.w, height: it.w * pc.aspect))
        }
    }

    private func drawDoor(_ l: GraphicsContext, _ u: CGFloat, _ H: CGFloat) {
        let w: CGFloat = 0.95, h: CGFloat = 1.75, x = u - w / 2, y = H - h - 0.04
        func rr(_ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat, _ top: CGFloat, _ bottom: CGFloat) -> Path {
            Path(roundedRect: CGRect(x: x, y: y, width: w, height: h), cornerRadii: RectangleCornerRadii(topLeading: top, bottomLeading: bottom, bottomTrailing: bottom, topTrailing: top))
        }
        l.fill(rr(x - 0.06, y - 0.06, w + 0.12, h + 0.08, 0.5, 0), with: .color(Color(hex: 0xB98252)))
        l.fill(rr(x, y, w, h, 0.45, 0), with: .color(Color(hex: 0xD9A46C)))
        l.fill(rr(x + 0.18, y + 0.18, w - 0.36, 0.55, 0.3, 0.05), with: .color(Color(hex: 0xA7D7EE)))
        l.fill(Path(ellipseIn: CGRect(x: x + w - 0.16 - 0.045, y: y + h * 0.58 - 0.045, width: 0.09, height: 0.09)), with: .color(Color(hex: 0x7A4C2A)))
        let sign = rr(x + 0.16, y + 0.86, w - 0.32, 0.26, 0.05, 0.05)
        l.fill(sign, with: .color(Color(hex: 0xFFF8EC)))
        l.stroke(sign, with: .color(Color(hex: 0x7A4C2A)), lineWidth: 0.025)
        var t = l
        t.translateBy(x: u, y: y + 0.99)
        t.scaleBy(x: 0.01, y: 0.01)
        t.draw(Text("OPEN").font(.system(size: 17, weight: .heavy, design: .rounded)).foregroundStyle(Color(hex: 0x2C8A7E)), at: .zero)
    }

    private func drawLight(_ ctx: GraphicsContext, _ size: CGSize) {
        let tints: [String: (tint: Color?, glow: Color?, r: CGFloat)] = [
            "morning": (Color(hex: 0xA0C8FF, alpha: 0.12), nil, 0), "day": (nil, nil, 0),
            "evening": (Color(hex: 0xFF7828, alpha: 0.16), Color(hex: 0xFFD696, alpha: 0.28), 1.9),
            "night": (Color(hex: 0x121642, alpha: 0.3), Color(hex: 0xFFC878, alpha: 0.34), 2.0),
        ]
        let Lt = tints[light] ?? (nil, nil, 0)
        let tint = Lt.tint ?? (dim ? Color(hex: 0x0A0C1E, alpha: 0.28) : nil)
        var l = ctx
        l.blendMode = .sourceAtop
        if let t = tint { l.fill(Path(CGRect(origin: .zero, size: size)), with: .color(t)) }
        if dim, Lt.tint != nil { l.fill(Path(CGRect(origin: .zero, size: size)), with: .color(Color(hex: 0x0A0C1E, alpha: 0.14))) }
        if let g = Lt.glow {
            for t in things {
                guard let lv = t.light else { continue }
                let p = pt(t.x, t.y, heightZ(t) * lv)
                let r = Lt.r * S * (t.id == "lamp" ? 1 : 0.55)
                l.fill(Path(CGRect(x: p.x - r, y: p.y - r, width: r * 2, height: r * 2)),
                       with: .radialGradient(Gradient(colors: [g, Color(hex: 0xFFBE69, alpha: 0)]), center: p, startRadius: 0, endRadius: r))
            }
        }
    }

    /// The cat or thing under a point, for a tap.
    func hit(_ p: CGPoint) -> (title: String, sub: String)? {
        for s in (things + cats).sorted(by: { $0.depth > $1.depth }) {
            guard let r = s.rect, r.contains(p) else { continue }
            if s.isCat { return (s.title, s.sub) }
            return (s.id.prefix(1).uppercased() + s.id.dropFirst(), "")
        }
        return nil
    }
}

// ---------------------------------------------------------------- the view

/// Holds the room, so buttons elsewhere can turn it.
final class CafeController: ObservableObject {
    let scene = CafeScene()
    func spin(_ by: Int) { scene.spin(by) }
}

/// The café, in a box: drag to turn it, tap a cat.
struct CafeView: View {
    var data: CafeData
    @ObservedObject var controller: CafeController
    @State private var dragStart: CGFloat?
    @State private var tip: (String, String, CGPoint)?
    @State private var size = CGSize(width: 600, height: 400)

    var body: some View {
        let scene = controller.scene
        ZStack(alignment: .bottom) {
            TimelineView(.animation(minimumInterval: 1.0 / 30)) { tl in
                Canvas { ctx, sz in
                    scene.show(data)
                    scene.step(date: tl.date, size: sz)
                    scene.draw(ctx, size: sz)
                }
                .background(GeometryReader { g in Color.clear.onAppear { size = g.size }.onChange(of: g.size) { size = $0 } })
            }
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { v in
                    if dragStart == nil { dragStart = scene.angle }
                    if abs(v.translation.width) > 6 { scene.dragging = true; scene.angle = (dragStart ?? scene.angle) + v.translation.width * 0.012; scene.goal = scene.angle }
                }
                .onEnded { v in
                    defer { dragStart = nil }
                    if scene.dragging { scene.dragging = false; scene.snap() }
                    else if let h = scene.hit(v.location) { tip = (h.title, h.sub, v.location); DispatchQueue.main.asyncAfter(deadline: .now() + 3) { tip = nil } }
                    else { tip = nil }
                })
            if let t = tip, !t.0.isEmpty {
                VStack(spacing: 1) {
                    Text(t.0).font(.system(size: 13, weight: .bold)).foregroundStyle(Palette.ink)
                    if !t.1.isEmpty { Text(t.1).font(.system(size: 12)).foregroundStyle(Palette.muted) }
                }
                .padding(.horizontal, 12).padding(.vertical, 7)
                .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Palette.card).shadow(color: .black.opacity(0.18), radius: 8, y: 3))
                .position(x: min(max(t.2.x, 80), size.width - 80), y: max(24, t.2.y - 26))
                .allowsHitTesting(false)
            }
        }
        .accessibilityLabel("Your Cat Café. Drag to turn it, tap a cat.")
    }
}

/// What the café is made of, for these blocks: the cats that came home, the things the account owns, the theme and the time of day.
extension CafeData {
    static func make(store: Store, blocks: [Hist], span: Span, dark: Bool, seed: Int) -> CafeData {
        let st = store.state
        var d = CafeData()
        d.size = span == .day ? "day" : span == .week ? "week" : "month"
        d.theme = st.theme
        let h = Calendar.current.component(.hour, from: Date())
        d.light = (5..<10).contains(h) ? "morning" : (10..<17).contains(h) ? "day" : (17..<21).contains(h) ? "evening" : "night"
        d.dim = dark
        d.things = st.things.map { $0.id }
        for t in st.things { d.views[t.id] = (t.side, t.back) }
        d.seed = seed
        let home = blocks.filter { $0.kitten == "home" }.sorted { $0.start < $1.start }
        d.cats = home.prefix(60).map { b in
            let c = st.cat(b.cat)
            return CafeCatData(key: b.id, id: c.id, src: c.portrait ?? "cats/\(c.id)-1.webp", napSrc: c.nap ?? "cats/\(c.id)-nap-1.webp",
                               kitten: b.minutes < 25, title: c.name, sub: b.title, poses: c.poses)
        }
        d.gone = blocks.filter { $0.kitten == "gone" }.map { $0.id }
        return d
    }
}
