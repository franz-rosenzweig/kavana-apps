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
        bump.model.leaveHeld = { [weak self] in self?.apply(self?.speed.leaveHeld(now: nowMs()) ?? []) }
        bump.model.editList = { [weak self] in
            // the card can't be left by clicking elsewhere, so the list opens beside it
            self?.openSettings("blocklist")
        }
        watcher.onFront = { [weak self] target, bid in self?.looked(target, bid) }
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
        guard shownCheckins.insert(key).inserted, st.catsOn, store.settings.catInCorner else { return }
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
    }
    private var askedNotify = false

    func second(_ now: Int) {
        seconds += 1
        status.refresh(now: now)
        if store.running, seconds % 2 == 0 { away.sample(now: now) }
        else if !store.running { away.sample(now: now) }
        if bumpable { watcher.look() }
        apply(speed.tick(block: bumpBlock(), now: now))
        if let t = store.toast { _ = t }
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

    func looked(_ target: Target?, _ bundleID: String?) {
        apply(speed.front(target: target, bundleID: bundleID, block: bumpBlock(), now: nowMs()))
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
            case .hide: bump.hide()
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

    // ---------------------------------------------------------------- windows and menus

    func openSettings(_ pane: String?) {
        popover?.hide()
        SettingsWindow.shared.show(store: store, app: self, pane: pane)
    }

    func openCafe() {
        popover.hide()
        if let u = URL(string: store.settings.serverURL) { NSWorkspace.shared.open(u) }
    }

    private func statusMenu() -> NSMenu {
        let m = NSMenu()
        m.addItem(withTitle: "Settings…", action: #selector(settingsItem), keyEquivalent: ",").target = self
        m.addItem(NSMenuItem.separator())
        m.addItem(withTitle: "Quit Chataltul", action: #selector(quitItem), keyEquivalent: "q").target = self
        return m
    }
    @objc private func settingsItem() { openSettings(nil) }
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

    func applicationWillTerminate(_ n: Notification) {
        sound?.stopAll()
    }
}
