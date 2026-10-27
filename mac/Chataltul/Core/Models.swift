import Foundation

// What bridge.js hands back (KB.run). The shapes are engine.js's own: E.view, E.history, E.pings. Fields
// the Mac app doesn't draw (the shop, missions, achievements, friends) are left out and ignored.
// Times are milliseconds since 1970, as the engine keeps them.

public struct Out: Decodable {
    public var changed: Bool
    public var result: JSONValue?
    public var error: String?
    public var meets: [String]
    public var done: [DoneNote]
    public var state: UiState

    public init(from decoder: Decoder) throws {
        let c = try decoder.keyed()
        changed = c.v("changed", false)
        result = c.o("result")
        error = c.o("error")
        meets = c.v("meets", [])
        done = c.v("done", [])
        state = try c.decode(UiState.self, forKey: AnyKey("state"))
    }
}

public struct DoneNote: Decodable {
    public var kind: String
    public var text: String
    public var pay: Int
    public var id: String?
    public init(from decoder: Decoder) throws {
        let c = try decoder.keyed()
        kind = c.v("kind", ""); text = c.v("text", ""); pay = c.v("pay", 0); id = c.o("id")
    }
}

public struct UiState: Decodable {
    public var now: Int
    public var view: ViewState
    public var history: [Hist]
    public var treats: Treats
    public var stats: Stats
    public var cats: [CatInfo]
    public var companion: String
    public var pings: [Ping]
    public var canPause: Bool
    public var owned: [String]
    public var prices: [String: Int]
    /// The things for the café (cats.js ITEMS) that this account owns, with their side and back paintings.
    public var things: [CafeThing]
    public var theme: String
    public var tally: [String: Int]
    /// What this engine can do beyond the web's (bridge.js, marked MAC).
    public var canTakeOff: Bool

    public init(from decoder: Decoder) throws {
        let c = try decoder.keyed()
        now = c.v("now", 0)
        view = c.v("view", ViewState())
        history = c.v("history", [])
        treats = c.v("treats", Treats())
        stats = c.v("stats", Stats())
        cats = c.v("cats", [])
        companion = c.v("companion", "miso")
        pings = c.v("pings", [])
        canPause = c.v("canPause", true)
        let shop = (c.o("shop") as ShopInfo?)
        owned = shop?.owned ?? []
        prices = shop?.prices ?? [:]
        things = (shop?.items ?? []).filter { i in shop?.owned.contains(i.id) ?? false }
        theme = view.settings.theme
        tally = c.v("tally", [:])
        canTakeOff = (c.o("caps") as Caps?)?.takeOff ?? false
    }

    /// The cat with this id, else the first one, else Miso.
    public func cat(_ id: String?) -> CatInfo {
        cats.first { $0.id == id } ?? cats.first ?? CatInfo(id: "miso", name: "Miso")
    }
    public var catsOn: Bool { view.settings.cat }
    public func owns(_ id: String) -> Bool { owned.contains(id) }
}

struct ShopInfo: Decodable {
    var owned: [String]
    var prices: [String: Int]
    var items: [CafeThing]
    init(from decoder: Decoder) throws {
        let c = try decoder.keyed()
        owned = c.v("owned", []); prices = c.v("prices", [:]); items = c.v("items", [])
    }
}

/// A thing for the café: its id and, where painted, its side and back.
public struct CafeThing: Decodable, Equatable {
    public var id: String
    public var name: String
    public var side: String?
    public var back: String?
    public init(from decoder: Decoder) throws {
        let c = try decoder.keyed()
        id = c.v("id", ""); name = c.v("name", "")
        let v: [String: String]? = c.o("views")
        side = v?["side"]; back = v?["back"]
    }
}

struct Caps: Decodable {
    var takeOff: Bool
    init(from decoder: Decoder) throws { takeOff = try decoder.keyed().v("takeOff", false) }
}

public struct ViewState: Decodable {
    public var now: Int
    public var settings: Settings
    public var active: Block?
    public var awaiting: Block?
    public var onBreak: Brk?
    public var breakOffer: BreakOffer?
    public var recent: [String]
    public var later: [LaterItem]

    public init() { now = 0; settings = Settings(); active = nil; awaiting = nil; onBreak = nil; breakOffer = nil; recent = []; later = [] }
    public init(from decoder: Decoder) throws {
        let c = try decoder.keyed()
        now = c.v("now", 0)
        settings = c.v("settings", Settings())
        active = c.o("active")
        awaiting = c.o("awaiting")
        onBreak = c.o("onBreak")
        breakOffer = c.o("breakOffer")
        recent = c.v("recent", [])
        later = c.v("later", [])
    }
}

public struct Settings: Decodable {
    public var interval: Int
    public var style: String
    public var goal: Int
    public var cat: Bool
    public var companion: String
    public var tags: [String]
    public var tag: String
    public var mode: String
    public var pause: String
    public var overtime: Bool
    public var closeCounts: Bool
    public var theme: String

    public init() { interval = 15; style = "ask"; goal = 0; cat = true; companion = "miso"; tags = []; tag = ""; mode = "timer"; pause = "five"; overtime = false; closeCounts = true; theme = "classic" }
    public init(from decoder: Decoder) throws {
        let c = try decoder.keyed()
        interval = c.v("interval", 15); style = c.v("style", "ask"); goal = c.v("goal", 0)
        cat = c.v("cat", true); companion = c.v("companion", "miso")
        tags = c.v("tags", []); tag = c.v("tag", ""); mode = c.v("mode", "timer")
        pause = c.v("pause", "five"); overtime = c.v("overtime", false); closeCounts = c.v("closeCounts", true)
        theme = c.v("theme", "classic")
    }
}

public struct Block: Decodable {
    public var id: String
    public var intention: String
    public var minutes: Int
    public var interval: Int
    public var start: Int
    public var end: Int
    public var ended: Int?
    public var stopped: Bool
    public var drifts: Int
    public var pending: Int?
    public var next: Int?
    public var style: String
    public var paused: Int?
    public var parked: [String]
    public var cat: String
    public var tag: String?
    public var kitten: String
    public var goneWhy: String?
    public var treats: Int
    public var mode: String
    public var over: Int?
    public var cancelUntil: Int
    public var note: String
    public var pauseEnds: Int?
    public var pauses: Int

    public init(from decoder: Decoder) throws {
        let c = try decoder.keyed()
        id = c.v("id", ""); intention = c.v("intention", ""); minutes = c.v("minutes", 0); interval = c.v("interval", 0)
        start = c.v("start", 0); end = c.v("end", 0); ended = c.o("ended"); stopped = c.v("stopped", false)
        drifts = c.v("drifts", 0); pending = c.o("pending"); next = c.o("next"); style = c.v("style", "ask")
        paused = c.o("paused"); parked = c.v("parked", []); cat = c.v("cat", "miso"); tag = c.o("tag")
        kitten = c.v("kitten", "growing"); goneWhy = c.o("goneWhy"); treats = c.v("treats", 0); mode = c.v("mode", "timer")
        over = c.o("over"); cancelUntil = c.v("cancelUntil", 0); note = c.v("note", ""); pauseEnds = c.o("pauseEnds"); pauses = c.v("pauses", 0)
    }

    public var stopwatch: Bool { mode == "stopwatch" }
    public var title: String { titleOf(intention, tag) }
}

public struct Brk: Decodable, Equatable {
    public var minutes: Int
    public var start: Int
    public var end: Int
    public init(minutes: Int, start: Int, end: Int) { self.minutes = minutes; self.start = start; self.end = end }
    public init(from decoder: Decoder) throws {
        let c = try decoder.keyed()
        minutes = c.v("minutes", 0); start = c.v("start", 0); end = c.v("end", 0)
    }
}

public struct BreakOffer: Decodable {
    public var id: String
    public var minutes: Int
    public init(from decoder: Decoder) throws {
        let c = try decoder.keyed()
        id = c.v("id", ""); minutes = c.v("minutes", 5)
    }
}

public struct LaterItem: Decodable {
    public var id: String
    public var text: String
    public init(from decoder: Decoder) throws {
        let c = try decoder.keyed()
        id = c.v("id", ""); text = c.v("text", "")
    }
}

public struct Hist: Decodable {
    public var id: String
    public var start: Int
    public var intention: String
    public var minutes: Int
    public var actual: Int
    public var stopped: Bool
    public var outcome: String?
    public var drifts: Int
    public var cat: String
    public var kitten: String
    public var treats: Int
    public var tag: String?
    public var mode: String
    public var note: String

    public init(from decoder: Decoder) throws {
        let c = try decoder.keyed()
        mode = c.v("mode", "timer"); note = c.v("note", "")
        id = c.v("id", ""); start = c.v("start", 0); intention = c.v("intention", ""); minutes = c.v("minutes", 0)
        actual = c.v("actual", 1); stopped = c.v("stopped", false); outcome = c.o("outcome"); drifts = c.v("drifts", 0)
        cat = c.v("cat", "miso"); kitten = c.v("kitten", "home"); treats = c.v("treats", 0); tag = c.o("tag")
    }
    public var title: String { titleOf(intention, tag) }
}

public struct Treats: Decodable {
    public var earned: Int
    public var spent: Int
    public var left: Int
    public init() { earned = 0; spent = 0; left = 0 }
    public init(from decoder: Decoder) throws {
        let c = try decoder.keyed()
        earned = c.v("earned", 0); spent = c.v("spent", 0); left = c.v("left", 0)
    }
}

public struct Stats: Decodable {
    public var today: Int
    public var week: Int
    public var weekBlocks: Int
    public var streak: Int
    public var answered: Int
    public var drifted: Int
    public init() { today = 0; week = 0; weekBlocks = 0; streak = 0; answered = 0; drifted = 0 }
    public init(from decoder: Decoder) throws {
        let c = try decoder.keyed()
        today = c.v("today", 0); week = c.v("week", 0); weekBlocks = c.v("weekBlocks", 0)
        streak = c.v("streak", 0); answered = c.v("answered", 0); drifted = c.v("drifted", 0)
    }
}

public struct CatInfo: Decodable {
    public var id: String
    public var name: String
    public var about: String
    public var met: Bool
    public var adopt: Bool
    public var portrait: String?
    public var kitten: String?
    public var nap: String?
    /// walk, loaf, stretch, wash, back: paintings some cats have, for the café and the break.
    public var poses: [String: String]?

    public init(id: String, name: String) { self.id = id; self.name = name; about = ""; met = true; adopt = false; portrait = nil; kitten = nil; nap = nil; poses = nil }
    public init(from decoder: Decoder) throws {
        let c = try decoder.keyed()
        id = c.v("id", "miso"); name = c.v("name", "Miso"); about = c.v("about", "")
        met = c.v("met", false); adopt = c.v("adopt", false)
        portrait = c.o("portrait"); kitten = c.o("kitten"); nap = c.o("nap"); poses = c.o("poses")
    }
}

public struct Ping: Decodable, Equatable {
    public var at: Int
    public var kind: String
    public var tag: String
    public var title: String
    public var body: String
    public var block: String?
    public var n: Int?

    public init(at: Int, kind: String, tag: String = "", title: String = "", body: String = "", block: String? = nil, n: Int? = nil) {
        self.at = at; self.kind = kind; self.tag = tag; self.title = title; self.body = body; self.block = block; self.n = n
    }
    public init(from decoder: Decoder) throws {
        let c = try decoder.keyed()
        at = c.v("at", 0); kind = c.v("kind", ""); tag = c.v("tag", ""); title = c.v("title", ""); body = c.v("body", "")
        block = c.o("block"); n = c.o("n")
    }
    /// The same ping in two lists (a check-in is its block and number; the end, its block and time).
    public var key: String { "\(kind):\(block ?? ""):\(n.map(String.init) ?? ""):\(at)" }
}
