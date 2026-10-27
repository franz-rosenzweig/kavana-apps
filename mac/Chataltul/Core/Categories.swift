import Foundation

/// One public list on the web that a category is made from.
public struct ListSource: Codable, Equatable, Identifiable {
    public var id: String
    public var name: String
    public var url: String
    /// A tar.gz archive holds the list as this file (UT1's `dating/domains`); nil for a plain text list.
    public var member: String?
    public var license: String
    public var credit: String
    /// About how much it downloads, in KB (0 when not known).
    public var sizeKB: Int
    /// Fewer domains than this and what came back isn't the list, so it is thrown away.
    public var floor: Int

    public init(id: String, name: String, url: String, member: String? = nil, license: String, credit: String, sizeKB: Int, floor: Int) {
        self.id = id; self.name = name; self.url = url; self.member = member; self.license = license; self.credit = credit; self.sizeKB = sizeKB; self.floor = floor
    }
}

/// A kind of site (Adult, Social media...) made from one or more public lists, or a list the person added by its link.
public struct ListCategory: Codable, Equatable, Identifiable {
    public var id: String
    public var name: String
    public var sources: [ListSource]
    public var custom: Bool

    public init(id: String, name: String, sources: [ListSource], custom: Bool = false) {
        self.id = id; self.name = name; self.sources = sources; self.custom = custom
    }
}

/// The ready-made lists. Nothing here is bundled: each one is downloaded to this Mac when it is switched on.
public enum Catalog {
    private static let ut1 = (license: "CC BY-SA 4.0", credit: "UT1 Blacklists, Université Toulouse Capitole")

    private static func ut1Source(_ name: String, _ file: String, kb: Int, floor: Int) -> ListSource {
        ListSource(id: "ut1-\(file)", name: "UT1 \(name)", url: "https://dsi.ut-capitole.fr/blacklists/download/\(file).tar.gz", member: "\(file)/domains",
                   license: ut1.license, credit: ut1.credit, sizeKB: kb, floor: floor)
    }
    private static func steven(_ name: String, _ folder: String, kb: Int, floor: Int) -> ListSource {
        ListSource(id: "stevenblack-\(folder)", name: "StevenBlack \(name)", url: "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/\(folder)/hosts",
                   license: "MIT", credit: "StevenBlack/hosts", sizeKB: kb, floor: floor)
    }

    public static let all: [ListCategory] = [
        ListCategory(id: "adult", name: "Adult", sources: [
            ListSource(id: "hagezi-nsfw", name: "Hagezi NSFW", url: "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/nsfw.txt",
                       license: "GPL-3.0", credit: "Hagezi DNS Blocklists", sizeKB: 1700, floor: 1000),
            steven("porn", "porn-only", kb: 2000, floor: 1000),
        ]),
        ListCategory(id: "social", name: "Social media", sources: [
            ut1Source("social networks", "social_networks", kb: 4, floor: 100),
            steven("social", "social-only", kb: 200, floor: 500),
        ]),
        ListCategory(id: "dating", name: "Dating", sources: [ut1Source("dating", "dating", kb: 60, floor: 1000)]),
        ListCategory(id: "gambling", name: "Gambling", sources: [
            ut1Source("gambling", "gambling", kb: 320, floor: 5000),
            steven("gambling", "gambling-only", kb: 150, floor: 500),
        ]),
        ListCategory(id: "games", name: "Games", sources: [ut1Source("games", "games", kb: 200, floor: 3000)]),
        ListCategory(id: "video", name: "Video and music", sources: [ut1Source("audio and video", "audio-video", kb: 25, floor: 500)]),
        ListCategory(id: "news", name: "News", sources: [ut1Source("press", "press", kb: 26, floor: 500)]),
        ListCategory(id: "shopping", name: "Shopping", sources: [ut1Source("shopping", "shopping", kb: 215, floor: 3000)]),
    ]

    private static let byID: [String: ListCategory] = Dictionary(uniqueKeysWithValues: all.map { ($0.id, $0) })

    /// The category with this id: a ready-made one, or one of the person's own.
    public static func category(_ id: String, custom: [ListCategory] = []) -> ListCategory? {
        byID[id] ?? custom.first { $0.id == id }
    }

    /// A list from its link. nil unless it is an https address with a host. Any public hosts file, list of
    /// domains or AdGuard / Adblock list will do.
    public static func custom(name: String, link: String) -> ListCategory? {
        let t = link.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let u = URLComponents(string: t), u.scheme?.lowercased() == "https", let host = u.host, host.contains("."),
              u.user == nil, u.password == nil else { return nil }
        let id = "custom-" + String(UUID().uuidString.prefix(8)).lowercased()
        let label = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let source = ListSource(id: id, name: host, url: t, license: "", credit: host, sizeKB: 0, floor: 5)
        return ListCategory(id: id, name: label.isEmpty ? host : label, sources: [source], custom: true)
    }
}
