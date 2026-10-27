import Foundation
import Security

/// The signed-in account (the server's /api/me).
public struct Account: Codable, Equatable {
    public var name: String
    public var display: String?
    public var email: String?
    public var hasPassword: Bool

    public init(name: String, display: String? = nil, email: String? = nil, hasPassword: Bool = true) {
        self.name = name; self.display = display; self.email = email; self.hasPassword = hasPassword
    }
    public init(from decoder: Decoder) throws {
        let c = try decoder.keyed()
        name = c.v("name", ""); display = c.o("display"); email = c.o("email")
        // accounts from before email sign-in all have a password
        hasPassword = c.v("hasPassword", c.v("password", true))
    }
    /// What to call the account in the app.
    public var label: String { email ?? display ?? name }
}

/// Where the session cookie is kept: the Keychain for the real app, a plain file for a test copy (which
/// would otherwise ask the person to unlock the Keychain).
public protocol SessionStore: AnyObject {
    var cookie: String? { get set }
}

public final class KeychainSession: SessionStore {
    private let service: String
    public init(service: String) { self.service = service }

    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: "session"]
    }

    public var cookie: String? {
        get {
            var q = query
            q[kSecReturnData as String] = true
            q[kSecMatchLimit as String] = kSecMatchLimitOne
            var out: AnyObject?
            guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
            return String(data: d, encoding: .utf8)
        }
        set {
            SecItemDelete(query as CFDictionary)
            guard let v = newValue else { return }
            var q = query
            q[kSecValueData as String] = Data(v.utf8)
            q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
            SecItemAdd(q as CFDictionary, nil)
        }
    }
}

public final class FileSession: SessionStore {
    private let file: URL
    public init(file: URL) { self.file = file }
    public var cookie: String? {
        get { (try? String(contentsOf: file, encoding: .utf8)).flatMap { $0.isEmpty ? nil : $0 } }
        set {
            if let v = newValue { try? Data(v.utf8).write(to: file, options: .atomic) } else { try? FileManager.default.removeItem(at: file) }
        }
    }
}

public struct ApiError: Error, CustomStringConvertible {
    public let status: Int
    public let error: String?
    public let body: [String: Any]?
    public var description: String { error ?? "HTTP \(status)" }
}

/// One call at a time to the web app's API: the same one the web page and the Android app use. The
/// session is the server's cookie, kept like a browser keeps it.
public final class SyncClient {
    public static let cookieName = "kavana"
    public let base: URL
    public let session: SessionStore
    private let urls: URLSession

    public init(base: String, session: SessionStore) {
        self.base = URL(string: base.hasSuffix("/") ? base : base + "/")!
        self.session = session
        let cfg = URLSessionConfiguration.ephemeral
        cfg.httpShouldSetCookies = false
        cfg.httpCookieAcceptPolicy = .never
        cfg.timeoutIntervalForRequest = 30
        urls = URLSession(configuration: cfg)
    }

    /// Is this server one on this Mac (a sandbox)? Tests that make accounts may only talk to one of these.
    public var isLocal: Bool {
        guard let h = base.host else { return false }
        return h == "127.0.0.1" || h == "localhost" || h == "::1"
    }

    /// A call. Throws ApiError for an answer that isn't OK, URLError when it can't be reached.
    @discardableResult
    public func api(_ method: String, _ path: String, _ body: [String: Any]? = nil, rawBody: String? = nil) async throws -> [String: Any]? {
        guard let url = URL(string: path, relativeTo: base) else { throw ApiError(status: 0, error: "bad address", body: nil) }
        var req = URLRequest(url: url.absoluteURL)
        req.httpMethod = method
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        if let c = session.cookie { req.setValue("\(Self.cookieName)=\(c)", forHTTPHeaderField: "Cookie") }
        if method != "GET" {
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            if let raw = rawBody { req.httpBody = Data(raw.utf8) }
            else { req.httpBody = try JSONSerialization.data(withJSONObject: body ?? [:]) }
        }
        let (data, response) = try await urls.data(for: req)
        let http = response as! HTTPURLResponse
        takeCookie(http)
        let obj = (data.isEmpty || http.statusCode == 204) ? nil : (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        guard (200..<300).contains(http.statusCode) else { throw ApiError(status: http.statusCode, error: obj?["error"] as? String, body: obj) }
        return obj
    }

    /// "kavana=<token>; Path=/; Max-Age=…": a new session, a renewed one, or (empty, Max-Age=0) none.
    private func takeCookie(_ r: HTTPURLResponse) {
        for (k, v) in r.allHeaderFields {
            guard let key = k as? String, key.lowercased() == "set-cookie", let header = v as? String else { continue }
            for part in header.components(separatedBy: ", \(Self.cookieName)=").enumerated() {
                let h = part.offset == 0 ? part.element : "\(Self.cookieName)=" + part.element
                guard h.hasPrefix("\(Self.cookieName)=") else { continue }
                let value = String(h.split(separator: ";", maxSplits: 1, omittingEmptySubsequences: false)[0].dropFirst(Self.cookieName.count + 1))
                let dead = h.lowercased().contains("max-age=0")
                session.cookie = (value.isEmpty || dead) ? nil : value
            }
        }
    }
}
