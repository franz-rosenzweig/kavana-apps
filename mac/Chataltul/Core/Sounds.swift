import Foundation

/// A background sound, as the web's sounds.js lists it (KINDS). `file` is its recording,
/// <server>/audio/<file>.mp3; the two noises have none and are made on the Mac.
public struct SoundKind: Decodable, Equatable {
    public var id: String
    public var name: String
    public var about: String?
    public var group: String
    public var free: Bool
    public var like: String?
    public var file: String?

    public init(from decoder: Decoder) throws {
        let c = try decoder.keyed()
        id = c.v("id", ""); name = c.v("name", ""); about = c.o("about"); group = c.v("group", "")
        free = c.v("free", false); like = c.o("like"); file = c.o("file")
    }
}

public struct SoundGroup: Decodable, Equatable {
    public var id: String
    public var name: String
}

/// The sound list. It is read from sounds.json, which tools/sync-web.sh makes out of KINDS in the web
/// repo's sounds.js, so the list is in one place and never typed in here.
public struct SoundCatalog: Decodable {
    public var groups: [SoundGroup]
    public var kinds: [SoundKind]
    public var meows: [String]

    public static func load(_ url: URL) throws -> SoundCatalog {
        try JSONDecoder().decode(SoundCatalog.self, from: Data(contentsOf: url))
    }

    public func kind(_ id: String) -> SoundKind? { kinds.first { $0.id == id } }
    public func kinds(in group: String) -> [SoundKind] { kinds.filter { $0.group == group } }

    /// Free, or bought in the shop ('sound-<id>').
    public func owned(_ kind: SoundKind, shop owned: [String]) -> Bool { kind.free || owned.contains("sound-\(kind.id)") }

    /// What in the mix can play: it exists and is owned. At most two.
    public func playable(_ mix: [MixEntry], shop owned: [String]) -> [MixEntry] {
        Array(mix.compactMap { m -> MixEntry? in
            guard let k = kind(m.id), self.owned(k, shop: owned) else { return nil }
            return m
        }.prefix(2))
    }

    /// "Rain + Purring".
    public func label(_ mix: [MixEntry], shop owned: [String]) -> String {
        let names = playable(mix, shop: owned).compactMap { kind($0.id)?.name }
        return names.isEmpty ? "Sound" : names.joined(separator: " + ")
    }

    /// Adds or removes a sound from the mix. A third one takes the oldest one's place.
    public static func toggle(_ id: String, in mix: [MixEntry]) -> [MixEntry] {
        if mix.contains(where: { $0.id == id }) { return mix.filter { $0.id != id } }
        return Array((mix + [MixEntry(id: id)]).suffix(2))
    }
}
