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

/// What a list is for: the apps and sites on it, or everything that isn't on it.
public enum ListKind: String, Codable, CaseIterable {
    /// The cat comes for these apps and sites (the way every list worked at first).
    case block
    /// The cat comes for every app and site except these: the ones for work, say. With no apps on it, apps are left
    /// to the other lists, and with no sites, sites are.
    case only
}

/// When a list is watched: during every block, and also on weekdays between two times, or always.
public struct GuardWhen: Codable, Equatable {
    public enum Kind: String, Codable { case blocks, weekdays, always }
    public var kind: Kind
    /// Minutes after midnight, for weekdays.
    public var fromMin: Int
    public var toMin: Int
    /// What it was before a link (chataltul://list?…&watch=on) made it Always, to go back to on watch=off.
    public var before: Kind?

    public init(_ kind: Kind = .blocks, fromMin: Int = 9 * 60, toMin: Int = 17 * 60) { self.kind = kind; self.fromMin = fromMin; self.toMin = toMin }
    public init(from decoder: Decoder) throws {
        let c = try decoder.keyed()
        kind = c.v("kind", Kind.blocks); fromMin = c.v("fromMin", 540); toMin = c.v("toMin", 1020); before = c.o("before")
    }

    /// Chosen in Settings: the person's choice, so what a link remembered is let go.
    public mutating func choose(_ k: Kind) { kind = k; before = nil }

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

/// One list: apps and sites (typed, and ready-made by kind), and what to do when one of them is opened.
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
    /// The ready-made lists switched on (ids from the Catalog, or the person's own), on top of the typed sites.
    public var categories: [String]
    /// Sites that are never matched by a ready-made list (and their subdomains).
    public var allow: [String]
    /// The things on it, or everything but them. Lists from before there was a choice are `.block`.
    public var kind: ListKind

    public init(id: String = UUID().uuidString, name: String, color: Int = 0, apps: [ListedApp] = [], sites: [String] = [], mode: GuardMode = .speedBump, when: GuardWhen = GuardWhen(), allowanceMin: Int = 0, categories: [String] = [], allow: [String] = [], kind: ListKind = .block) {
        self.id = id; self.name = name; self.color = color; self.apps = apps; self.sites = sites; self.mode = mode; self.when = when; self.allowanceMin = allowanceMin
        self.categories = categories; self.allow = allow; self.kind = kind
    }
    public init(from decoder: Decoder) throws {
        let c = try decoder.keyed()
        id = c.v("id", UUID().uuidString); name = c.v("name", "List"); color = c.v("color", 0)
        apps = c.v("apps", []); sites = c.v("sites", [])
        mode = c.v("mode", GuardMode.speedBump); when = c.v("when", GuardWhen()); allowanceMin = c.v("allowanceMin", 0)
        categories = c.v("categories", []); allow = c.v("allow", []); kind = c.v("kind", ListKind.block)
    }

    /// The apps and typed sites. The ready-made lists are counted apart.
    public var count: Int { apps.count + sites.count }
    public func site(forURL url: String) -> String? { site(forURL: url, matcher: nil) }

    /// The typed site this address is on, else (unless it is allowed) the domain a ready-made list names for it.
    public func site(forURL url: String, matcher: DomainMatcher?) -> String? {
        guard let host = Blocklist.host(of: url) else { return nil }
        if let s = sites.first(where: { host == $0 || host.hasSuffix("." + $0) }) { return s }
        guard let matcher, !categories.isEmpty, !isAllowed(host) else { return nil }
        return matcher.match(host: host, categories: categories)
    }

    public func isAllowed(_ host: String) -> Bool { allow.contains { host == $0 || host.hasSuffix("." + $0) } }

    /// An "every app except these" list with apps on it: any other app counts.
    public var onlyApps: Bool { kind == .only && !apps.isEmpty }
    /// An "every site except these" list with sites on it: any other site, in a browser that is read, counts.
    public var onlySites: Bool { kind == .only && !sites.isEmpty }
    /// Is this host one of the list's sites, or under one?
    public func hasSite(_ host: String) -> Bool { sites.contains { host == $0 || host.hasSuffix("." + $0) } }

    /// A link's watch=on: watched all the time, the same as Always, and what it was before is remembered.
    /// watch=off: back to that (during blocks, or its weekdays), or to during blocks if it was Always already.
    /// A list that isn't Always is already off. True if it changed.
    @discardableResult
    public mutating func watch(_ on: Bool) -> Bool {
        if on {
            guard when.kind != .always else { return false }
            when.before = when.kind
            when.kind = .always
        } else {
            guard when.kind == .always else { return false }
            when.kind = when.before ?? .blocks
            when.before = nil
        }
        return true
    }
}

/// chataltul://list?name=Work&watch=on (or off): a list watched all the time, or back to its own times.
/// Something else on the Mac opens it (a mode switch, a Shortcut, `open -g`), so it shows nothing.
public struct ListLink: Equatable {
    public var name: String
    public var on: Bool

    public init(name: String, on: Bool) { self.name = name; self.on = on }

    public static let host = "list"

    /// The link read, or nil and why not. The name comes URL-encoded ("Deep%20Work").
    public static func read(_ url: URL) -> (link: ListLink?, why: String) {
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        func item(_ n: String) -> String? { items.first { $0.name == n }?.value }
        guard let name = item("name")?.trimmingCharacters(in: .whitespaces), !name.isEmpty else { return (nil, "it names no list (name=…)") }
        switch item("watch")?.lowercased() {
        case "on": return (ListLink(name: name, on: true), "")
        case "off": return (ListLink(name: name, on: false), "")
        case let w: return (nil, "watch is \(w.map { "\"\($0)\"" } ?? "missing"), not on or off")
        }
    }

    public static func make(name: String, on: Bool) -> URL {
        var c = URLComponents()
        c.scheme = WidgetSnapshot.scheme; c.host = host
        c.queryItems = [URLQueryItem(name: "name", value: name), URLQueryItem(name: "watch", value: on ? "on" : "off")]
        return c.url!
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
    public var isEmpty: Bool { count == 0 && categoryCount == 0 }

    /// The lists of things the cat comes for (not the "every app except these" ones).
    public var blocking: [GuardList] { lists.filter { $0.kind == .block } }
    /// How many ready-made lists are switched on, over all the lists. A list of what's allowed has none.
    public var categoryCount: Int { blocking.reduce(0) { $0 + $1.categories.count } }
    /// The ready-made lists switched on anywhere, once each.
    public var categoryIDs: [String] {
        var seen = Set<String>()
        return blocking.flatMap { $0.categories }.filter { seen.insert($0).inserted }
    }
    /// Is there anything on the web to watch for (typed sites, ready-made lists, or the only sites allowed)?
    public var watchesSites: Bool { !sites.isEmpty || categoryCount > 0 }
    /// A list with sites that is watched outside blocks too, so a browser in front is read every second then.
    public var watchesSitesOnSchedule: Bool {
        lists.contains { $0.when.kind != .blocks && (!$0.sites.isEmpty || ($0.kind == .block && !$0.categories.isEmpty)) }
    }

    /// The listed app with this bundle id.
    public func app(_ bundleID: String) -> ListedApp? { apps.first { $0.bundleID == bundleID } }

    /// The lists a link names: by the name as it is in Settings, else ignoring case, else with a + read as a space
    /// (the way a web form encodes one).
    public func indices(named name: String) -> [Int] {
        func plain(_ s: String) -> String { s.trimmingCharacters(in: .whitespaces) }
        let n = plain(name)
        for wanted in [n, n.replacingOccurrences(of: "+", with: " ")] {
            let exact = lists.indices.filter { plain(lists[$0].name) == wanted }
            if !exact.isEmpty { return exact }
            let loose = lists.indices.filter { plain(lists[$0].name).caseInsensitiveCompare(wanted) == .orderedSame }
            if !loose.isEmpty { return loose }
        }
        return []
    }

    /// What a link asks of the lists with its name. Nil when there's no such list; else how many changed
    /// (none when they were that way already).
    public mutating func apply(_ link: ListLink) -> Int? {
        let found = indices(named: link.name)
        guard !found.isEmpty else { return nil }
        return found.filter { lists[$0].watch(link.on) }.count
    }

    /// The listed domain a page address falls under (a listed domain covers its subdomains), or nil.
    public func site(forURL url: String, matcher: DomainMatcher? = nil) -> String? {
        for l in blocking { if let s = l.site(forURL: url, matcher: matcher) { return s } }
        return nil
    }

    /// The list that has this app, if any.
    public func hit(bundleID: String) -> GuardHit? {
        for l in blocking { if let a = l.apps.first(where: { $0.bundleID == bundleID }) { return GuardHit(list: l, target: .app(a)) } }
        return nil
    }

    /// The list that has the site this address is on, if any. The typed sites of every list come before the
    /// ready-made ones, so a site someone typed into one list isn't taken by another list's category.
    public func hit(url: String, browser: String, matcher: DomainMatcher? = nil) -> GuardHit? {
        for l in blocking { if let d = l.site(forURL: url) { return GuardHit(list: l, target: .site(domain: d, browser: browser)) } }
        guard let matcher else { return nil }
        for l in blocking { if let d = l.site(forURL: url, matcher: matcher) { return GuardHit(list: l, target: .site(domain: d, browser: browser)) } }
        return nil
    }

    // ---------------------------------------------------------------- every app except these

    /// The "every app except these" list an app in front isn't on, for an app no list of things to block has.
    /// `active`: does the list ask for anything now (a block, or inside its times)? A list that does comes first;
    /// failing that, the first one, so a window that begins while the app is in front is seen.
    public func outside(app: ListedApp, active: (GuardList) -> Bool = { _ in true }) -> GuardHit? {
        guard !Blocklist.neverCounts(app.bundleID) else { return nil }
        let out = lists.filter { $0.onlyApps && !$0.apps.contains { $0.bundleID == app.bundleID } }
        return (out.first(where: active) ?? out.first).map { GuardHit(list: $0, target: .app(app)) }
    }

    /// The "every site except these" list a page isn't on, in a browser it allows (a list with no apps allows
    /// them all). Only web pages count: a new tab or a browser's own pages never do.
    public func outside(url: String, browser: String, bundleID: String, active: (GuardList) -> Bool = { _ in true }) -> GuardHit? {
        guard let host = Blocklist.webHost(of: url) else { return nil }
        let out = lists.filter { l in l.onlySites && !l.hasSite(host) && (l.apps.isEmpty || l.apps.contains { $0.bundleID == bundleID }) }
        return (out.first(where: active) ?? out.first).map { GuardHit(list: $0, target: .site(domain: host, browser: browser)) }
    }

    /// Apps that never count against an "every app except these" list: Chataltul, the Finder, System Settings,
    /// the Dock, Spotlight, Notification Center and Control Center, the login window and the screen saver, and the
    /// password, permission and other prompts macOS brings to the front.
    public static let alwaysAllowed: Set<String> = [
        "com.apple.finder", "com.apple.systempreferences", "com.apple.dock", "com.apple.Spotlight",
        "com.apple.notificationcenterui", "com.apple.controlcenter", "com.apple.WindowManager", "com.apple.loginwindow",
        "com.apple.SecurityAgent", "com.apple.UserNotificationCenter", "com.apple.coreservices.uiagent",
        "com.apple.CoreServicesUIAgent", "com.apple.LocalAuthentication.UIAgent", "com.apple.accessibility.universalAccessAuthWarn",
        "com.apple.screencaptureui",
    ]
    public static func neverCounts(_ bundleID: String) -> Bool {
        alwaysAllowed.contains(bundleID) || bundleID.hasPrefix("com.apple.ScreenSaver") || bundleID.hasPrefix("xyz.tiresia.chataltul")
    }

    /// The host of a web page (http or https), or nil for anything else: "chrome://newtab", "about:blank", a file.
    public static func webHost(of address: String) -> String? {
        let t = address.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard t.hasPrefix("http://") || t.hasPrefix("https://"), let h = host(of: t), h.contains(".") else { return nil }
        return h
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

/// Browsers whose address the app can read. There are three ways, one for each family a browser is built
/// on, so a browser only says which family it's in.
public struct ReadableBrowser: Equatable {
    public enum Family: Equatable {
        /// Safari's AppleScript, "URL of current tab": Safari, and Orion, which copies it.
        case safari
        /// Chrome's AppleScript, "URL of active tab": the browsers built on Chromium.
        case chromium
        /// No AppleScript: the page is read through Accessibility (PageFinder). Firefox and the browsers built on it.
        case mozilla
    }
    public enum Reader: Equatable {
        /// An AppleScript that gives the front window's current address (run in the browser). macOS asks once
        /// for each browser, under Privacy → Automation.
        case appleScript(String)
        /// The front window's page, found in its accessibility tree. macOS asks once for all such browsers,
        /// under Privacy → Accessibility.
        case accessibility
    }
    public let bundleID: String
    public let name: String
    public let family: Family

    public init(_ bundleID: String, _ name: String, _ family: Family) { self.bundleID = bundleID; self.name = name; self.family = family }

    public var reader: Reader {
        // AppleScript is sent to one app by its id; the rest of the line is the family's
        let ask = "tell application id \"\(bundleID)\" to if (count of windows) > 0 then return URL of"
        switch family {
        case .safari: return .appleScript("\(ask) current tab of front window")
        case .chromium: return .appleScript("\(ask) active tab of front window")
        case .mozilla: return .accessibility
        }
    }
    public var script: String? { if case .appleScript(let s) = reader { return s } else { return nil } }

    public static let all: [ReadableBrowser] = [
        ReadableBrowser("com.apple.Safari", "Safari", .safari),
        ReadableBrowser("com.kagi.kagimacOS", "Orion", .safari),
        ReadableBrowser("com.google.Chrome", "Chrome", .chromium),
        ReadableBrowser("com.brave.Browser", "Brave", .chromium),
        ReadableBrowser("company.thebrowser.Browser", "Arc", .chromium),
        ReadableBrowser("com.microsoft.edgemac", "Edge", .chromium),
        ReadableBrowser("org.chromium.Chromium", "Chromium", .chromium),
        ReadableBrowser("io.ungoogled-software.ungoogled-chromium", "Chromium", .chromium),
        ReadableBrowser("app.zen-browser.zen", "Zen", .mozilla),
        ReadableBrowser("org.mozilla.firefox", "Firefox", .mozilla),
    ]

    public static func with(bundleID: String) -> ReadableBrowser? { all.first { $0.bundleID == bundleID } }

    /// "Safari, Orion, … and Firefox", each name once, for the Blocklist.
    public static var names: String {
        var seen = Set<String>()
        let n = all.map { $0.name }.filter { seen.insert($0).inserted }
        return n.count < 2 ? n.joined() : n.dropLast().joined(separator: ", ") + " and " + n.last!
    }
}
