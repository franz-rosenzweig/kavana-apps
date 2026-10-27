import AppKit
import Combine
import SwiftUI

extension Notification.Name {
    /// The popover opened to start a block: put the cursor in the title.
    static let focusTitle = Notification.Name("xyz.tiresia.chataltul.focusTitle")
}

/// Puts the pieces together: the menu bar item and popover, the cat, the speed bump, the shortcuts.
final class AppDelegate: NSObject, NSApplicationDelegate {
    var store: Store!
    var status: StatusController!
    var popover: PopoverController!
    var park: ParkController!
    var hotkeys: Hotkeys!
    var watcher: Watcher!
    var away: AwayMonitor!
    var notifier: Notifier!
    var sound: SoundPlayer!
    var sync: SyncController!
    let cat = CatController()
    let bump = BumpController()
    var speed = SpeedBump()
    var other = OtherGuard()
    let breakScreen = BreakController()
    var eye = EyeBreakTimer()
    private var eyeBlockID: String?
    private var usageSaved = 0
    /// What is in front now, if it is on a list.
    private(set) var currentHit: GuardHit?
    private var currentBundle: String?
    private var shownCheckins = Set<String>()
    private var seconds = 0
    private var cancellables = Set<AnyCancellable>()

    func applicationDidFinishLaunching(_ n: Notification) {
        NSApp.setActivationPolicy(.accessory)
        installMainMenu()
        do { store = try Store() } catch {
            let a = NSAlert()
            a.messageText = "Chataltul can't start"
            a.informativeText = "\(error)"
            a.runModal()
            NSApp.terminate(nil)
            return
        }
        start()
    }

    /// Everything after the store exists. Tests call this after setting up their own folder.
    func start() {
        hotkeys = Hotkeys()
        notifier = Notifier(store: store)
        sound = SoundPlayer(store: store)
        sync = SyncController(store: store)
        watcher = Watcher(store: store)
        away = AwayMonitor(store: store)
        park = ParkController(store: store)

        var actions = PopoverActions()
        actions.openSettings = { [weak self] pane in self?.openSettings(pane) }
        actions.openCafe = { [weak self] in self?.openCafe() }
        actions.park = { [weak self] in self?.popover.hide(); self?.park.show() }
        actions.quit = { NSApp.terminate(nil) }
        popover = PopoverController(store: store, actions: actions)
        status = StatusController(store: store)
        popover.anchor = { [weak self] in self?.status.buttonFrameOnScreen }
        status.onClick = { [weak self] in self?.popover.toggle(); self?.focusTitleSoon() }
        status.onMenu = { [weak self] in self?.statusMenu() ?? NSMenu() }

        bump.model.backToWork = { [weak self] in self?.apply(self?.speed.backToWork() ?? []) }
        bump.model.backToWorkOther = { [weak self] in self?.applyOther(self?.other.backToWork() ?? []) }
        bump.model.leaveHeld = { [weak self] in self?.apply(self?.speed.leaveHeld(now: nowMs()) ?? []) }
        bump.model.editList = { [weak self] in
            // the card can't be left by clicking elsewhere, so the list opens beside it
            self?.openSettings("blocklist")
        }
        watcher.onFront = { [weak self] hit, bid in self?.looked(hit, bid) }
        watcher.onNeedBrowserAccess = { [weak self] browser in self?.askBrowserAccess(browser) }
        notifier.onAnswer = { [weak self] id, n, a in self?.answerFromNotification(id, n, a) }
        notifier.onOpen = { [weak self] in self?.popover.show(key: true) }

        store.onDue = { [weak self] due in self?.deliver(due) }
        store.afterChange.append { [weak self] st in self?.changed(st) }
        store.onSecond.append { [weak self] now in self?.second(now) }
        store.$awayPrompt.sink { [weak self] p in
            guard let self, p != nil else { return }
            self.popover.show(key: false)
        }.store(in: &cancellables)
        store.changed = { [weak self] in self?.sync.queue() }

        registerShortcuts()
        store.$settings.removeDuplicates().sink { [weak self] _ in DispatchQueue.main.async { self?.registerShortcuts() } }.store(in: &cancellables)
        changed(store.state)
        store.startClock()
        sync.opened()
    }

    // ---------------------------------------------------------------- shortcuts

    private var wired: (KeyCombo, KeyCombo, KeyCombo, KeyCombo)?

    func registerShortcuts() {
        let s = store.settings
        let now = (s.startKey, s.parkKey, s.yesKey, s.driftedKey)
        if let w = wired, w == now { return }
        wired = now
        hotkeys.set("start", s.startKey) { [weak self] in self?.startShortcut() }
        hotkeys.set("park", s.parkKey) { [weak self] in self?.park.toggle() }
        registerCheckinKeys()
    }

    /// Yes and Drifted are only claimed while a check-in is waiting, so ⌃⌥Y and ⌃⌥D are free in other apps the rest of the time.
    private func registerCheckinKeys() {
        let s = store.settings
        if store.active?.pending != nil {
            hotkeys.set("yes", s.yesKey) { [weak self] in self?.answer("yes") }
            hotkeys.set("drifted", s.driftedKey) { [weak self] in self?.answer("drifted") }
        } else {
            hotkeys.clear("yes"); hotkeys.clear("drifted")
        }
    }

    /// ⌃⌥Space: open the popover with the cursor in the title; pressed again, start the block.
    func startShortcut() {
        if popover.isShown, store.active == nil, store.awaiting == nil, store.onBreak == nil, !park.isShown {
            store.start(title: "")
            popover.hide()
            return
        }
        popover.show(key: true)
        focusTitleSoon()
    }

    private func focusTitleSoon() {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { NotificationCenter.default.post(name: .focusTitle, object: nil) }
    }

    // ---------------------------------------------------------------- check-ins and the other pings

    func answer(_ a: String) {
        guard store.active?.pending != nil else { return }
        let c = store.cat(store.active?.cat)
        store.answer(a)
        notifier.clearDelivered()
        cat.hide()
        if a == "drifted" { say("No worries.", "Back to it, \(c.name) is with you.", cat: c.id, seconds: 2.5) }
    }

    private func answerFromNotification(_ id: String, _ n: Int, _ a: String) {
        guard let b = store.active, b.id == id else { return }
        store.act("answer", ["id": id, "n": n, "answer": a])
        cat.hide()
    }

    private func say(_ title: String, _ body: String?, cat catID: String, seconds: TimeInterval, kind: String = "say", picture: String? = nil) {
        guard store.state.catsOn, store.settings.catInCorner else { return }
        cat.show(kind, model: CatCardModel(cat: catID, picture: picture, title: title, body: body), seconds: seconds)
    }

    private func showCheckin(_ b: Block, _ n: Int) {
        let c = store.cat(b.cat)
        let left = max(0, b.end - nowMs())
        let title = store.state.catsOn ? (b.intention.isEmpty ? "Boo! Still focusing?" : "Boo! Still on: \(b.intention)?") : (b.intention.isEmpty ? "Still focusing?" : "Still on: \(b.intention)?")
        let s = store.settings
        let model = CatCardModel(
            label: "CHECK-IN", cat: c.id, title: title,
            body: "\(minLeft(left)) min left · answer here, no need to switch apps",
            buttons: [
                CatButton(label: "Yes", primary: true, symbol: "checkmark") { [weak self] in self?.answer("yes") },
                CatButton(label: "Drifted", symbol: "arrow.triangle.2.circlepath") { [weak self] in self?.answer("drifted") },
            ],
            hint: "Or press \(s.yesKey.label) for Yes, \(s.driftedKey.label) for Drifted")
        cat.show("checkin", model: model, seconds: 90) { [weak self] in
            // nobody answered: it waits in the notifications and the menu bar's orange dot
            self?.notifier.postNow(title: title, body: "Tap to answer.")
        }
    }

    /// A check-in that is waiting: the cat shows it once (the ping that brought it is already gone).
    private func showPendingCheckin(_ st: UiState) {
        guard let b = st.view.active, let n = b.pending else { return }
        let key = "\(b.id):\(n)"
        guard st.catsOn, store.settings.catInCorner, !shownCheckins.contains(key) else { return }
        // during a call or full-screen video it holds, and comes when the Mac is free (breaksSecond looks again)
        if store.settings.breaks.waitDuringCalls, Busy.now() { return }
        shownCheckins.insert(key)
        showCheckin(b, n)
    }

    private func deliver(_ due: [Ping]) {
        let st = store.state
        for p in due {
            notifier.shown(p)
            guard st.catsOn, store.settings.catInCorner else { notifier.postNow(title: p.title, body: p.body); continue }
            let catID = st.view.active?.cat ?? st.view.awaiting?.cat ?? st.companion
            let title = p.title.replacingOccurrences(of: "🐱 ", with: "")
            switch p.kind {
            case "checkin": break   // showPendingCheckin does it
            case "nudge":
                cat.show("nudge", model: CatCardModel(cat: catID, title: p.body, body: st.view.active?.intention), seconds: 9)
            case "end":
                if let a = st.view.active {
                    cat.show("end", model: CatCardModel(cat: a.cat, title: title, body: p.body, buttons: [CatButton(label: "Open Chataltul", primary: true) { [weak self] in self?.cat.hide(); self?.popover.show(key: true) }]), seconds: 30)
                } else if let w = st.view.awaiting {
                    sound.meow(if: w.kitten == "home")
                    if store.settings.breaks.start == .auto, store.onBreak == nil { store.startBreak(store.breakAfter(w)) }
                    let c = st.cat(w.cat)
                    let says = w.kitten == "home" ? "\(c.name) came home to your café. +\(w.treats) treats" : "\(c.name) wandered off."
                    let b = [w.intention.isEmpty ? nil : w.intention, says].compactMap { $0 }.joined(separator: "\n")
                    func outcome(_ o: String) -> () -> Void { { [weak self] in self?.store.outcome(w.id, o); self?.cat.hide(); self?.notifier.clearDelivered() } }
                    cat.show("end", model: CatCardModel(cat: w.cat, title: title, body: b, buttons: [
                        CatButton(label: "Done", primary: true, action: outcome("done")),
                        CatButton(label: "Partly", action: outcome("partly")),
                        CatButton(label: "Not really", action: outcome("notreally")),
                    ]), seconds: 60) { [weak self] in self?.notifier.postNow(title: title, body: p.body) }
                }
            case "break-over":
                if store.macBreakEnd != nil { continue }   // a longer break is still on: the Mac says when it is over
                cat.show("break", model: CatCardModel(cat: catID, title: title, body: "It's time to focus. Ready for the next block?", buttons: [CatButton(label: "Open Chataltul", primary: true) { [weak self] in self?.cat.hide(); self?.popover.show(key: true) }]), seconds: 30) { [weak self] in
                    self?.notifier.postNow(title: title, body: p.body)
                }
            default: break
            }
        }
    }

    // ---------------------------------------------------------------- after every change, and every second

    private func changed(_ st: UiState) {
        status.refresh(now: nowMs())
        notifier.sync(st.pings)
        sound.update(st)
        registerCheckinKeys()
        showPendingCheckin(st)
        if st.view.active?.pending == nil { cat.hide("checkin") }
        if st.view.active == nil || st.view.active?.kitten != "growing" || st.view.active?.paused != nil {
            apply(speed.tick(block: bumpBlock(), now: nowMs()))
        }
        if st.view.active != nil, !askedNotify { askedNotify = true; notifier.askIfNeeded() }
        if st.view.active != nil { watcher.look() }
        trackEyeBlock(st)
        updateBreakScreen()
    }
    private var askedNotify = false

    func second(_ now: Int) {
        seconds += 1
        status.refresh(now: now)
        if seconds % 2 == 0 { away.sample(now: now) }
        if bumpable || currentHit != nil { watcher.look() }
        // an allowance counts the seconds spent on a list outside a block
        if let h = currentHit {
            let date = Date()
            let blockEnd = store.running ? store.active?.end : nil
            if GuardPolicy.countsUsage(for: h.list, now: date, blockEnd: blockEnd, usedMs: store.usedMs(h.list.id, at: date)) {
                store.addUsage(h.list.id, ms: 1000, at: date)
                if now - usageSaved > 10_000 { store.saveUsage(); usageSaved = now }
            }
            looked(h, currentBundle)   // it may have been used up, or a window may have begun
        }
        apply(speed.tick(block: bumpBlock(), now: now))
        let guarded = currentHit.flatMap { enforcement(for: $0.list) }.map { $0.mode != .speedBump } ?? false
        applyOther(other.tick(now: now, stillGuarded: guarded))
        breaksSecond(now)
    }

    // ---------------------------------------------------------------- the speed bump

    /// A block the cat may guard: running, kitten growing, and a list to guard.
    var bumpable: Bool {
        guard store.settings.speedBump, !store.settings.blocklist.isEmpty, store.state.catsOn, let b = store.active else { return false }
        return b.kitten == "growing" && b.paused == nil
    }

    func bumpBlock() -> BumpBlock? {
        guard store.settings.speedBump, !store.settings.blocklist.isEmpty, store.state.catsOn, let b = store.active else { return nil }
        return BumpBlock(id: b.id, paused: b.paused != nil, kitten: b.kitten, cat: b.cat, catName: store.cat(b.cat).name, title: b.title, end: b.end)
    }

    /// What a list asks for now: with the running block's end, and what its allowance has seen today.
    func enforcement(for list: GuardList, at date: Date = Date()) -> Enforcement? {
        let blockEnd = store.running ? store.active?.end : nil
        return GuardPolicy.enforcement(for: list, now: date, blockEnd: blockEnd, usedMs: store.usedMs(list.id, at: date))
    }

    /// Something was brought to the front (or looked at again): the speed bump, the wait or the lock may answer.
    func looked(_ hit: GuardHit?, _ bundleID: String?) {
        currentHit = hit; currentBundle = bundleID
        let n = nowMs()
        guard store.settings.speedBump else { apply(speed.front(target: nil, bundleID: bundleID, block: nil, now: n)); applyOther(other.front(hit: nil, enforcement: nil, bundleID: bundleID, cat: "", catName: "", now: n)); return }
        let enf = hit.flatMap { enforcement(for: $0.list) }
        let bumpTarget = enf?.mode == .speedBump ? hit?.target : nil
        apply(speed.front(target: bumpTarget, bundleID: hit == nil ? bundleID : nil, block: bumpBlock(), now: n))
        let cat = store.active?.cat ?? store.state.companion
        applyOther(other.front(hit: hit, enforcement: enf, bundleID: bundleID, cat: cat, catName: store.cat(cat).name, now: n))
    }

    func applyOther(_ actions: [OtherAction]) {
        for a in actions {
            switch a {
            case .show(let c), .update(let c):
                bump.model.other = c
                bump.show()
                cat.hide()
            case .hide:
                bump.model.other = nil
                if bump.model.card == nil { bump.hide() }
            case .activate(let bid): Watcher.activate(bid)
            }
        }
    }

    func apply(_ actions: [BumpAction]) {
        for a in actions {
            switch a {
            case .show(let c):
                bump.model.graceMs = speed.graceMs
                bump.model.card = c
                bump.show()
                cat.hide()
            case .update(let c): bump.model.card = c
            case .hide:
                bump.model.card = nil
                if bump.model.other == nil { bump.hide() }
            case .away(let at, let grace): store.kittenLeaves(at: at, grace: grace)
            case .wandered(let name):
                let id = store.active?.cat ?? store.state.companion
                say("\(name) wandered off.", "Your block keeps going.", cat: id, seconds: 4, kind: "gone", picture: Art.gone)
            case .activate(let bid): Watcher.activate(bid)
            }
        }
    }

    /// The first time a browser with sites on the list is in front: say plainly what is about to be asked.
    private func askBrowserAccess(_ browser: ReadableBrowser) {
        guard store.running else { return }
        let c = store.shownCat
        cat.show("access", model: CatCardModel(
            label: "ONE QUESTION", cat: c.id, title: "May I see which page is open in \(browser.name)?",
            body: "Your list has websites on it. macOS will ask once. I only look at the address of the front tab, and nothing leaves this Mac. If you say no, I just won't watch \(browser.name).",
            buttons: [
                CatButton(label: "Ask macOS", primary: true) { [weak self] in
                    self?.cat.hide("access")
                    self?.watcher.requestAccess(browser)
                },
                CatButton(label: "Not now") { [weak self] in self?.cat.hide("access") },
            ]), seconds: 120)
    }

    // ---------------------------------------------------------------- breaks

    /// A block started or ended: the eye breaks count from the start.
    private func trackEyeBlock(_ st: UiState) {
        let id = st.view.active?.id
        guard id != eyeBlockID else { return }
        eyeBlockID = id
        if let b = st.view.active { eye.blockStarted(at: b.start) } else { eye.blockEnded() }
    }

    func updateBreakScreen() {
        guard store.settings.breaks.fullScreen, let b = store.onBreak else { breakScreen.hide(); return }
        if let m = breakScreen.model { m.brk = b; return }
        let bs = store.settings.breaks
        let m = BreakModel(brk: b, idea: bs.ideaFor(store.catsHomeToday + store.blocksToday), strict: bs.strict, postponeMin: bs.postponeMin, cat: store.state.companion, eyeTip: bs.eyeBreaks)
        m.skip = { [weak self] in self?.store.stopBreak() }
        m.postpone = { [weak self] in self?.postponeBreak() }
        m.editIdeas = { [weak self] in self?.breakScreen.hide(); self?.openSettings("breaks") }
        breakScreen.show(m)
    }

    /// "Postpone 5 min": the break goes, and comes back.
    func postponeBreak() {
        guard let b = store.onBreak else { return }
        let mins = max(1, b.minutes)
        store.stopBreak()
        store.postponed = (at: nowMs() + store.settings.breaks.postponeMin * 60_000, minutes: mins)
        say("Break postponed.", "\(store.settings.breaks.postponeMin) minutes, then it's back.", cat: store.state.companion, seconds: 4)
    }

    /// Once a second: a postponed break coming back, a long break's end, an eye break.
    func breaksSecond(_ now: Int) {
        if store.active?.pending != nil { showPendingCheckin(store.state) }
        if let p = store.postponed, now >= p.at { store.postponed = nil; store.startBreak(p.minutes) }
        if store.macBreakOver() {
            let c = store.cat(nil)
            say("Break's over.", "It's time to focus. Ready for the next block?", cat: store.state.companion, seconds: 20)
            _ = c
        }
        let bs = store.settings.breaks
        guard bs.eyeBreaks else { return }
        eye.everyMs = bs.eyeEveryMin * 60_000; eye.seconds = bs.eyeSeconds
        let busy = bs.waitDuringCalls && Busy.now()
        if eye.check(now: now, running: store.running, busy: busy) { showEyeBreak(now) }
    }

    func showEyeBreak(_ now: Int) {
        let bs = store.settings.breaks
        let c = store.shownCat
        let total = bs.eyeSeconds * 1000
        var model = CatCardModel(label: "EYE BREAK", cat: c.id, title: "Look at something far away.", body: "For \(bs.eyeSeconds) seconds. Your eyes will thank you.",
                                 buttons: bs.strict ? [] : [CatButton(label: "Skip") { [weak self] in self?.endEyeBreak() }])
        model.countdownUntil = now + total
        model.countdownTotalMs = total
        model.close = bs.strict ? nil : { [weak self] in self?.endEyeBreak() }
        cat.show("eye", model: model, seconds: Double(bs.eyeSeconds)) { [weak self] in self?.eye.finish(at: nowMs()) }
    }

    func endEyeBreak() {
        cat.hide("eye")
        eye.finish(at: nowMs())
    }

    // ---------------------------------------------------------------- windows and menus

    func openSettings(_ pane: String?) {
        popover?.hide()
        SettingsWindow.shared.show(store: store, app: self, pane: pane)
    }

    /// The app window, on a page (today, cafe, history, later, blocklist, breaks).
    func openWindow(_ section: String?) {
        popover?.hide()
        AppWindow.shared.show(store: store, app: self, section: section)
    }

    func openCafe() { openWindow("cafe") }

    /// "Start a block" in the window's sidebar.
    func startFromWindow() { store.start(title: "", stopwatch: store.preset.stopwatch) }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        if !flag { openWindow(nil) }
        return true
    }

    private func statusMenu() -> NSMenu {
        let m = NSMenu()
        m.addItem(withTitle: "Open Chataltul", action: #selector(windowItem), keyEquivalent: "").target = self
        m.addItem(withTitle: "Settings…", action: #selector(settingsItem), keyEquivalent: ",").target = self
        m.addItem(NSMenuItem.separator())
        m.addItem(withTitle: "Quit Chataltul", action: #selector(quitItem), keyEquivalent: "q").target = self
        return m
    }
    @objc private func settingsItem() { openSettings(nil) }
    @objc private func windowItem() { openWindow(nil) }
    @objc private func quitItem() { NSApp.terminate(nil) }

    /// A menu for the sake of ⌘C and ⌘V in text fields: an accessory app has no menu bar, but its key equivalents still work.
    private func installMainMenu() {
        let main = NSMenu()
        let appItem = NSMenuItem(); main.addItem(appItem)
        let appMenu = NSMenu(); appItem.submenu = appMenu
        appMenu.addItem(withTitle: "Quit Chataltul", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        let editItem = NSMenuItem(); main.addItem(editItem)
        let edit = NSMenu(title: "Edit"); editItem.submenu = edit
        edit.addItem(withTitle: "Undo", action: Selector(("undo:")), keyEquivalent: "z")
        edit.addItem(withTitle: "Redo", action: Selector(("redo:")), keyEquivalent: "Z")
        edit.addItem(NSMenuItem.separator())
        edit.addItem(withTitle: "Cut", action: #selector(NSText.cut(_:)), keyEquivalent: "x")
        edit.addItem(withTitle: "Copy", action: #selector(NSText.copy(_:)), keyEquivalent: "c")
        edit.addItem(withTitle: "Paste", action: #selector(NSText.paste(_:)), keyEquivalent: "v")
        edit.addItem(withTitle: "Select All", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a")
        let windowItem = NSMenuItem(); main.addItem(windowItem)
        let window = NSMenu(title: "Window"); windowItem.submenu = window
        window.addItem(withTitle: "Close", action: #selector(NSWindow.performClose(_:)), keyEquivalent: "w")
        NSApp.mainMenu = main
    }

    /// A locked list keeps Chataltul on until the block ends: quitting would be the way round it.
    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        guard let until = store?.lockedUntil else { return .terminateNow }
        say("Locked until \(clockTime(until)).", "A locked list keeps Chataltul on until your block ends.", cat: store.state.companion, seconds: 5)
        return .terminateCancel
    }

    func applicationWillTerminate(_ n: Notification) {
        sound?.stopAll()
    }
}
