import Foundation
import JavaScriptCore

/// Chataltul's engine: the web app's own lines.js, cats.js, engine.js and news.js (copied as they are by
/// tools/sync-web.sh) with the bridge, run in JavaScriptCore. One context holds the doc between calls.
/// JSContext is not thread-safe: the app calls this from one serial queue.
public final class EngineHost {
    public enum Failure: Error, CustomStringConvertible {
        case missing(String)
        case script(String)
        public var description: String {
            switch self { case .missing(let f): return "missing \(f)"; case .script(let m): return m }
        }
    }

    /// cats.js comes before engine.js: the engine names a cat from it when it writes a ping.
    public static let files = ["lines.js", "cats.js", "engine.js", "news.js", "bridge.js"]

    private let ctx: JSContext
    private var lastException: String?

    /// `js` is the folder with the five files (the app's Resources/js).
    public init(js: URL) throws {
        guard let c = JSContext() else { throw Failure.script("no JavaScript context") }
        ctx = c
        ctx.exceptionHandler = { [weak self] _, e in self?.lastException = e?.toString() }
        for f in Self.files {
            let url = js.appendingPathComponent(f)
            guard let src = try? String(contentsOf: url, encoding: .utf8) else { throw Failure.missing(f) }
            _ = ctx.evaluateScript(src, withSourceURL: url)
            if let e = lastException { lastException = nil; throw Failure.script("\(f): \(e)") }
        }
        if ctx.evaluateScript("typeof KB")?.toString() != "object" { throw Failure.script("the bridge didn't load") }
    }

    private func call(_ fn: String, _ args: [Any]) throws -> String {
        let kb = ctx.objectForKeyedSubscript("KB")
        guard let f = kb?.objectForKeyedSubscript(fn), let r = f.call(withArguments: args) else { throw Failure.script("KB.\(fn) failed") }
        if let e = lastException { lastException = nil; throw Failure.script("KB.\(fn): \(e)") }
        return r.toString() ?? ""
    }

    /// The doc from the app's file (a JSON string), or a new one for nil.
    public func load(_ json: String?) throws { _ = try call("load", [json ?? NSNull()]) }

    /// The doc as JSON, for the file.
    public func dump() throws -> String { try call("dump", []) }

    /// A minute of `ms` milliseconds, for tests that can't wait.
    public func minute(_ ms: Int) throws { _ = try call("minute", [ms]) }

    /// One change (or just a tick) at `now`. Returns everything the screens need.
    @discardableResult
    public func run(_ op: String, _ fields: [String: Any] = [:], now: Int = nowMs()) throws -> Out {
        var o = fields
        o["op"] = op
        let data = try JSONSerialization.data(withJSONObject: o, options: [.sortedKeys])
        let raw = try call("run", [String(decoding: data, as: UTF8.self), now])
        return try Self.decode(raw)
    }

    /// The same, from an op already written as JSON (the sync merges the server's doc this way).
    @discardableResult
    public func run(json: String, now: Int = nowMs()) throws -> Out {
        try Self.decode(try call("run", [json, now]))
    }

    static func decode(_ raw: String) throws -> Out {
        do { return try JSONDecoder().decode(Out.self, from: Data(raw.utf8)) }
        catch { throw Failure.script("the engine's answer didn't decode: \(error)") }
    }
}
