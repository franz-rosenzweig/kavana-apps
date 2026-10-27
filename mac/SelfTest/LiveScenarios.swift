import AppKit
import SwiftUI

/// The real app wiring, end to end: the engine, the speed bump, the cat in the corner, the shortcuts, the
/// away prompt, with every window far off the screen, no notifications, and no reading of the real front
/// app or any browser. What the person would press is called the way the buttons call it.
enum LiveScenarios {
    static let MIN = 60_000

    /// A real mouse press on a control of a panel, held for `seconds` (the runloop keeps running while it is
    /// held, and the window's own gesture sees it). `name` is what the view called itself with .probe().
    @discardableResult
    static func press(_ panel: NSWindow?, _ name: String, hold seconds: Double = 0.05) -> Bool {
        guard let panel, let view = panel.contentView, let f = Probe.frames[name] else { print("  (no control called \"\(name)\" found)"); return false }
        // the view lays out from the top left; the window's points count up from the bottom
        let p = NSPoint(x: f.midX, y: view.bounds.height - f.midY)
        func event(_ type: NSEvent.EventType) -> NSEvent? {
            NSEvent.mouseEvent(with: type, location: p, modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime, windowNumber: panel.windowNumber, context: nil, eventNumber: 0, clickCount: 1, pressure: type == .leftMouseUp ? 0 : 1)
        }
        if let e = event(.leftMouseDown) { panel.sendEvent(e) }
        spin(seconds)
        if let e = event(.leftMouseUp) { panel.sendEvent(e) }
        spin(0.1)
        return true
    }

    static func spin(_ s: Double = 0.15) { RunLoop.current.run(until: Date().addingTimeInterval(s)) }

    static func run() throws {
        let app = AppDelegate()
        app.store = try Store()
        let store = app.store!
        store.resetForTest()
        // the real idle time of whoever runs this must not leak in
        var idle = 1_000
        app.start()
        app.away.idleOverride = { idle }
        let fake = ListedApp(bundleID: "test.fake.app", name: "Fake")
        store.settings.blocklist = Blocklist(apps: [fake], sites: ["example.test"])
        spin()

        // ---- shortcuts
        check("shortcuts: start and park are registered", app.hotkeys.isSet("start") && app.hotkeys.isSet("park"))
        check("shortcuts: Yes and Drifted aren't claimed while nothing waits", !app.hotkeys.isSet("yes") && !app.hotkeys.isSet("drifted"))

        // ---- the start shortcut: open, then start
        app.popover.anchor = { NSRect(x: 1000, y: 876, width: 70, height: 24) }
        app.startShortcut()
        spin()
        check("start shortcut: the popover opens", app.popover.isShown)
        Shot.renderPanel(app.popover.panel, name: "live-popover-ready", dark: false)
        app.startShortcut()
        spin()
        check("start shortcut again: a block starts and the popover goes", store.active != nil && !app.popover.isShown)
        check("the kitten is growing", store.active?.kitten == "growing")

        // ---- the speed bump on an app: held Leave anyway
        check("speed bump: this block may be guarded", app.bumpable)
        app.looked(.app(fake), "test.fake.app")
        spin()
        check("speed bump: the card comes up over a listed app", app.bump.isShown && app.bump.model.card?.catName == "Miso")
        check("speed bump: it counts 15 seconds", app.bump.model.graceMs == 15_000)
        Shot.renderPanel(app.bump.panel, name: "live-speedbump", dark: false)
        check("a plain tap on Leave anyway doesn't send the kitten off", press(app.bump.panel, "hold", hold: 0.15) && store.active?.kitten == "growing" && app.bump.isShown)
        check("holding it for a second and a half doesn't either", press(app.bump.panel, "hold", hold: 1.5) && store.active?.kitten == "growing" && app.bump.isShown)
        check("held for two seconds, with a real press, it does", press(app.bump.panel, "hold", hold: 2.4))
        spin()
        check("leave anyway: the kitten wandered off", store.active?.kitten == "gone" && store.active?.goneWhy == "away")
        check("leave anyway: the block keeps going", store.active != nil)
        check("leave anyway: the card is gone", !app.bump.isShown)
        check("leave anyway: the cat says so", app.cat.kind == "gone")
        app.looked(.app(fake), "test.fake.app")
        spin()
        check("leave anyway: the cat doesn't come up again for that block", !app.bump.isShown)
        check("leave anyway: it isn't guarded any more", !app.bumpable)
        store.stop()   // inside ten seconds: taken back
        check("a stop in the first ten seconds takes the block back", store.active == nil && store.state.history.isEmpty)

        // ---- the 15 seconds run out
        store.start(title: "Second block")
        app.looked(.site(domain: "example.test", browser: "Chrome"), "com.google.Chrome")
        spin()
        guard let shown = app.bump.model.card?.shownAt else { check("a listed site brings the card", false); return }
        check("a listed site brings the card", app.bump.isShown && app.bump.model.card?.target.label == "example.test")
        app.second(shown + 14_000)
        check("14 seconds in the card is still there", app.bump.isShown && store.active?.kitten == "growing")
        app.second(shown + 15_000)
        spin()
        check("15 seconds: the kitten wandered off", store.active?.kitten == "gone" && store.active?.goneWhy == "away")
        check("15 seconds: the card is gone and the cat says so", !app.bump.isShown && app.cat.kind == "gone")
        Shot.renderPanel(app.cat.panel, name: "live-wandered", dark: false)
        store.act("stop"); store.act("tick", now: nowMs() + 10 * MIN)
        if let a = store.awaiting { store.act("outcome", ["id": a.id, "outcome": nil as Any? ?? NSNull()]) }

        // ---- Back to work
        store.resetForTest()
        app.speed = SpeedBump()
        store.start(title: "Third block")
        app.looked(nil, "test.writer")                  // where they were
        app.looked(.app(fake), "test.fake.app")
        spin()
        check("back to work: the card is up", app.bump.isShown)
        check("back to work: a real click on the button", press(app.bump.panel, "back"))
        spin()
        check("back to work: the card goes, the kitten stays", !app.bump.isShown && store.active?.kitten == "growing")
        check("back to work: it remembers where they were", app.speed.previous == "test.writer")
        app.looked(.app(fake), "test.fake.app")
        check("back to work: no second card while the app stays in front", !app.bump.isShown)
        app.looked(nil, "test.writer")
        app.looked(.app(fake), "test.fake.app")
        check("back to work: a new card after leaving and coming back", app.bump.isShown)
        app.looked(nil, "test.writer")
        check("leaving the listed app takes the card away", !app.bump.isShown && store.active?.kitten == "growing")

        // ---- a pause: no bump
        store.pause()
        app.looked(.app(fake), "test.fake.app")
        check("a paused block isn't guarded", !app.bump.isShown && !app.bumpable)
        store.resume()
        store.act("stop"); store.act("tick", now: nowMs() + 10 * MIN)
        if let a = store.awaiting { store.act("outcome", ["id": a.id, "outcome": nil as Any? ?? NSNull()]) }

        // ---- a check-in
        store.resetForTest()
        let now = nowMs()
        store.act("start", ["intention": "Draft the newsletter", "minutes": 25, "style": "ask", "cat": "miso"], now: now - 16 * MIN)
        store.act("tick", now: now)
        spin()
        check("check-in: the cat comes up", app.cat.kind == "checkin")
        check("check-in: Yes and Drifted are claimed while it waits", app.hotkeys.isSet("yes") && app.hotkeys.isSet("drifted"))
        Shot.renderPanel(app.cat.panel, name: "live-checkin", dark: true)
        check("check-in: a real click on Yes", press(app.cat.panel, "cat.Yes"))
        spin()
        check("check-in: Yes is answered", store.active?.pending == nil)
        check("check-in: the cat goes and the keys are free again", app.cat.kind == nil && !app.hotkeys.isSet("yes") && !app.hotkeys.isSet("drifted"))
        check("check-in: the engine counted it", store.state.view.active?.drifts == 0)
        store.act("tick", now: now + 15 * MIN)   // the second check-in, at 30 minutes of a 25-minute block, doesn't exist: the block ends
        store.act("stop"); store.act("tick", now: now + 30 * MIN)
        if let a = store.awaiting { store.act("outcome", ["id": a.id, "outcome": nil as Any? ?? NSNull()]) }

        store.resetForTest()
        store.act("start", ["intention": "Draft", "minutes": 60, "style": "ask", "cat": "miso"], now: now - 16 * MIN)
        store.act("tick", now: now)
        spin()
        app.answer("drifted")
        spin()
        check("drifted: counted", store.active?.drifts == 1)
        check("drifted: the cat says no worries", app.cat.kind == "say")
        app.cat.hide()

        // ---- the end of a block
        store.resetForTest()
        store.act("start", ["intention": "Spanish practice", "minutes": 25, "style": "ask", "cat": "miso"], now: now - 26 * MIN)
        store.act("tick", now: now)
        spin()
        check("the end: the kitten came home", store.awaiting?.kitten == "home")
        check("the end: the cat asks how it went", app.cat.kind == "end")
        Shot.renderPanel(app.cat.panel, name: "live-end", dark: false)
        app.cat.hide()
        let st = StatusController(store: store)
        if case .home = st.menuState(now: now) { check("the end: the menu bar says Home", true) } else { check("the end: the menu bar says Home", false) }
        store.outcome(store.awaiting!.id, "done")
        check("the end: filed as Done", store.awaiting == nil && store.state.history.first?.outcome == "done")
        check("the end: a break is offered", store.state.view.breakOffer != nil)
        Shot.renderPanel(nil, name: "x", dark: false)
        store.stopBreak()

        // ---- park a thought
        app.park.toggle()
        spin()
        check("park: the field opens", app.park.isShown)
        Shot.renderPanel(app.park.panel, name: "live-park", dark: false)
        check("park: a thought goes to Later", store.park("Find the photos from March") && store.state.view.later.contains { $0.text == "Find the photos from March" })
        check("park: an empty thought doesn't", !store.park("   "))
        app.park.hide()
        check("park: the field closes", !app.park.isShown)

        // ---- away from the Mac
        store.resetForTest()
        store.act("start", ["intention": "Read chapter 4", "minutes": 50, "style": "ask", "cat": "miso"], now: nowMs() - 20 * MIN)
        idle = 1_000
        let t0 = nowMs()
        app.away.sample(now: t0)
        idle = 3 * MIN
        app.away.sample(now: t0 + 3 * MIN)
        check("away: not asked while away", store.awayPrompt == nil)
        idle = 800
        app.away.sample(now: t0 + 12 * MIN)
        check("away: asked when back", store.awayPrompt != nil)
        check("away: about 12 minutes", (store.awayPrompt?.minutes ?? 0) >= 9 && (store.awayPrompt?.minutes ?? 99) <= 12)
        check("away: the kitten is still here (away isn't drifting)", store.active?.kitten == "growing")
        spin()
        check("away: the popover opens by itself", app.popover.isShown)
        Shot.renderPanel(app.popover.panel, name: "live-popover-away", dark: false)
        store.countAway()
        check("away: Count them clears it", store.awayPrompt == nil)
        app.popover.hide()

        // ---- the popover while focusing
        Shot.renderPanel(nil, name: "x", dark: false)
        app.popover.show(key: false)
        spin()
        Shot.renderPanel(app.popover.panel, name: "live-popover-focus", dark: true)
        check("popover: it fits its card", app.popover.panel.frame.width == 340)
        app.popover.hide()
        check("popover: it hides", !app.popover.isShown)

        // ---- the settings window opens
        app.openSettings("blocklist")
        spin()
        check("settings: the window is made", NSApp.windows.contains { $0.title == "Chataltul Settings" })
        for w in NSApp.windows where w.title == "Chataltul Settings" { w.orderOut(nil) }
    }
}
