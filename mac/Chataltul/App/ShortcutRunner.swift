import Foundation

/// Runs a Shortcut by name (`shortcuts run`), for turning a Focus on when a block starts and off when it ends:
/// Chataltul can't set a Focus itself, but a Shortcut with "Set Focus" can. Nothing runs if no name is given.
enum ShortcutRunner {
    /// Tests see what would have run, and nothing does.
    static var onRun: ((String) -> Void)?

    static func run(_ name: String) {
        let n = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !n.isEmpty else { return }
        if let o = onRun { o(n); return }
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/shortcuts")
        p.arguments = ["run", n]
        p.standardOutput = FileHandle.nullDevice
        p.standardError = FileHandle.nullDevice
        try? p.run()
    }
}
