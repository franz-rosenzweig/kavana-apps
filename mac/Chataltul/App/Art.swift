import AppKit
import SwiftUI

/// The web app's painted cats (Resources/cats, Resources/cafe), decoded once and kept.
enum Art {
    private static var cache: [String: NSImage] = [:]
    /// Each cat's painting paths, from the engine's cats (cats.js): a repainted cat's have a new number.
    private static var cats: [String: CatInfo] = [:]

    static func learn(_ list: [CatInfo]) { cats = Dictionary(list.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a }) }

    static func image(_ path: String) -> NSImage? {
        if let i = cache[path] { return i }
        guard let url = Bundle.main.resourceURL?.appendingPathComponent(path), let i = NSImage(contentsOf: url) else { return nil }
        cache[path] = i
        return i
    }

    static func portrait(_ id: String) -> String { cats[id]?.portrait ?? "cats/\(id)-1.webp" }
    static func kitten(_ id: String) -> String { cats[id]?.kitten ?? "cats/\(id)-kitten-1.webp" }
    static func nap(_ id: String) -> String { cats[id]?.nap ?? "cats/\(id)-nap-1.webp" }
    static let gone = "cafe/gone-1.webp"

    /// A pose painting (walk, loaf, stretch, wash, back) if this cat has it, else its napping picture.
    static func pose(_ id: String, _ pose: String) -> String { cats[id]?.poses?[pose] ?? nap(id) }
    static func hasPose(_ id: String, _ pose: String) -> Bool { cats[id]?.poses?[pose] != nil }

    enum Stage { case nap, kitten, grown, home, gone }

    /// Which picture a block's cat is in: it grows with the block (web/app.js renderSky).
    static func stage(_ b: Block, now: Int) -> (stage: Stage, progress: Double) {
        if b.kitten == "gone" { return (.gone, 0) }
        if b.kitten == "home" { return (.home, 1) }
        let total = max(1, b.end - b.start)
        let p = max(0, min(1, Double(now - b.start) / Double(total)))
        var s: Stage = p < 0.25 ? .nap : p < 0.75 ? .kitten : .grown
        if s == .grown && smallBlock(b, now: now) { s = .kitten }
        return (s, p)
    }

    /// Under 25 minutes, a cat comes home a kitten.
    static func smallBlock(_ b: Block, now: Int) -> Bool {
        b.stopwatch ? (now - b.start) < 25 * 60_000 : b.minutes < 25
    }

    static func path(for cat: String, stage: Stage, small: Bool = false) -> String {
        switch stage {
        case .nap: return nap(cat)
        case .kitten: return kitten(cat)
        case .grown: return portrait(cat)
        case .home: return small ? kitten(cat) : portrait(cat)
        case .gone: return gone
        }
    }

    /// How big the cat is in its window (web/app.js growSize).
    static func size(_ stage: Stage, _ p: Double) -> Double {
        switch stage {
        case .nap: return 0.55 + 0.13 * (p / 0.25)
        case .kitten: return 0.68 + 0.18 * ((p - 0.25) / 0.5)
        case .grown: return 0.86 + 0.14 * ((p - 0.75) / 0.25)
        case .home: return 1
        case .gone: return 0.8
        }
    }
}

/// A painting by its path in Resources, or nothing if it isn't there.
struct CatImage: View {
    var path: String
    var body: some View {
        if let i = Art.image(path) {
            Image(nsImage: i).resizable().interpolation(.high).aspectRatio(contentMode: .fit)
        } else {
            Color.clear
        }
    }
}
