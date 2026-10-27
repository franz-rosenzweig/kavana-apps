import Foundation

/// A listed app: found by its bundle id, shown by its name.
public struct ListedApp: Codable, Equatable, Hashable {
    public var bundleID: String
    public var name: String
    public init(bundleID: String, name: String) { self.bundleID = bundleID; self.name = name }
}

/// What happens when something on a list is opened.
public enum GuardMode: String, Codable, CaseIterable {
    /// The cat walks over it: 15 seconds to come back, or hold to leave and the kitten wanders off (during a block).
    case speedBump
    /// A 30-second countdown with the cat before it opens, every time.
    case waitItOut
    /// It doesn't open until the block ends.
    case locked
}

/// When a list is watched: during every block, and also on weekdays between two times, or always.
public struct GuardWhen: Codable, Equatable {
    public enum Kind: String, Codable { case blocks, weekdays, always }
    public var kind: Kind
    /// Minutes after midnight, for weekdays.
    public var fromMin: Int
    public var toMin: Int

    public init(_ kind: Kind = .blocks, fromMin: Int = 9 * 60, toMin: Int = 17 * 60) { self.kind = kind; self.fromMin = fromMin; self.toMin = toMin }
    public init(from decoder: Decoder) throws {
        let c = try decoder.keyed()
        kind = c.v("kind", Kind.blocks); fromMin = c.v("fromMin", 540); toMin = c.v("toMin", 1020)
    }

    /// Is this moment inside the window, outside any block?
    public func covers(_ date: Date, calendar: Calendar = .current) -> Bool {
        switch kind {
        case .blocks: return false
        case .always: return true
        case .weekdays:
            let wd = calendar.component(.weekday, from: date)   // 1 Sunday ... 7 Saturday
            guard (2...6).contains(wd) else { return false }
            let m = calendar.component(.hour, from: date) * 60 + calendar.component(.minute, from: date)
            return m >= fromMin && m < toMin
        }
    }

    /// When the window ends today, for the card ("until 5:00 PM"); nil when always.
    public func end(on date: Date, calendar: Calendar = .current) -> Date? {
        guard kind == .weekdays else { return nil }
        return calendar.date(bySettingHour: toMin / 60, minute: toMin % 60, second: 0, of: date)
    }
}

/// One list: apps and sites, and what to do when one of them is opened.
public struct GuardList: Codable, Equatable, Identifiable {
    public var id: String
    public var name: String
    /// Which of the app's colors marks it (0...5).
    public var color: Int
    public var apps: [ListedApp]
    public var sites: [String]
    public var mode: GuardMode
    public var when: GuardWhen
    /// Minutes a day allowed outside blocks (0: none).
    public var allowanceMin: Int

    public init(id: String = UUID().uuidString, name: String, color: Int = 0, apps: [ListedApp] = [], sites: [String] = [], mode: GuardMode = .speedBump, when: GuardWhen = GuardWhen(), allowanceMin: Int = 0) {
        self.id = id; self.name = name; self.color = color; self.apps = apps; self.sites = sites; self.mode = mode; self.when = when; self.allowanceMin = allowanceMin
    }
    public init(from decoder: Decoder) throws {
        let c = try decoder.keyed()
        id = c.v("id", UUID().uuidString); name = c.v("name", "List"); color = c.v("color", 0)
        apps = c.v("apps", []); sites = c.v("sites", [])
        mode = c.v("mode", GuardMode.speedBump); when = c.v("when", GuardWhen()); allowanceMin = c.v("allowanceMin", 0)
    }

    public var count: Int { apps.count + sites.count }
    public func site(forURL url: String) -> String? {
        guard let host = Blocklist.host(of: url) else { return nil }
        return sites.first { host == $0 || host.hasSuffix("." + $0) }
    }
}

/// A list, and the thing on it that was found.
public struct GuardHit: Equatable {
    public var list: GuardList
    public var target: Target
}

/// What the cat walks over: lists of apps and of sites in a browser the app can read.
public struct Blocklist: Codable, Equatable {
    public var lists: [GuardList]

    public init(lists: [GuardList]) { self.lists = lists }
    /// One list called Distractions with these in it (the way the first version kept them).
    public init(apps: [ListedApp] = [], sites: [String] = []) {
        lists = [GuardList(name: "Distractions", apps: apps, sites: sites)]
    }
    public init(from decoder: Decoder) throws {
        let c = try decoder.keyed()
        if let l: [GuardList] = c.o("lists") { lists = l }
        else { lists = [GuardList(name: "Distractions", apps: c.v("apps", []), sites: c.v("sites", []))] }   // the first version's shape
    }

    /// What a new install starts with: the sites most people mean, and the two chatty built-in apps.
    /// Nothing the person might need for work (Mail, Slack) is listed for them.
    public static let starter = Blocklist(
        apps: [ListedApp(bundleID: "com.apple.MobileSMS", name: "Messages"), ListedApp(bundleID: "com.apple.news", name: "News")],
        sites: ["youtube.com", "reddit.com", "x.com", "instagram.com", "facebook.com", "tiktok.com", "news.ycombinator.com"])

    public var apps: [ListedApp] { lists.flatMap { $0.apps } }
    public var sites: [String] { lists.flatMap { $0.sites } }
    public var count: Int { lists.reduce(0) { $0 + $1.count } }
    public var isEmpty: Bool { count == 0 }

    /// The listed app with this bundle id.
    public func app(_ bundleID: String) -> ListedApp? { apps.first { $0.bundleID == bundleID } }

    /// The listed domain a page address falls under (a listed domain covers its subdomains), or nil.
    public func site(forURL url: String) -> String? {
        for l in lists { if let s = l.site(forURL: url) { return s } }
        return nil
    }

    /// The list that has this app, if any.
    public func hit(bundleID: String) -> GuardHit? {
        for l in lists { if let a = l.apps.first(where: { $0.bundleID == bundleID }) { return GuardHit(list: l, target: .app(a)) } }
        return nil
    }

    /// The list that has the site this address is on, if any.
    public func hit(url: String, browser: String) -> GuardHit? {
        for l in lists { if let d = l.site(forURL: url) { return GuardHit(list: l, target: .site(domain: d, browser: browser)) } }
        return nil
    }

    /// "https://www.Reddit.com/r/cats?x=1" and "reddit.com/r/cats" both give "reddit.com".
    public static func host(of address: String) -> String? {
        let t = address.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty { return nil }
        let withScheme = t.contains("://") ? t : "https://" + t
        guard let h = URLComponents(string: withScheme)?.host?.lowercased(), !h.isEmpty else { return nil }
        var host = h
        while host.hasSuffix(".") { host.removeLast() }
        if host.hasPrefix("www.") { host.removeFirst(4) }
        return host.isEmpty ? nil : host
    }

    /// What goes in the list when someone types a site: the bare domain, or nil if it isn't one.
    public static func normalizeDomain(_ typed: String) -> String? {
        guard let h = host(of: typed), h.contains("."), !h.contains(" ") else { return nil }
        return h
    }

    /// Adds to the first list (or a new one called Distractions).
    @discardableResult
    public mutating func add(site typed: String, to id: String? = nil) -> Bool {
        guard let d = Blocklist.normalizeDomain(typed), !sites.contains(d) else { return false }
        let i = id.flatMap { id in lists.firstIndex { $0.id == id } } ?? 0
        if lists.isEmpty { lists = [GuardList(name: "Distractions")] }
        lists[i].sites.append(d)
        return true
    }

    @discardableResult
    public mutating func add(app: ListedApp, to id: String? = nil) -> Bool {
        guard !apps.contains(where: { $0.bundleID == app.bundleID }) else { return false }
        let i = id.flatMap { id in lists.firstIndex { $0.id == id } } ?? 0
        if lists.isEmpty { lists = [GuardList(name: "Distractions")] }
        lists[i].apps.append(app)
        return true
    }
}

/// What the person is looking at, as far as the list cares.
public enum Target: Equatable {
    case app(ListedApp)
    case site(domain: String, browser: String)

    /// For the card: "youtube.com" or "Messages".
    public var label: String {
        switch self { case .app(let a): return a.name; case .site(let d, _): return d }
    }
}

/// Browsers whose address the app can read with AppleScript, and how. Firefox and Zen don't have the
/// scripting needed, so they can't be read.
public struct ReadableBrowser: Equatable {
    public let bundleID: String
    public let name: String
    /// The AppleScript that gives the front window's current address (run in the browser).
    public let script: String

    public static let all: [ReadableBrowser] = [
        ReadableBrowser(bundleID: "com.apple.Safari", name: "Safari", script: "tell application id \"com.apple.Safari\" to if (count of windows) > 0 then return URL of current tab of front window"),
        ReadableBrowser(bundleID: "com.google.Chrome", name: "Chrome", script: "tell application id \"com.google.Chrome\" to if (count of windows) > 0 then return URL of active tab of front window"),
        ReadableBrowser(bundleID: "com.brave.Browser", name: "Brave", script: "tell application id \"com.brave.Browser\" to if (count of windows) > 0 then return URL of active tab of front window"),
        ReadableBrowser(bundleID: "company.thebrowser.Browser", name: "Arc", script: "tell application id \"company.thebrowser.Browser\" to if (count of windows) > 0 then return URL of active tab of front window"),
        ReadableBrowser(bundleID: "com.microsoft.edgemac", name: "Edge", script: "tell application id \"com.microsoft.edgemac\" to if (count of windows) > 0 then return URL of active tab of front window"),
    ]

    public static func with(bundleID: String) -> ReadableBrowser? { all.first { $0.bundleID == bundleID } }

    /// Browsers that can't be read, so the list says so.
    public static let unreadable: [String: String] = ["org.mozilla.firefox": "Firefox", "app.zen-browser.zen": "Zen"]
}
