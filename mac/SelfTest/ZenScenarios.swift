import AppKit
import ApplicationServices

/// Reading the page in Zen through Accessibility, against a Zen of its own: selftest.sh zen opens one in the
/// background with a profile of its own, on two pages from a small web server on this Mac, and gives its process
/// id. The person's own Zen is never read.
enum ZenScenarios {
    static func run(pid: pid_t, port: Int) throws {
        guard AXPage.allowed() else {
            print("SKIP zen: whatever runs this test isn't allowed under Privacy & Security → Accessibility")
            return
        }
        let a = "http://127.0.0.1:\(port)/a.html", b = "http://127.0.0.1:\(port)/b.html"
        var read: String?
        // Firefox's engine builds its accessibility tree when it is first asked, so the first looks come back empty
        let woke = SyncScenarios.wait("Zen's first page", 30) { read = AXPage.address(pid: pid); return read == a }
        check("zen: the page in front is read, from Zen's own start", woke)
        check("zen: the whole address, not the address bar's short one", read == a)

        let t0 = Date()
        for _ in 0..<10 { _ = AXPage.address(pid: pid) }
        let each = Date().timeIntervalSince(t0) / 10
        print("  (a look took \(Int(each * 1000)) ms)")
        check("zen: a look takes well under a second", each < 0.3)

        check("zen: another tab, and it reads that one", press(tab: 1, pid: pid) && SyncScenarios.wait("the second tab", 15) { AXPage.address(pid: pid) == b })
        check("zen: and back", press(tab: 0, pid: pid) && SyncScenarios.wait("the first tab", 15) { AXPage.address(pid: pid) == a })

        // something typed in the address bar, before Return, isn't the page. Zen builds the bar anew when it
        // takes the keyboard, so the bar is found again after it's focused.
        func bar() -> AXPage? { find(pid: pid, where: { $0.value("AXDOMIdentifier") as? String == "urlbar-input" }) }
        var typed = false
        for _ in 0..<4 where !typed {
            guard let b = bar() else { break }
            _ = AXUIElementSetAttributeValue(b.element, kAXFocusedAttribute as CFString, kCFBooleanTrue)
            LiveScenarios.spin(0.4)
            guard let focused = bar() else { continue }
            _ = AXUIElementSetAttributeValue(focused.element, kAXValueAttribute as CFString, "youtube.com" as CFString)
            typed = SyncScenarios.wait("the address bar to say youtube.com", 2) { bar()?.value(kAXValueAttribute) as? String == "youtube.com" }
        }
        check("zen: youtube.com typed in the address bar", typed)
        check("zen: and it's still the page that's open", AXPage.address(pid: pid) == a)

        // the app's own pieces
        let zen = ReadableBrowser.with(bundleID: "app.zen-browser.zen")
        check("zen: Chataltul reads Zen the Accessibility way", zen?.reader == .accessibility)
        let watcher = Watcher(store: try Store())
        check("zen: and knows it is allowed", zen.map { watcher.state(of: $0) } == .allowed)
        let local = Blocklist(lists: [GuardList(name: "Local", sites: ["127.0.0.1"])])
        check("zen: a site on a list is caught in Zen", local.hit(url: AXPage.address(pid: pid) ?? "", browser: "Zen")?.target == .site(domain: "127.0.0.1", browser: "Zen"))
        let work = Blocklist(lists: [GuardList(name: "Work", sites: ["github.com"], kind: .only)])
        check("zen: and a site that isn't on a list of what's allowed", work.outside(url: AXPage.address(pid: pid) ?? "", browser: "Zen", bundleID: "app.zen-browser.zen")?.list.name == "Work")
    }

    /// The first element of the front window that fits.
    static func find(pid: pid_t, where fits: (AXPage) -> Bool) -> AXPage? {
        let app = AXPage(AXUIElementCreateApplication(pid))
        guard let w = app.element(kAXFocusedWindowAttribute) ?? app.element(kAXMainWindowAttribute) else { return nil }
        var queue = [w], i = 0
        while i < queue.count, i < 5000 {
            let n = queue[i]; i += 1
            if fits(n) { return n }
            if n.role != PageFinder.webArea { queue.append(contentsOf: n.children) }
        }
        return nil
    }

    /// Presses a tab in Zen's sidebar, the way a click would.
    static func press(tab i: Int, pid: pid_t) -> Bool {
        var tabs: [AXPage] = []
        _ = find(pid: pid) { n in
            if n.value(kAXSubroleAttribute) as? String == "AXTabButton" { tabs.append(n) }
            return false
        }
        guard i < tabs.count else { print("  (Zen has \(tabs.count) tabs)"); return false }
        return AXUIElementPerformAction(tabs[i].element, kAXPressAction as CFString) == .success
    }
}
