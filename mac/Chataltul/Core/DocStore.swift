import Foundation

/// The doc: one JSON file in the app's own folder, the same shape the web keeps in the browser.
public struct DocStore {
    public let file: URL

    public init(folder: URL) {
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        file = folder.appendingPathComponent("doc.json")
    }

    public func read() -> String? {
        guard let d = try? Data(contentsOf: file), let s = String(data: d, encoding: .utf8), !s.isEmpty else { return nil }
        return s
    }

    /// Written whole and swapped in, so a crash in the middle never leaves half a doc.
    public func write(_ json: String) throws {
        try Data(json.utf8).write(to: file, options: .atomic)
    }

    /// A doc that doesn't load is kept aside, never written over.
    @discardableResult
    public func setAside() -> URL? {
        let to = file.deletingLastPathComponent().appendingPathComponent("doc.json.bad-\(nowMs())")
        return (try? FileManager.default.copyItem(at: file, to: to)) != nil ? to : nil
    }
}
