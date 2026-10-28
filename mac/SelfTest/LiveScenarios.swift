import AppKit
import SwiftUI
import UniformTypeIdentifiers

/// The real app wiring, end to end: the engine, the speed bump, the cat in the corner, the shortcuts, the
/// away prompt, with every window far off the screen, no notifications, and no reading of the real front
/// app or any browser. What the person would press is called the way the buttons call it.
enum LiveScenarios {
    static let MIN = 60_000

    /// A real mouse press on a control of a panel, held for `seconds` (the runloop keeps running while it is
    /// held, and the window's own gesture sees it). `name` is what the view called itself with .probe().
    @discardableResult
    static func press(_ panel: NSWindow?, _ name: String, hold seconds: Double = 0.05) -> Bool {
        guard let panel, let view = panel.contentView, let f = Probe.frames[name] else { print("  (no control called \"\(name)\" found; known: \(Probe.frames.keys.sorted()))"); return false }
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
        let hitApp = GuardHit(list: store.settings.blocklist.lists[0], target: .app(fake))
        let hitSite = GuardHit(list: store.settings.blocklist.lists[0], target: .site(domain: "example.test", browser: "Chrome"))
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
        app.looked(hitApp, "test.fake.app")
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
        app.looked(hitApp, "test.fake.app")
        spin()
        check("leave anyway: the cat doesn't come up again for that block", !app.bump.isShown)
        check("leave anyway: it isn't guarded any more", !app.bumpable)
        store.resetForTest()   // (the checks above take more than ten seconds on a slow run, so start a block now)
        store.start(title: "Cancel soon")
        check("a stop in the first ten seconds takes the block back", store.stop() && store.active == nil && store.state.history.isEmpty)

        // ---- the 15 seconds run out
        store.start(title: "Second block")
        app.looked(hitSite, "com.google.Chrome")
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

        // ---- a site that only a ready-made list names
        store.resetForTest()
        app.speed = SpeedBump()
        app.looked(nil, "test.writer")
        let listedBefore = store.settings.blocklist
        store.categories.install(["listed.test"], source: "ut1-dating")
        store.settings.blocklist.lists[0].categories = ["dating"]
        store.start(title: "Ready-made")
        let viaList = store.settings.blocklist.hit(url: "https://www.m.listed.test/page?x=1", browser: "Chrome", matcher: store.categories)
        check("a ready-made list finds a site no one typed", viaList?.target == .site(domain: "listed.test", browser: "Chrome"))
        app.looked(viaList, "com.google.Chrome")
        spin()
        check("a site only a ready-made list names brings the card", app.bump.isShown && app.bump.model.card?.target.label == "listed.test")
        app.looked(nil, "test.writer")
        spin()
        check("leaving it takes the card away", !app.bump.isShown)
        store.settings.blocklist.lists[0].allow = ["listed.test"]
        check("allowed, it brings nothing", store.settings.blocklist.hit(url: "https://m.listed.test/", browser: "Chrome", matcher: store.categories) == nil)
        store.settings.blocklist = listedBefore
        store.categories.remove(sources: ["ut1-dating"])
        check("taken off the list, nothing is found", store.settings.blocklist.hit(url: "https://listed.test/", browser: "Chrome", matcher: store.categories) == nil)
        let readyListID = store.settings.blocklist.lists[0].id
        store.setCategory("social", on: true, in: readyListID)
        check("switching a ready-made list on puts it on the list", store.settings.blocklist.lists[0].categories == ["social"])
        store.setCategory("social", on: true, in: readyListID)
        check("twice doesn't put it there twice", store.settings.blocklist.lists[0].categories == ["social"])
        store.setCategory("social", on: false, in: readyListID)
        check("switching it off takes it away", store.settings.blocklist.lists[0].categories.isEmpty)
        check("a link that isn't https is refused", !store.addCustomList(name: "x", link: "http://lists.example.net/x.txt", to: readyListID) && store.settings.customLists.isEmpty)
        check("an https link is added and switched on", store.addCustomList(name: "Mine", link: "https://lists.example.net/x.txt", to: readyListID) && store.settings.customLists.count == 1 && store.settings.blocklist.lists[0].categories.count == 1)
        check("and it is kept in the settings file", store.settingsStore.read().customLists.count == 1)
        store.removeCustomList(store.settings.customLists[0].id)
        check("taking it away takes it off the list too", store.settings.customLists.isEmpty && store.settings.blocklist.lists[0].categories.isEmpty)
        store.settings.blocklist = listedBefore

        // ---- Back to work
        store.resetForTest()
        app.speed = SpeedBump()
        store.start(title: "Third block")
        app.looked(nil, "test.writer")                  // where they were
        app.looked(hitApp, "test.fake.app")
        spin()
        check("back to work: the card is up", app.bump.isShown)
        check("back to work: a real click on the button", press(app.bump.panel, "back"))
        spin()
        check("back to work: the card goes, the kitten stays", !app.bump.isShown && store.active?.kitten == "growing")
        check("back to work: it remembers where they were", app.speed.previous == "test.writer")
        app.looked(hitApp, "test.fake.app")
        check("back to work: no second card while the app stays in front", !app.bump.isShown)
        app.looked(nil, "test.writer")
        app.looked(hitApp, "test.fake.app")
        check("back to work: a new card after leaving and coming back", app.bump.isShown)
        app.looked(nil, "test.writer")
        check("leaving the listed app takes the card away", !app.bump.isShown && store.active?.kitten == "growing")

        // ---- a pause: no bump
        store.pause()
        app.looked(hitApp, "test.fake.app")
        check("a paused block isn't guarded", !app.bump.isShown && !app.bumpable)
        store.resume()
        store.act("stop"); store.act("tick", now: nowMs() + 10 * MIN)
        if let a = store.awaiting { store.act("outcome", ["id": a.id, "outcome": nil as Any? ?? NSNull()]) }

        // ---- a check-in
        app.looked(nil, "test.writer")   // the listed app is no longer in front
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
        check("drifted: the cat asks what pulled them away", app.cat.kind == "reason")
        Shot.renderPanel(app.cat.panel, name: "live-reason", dark: false)
        check("drifted: a real tap on a reason notes it", press(app.cat.panel, "cat.The web"))
        spin()
        store.act("stop")
        if let a = store.awaiting { store.act("outcome", ["id": a.id, "outcome": nil as Any? ?? NSNull()]) }
        check("drifted: the reason is kept with the check-in", store.state.history.first?.whys == ["web"])
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


        // ================================================================ stage 2, live
        let listID = store.settings.blocklist.lists[0].id
        func setList(_ mode: GuardMode, when: GuardWhen = GuardWhen(), allowance: Int = 0) {
            var l = store.settings.blocklist.lists[0]; l.mode = mode; l.when = when; l.allowanceMin = allowance
            store.settings.blocklist = Blocklist(lists: [l])
        }
        func hitNow() -> GuardHit { GuardHit(list: store.settings.blocklist.lists[0], target: .app(fake)) }
        func fresh() { store.resetForTest(); app.speed = SpeedBump(); app.other = OtherGuard(); app.bump.hide(); app.cat.hide(); app.breakScreen.hide(); app.looked(nil, "test.writer") }

        // ---- wait it out
        fresh(); setList(.waitItOut)
        store.act("start", ["intention": "Wait test", "minutes": 25], now: nowMs() - 60_000)
        app.looked(hitNow(), "test.fake.app"); spin()
        check("wait it out: the card comes up over a listed app", app.bump.isShown && app.bump.model.other?.kind == .waitItOut)
        check("wait it out: the kitten isn't at risk", store.active?.kitten == "growing" && app.bump.model.card == nil)
        Shot.renderPanel(app.bump.panel, name: "live-wait", dark: false)
        let wshown = app.bump.model.other?.shownAt ?? 0
        app.second(wshown + 29_000)
        check("wait it out: still waiting at 29 seconds", app.bump.model.other != nil)
        app.second(wshown + 30_000); spin()
        check("wait it out: at 30 seconds it opens", !app.bump.isShown && app.bump.model.other == nil)
        app.looked(hitNow(), "test.fake.app")
        check("wait it out: the visit that waited may stay", !app.bump.isShown)
        check("wait it out: and nothing happened to the kitten", store.active?.kitten == "growing")
        fresh(); setList(.waitItOut)
        store.act("start", ["intention": "Wait test 2", "minutes": 25], now: nowMs() - 60_000)
        app.looked(nil, "test.writer"); app.looked(hitNow(), "test.fake.app"); spin()
        check("wait it out: a real click on Back to work", press(app.bump.panel, "back"))
        check("wait it out: the card goes", !app.bump.isShown)

        // ---- locked
        fresh(); setList(.locked)
        store.act("start", ["intention": "Locked test", "minutes": 25], now: nowMs() - 60_000)
        check("locked: the block knows it is locked", store.lockedUntil != nil)
        app.looked(hitNow(), "test.fake.app"); spin()
        check("locked: the card comes up", app.bump.model.other?.kind == .locked)
        Shot.renderPanel(app.bump.panel, name: "live-locked", dark: true)
        check("locked: the block can't be stopped", store.stop() == false && store.active != nil)
        check("locked: Chataltul won't quit", app.applicationShouldTerminate(NSApp) == .terminateCancel)
        app.second(nowMs() + 60 * MIN)
        check("locked: it never opens by itself", app.bump.isShown && app.bump.model.other?.kind == .locked)
        check("locked: Back to work is still there", press(app.bump.panel, "back") && !app.bump.isShown)
        fresh(); setList(.locked)
        store.act("start", ["intention": "Cancel test", "minutes": 25], now: nowMs() - 2_000)
        check("locked: in the first ten seconds the block can still be taken back", store.lockedUntil == nil && store.stop() && store.active == nil)
        fresh(); setList(.speedBump)
        store.act("start", ["intention": "Not locked", "minutes": 25], now: nowMs() - 60_000)
        check("not locked: the block can be stopped and Chataltul can quit", store.lockedUntil == nil && app.applicationShouldTerminate(NSApp) == .terminateNow)
        store.act("stop")
        if let a = store.awaiting { store.act("outcome", ["id": a.id, "outcome": nil as Any? ?? NSNull()]) }

        // ---- a window outside blocks, and an allowance
        fresh(); setList(.waitItOut, when: GuardWhen(.always), allowance: 1)
        app.looked(hitNow(), "test.fake.app"); spin()
        check("allowance: inside it, nothing stands in the way", !app.bump.isShown)
        for _ in 0..<62 { app.second(nowMs()) }
        check("allowance: the seconds on the list are counted", store.usedMs(listID) >= 60_000)
        spin()
        check("allowance: used up, it stops them for the day", app.bump.model.other?.kind == .usedUp)
        Shot.renderPanel(app.bump.panel, name: "live-usedup", dark: false)
        app.bump.hide(); app.other = OtherGuard()
        app.looked(nil, "test.writer")
        setList(.speedBump, when: GuardWhen(.blocks))
        app.looked(hitNow(), "test.fake.app")
        check("during blocks only, with no block: nothing", !app.bump.isShown)

        // ---- breaks
        fresh(); store.settings.breaks = BreakSettings()
        store.startBreak(10); spin()
        check("break: a full-screen break comes up", app.breakScreen.isShown && store.onBreak?.minutes == 10)
        Shot.renderPanel(app.breakScreen.primaryPanel, name: "live-break", dark: true)
        let pstart = nowMs()
        check("break: a real press on Postpone", press(app.breakScreen.primaryPanel, "postpone"))
        spin()
        check("break: postponed, it goes", store.onBreak == nil && !app.breakScreen.isShown && store.postponed != nil)
        check("break: and comes back in 5 minutes", (store.postponed?.at ?? 0) >= pstart + 5 * MIN - 2_000)
        app.second(pstart + 5 * MIN + 2_000); spin()
        check("break: back again after the postponement", store.onBreak != nil && app.breakScreen.isShown)
        check("break: a real press on Skip", press(app.breakScreen.primaryPanel, "skip"))
        spin()
        check("break: skipped, it ends", store.onBreak == nil && !app.breakScreen.isShown)
        store.settings.breaks.strict = true
        store.startBreak(10); spin()
        check("strict break: no way to skip or postpone", app.breakScreen.model?.strict == true)
        store.stopBreak()
        store.settings.breaks.strict = false
        store.settings.breaks.fullScreen = false
        store.startBreak(5); spin()
        check("full-screen breaks off: no screen", !app.breakScreen.isShown && store.onBreak != nil)
        store.stopBreak(); store.settings.breaks.fullScreen = true
        let bn = nowMs()
        store.startBreak(17)
        check("a 17-minute break: the Mac keeps its own end", store.onBreak?.minutes == 17)
        store.act("tick", now: bn + 16 * MIN)
        check("a 17-minute break: it's still on after the engine's 15", store.onBreak != nil)
        store.stopBreak()
        fresh(); store.settings.breaks.start = .auto
        store.act("start", ["intention": "Auto", "minutes": 25, "style": "ask", "cat": "miso"], now: nowMs() - 26 * MIN)
        store.act("tick", now: nowMs()); spin()
        check("a break that starts by itself when the kitten is home", store.awaiting?.kitten == "home" && store.onBreak != nil)
        store.stopBreak(); store.settings.breaks.start = .offer

        // ---- eye breaks
        fresh()
        store.setEngine(["interval": 0])   // no check-ins to get in the way
        store.act("start", ["intention": "Eyes", "minutes": 60, "style": "ask", "cat": "miso"], now: nowMs() - 21 * MIN)
        app.eye.blockStarted(at: nowMs() - 21 * MIN)
        Busy.override = { true }
        app.second(nowMs())
        check("eye break: it waits while the Mac is busy with a call", app.cat.kind != "eye")
        Busy.override = { false }
        app.second(nowMs()); spin()
        check("eye break: 21 minutes in, the card comes up", app.cat.kind == "eye")
        Shot.renderPanel(app.cat.panel, name: "live-eye", dark: false)
        check("eye break: a real click on Skip", press(app.cat.panel, "cat.Skip"))
        spin()
        check("eye break: skipped, the next is a full interval away", app.cat.kind == nil && (app.eye.nextAt ?? 0) > nowMs() + 19 * MIN)
        Busy.override = nil
        store.settings.breaks.eyeBreaks = false
        app.eye.blockStarted(at: nowMs() - 60 * MIN)
        app.second(nowMs())
        check("eye breaks off: none", app.cat.kind != "eye")
        store.settings.breaks.eyeBreaks = true
        store.setEngine(["interval": 15])

        // ---- check-ins hold during a call
        fresh()
        store.setEngine(["interval": 15])
        Busy.override = { true }
        store.act("start", ["intention": "Call", "minutes": 60, "style": "ask", "cat": "miso"], now: nowMs() - 16 * MIN)
        store.act("tick", now: nowMs()); spin()
        check("a check-in holds during a call", store.active?.pending == 1 && app.cat.kind != "checkin")
        Busy.override = { false }
        app.second(nowMs()); spin()
        check("and comes when the Mac is free", app.cat.kind == "checkin")
        Busy.override = nil
        app.answer("yes")
        store.act("stop"); store.act("tick", now: nowMs() + 70 * MIN)
        if let a = store.awaiting { store.act("outcome", ["id": a.id, "outcome": nil as Any? ?? NSNull()]) }

        // ---- the cycle
        fresh()
        store.apply(.deep)
        check("cycle: Deep work is 90 and 20", store.settings.focusMinutes == 90 && store.settings.cycle.shortBreak == 20)
        store.apply(.fiftyTwo)
        check("cycle: 52 and 17", store.settings.focusMinutes == 52 && store.settings.cycle.shortBreak == 17)
        store.apply(.flow)
        store.start(title: "Flow", stopwatch: store.preset.stopwatch)
        check("cycle: Flowtime starts a stopwatch", store.active?.stopwatch == true)
        check("cycle: and the break is by feel", store.breakAfter(store.active) >= 5)
        store.act("cancel", now: nowMs() + 1)
        store.apply(.pomodoro)

        // ---- the app window
        app.openWindow("today"); spin(0.6)
        check("window: it opens", AppWindow.shared.isShown)
        Shot.renderPanel(AppWindow.shared.windowForTest, name: "live-window-today", dark: false)
        AppWindow.shared.nav.section = .blocklist; spin(0.4)
        Shot.renderPanel(AppWindow.shared.windowForTest, name: "live-window-blocklist", dark: true)
        check("window: pages change", AppWindow.shared.nav.section == .blocklist)
        AppWindow.shared.windowForTest?.orderOut(nil)


        // ================================================================ stage 3, live
        // ---- where it went, recorded from the front app
        fresh()
        store.settings.recordTiming = true
        store.clearTiming()
        var frontNow: (id: String, name: String)? = ("com.apple.TextEdit", "Writer")
        app.timingFront = { frontNow }
        store.act("start", ["intention": "Timing test", "minutes": 25, "style": "ask", "cat": "miso"], now: nowMs() - 60_000)
        let blockID = store.active?.id ?? ""
        for _ in 0..<5 { app.second(nowMs()) }
        frontNow = ("com.apple.mail", "Mail")
        for _ in 0..<2 { app.second(nowMs()) }
        check("timing: the seconds go to the app in front", store.wentRows(for: [blockID]).map { $0.name }.prefix(2) == ["Writer", "Mail"])
        frontNow = nil
        app.second(nowMs())
        check("timing: Chataltul's own windows aren't a place the time went", store.wentRows(for: [blockID]).count == 2)
        frontNow = ("com.apple.TextEdit", "Writer")
        let idleBefore = app.away.idleOverride
        app.away.idleOverride = { 5 * MIN }
        let before = store.timing.blocks[blockID]?.totalMs ?? 0
        app.second(nowMs())
        check("timing: an idle Mac isn't counted", (store.timing.blocks[blockID]?.totalMs ?? 0) == before)
        app.away.idleOverride = idleBefore
        store.act("pause")
        let paused = store.timing.blocks[blockID]?.totalMs ?? 0
        app.second(nowMs())
        check("timing: a pause isn't counted", (store.timing.blocks[blockID]?.totalMs ?? 0) == paused)
        store.act("resume")
        store.settings.recordTiming = false
        app.second(nowMs())
        check("timing: switched off, nothing is remembered", (store.timing.blocks[blockID]?.totalMs ?? 0) == paused)
        store.settings.recordTiming = true
        app.timingFront = nil
        store.saveTiming()
        check("timing: it is kept in a file on this Mac", FileManager.default.fileExists(atPath: store.folder.appendingPathComponent("timing.json").path))
        let raw = (try? String(contentsOf: store.folder.appendingPathComponent("timing.json"), encoding: .utf8)) ?? ""
        check("timing: it has app names and seconds, no addresses", !raw.contains("http") && raw.contains("Writer"))
        check("timing: and it is not in the doc that sync carries", !store.docJSON().contains("Writer"))
        store.clearTiming()
        check("timing: forgotten when asked", store.timing.isEmpty && !FileManager.default.fileExists(atPath: store.folder.appendingPathComponent("timing.json").path))
        store.act("stop"); store.act("tick", now: nowMs() + 70 * MIN)
        if let a = store.awaiting { store.act("outcome", ["id": a.id, "outcome": nil as Any? ?? NSNull()]) }

        // ---- export
        let exportDir = FileManager.default.temporaryDirectory.appendingPathComponent("chataltul-export-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: exportDir, withIntermediateDirectories: true)
        Exporter.testFolder = exportDir
        let saved = Exporter.save(Export.blocksCSV(store.state.history, catName: { store.cat($0).name }), name: "blocks.csv", type: .commaSeparatedText)
        check("export: a file is written", saved != nil && FileManager.default.fileExists(atPath: saved!.path))
        check("export: it is a spreadsheet with a header", ((try? String(contentsOf: saved!, encoding: .utf8)) ?? "").hasPrefix("start,minutes,title"))
        Exporter.testFolder = nil
        try? FileManager.default.removeItem(at: exportDir)

        // ---- Focus Shortcuts when a block starts and ends
        fresh()
        var ran: [String] = []
        ShortcutRunner.onRun = { ran.append($0) }
        store.settings.shortcutStart = "Focus On"; store.settings.shortcutEnd = "Focus Off"
        store.act("start", ["intention": "Shortcut test", "minutes": 25, "style": "ask", "cat": "miso"], now: nowMs() - 60_000)
        check("shortcuts: one runs when a block starts", ran == ["Focus On"])
        store.act("stop"); store.act("tick", now: nowMs() + 70 * MIN)
        check("shortcuts: and one when it ends", ran == ["Focus On", "Focus Off"])
        store.settings.shortcutStart = ""; store.settings.shortcutEnd = ""
        ShortcutRunner.onRun = nil
        if let a = store.awaiting { store.act("outcome", ["id": a.id, "outcome": nil as Any? ?? NSNull()]) }

        // ---- the popover's week button opens the window on the week
        app.openWindow("today", span: .week); spin(0.5)
        check("week: the window opens on the week", AppWindow.shared.nav.openSpan == .week && AppWindow.shared.isShown)
        Shot.renderPanel(AppWindow.shared.windowForTest, name: "live-window-week", dark: false)
        AppWindow.shared.windowForTest?.orderOut(nil)

        // ---- the model on this Mac, if there is one (not checked, only tried)
        print("  (the model on this Mac is \(OnDevice.available ? "there" : "not there"))")
        if OnDevice.available {
            var reply: String??
            Task { reply = .some(await OnDevice.answer("Which day was my best day?", facts: "Period: This week\nBest day: Tuesday, 3 h 10 min\nFocused: 8 h 40 min over 6 blocks")) }
            _ = SyncScenarios.wait("the model", 60) { reply != nil }
            print("  (a trial question to the model: \(String(describing: reply ?? nil)))")
        }

        // ---- the settings window opens
        app.openSettings("blocklist")
        spin()
        check("settings: the window is made", NSApp.windows.contains { $0.title == "Chataltul Settings" })
        for w in NSApp.windows where w.title == "Chataltul Settings" { w.orderOut(nil) }
    }
}
