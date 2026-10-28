import Foundation

/// A global shortcut: a key code and Carbon's modifier mask (control 4096, option 2048, shift 512, command 256).
public struct KeyCombo: Codable, Equatable, Hashable {
    public var code: Int
    public var mods: Int
    public init(code: Int, mods: Int) { self.code = code; self.mods = mods }

    public static let control = 4096, option = 2048, shift = 512, command = 256
    static let co = control | option

    public var isValid: Bool { mods & (KeyCombo.control | KeyCombo.option | KeyCombo.command) != 0 }

    /// "⌃⌥Space", "⌃⌥P".
    public var label: String {
        var s = ""
        if mods & KeyCombo.control != 0 { s += "⌃" }
        if mods & KeyCombo.option != 0 { s += "⌥" }
        if mods & KeyCombo.shift != 0 { s += "⇧" }
        if mods & KeyCombo.command != 0 { s += "⌘" }
        return s + KeyCombo.name(code)
    }

    static let names: [Int: String] = [
        0: "A", 1: "S", 2: "D", 3: "F", 4: "H", 5: "G", 6: "Z", 7: "X", 8: "C", 9: "V", 11: "B", 12: "Q", 13: "W", 14: "E", 15: "R",
        16: "Y", 17: "T", 18: "1", 19: "2", 20: "3", 21: "4", 22: "6", 23: "5", 24: "=", 25: "9", 26: "7", 27: "-", 28: "8", 29: "0",
        30: "]", 31: "O", 32: "U", 33: "[", 34: "I", 35: "P", 37: "L", 38: "J", 39: "'", 40: "K", 41: ";", 42: "\\", 43: ",", 44: "/",
        45: "N", 46: "M", 47: ".", 49: "Space", 36: "Return", 48: "Tab", 51: "Delete", 53: "Esc",
        122: "F1", 120: "F2", 99: "F3", 118: "F4", 96: "F5", 97: "F6", 98: "F7", 100: "F8", 101: "F9", 109: "F10", 103: "F11", 111: "F12",
        123: "←", 124: "→", 125: "↓", 126: "↑",
    ]
    static func name(_ code: Int) -> String { names[code] ?? "Key \(code)" }
}

public enum AwayAction: String, Codable, CaseIterable {
    case ask, count, takeOff
}

/// The plain Pomodoro cycle the popover steps through. It lives on this Mac only (the engine has no
/// cycles), so it isn't synced.
public struct Cycle: Codable, Equatable {
    public var focus = 25
    public var shortBreak = 5
    public var longBreak = 15
    /// A long break after this many blocks in a day.
    public var longAfter = 4
    /// The dashes in the popover: how many blocks make a day.
    public var perDay = 6
    public var preset = CyclePreset.pomodoro

    public init() {}
    public init(from decoder: Decoder) throws {
        let c = try decoder.keyed()
        focus = c.v("focus", 25); shortBreak = c.v("shortBreak", 5); longBreak = c.v("longBreak", 15)
        longAfter = c.v("longAfter", 4); perDay = c.v("perDay", 6); preset = c.v("preset", CyclePreset.pomodoro)
    }

    /// Is the break after this many blocks today the long one?
    public func isLong(afterBlocks n: Int) -> Bool { longAfter > 0 && n > 0 && n % longAfter == 0 }
}

public struct MixEntry: Codable, Equatable {
    public var id: String
    public var vol: Double
    public init(id: String, vol: Double = 0.8) { self.id = id; self.vol = vol }
}

/// Everything the Mac app keeps for itself, apart from the doc: mac.json in the app's folder.
public struct MacSettings: Codable, Equatable {
    public var blocklist = Blocklist.starter
    /// Lists from the web the person added by their link, next to the ready-made ones.
    public var customLists: [ListCategory] = []
    /// The speed bump on/off. On, but with an empty list, it never comes up.
    public var speedBump = true
    public var awayAfterMin = 2
    public var awayAction = AwayAction.ask
    public var cycle = Cycle()
    public var startKey = KeyCombo(code: 49, mods: KeyCombo.co)   // ⌃⌥Space
    public var parkKey = KeyCombo(code: 35, mods: KeyCombo.co)    // ⌃⌥P
    public var yesKey = KeyCombo(code: 16, mods: KeyCombo.co)     // ⌃⌥Y
    public var driftedKey = KeyCombo(code: 2, mods: KeyCombo.co)  // ⌃⌥D
    public var mix: [MixEntry] = []
    public var breaks = BreakSettings()
    /// Remember which apps (and sites) a block's time went to. Kept in timing.json on this Mac, never sent anywhere.
    public var recordTiming = true
    /// The names of two Shortcuts to run when a block starts and when it ends (to turn a Focus on and off), or empty.
    public var shortcutStart = ""
    public var shortcutEnd = ""
    /// The cat peeks up in the corner of the screen; off, check-ins come as notifications only.
    public var catInCorner = true
    /// Which minutes the popover's Focus tab starts, remembered between launches.
    public var focusMinutes = 25
    /// The server to sign in to and sync with. Empty means the web app's own.
    public var server = ""
    /// Seen the first-run welcome.
    public var welcomed = false

    public init() {}
    public init(from decoder: Decoder) throws {
        let c = try decoder.keyed()
        blocklist = c.v("blocklist", Blocklist.starter); customLists = c.v("customLists", []); speedBump = c.v("speedBump", true)
        awayAfterMin = c.v("awayAfterMin", 2); awayAction = c.v("awayAction", AwayAction.ask)
        cycle = c.v("cycle", Cycle())
        startKey = c.v("startKey", startKey); parkKey = c.v("parkKey", parkKey)
        yesKey = c.v("yesKey", yesKey); driftedKey = c.v("driftedKey", driftedKey)
        mix = c.v("mix", []); breaks = c.v("breaks", BreakSettings())
        recordTiming = c.v("recordTiming", true); shortcutStart = c.v("shortcutStart", ""); shortcutEnd = c.v("shortcutEnd", ""); catInCorner = c.v("catInCorner", true)
        focusMinutes = c.v("focusMinutes", 25); server = c.v("server", ""); welcomed = c.v("welcomed", false)
    }

    public static let defaultServer = "https://stuffs.tiresia.xyz/kavana/"
    public var serverURL: String { server.isEmpty ? MacSettings.defaultServer : (server.hasSuffix("/") ? server : server + "/") }
}

/// mac.json, read and written whole.
public struct MacSettingsStore {
    public let file: URL
    public init(folder: URL) {
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        file = folder.appendingPathComponent("mac.json")
    }
    public func read() -> MacSettings {
        guard let d = try? Data(contentsOf: file), let s = try? JSONDecoder().decode(MacSettings.self, from: d) else { return MacSettings() }
        return s
    }
    public func write(_ s: MacSettings) {
        let e = JSONEncoder()
        e.outputFormatting = [.prettyPrinted, .sortedKeys]
        if let d = try? e.encode(s) { try? d.write(to: file, options: .atomic) }
    }
}
