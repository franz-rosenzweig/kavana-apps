import AppKit
import Foundation

/// Signing in and syncing, against a sandbox copy of the web server on this Mac (selftest.sh sync starts it
/// with KAVANA_MAIL=outbox, so the sign-in codes are written to a file this reads). It plays a second Mac
/// through the API, and also checks that a recording is downloaded from that server and played.
enum SyncScenarios {
    static let MIN = 60_000

    static func wait(_ what: String, _ seconds: Double = 20, _ cond: () -> Bool) -> Bool {
        let end = Date().addingTimeInterval(seconds)
        while Date() < end {
            if cond() { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.1))
        }
        print("  (gave up waiting for \(what))")
        return false
    }

    /// The newest sign-in code mailed to this address.
    static func code(for email: String, outbox: URL, after: Int) -> String? {
        guard let text = try? String(contentsOf: outbox, encoding: .utf8) else { return nil }
        for line in text.split(separator: "\n").reversed() {
            guard let d = try? JSONSerialization.jsonObject(with: Data(line.utf8)) as? [String: Any],
                  (d["to"] as? String) == email, (d["at"] as? Int ?? 0) >= after, let subject = d["subject"] as? String,
                  let r = subject.range(of: "^[0-9]{6}", options: .regularExpression) else { continue }   // "123456 is your Chataltul code"
            return String(subject[r])
        }
        return nil
    }

    static func run(base: String, outbox: URL) throws {
        // the one rule of these tests: only a server on this Mac, never the real one
        guard let host = URL(string: base)?.host, host == "127.0.0.1" || host == "localhost" else {
            check("the sync test refuses a server that isn't on this Mac", true)
            print("FAIL refusing to run against \(base)"); failed += 1
            return
        }
        let store = try Store()
        store.resetForTest()
        store.settings.server = base
        let sync = SyncController(store: store)
        store.changed = { sync.queue() }

        let email = "mac-test-\(Int.random(in: 100000...999999))@example.test"
        let folder = Store.dataFolder()

        // a second Mac, through the API and its own engine
        let d2 = SyncClient(base: base, session: FileSession(file: folder.appendingPathComponent("session2")))
        let engine2 = try EngineHost(js: Bundle.main.resourceURL!.appendingPathComponent("js"))
        func signIn2() async throws {
            let since = nowMs() - 1000
            try await d2.api("POST", "api/email/code", ["email": email])
            guard let c = code(for: email, outbox: outbox, after: since) else { throw ApiError(status: 0, error: "no code in the outbox", body: nil) }
            try await d2.api("POST", "api/email/signin", ["email": email, "code": c])
        }
        func serverDoc() -> [String: Any]? {
            var out: [String: Any]?; var done = false
            Task { out = try? await d2.api("GET", "api/sync?rev=-1"); done = true }
            _ = wait("the server's copy") { done }
            return out
        }
        func serverTitles() -> [String] { ((serverDoc()?["doc"] as? [String: Any])?["blocks"] as? [[String: Any]] ?? []).compactMap { $0["intention"] as? String } }

        // ---- this Mac's own history first
        let now = nowMs()
        store.act("start", ["intention": "On this Mac", "minutes": 25], now: now - 90 * MIN)
        store.act("tick", now: now - 60 * MIN)
        if let a = store.awaiting { store.act("outcome", ["id": a.id, "outcome": "done"], now: now - 59 * MIN) }
        check("signed out: nothing is signed in", sync.account == nil && !sync.signedIn)

        // ---- the sign-in screen asks the server what it can do
        sync.loadConfig()
        check("the server says it sends email", wait("config") { sync.emailOn == true })
        if sync.emailOn != true { print("  message: \(sync.message ?? "none") at \(sync.server)") }

        // ---- a wrong code
        let since = nowMs() - 1000
        sync.sendCode(email)
        check("the code is sent", wait("the code") { sync.codeSentTo == email })
        guard let good = code(for: email, outbox: outbox, after: since) else { check("the code is in the outbox", false); return }
        check("the code is six digits", good.count == 6)
        sync.signIn(email: email, code: good == "000000" ? "111111" : "000000")
        check("a wrong code is refused, in words", wait("the refusal") { sync.message != nil } && sync.account == nil && sync.message == "That code didn't work. Ask for a new one.")
        print("  said: \(sync.message ?? "")")

        // ---- the right one
        sync.signIn(email: email, code: good)
        check("the right code signs in", wait("sign in") { sync.account != nil })
        check("the account is the email's", sync.account?.email == email)
        // ---- a second Mac signs in and sees it, then adds a block of its own
        Task { try? await signIn2() }
        _ = wait("the second Mac's sign-in") { d2.session.cookie != nil }
        check("a second Mac signed in with its own code", d2.session.cookie != nil)
        check("this Mac's block went up with the sign-in", wait("the first push") { serverTitles().contains("On this Mac") })
        let theirs = serverDoc()
        try engine2.load(nil)
        if let doc = theirs?["doc"], let data = try? JSONSerialization.data(withJSONObject: doc) { try engine2.load(String(decoding: data, as: UTF8.self)) }
        let t2 = nowMs()
        try engine2.run("start", ["intention": "On the other Mac", "minutes": 25], now: t2 - 50 * MIN)
        try engine2.run("tick", now: t2 - 20 * MIN)
        var rev2 = (theirs?["rev"] as? Int) ?? 0
        var pushed = false
        Task {
            let doc = (try? engine2.dump()) ?? "{}"
            if let r = try? await d2.api("POST", "api/sync", rawBody: "{\"rev\":\(rev2),\"doc\":\(doc)}") { rev2 = (r["rev"] as? Int) ?? rev2; pushed = true }
        }
        check("the second Mac pushed", wait("the second push") { pushed })
        sync.pull()
        check("what the other Mac did comes back", wait("the pull") { store.state.history.contains { $0.intention == "On the other Mac" } })
        check("and this Mac's own is still there", store.state.history.contains { $0.intention == "On this Mac" })

        // ---- a change here goes up by itself
        store.act("start", ["intention": "Made after signing in", "minutes": 25], now: nowMs() - 30 * MIN)
        store.act("tick", now: nowMs() - 3 * MIN)
        check("a change on this Mac goes up on its own", wait("the push") { serverTitles().contains("Made after signing in") })

        // ---- a recording is downloaded from that server and played (silently)
        store.settings.mix = [MixEntry(id: "purr")]
        let player = SoundPlayer(store: store)
        store.act("start", ["intention": "Sound check", "minutes": 25], now: nowMs())
        player.update(store.state)
        check("a recording is downloaded the first time it is picked, and plays", wait("the recording", 40) { player.playing == ["purr"] })
        let cached = player.cache.appendingPathComponent("purr-1.mp3")
        check("it is kept in the app's folder", ((try? FileManager.default.attributesOfItem(atPath: cached.path)[.size] as? Int) ?? 0) > 10_000)
        store.settings.mix = [MixEntry(id: "purr"), MixEntry(id: "brown")]
        player.update(store.state)
        check("two sounds at once, one of them made on the Mac", wait("brown noise") { player.playing == ["brown", "purr"] })
        store.settings.mix = [MixEntry(id: "purr"), MixEntry(id: "brown"), MixEntry(id: "white")]
        player.update(store.state)
        check("a third is not played", player.playing.count <= 2)
        store.act("pause")
        player.update(store.state)
        check("a pause stops the sounds", wait("the fade", 5) { player.playing.isEmpty })
        store.act("resume")
        store.act("stop"); store.act("cancel")
        player.stopAll()

        // ---- signing out takes the account's history off this Mac, and it stays on the server
        sync.signOut()
        check("signing out", wait("sign out") { sync.account == nil })
        check("the account's history left this Mac", wait("the doc to empty") { store.state.history.isEmpty })
        check("and is safe on the server", serverTitles().contains("On the other Mac"))

        // ---- and signing back in brings it home
        let since2 = nowMs() - 1000
        sync.sendCode(email)
        _ = wait("the second code") { code(for: email, outbox: outbox, after: since2) != nil }
        if let c2 = code(for: email, outbox: outbox, after: since2) { sync.signIn(email: email, code: c2) }
        check("signing back in", wait("sign in again") { sync.account != nil })
        check("the history comes back", wait("the history") { store.state.history.contains { $0.intention == "On the other Mac" } && store.state.history.contains { $0.intention == "On this Mac" } })
        sync.signOut()
        _ = wait("the last sign out") { sync.account == nil }
    }
}
