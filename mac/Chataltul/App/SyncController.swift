import AppKit
import Foundation

/// Signing in, and keeping the doc in step with the web app's server: the same API and the same rules as
/// the Android app (web/data.js). Without an account nothing leaves the Mac. Signed in, each change goes
/// up a moment later, and what other devices did comes back when the app opens and every 30 seconds,
/// merged by the engine (E.merge) exactly as the web merges it.
final class SyncController: ObservableObject {
    let store: Store
    @Published private(set) var account: Account?
    /// "too big" while the server won't take this Mac's copy, even folded.
    @Published private(set) var trouble: String?
    /// Whether the server sends email (GET /api/config); nil until asked.
    @Published private(set) var emailOn: Bool?
    @Published private(set) var busy = false
    /// The address a sign-in code went to.
    @Published var codeSentTo: String?
    /// What went wrong, in words for the person.
    @Published var message: String?
    @Published private(set) var lastSync: Date?

    private var client: SyncClient
    private var rev = 0
    private var syncing = false
    private var again = false
    private var pending: Task<Void, Never>?
    private var poll: Timer?
    private let stateFile: URL
    private var serverNow: String

    private struct Saved: Codable { var rev = 0; var account: Account? }

    init(store: Store) {
        self.store = store
        stateFile = store.folder.appendingPathComponent("sync.json")
        serverNow = store.settings.serverURL
        let session: SessionStore = ProcessInfo.processInfo.environment["CHATALTUL_FILE_SESSION"] != nil
            ? FileSession(file: store.folder.appendingPathComponent("session"))
            : KeychainSession(service: (Bundle.main.bundleIdentifier ?? "xyz.tiresia.chataltul") + ".session")
        client = SyncClient(base: store.settings.serverURL, session: session)
        if let d = try? Data(contentsOf: stateFile), let s = try? JSONDecoder().decode(Saved.self, from: d) {
            account = s.account; rev = s.rev
        }
        // a session without an account on record (or the other way round) is no sign-in
        if account != nil, client.session.cookie == nil { account = nil; rev = 0; save() }
    }

    var signedIn: Bool { account != nil }
    var server: String { client.base.absoluteString }

    private func save() {
        try? JSONEncoder().encode(Saved(rev: rev, account: account)).write(to: stateFile, options: .atomic)
    }

    private func main<T>(_ f: @escaping () -> T) async -> T { await MainActor.run(body: f) }

    /// The server may have been changed in Settings (tests do).
    private func refreshClient() {
        if store.settings.serverURL != serverNow {
            serverNow = store.settings.serverURL
            client = SyncClient(base: serverNow, session: client.session)
        }
    }

    // ---------------------------------------------------------------- when to sync

    /// The app opened: who this is now (a password changed elsewhere signs this Mac out), then what's new.
    func opened() {
        poll?.invalidate()
        let t = Timer(timeInterval: 30, repeats: true) { [weak self] _ in self?.pull() }
        RunLoop.main.add(t, forMode: .common)
        poll = t
        guard account != nil else { return }
        Task { await refreshMe(); await doPull() }
    }

    /// After a change: send it up in a moment (several changes in a row go as one).
    func queue() {
        guard account != nil else { return }
        pending?.cancel()
        pending = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 700_000_000)
            guard !Task.isCancelled else { return }
            await self?.push()
        }
    }

    func pull() { guard account != nil else { return }; Task { await doPull() } }

    // ---------------------------------------------------------------- the sync

    func push() async {
        guard account != nil else { return }
        refreshClient()
        if syncing { again = true; return }
        syncing = true
        var squeezed = false
        defer {
            syncing = false
            if again { again = false; queue() }
        }
        for _ in 0..<4 {
            do {
                let doc = await main { self.store.docJSON() }
                let r = try await client.api("POST", "api/sync", rawBody: "{\"rev\":\(rev),\"doc\":\(doc)}")
                rev = (r?["rev"] as? Int) ?? rev
                save()
                await main { self.trouble = nil; self.lastSync = Date() }
                break
            } catch let e as ApiError {
                if e.status == 409, let b = e.body { _ = await merge(b["doc"], rev: (b["rev"] as? Int) ?? rev); continue }   // another device synced first
                if e.status == 413 { if await squeeze(already: squeezed) { squeezed = true; continue } else { break } }
                if e.status == 401 { await signedOutElsewhere() }
                break
            } catch {
                // offline, or a doc past what the server reads at all (it cuts the connection instead of answering)
                let big = await main { self.store.act("size")?.result?["big"]?.bool == true }
                if big, await squeeze(already: squeezed) { squeezed = true; continue }
                break
            }
        }
    }

    private func squeeze(already: Bool) async -> Bool {
        if already { await main { self.trouble = "too big" }; return false }
        await main { self.store.act("squeeze") }
        return true
    }

    private func doPull() async {
        guard account != nil, !syncing else { return }
        refreshClient()
        do {
            guard let r = try await client.api("GET", "api/sync?rev=\(rev)") else { return }   // nothing new is a 204
            if await merge(r["doc"], rev: (r["rev"] as? Int) ?? rev) { queue() }
            await main { self.lastSync = Date() }
        } catch let e as ApiError {
            if e.status == 401 { await signedOutElsewhere() }
        } catch {}
    }

    /// The server's copy merged into this Mac's; true if the result has something the server's doesn't.
    private func merge(_ theirs: Any?, rev newRev: Int, adopt: Bool = false) async -> Bool {
        guard let theirs, let data = try? JSONSerialization.data(withJSONObject: ["op": "merge", "doc": theirs, "adopt": adopt] as [String: Any]) else { return false }
        let json = String(decoding: data, as: UTF8.self)
        let out = await main { self.store.act(json: json, op: "merge") }
        guard let out, out.error == nil else { return false }
        rev = newRev
        save()
        return out.result?["mineToo"]?.bool == true
    }

    // ---------------------------------------------------------------- signing in and out

    func loadConfig() {
        refreshClient()
        guard emailOn == nil else { return }
        Task {
            do {
                if let r = try await client.api("GET", "api/config") { await main { self.emailOn = (r["email"] as? Bool) == true } }
            } catch {
                await main { self.message = "Can't reach the server." }
                NSLog("config: \(error)")
            }
        }
    }

    /// A 6-digit code to this address. It signs in, or makes the account if there isn't one.
    func sendCode(_ email: String) {
        let e = email.trimmingCharacters(in: .whitespacesAndNewlines)
        guard e.contains("@") else { message = "That doesn't look like an email address."; return }
        run {
            try await self.client.api("POST", "api/email/code", ["email": e])
            await self.main { self.codeSentTo = e }
        }
    }

    func signIn(email: String, code: String) {
        let e = email.trimmingCharacters(in: .whitespacesAndNewlines)
        let c = code.trimmingCharacters(in: .whitespacesAndNewlines)
        run(asking: "code") {
            let r = try await self.client.api("POST", "api/email/signin", ["email": e, "code": c])
            let created = (r?["created"] as? Bool) == true
            await self.joined(r ?? [:], existing: !created)
            await self.main { self.codeSentTo = nil }
        }
    }

    /// kind "signup" or "login" (a name or an email, and the password).
    func signIn(kind: String, name: String, password: String) {
        run(asking: "password") {
            let r = try await self.client.api("POST", kind == "signup" ? "api/signup" : "api/login", ["name": name, "password": password])
            await self.joined(r ?? [:], existing: kind == "login")
        }
    }

    /// After any sign-in this Mac's history comes along into the account.
    private func joined(_ me: [String: Any], existing: Bool) async {
        await setAccount(me)
        if let r = try? await client.api("GET", "api/sync?rev=-1") {
            _ = await merge(r["doc"], rev: (r["rev"] as? Int) ?? 0, adopt: existing)
        }
        await push()
    }

    private func setAccount(_ me: [String: Any]) async {
        var m = me
        m.removeValue(forKey: "created")
        guard let data = try? JSONSerialization.data(withJSONObject: m), let a = try? JSONDecoder().decode(Account.self, from: data) else { return }
        await main { self.account = a; self.save() }
    }

    func refreshMe() async {
        guard account != nil else { return }
        do { if let me = try await client.api("GET", "api/me") { await setAccount(me) } }
        catch let e as ApiError { if e.status == 401 { await signedOutElsewhere() } }
        catch {}
    }

    /// The account's history leaves this Mac on sign out (it's safe on the server).
    func signOut() {
        run {
            await self.push()
            _ = try? await self.client.api("POST", "api/logout")
            await self.forget()
            await self.main { self.store.act("empty") }
        }
    }

    private func signedOutElsewhere() async { await forget() }

    private func forget() async {
        pending?.cancel()
        client.session.cookie = nil
        await main {
            self.account = nil; self.rev = 0; self.trouble = nil; self.codeSentTo = nil
            self.save()
        }
    }

    // ---------------------------------------------------------------- plumbing

    /// `asking`: what the person was doing, so a refusal can be put in the right words ("code" or "password").
    private func run(asking: String = "", _ body: @escaping () async throws -> Void) {
        refreshClient()
        busy = true
        message = nil
        Task {
            do { try await body() }
            catch let e as ApiError { await main { self.message = Self.words(for: e, asking: asking) } }
            catch { await main { self.message = "Can't reach the server." } }
            await main { self.busy = false }
        }
    }

    static func words(for e: ApiError, asking: String = "") -> String {
        let m = (e.error ?? "").lowercased()
        switch e.status {
        case 429: return "Too many tries. Wait a few minutes."
        case 401 where asking == "code": return "That code didn't work. Ask for a new one."
        case 401 where asking == "password": return "That name or password didn't work."
        case 400 where m == "email": return "That doesn't look like an email address."
        case 404 where m == "no email": return "This server doesn't send email. Use a name and a password."
        default: return e.error.map { $0.prefix(1).uppercased() + $0.dropFirst() + "." } ?? "The server had a problem (\(e.status))."
        }
    }
}
