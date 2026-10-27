import AppKit
import UniformTypeIdentifiers

/// Saves what the window shows as a file: the blocks as CSV, where the time went as CSV, the review as Markdown.
enum Exporter {
    /// Tests put the files here instead of asking where.
    static var testFolder: URL?
    /// What was last saved (a test looks).
    static var lastSaved: URL?

    @discardableResult
    static func save(_ text: String, name: String, type: UTType) -> URL? {
        if let f = testFolder {
            let u = f.appendingPathComponent(name)
            try? text.write(to: u, atomically: true, encoding: .utf8)
            lastSaved = u
            return u
        }
        let p = NSSavePanel()
        p.nameFieldStringValue = name
        p.allowedContentTypes = [type]
        p.canCreateDirectories = true
        guard p.runModal() == .OK, let u = p.url else { return nil }
        do { try text.write(to: u, atomically: true, encoding: .utf8); lastSaved = u; return u } catch { NSSound.beep(); return nil }
    }
}
