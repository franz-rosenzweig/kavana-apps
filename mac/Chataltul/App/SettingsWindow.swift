import AppKit
import ServiceManagement
import SwiftUI

enum SettingsPane: String, CaseIterable, Identifiable {
    case general, blocks, breaks, blocklist, shortcuts, account
    var id: String { rawValue }
    var title: String {
        switch self {
        case .general: return "General"
        case .blocks: return "Blocks and check-ins"
        case .breaks: return "Breaks"
        case .shortcuts: return "Shortcuts"
        case .blocklist: return "Blocklist"
        case .account: return "Account and sync"
        }
    }
    var symbol: String {
        switch self {
        case .general: return "gearshape"
        case .blocks: return "timer"
        case .breaks: return "cup.and.saucer"
        case .shortcuts: return "command"
        case .blocklist: return "pawprint"
        case .account: return "person.crop.circle"
        }
    }
}

final class SettingsNav: ObservableObject {
    @Published var pane: SettingsPane
    init(_ p: SettingsPane = .general) { pane = p }
}

/// The Settings window.
final class SettingsWindow {
    static let shared = SettingsWindow()
    private var window: NSWindow?
    private let nav = SettingsNav()

    func show(store: Store, app: AppDelegate, pane: String?) {
        if let p = pane.flatMap(SettingsPane.init(rawValue:)) { nav.pane = p }
        if window == nil {
            let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 800, height: 560), styleMask: [.titled, .closable, .fullSizeContentView], backing: .buffered, defer: false)
            w.title = "Chataltul Settings"
            w.titlebarAppearsTransparent = true
            w.titleVisibility = .hidden
            w.isReleasedWhenClosed = false
            w.contentView = NSHostingView(rootView: SettingsView(store: store, nav: nav, sync: app.sync, watcher: app.watcher))
            w.center()
            if FloatingPanel.offscreen { w.setFrameOrigin(NSPoint(x: -40_000, y: -40_000)) }   // a test: far off the screen
            window = w
        }
        if !FloatingPanel.offscreen { NSApp.activate(ignoringOtherApps: true) }
        window?.makeKeyAndOrderFront(nil)
    }
}

struct SettingsView: View {
    @ObservedObject var store: Store
    @ObservedObject var nav: SettingsNav
    var sync: SyncController
    var watcher: Watcher

    var body: some View {
        HStack(spacing: 0) {
            sidebar
            Rectangle().fill(Palette.hairline).frame(width: 1)
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    Text(nav.pane.title).font(.system(size: 24, weight: .bold)).foregroundStyle(Palette.ink)
                    pane
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.init(top: 44, leading: 32, bottom: 32, trailing: 32))
            }
        }
        .frame(width: 800, height: 560)
        .background(Wall())
    }

    private var sidebar: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                Image(systemName: "pawprint.fill").foregroundStyle(Palette.accent)
                Text("Chataltul").font(.system(size: 15, weight: .bold)).foregroundStyle(Palette.ink)
            }.padding(.horizontal, 12).padding(.bottom, 14)
            ForEach(SettingsPane.allCases) { p in
                Button { nav.pane = p } label: {
                    HStack(spacing: 10) {
                        Image(systemName: p.symbol).frame(width: 18)
                        Text(p.title).font(.system(size: 13, weight: nav.pane == p ? .semibold : .regular))
                        Spacer()
                    }
                    .foregroundStyle(nav.pane == p ? Palette.ink : Palette.muted2)
                    .padding(.horizontal, 12).padding(.vertical, 8)
                    .background(RoundedRectangle(cornerRadius: 8, style: .continuous).fill(nav.pane == p ? Palette.soft : .clear))
                    .contentShape(Rectangle())
                }.buttonStyle(.plain)
            }
            Spacer()
            Text("Version \(Self.version)").font(.system(size: 11)).foregroundStyle(Palette.muted).padding(.horizontal, 12)
        }
        .padding(.init(top: 44, leading: 12, bottom: 16, trailing: 12))
        .frame(width: 210)
        .background(Palette.softer)
    }

    static var version: String {
        (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? "0"
    }

    @ViewBuilder private var pane: some View {
        switch nav.pane {
        case .general: GeneralPane(store: store)
        case .blocks: BlocksPane(store: store)
        case .breaks: BreaksView(store: store)
        case .shortcuts: ShortcutsPane(store: store)
        case .blocklist: BlocklistView(store: store, watcher: watcher)
        case .account: AccountPane(store: store, sync: sync)
        }
    }
}

// ---------------------------------------------------------------- pieces

struct Section_: View {
    var title: String?
    var note: String?
    @ViewBuilder var content: () -> AnyView
    init(_ title: String? = nil, note: String? = nil, @ViewBuilder content: @escaping () -> some View) {
        self.title = title; self.note = note; self.content = { AnyView(content()) }
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let t = title { Label11(text: t.uppercased()) }
            VStack(spacing: 0) { content() }
                .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Palette.card))
                .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(Palette.ringTrack, lineWidth: 1))
            if let n = note { Text(n).font(.system(size: 12)).foregroundStyle(Palette.muted).fixedSize(horizontal: false, vertical: true) }
        }
    }
}

struct Row<Control: View>: View {
    var title: String
    var detail: String?
    var last = false
    @ViewBuilder var control: () -> Control
    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.system(size: 14)).foregroundStyle(Palette.ink)
                    if let d = detail { Text(d).font(.system(size: 12)).foregroundStyle(Palette.muted).fixedSize(horizontal: false, vertical: true) }
                }
                Spacer(minLength: 12)
                control()
            }
            .padding(.horizontal, 14).padding(.vertical, 11)
            if !last { Rectangle().fill(Palette.hairline).frame(height: 1).padding(.leading, 14) }
        }
    }
}

struct Switch: View {
    @Binding var on: Bool
    var body: some View {
        Toggle("", isOn: $on).toggleStyle(.switch).labelsHidden().tint(Palette.teal)
    }
}

struct PickRow<V: Hashable>: View {
    var title: String
    var detail: String?
    var last = false
    var options: [(V, String)]
    @Binding var value: V
    var body: some View {
        Row(title: title, detail: detail, last: last) {
            Picker("", selection: $value) { ForEach(0..<options.count, id: \.self) { i in Text(options[i].1).tag(options[i].0) } }
                .labelsHidden().pickerStyle(.menu).fixedSize()
        }
    }
}

// ---------------------------------------------------------------- General

struct GeneralPane: View {
    @ObservedObject var store: Store
    @State private var login = SMAppService.mainApp.status == .enabled

    var body: some View {
        let eng = store.engineSettings
        VStack(alignment: .leading, spacing: 22) {
            Section_("While you focus") {
                Row(title: "Cat mode", detail: "A kitten grows in the ring while you focus, and comes home if you stay.") {
                    Switch(on: Binding(get: { eng.cat }, set: { store.setEngine(["cat": $0]) }))
                }
                Row(title: "Show the cat in the corner", detail: "Check-ins pop up as the cat. Off, they come as notifications only.", last: eng.cat == false) {
                    Switch(on: $store.settings.catInCorner)
                }
                if eng.cat {
                    PickRow(title: "Next kitten", detail: nil, last: true, options: store.state.cats.filter { $0.met }.map { ($0.id, $0.name) }, value: Binding(get: { eng.companion }, set: { store.setEngine(["companion": $0]) }))
                }
            }
            Section_("Where the time goes", note: "While a block runs, Chataltul notes which app is in front and, in a browser, the site's name (never the address or the page). It stays in a file on this Mac and is never sent anywhere, not even with an account.") {
                Row(title: "Remember where each block went", detail: "Shown after a block and in the week's review.") { Switch(on: $store.settings.recordTiming) }
                Row(title: "Forget it all", detail: "\(store.timing.blocks.count) blocks remembered", last: true) {
                    Button("Forget") { store.clearTiming() }.buttonStyle(Chip(size: 13, radius: 9, padding: EdgeInsets(top: 6, leading: 12, bottom: 6, trailing: 12))).disabled(store.timing.isEmpty)
                }
            }
            Section_("Focus on your Mac", note: "Chataltul can't turn a Focus on itself, but a Shortcut can. Make two Shortcuts in the Shortcuts app that set the Focus on and off, and put their names here: they run when a block starts and when it ends.") {
                Row(title: "When a block starts") { TitleField(placeholder: "Shortcut name", text: $store.settings.shortcutStart, size: 13, center: false).frame(width: 220) }
                Row(title: "When a block ends", last: true) { TitleField(placeholder: "Shortcut name", text: $store.settings.shortcutEnd, size: 13, center: false).frame(width: 220) }
            }
            Section_("This Mac") {
                Row(title: "Open at login") {
                    Switch(on: Binding(get: { login }, set: { setLogin($0) }))
                }
                Row(title: "About", detail: aboutLine, last: true) { EmptyView() }
            }
        }
    }

    private var aboutLine: String {
        let commit = (try? String(contentsOf: Bundle.main.resourceURL!.appendingPathComponent("js/WEB_COMMIT"), encoding: .utf8))?.trimmingCharacters(in: .whitespacesAndNewlines).prefix(7) ?? "?"
        return "Chataltul \(SettingsView.version) · the web app's engine at \(commit)"
    }

    private func setLogin(_ on: Bool) {
        do { if on { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() } } catch { NSLog("login item: \(error)") }
        login = SMAppService.mainApp.status == .enabled
    }
}

// ---------------------------------------------------------------- Blocks and check-ins

struct BlocksPane: View {
    @ObservedObject var store: Store

    var body: some View {
        let eng = store.engineSettings
        VStack(alignment: .leading, spacing: 22) {
            Section_("Check-ins") {
                PickRow(title: "How", options: [("ask", "Ask me"), ("nudge", "Nudge me")], value: Binding(get: { eng.style }, set: { store.setEngine(["style": $0]) }))
                PickRow(title: "Every", last: true, options: [(10, "10 min"), (15, "15 min"), (20, "20 min"), (30, "30 min"), (45, "45 min"), (60, "60 min"), (0, "Only at the end")],
                        value: Binding(get: { eng.interval }, set: { store.setEngine(["interval": $0]) }))
            }
            Section_("During a block") {
                Row(title: "Allow a pause", detail: "Five minutes at most, then it starts again by itself.") {
                    Switch(on: Binding(get: { eng.pause != "off" }, set: { store.setEngine(["pause": $0 ? "five" : "off"]) }))
                }
                Row(title: "Count extra time", detail: "At the end the block keeps counting until you stop it.", last: true) {
                    Switch(on: Binding(get: { eng.overtime }, set: { store.setEngine(["overtime": $0]) }))
                }
            }
            Section_("The day's cycle", note: "Kept on this Mac only. The popover steps through Focus, Break and Long.") {
                PickRow(title: "Focus", options: [15, 20, 25, 30, 45, 50, 60, 90].map { ($0, "\($0) min") }, value: $store.settings.focusMinutes)
                PickRow(title: "Break", options: [3, 5, 10, 15].map { ($0, "\($0) min") }, value: $store.settings.cycle.shortBreak)
                PickRow(title: "Long break", options: [10, 15, 20, 30].map { ($0, "\($0) min") }, value: $store.settings.cycle.longBreak)
                PickRow(title: "Long break after", options: [2, 3, 4, 5, 6].map { ($0, "\($0) blocks") }, value: $store.settings.cycle.longAfter)
                PickRow(title: "Blocks in a day", last: true, options: [3, 4, 5, 6, 8, 10].map { ($0, "\($0)") }, value: $store.settings.cycle.perDay)
            }
        }
    }
}

// ---------------------------------------------------------------- Shortcuts

struct ShortcutsPane: View {
    @ObservedObject var store: Store

    var body: some View {
        VStack(alignment: .leading, spacing: 22) {
            Section_("Anywhere on the Mac", note: "Yes and Drifted answer a check-in, and only work while one is waiting, so ⌃⌥Y and ⌃⌥D stay free in other apps the rest of the time. Click a shortcut, then press the keys you want. Esc keeps the old one.") {
                Row(title: "Start a block", detail: "Opens the popover with the cursor in the title. Press it again to start.") { KeyRecorder(combo: $store.settings.startKey) }
                Row(title: "Park a thought", detail: "A small field opens; the thought goes to Later.") { KeyRecorder(combo: $store.settings.parkKey) }
                Row(title: "Yes, still on it") { KeyRecorder(combo: $store.settings.yesKey) }
                Row(title: "Drifted", last: true) { KeyRecorder(combo: $store.settings.driftedKey) }
            }
            Button("Back to the usual ones") {
                let d = MacSettings()
                store.settings.startKey = d.startKey; store.settings.parkKey = d.parkKey
                store.settings.yesKey = d.yesKey; store.settings.driftedKey = d.driftedKey
            }.buttonStyle(Chip(size: 13))
        }
    }
}

/// Click, then press the keys.
struct KeyRecorder: NSViewRepresentable {
    @Binding var combo: KeyCombo
    func makeNSView(context: Context) -> RecorderView {
        let v = RecorderView()
        v.onChange = { c in combo = c }
        v.combo = combo
        return v
    }
    func updateNSView(_ v: RecorderView, context: Context) { v.combo = combo; v.onChange = { c in combo = c } }
}

final class RecorderView: NSView {
    var combo = KeyCombo(code: 49, mods: KeyCombo.co) { didSet { needsDisplay = true } }
    var onChange: (KeyCombo) -> Void = { _ in }
    private var recording = false { didSet { needsDisplay = true } }

    override var acceptsFirstResponder: Bool { true }
    override var intrinsicContentSize: NSSize { NSSize(width: 130, height: 28) }
    override func mouseDown(with event: NSEvent) { recording = true; window?.makeFirstResponder(self) }
    override func resignFirstResponder() -> Bool { recording = false; return true }

    override func performKeyEquivalent(with event: NSEvent) -> Bool {
        guard recording else { return false }
        keyDown(with: event)
        return true
    }

    override func keyDown(with event: NSEvent) {
        guard recording else { super.keyDown(with: event); return }
        if event.keyCode == 53 { recording = false; return }
        if let c = keyCombo(from: event) { combo = c; onChange(c); recording = false }
        else { NSSound.beep() }
    }

    override func draw(_ dirtyRect: NSRect) {
        let r = bounds.insetBy(dx: 0.5, dy: 0.5)
        let path = NSBezierPath(roundedRect: r, xRadius: 7, yRadius: 7)
        (recording ? NSColor(hex: 0x2C8A7E, alpha: 0.18) : NSColor.labelColor.withAlphaComponent(0.06)).setFill()
        path.fill()
        (recording ? NSColor(hex: 0x2C8A7E) : NSColor.labelColor.withAlphaComponent(0.14)).setStroke()
        path.stroke()
        let text = recording ? "Press the keys…" : combo.label
        let style = NSMutableParagraphStyle(); style.alignment = .center
        let attrs: [NSAttributedString.Key: Any] = [.font: NSFont.systemFont(ofSize: 13, weight: .medium), .foregroundColor: NSColor.labelColor, .paragraphStyle: style]
        let h = NSAttributedString(string: text, attributes: attrs).size().height
        NSAttributedString(string: text, attributes: attrs).draw(in: NSRect(x: 0, y: (bounds.height - h) / 2, width: bounds.width, height: h))
    }
}

// ---------------------------------------------------------------- Account and sync

struct AccountPane: View {
    @ObservedObject var store: Store
    @ObservedObject var sync: SyncController
    @State private var email = ""
    @State private var code = ""
    @State private var name = ""
    @State private var password = ""
    @State private var usePassword = false

    var body: some View {
        VStack(alignment: .leading, spacing: 22) {
            if let a = sync.account { signedIn(a) } else { signedOut }
            if let m = sync.message { Text(m).font(.system(size: 13)).foregroundStyle(Palette.orange) }
        }
        .onAppear { sync.loadConfig() }
    }

    private func signedIn(_ a: Account) -> some View {
        VStack(alignment: .leading, spacing: 22) {
            Section_("Signed in", note: "Your blocks, Later, cats and settings are the same here and on the web. A change goes up a moment after you make it. Signing out takes the account's history off this Mac; it stays safe on the server.") {
                Row(title: a.label, detail: sync.trouble == "too big" ? "Not syncing: the history is too big for the server." : "Synced with \(sync.server)") {
                    Button("Sign out") { sync.signOut() }.buttonStyle(Chip(size: 13, radius: 9, padding: EdgeInsets(top: 6, leading: 12, bottom: 6, trailing: 12)))
                }
                Row(title: "The café on the web", detail: "Shows the same data while you're signed in.", last: true) {
                    Button("Open") { if let u = URL(string: store.settings.serverURL) { NSWorkspace.shared.open(u) } }
                        .buttonStyle(Chip(size: 13, radius: 9, padding: EdgeInsets(top: 6, leading: 12, bottom: 6, trailing: 12)))
                }
            }
        }
    }

    private var signedOut: some View {
        VStack(alignment: .leading, spacing: 22) {
            Section_("Sign in", note: sync.emailOn == false ? "This server doesn't send email, so use a name and password." : "No password to remember: Chataltul emails you a 6-digit code. If you're new, the code makes your account. This Mac's blocks come along. Without an account nothing leaves this Mac.") {
                if usePassword || sync.emailOn == false {
                    Row(title: "Name or email") { field("name", text: $name) }
                    Row(title: "Password", last: true) { SecureField("", text: $password).textFieldStyle(.roundedBorder).frame(width: 220) }
                } else if let sent = sync.codeSentTo {
                    Row(title: "Code", detail: "Sent to \(sent)", last: true) { field("123456", text: $code, submit: { sync.signIn(email: sent, code: code) }) }
                } else {
                    Row(title: "Email", last: true) { field("you@example.com", text: $email, submit: { sync.sendCode(email) }) }
                }
            }
            HStack(spacing: 10) {
                if usePassword || sync.emailOn == false {
                    Button("Sign in") { sync.signIn(kind: "login", name: name, password: password) }.buttonStyle(Chip(fill: Palette.accent, text: Palette.onAccent, size: 14, radius: 10))
                    Button("Make an account") { sync.signIn(kind: "signup", name: name, password: password) }.buttonStyle(Chip(size: 14, radius: 10))
                } else if let sent = sync.codeSentTo {
                    Button("Sign in") { sync.signIn(email: sent, code: code) }.buttonStyle(Chip(fill: Palette.accent, text: Palette.onAccent, size: 14, radius: 10))
                    Button("Another address") { sync.codeSentTo = nil; code = "" }.buttonStyle(Chip(size: 14, radius: 10))
                } else {
                    Button("Send me a code") { sync.sendCode(email) }.buttonStyle(Chip(fill: Palette.accent, text: Palette.onAccent, size: 14, radius: 10))
                }
                if sync.emailOn != false { Button(usePassword ? "Use an email code" : "Use a password") { usePassword.toggle(); sync.message = nil }.buttonStyle(.plain).foregroundStyle(Palette.accentText).font(.system(size: 13)) }
                if sync.busy { ProgressView().controlSize(.small) }
            }
        }
    }

    private func field(_ placeholder: String, text: Binding<String>, submit: @escaping () -> Void = {}) -> some View {
        TitleField(placeholder: placeholder, text: text, size: 13, center: false, onSubmit: submit).frame(width: 240)
    }
}
