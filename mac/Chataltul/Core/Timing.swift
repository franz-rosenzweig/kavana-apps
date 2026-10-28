import Foundation

// Where each block went, app by app: which app was in front each second of a block, and for a browser the
// site (the domain only, never an address or a title). It lives in timing.json on this Mac and is never sent
// anywhere: it isn't in the doc the sync carries.

public struct TimedApp: Codable, Equatable {
    public var bundleID: String
    public var name: String
    public var ms: Int
}

public struct TimedSite: Codable, Equatable {
    public var domain: String
    public var browser: String
    public var ms: Int
}

public struct BlockTiming: Codable, Equatable {
    public var start: Int
    public var apps: [String: TimedApp]
    public var sites: [String: TimedSite]

    public init(start: Int, apps: [String: TimedApp] = [:], sites: [String: TimedSite] = [:]) { self.start = start; self.apps = apps; self.sites = sites }
    public init(from decoder: Decoder) throws {
        let c = try decoder.keyed()
        start = c.v("start", 0); apps = c.v("apps", [:]); sites = c.v("sites", [:])
    }

    public var totalMs: Int { apps.values.reduce(0) { $0 + $1.ms } }
}

/// All the blocks' timings, by block id.
public struct TimingLog: Codable, Equatable {
    public var blocks: [String: BlockTiming]

    public init(blocks: [String: BlockTiming] = [:]) { self.blocks = blocks }
    public init(from decoder: Decoder) throws { blocks = try decoder.keyed().v("blocks", [:]) }

    public mutating func add(block id: String, start: Int, bundleID: String, name: String, ms: Int, site: (domain: String, browser: String)? = nil) {
        var t = blocks[id] ?? BlockTiming(start: start)
        var a = t.apps[bundleID] ?? TimedApp(bundleID: bundleID, name: name, ms: 0)
        a.ms += ms
        t.apps[bundleID] = a
        if let s = site {
            var x = t.sites[s.domain] ?? TimedSite(domain: s.domain, browser: s.browser, ms: 0)
            x.ms += ms
            t.sites[s.domain] = x
        }
        blocks[id] = t
    }

    /// Blocks older than this are dropped (the log must not grow for ever).
    public mutating func prune(before ms: Int) { blocks = blocks.filter { $0.value.start >= ms } }
    public mutating func clear() { blocks = [:] }

    /// The timings of these blocks, in no particular order.
    public func timings(for ids: [String]) -> [BlockTiming] { ids.compactMap { blocks[$0] } }
    public var isEmpty: Bool { blocks.isEmpty }
}

public struct TimingStore {
    public let file: URL
    public init(folder: URL) { file = folder.appendingPathComponent("timing.json") }
    public func read() -> TimingLog {
        guard let d = try? Data(contentsOf: file), let l = try? JSONDecoder().decode(TimingLog.self, from: d) else { return TimingLog() }
        return l
    }
    public func write(_ l: TimingLog) { if let d = try? JSONEncoder().encode(l) { try? d.write(to: file, options: .atomic) } }
    public func erase() { try? FileManager.default.removeItem(at: file) }
}

/// One line of "where it went".
public struct WentRow: Equatable {
    public var name: String
    public var ms: Int
    public var share: Double
    /// A browser's busiest sites, for its line ("Safari: bbc.co.uk, youtube.com").
    public var sites: [String]
    /// On a list of things that pull the person away.
    public var distraction: Bool

    public var label: String { sites.isEmpty ? name : "\(name): \(sites.joined(separator: ", "))" }
    public var minutes: Int { max(1, Int((Double(ms) / 60000).rounded())) }
}

public enum Went {
    /// The apps (and sites) these blocks spent their time in, biggest first. Apps under 3% are folded into "Other".
    public static func rows(_ timings: [BlockTiming], blocklist: Blocklist = Blocklist(lists: []), matcher: DomainMatcher? = nil, limit: Int = 6) -> [WentRow] {
        var apps: [String: TimedApp] = [:], sites: [String: [String: Int]] = [:]
        for t in timings {
            for (id, a) in t.apps { var x = apps[id] ?? TimedApp(bundleID: id, name: a.name, ms: 0); x.ms += a.ms; apps[id] = x }
            for (d, s) in t.sites { sites[s.browser, default: [:]][d, default: 0] += s.ms }
        }
        let total = max(1, apps.values.reduce(0) { $0 + $1.ms })
        var rows = apps.values.sorted { $0.ms != $1.ms ? $0.ms > $1.ms : $0.name < $1.name }.map { a -> WentRow in
            let top = (sites[a.name] ?? [:]).sorted { $0.value != $1.value ? $0.value > $1.value : $0.key < $1.key }.prefix(2).map { $0.key }
            let bad = blocklist.app(a.bundleID) != nil || top.contains { blocklist.sites.contains($0) || blocklist.site(forURL: $0, matcher: matcher) != nil }
            return WentRow(name: a.name, ms: a.ms, share: Double(a.ms) / Double(total), sites: Array(top), distraction: bad)
        }
        if rows.count > limit {
            let rest = rows[(limit - 1)...]
            let other = WentRow(name: "Other", ms: rest.reduce(0) { $0 + $1.ms }, share: rest.reduce(0) { $0 + $1.share }, sites: [], distraction: false)
            rows = Array(rows[..<(limit - 1)]) + [other]
        }
        return rows
    }
}
