import AppKit
import SwiftUI

enum WindowSection: String, CaseIterable, Identifiable {
    case today, cafe, history, later, blocklist, breaks
    var id: String { rawValue }
    var title: String {
        switch self {
        case .today: return "Today"
        case .cafe: return "Café"
        case .history: return "History"
        case .later: return "Later"
        case .blocklist: return "Blocklist"
        case .breaks: return "Breaks"
        }
    }
    var symbol: String {
        switch self {
        case .today: return "circle.dotted"
        case .cafe: return "cup.and.saucer"
        case .history: return "clock.arrow.circlepath"
        case .later: return "tray"
        case .blocklist: return "pawprint"
        case .breaks: return "moon.zzz"
        }
    }
}

final class WindowNav: ObservableObject {
    @Published var section: WindowSection
    init(_ s: WindowSection = .today) { section = s }
}

/// The app window (screens 11, 12 and 14): Today, the café, History, Later, the Blocklist and Breaks. A menu bar
/// app has no Dock icon until this opens; closing it gives the Dock back.
final class AppWindow: NSObject, NSWindowDelegate {
    static let shared = AppWindow()
    private var window: NSWindow?
    let nav = WindowNav()

    func show(store: Store, app: AppDelegate, section: String?) {
        if let s = section.flatMap(WindowSection.init(rawValue:)) { nav.section = s }
        if window == nil {
            let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1240, height: 800), styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView], backing: .buffered, defer: false)
            w.title = "Chataltul"
            w.titlebarAppearsTransparent = true
            w.titleVisibility = .hidden
            w.isReleasedWhenClosed = false
            w.minSize = NSSize(width: 1020, height: 680)
            w.delegate = self
            w.contentView = NSHostingView(rootView: WindowRoot(store: store, nav: nav, sync: app.sync, watcher: app.watcher, openSettings: { [weak app] p in app?.openSettings(p) }, startBlock: { [weak app] in app?.startFromWindow() }))
            w.center()
            if FloatingPanel.offscreen { w.setFrameOrigin(NSPoint(x: -40_000, y: -40_000)) }   // a test: far off the screen
            window = w
        }
        if !FloatingPanel.offscreen {
            NSApp.setActivationPolicy(.regular)
            NSApp.activate(ignoringOtherApps: true)
        }
        window?.makeKeyAndOrderFront(nil)
    }

    var isShown: Bool { window?.isVisible == true }
    var windowForTest: NSWindow? { window }

    func windowWillClose(_ notification: Notification) {
        // the Dock icon goes with the window, unless Settings is still open
        if !NSApp.windows.contains(where: { $0.title == "Chataltul Settings" && $0.isVisible }) { NSApp.setActivationPolicy(.accessory) }
    }
}

// ---------------------------------------------------------------- the window

struct WindowRoot: View {
    @ObservedObject var store: Store
    @ObservedObject var nav: WindowNav
    var sync: SyncController
    var watcher: Watcher
    var openSettings: (String?) -> Void
    var startBlock: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            sidebar
            Rectangle().fill(Palette.tileLine).frame(width: 1)
            page
        }
        .frame(minWidth: 1020, minHeight: 680)
        .background(Palette.page)
    }

    private var sidebar: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 10) {
                if let icon = NSApp.applicationIconImage { Image(nsImage: icon).resizable().frame(width: 28, height: 28) }
                Text("Chataltul").font(.system(size: 16, weight: .bold)).foregroundStyle(Palette.ink)
            }.padding(.horizontal, 8).padding(.bottom, 14)
            ForEach(WindowSection.allCases) { s in
                Button { nav.section = s } label: {
                    HStack(spacing: 10) {
                        Image(systemName: s.symbol).frame(width: 18)
                        Text(s.title).font(.system(size: 14, weight: nav.section == s ? .semibold : .regular))
                        Spacer()
                        if s == .later, store.state.view.later.count > 0 { Text("\(store.state.view.later.count)").font(.system(size: 12)).foregroundStyle(Palette.muted) }
                    }
                    .foregroundStyle(nav.section == s ? Palette.accentText : Palette.ink)
                    .padding(.horizontal, 10).padding(.vertical, 8)
                    .background(RoundedRectangle(cornerRadius: 9, style: .continuous).fill(nav.section == s ? Palette.accent.opacity(0.14) : .clear))
                    .contentShape(Rectangle())
                }.buttonStyle(.plain)
            }
            Button { if let u = URL(string: store.settings.serverURL) { NSWorkspace.shared.open(u) } } label: {
                HStack(spacing: 10) { Image(systemName: "bag").frame(width: 18); Text("Cats and shop").font(.system(size: 14)); Spacer(); Image(systemName: "arrow.up.right").font(.system(size: 10)).foregroundStyle(Palette.muted) }
                    .foregroundStyle(Palette.ink).padding(.horizontal, 10).padding(.vertical, 8).contentShape(Rectangle())
            }.buttonStyle(.plain).help("The shop and the cat collection are on the web")
            Spacer()
            VStack(spacing: 8) {
                if let b = store.active {
                    TimelineView(.periodic(from: .now, by: 1)) { _ in
                        HStack(spacing: 8) {
                            Circle().fill(Palette.accent).frame(width: 8, height: 8)
                            VStack(alignment: .leading, spacing: 1) {
                                Text(b.title).font(.system(size: 13, weight: .semibold)).foregroundStyle(Palette.ink).lineLimit(1)
                                Text(b.paused != nil ? "paused" : "\(fmtClock(b.stopwatch ? nowMs() - b.start : max(0, b.end - nowMs()))) left").font(.system(size: 12)).foregroundStyle(Palette.muted)
                            }
                            Spacer()
                        }
                        .padding(10).background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Palette.accent.opacity(0.10)))
                    }
                } else {
                    Button(action: startBlock) { HStack(spacing: 8) { Symbol.play(); Text("Start a block") }.frame(maxWidth: .infinity) }
                        .buttonStyle(Chip(fill: Palette.accent, text: Palette.onAccent, size: 14, radius: 10, padding: EdgeInsets(top: 10, leading: 10, bottom: 10, trailing: 10)))
                    Text("or \(store.settings.startKey.label) from any app").font(.system(size: 12)).foregroundStyle(Palette.muted)
                }
                Button { openSettings(nil) } label: { HStack(spacing: 6) { Image(systemName: "gearshape"); Text("Settings") }.font(.system(size: 13)).foregroundStyle(Palette.muted2) }.buttonStyle(.plain)
            }
        }
        .padding(.init(top: 44, leading: 12, bottom: 16, trailing: 12))
        .frame(width: 232)
        .background(Palette.sidebar)
    }

    @ViewBuilder private var page: some View {
        switch nav.section {
        case .today: TodayView(store: store, showCafe: { nav.section = .cafe })
        case .cafe: CafePage(store: store)
        case .history: HistoryView(store: store)
        case .later: LaterView(store: store)
        case .blocklist: Scrolled(title: "Blocklist", subtitle: nil) { BlocklistView(store: store, watcher: watcher) }
        case .breaks: Scrolled(title: "Breaks", subtitle: "When to stop, and how firmly Chataltul asks.") { BreaksView(store: store) }
        }
    }
}

/// A page of the window: a title and something that scrolls.
struct Scrolled<Content: View>: View {
    var title: String
    var subtitle: String?
    @ViewBuilder var content: () -> Content
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                if title != "Blocklist" {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(title).font(.system(size: 30, weight: .bold)).foregroundStyle(Palette.ink)
                        if let s = subtitle { Text(s).font(.system(size: 14)).foregroundStyle(Palette.muted) }
                    }
                }
                content()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.init(top: 38, leading: 36, bottom: 32, trailing: 36))
        }
    }
}

// ---------------------------------------------------------------- Today

/// Today, the week, the month, the year: the numbers, the café of the period and where the time went.
struct TodayView: View {
    @ObservedObject var store: Store
    var showCafe: () -> Void
    @State private var span: Span
    init(store: Store, showCafe: @escaping () -> Void, initialSpan: Span = .day) {
        self.store = store; self.showCafe = showCafe; _span = State(initialValue: initialSpan)
    }
    @State private var blocks: [Hist] = []
    @State private var before: [Hist] = []
    @StateObject private var cafe = CafeController()
    @Environment(\.colorScheme) private var scheme

    private var period: Period { Insights.period(span, containing: Date()) }

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            header
            stats
            GeometryReader { g in
                let w = g.size.width - 16
                HStack(alignment: .top, spacing: 16) {
                    cafeCard.frame(width: w * 0.535, height: g.size.height)
                    VStack(spacing: 14) {
                        if span == .day { timeline; hours } else { chart; tags }
                        Spacer(minLength: 0)
                    }.frame(width: w * 0.465, height: g.size.height, alignment: .top)
                }
            }
            .frame(maxHeight: .infinity)
        }
        .padding(.init(top: 34, leading: 36, bottom: 28, trailing: 36))
        .onAppear(perform: load)
        .onChange(of: span) { _ in load() }
        .onChange(of: store.state.history.first?.id) { _ in load() }
    }

    private func load() {
        let p = period
        blocks = store.blocks(from: p.from, to: p.to)
        before = span == .day ? [] : { let b = Insights.before(p); return store.blocks(from: b.from, to: b.to) }()
    }

    // ---------------------------------------------------------------- header and numbers

    private var header: some View {
        HStack(alignment: .bottom) {
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.system(size: 30, weight: .bold)).foregroundStyle(Palette.ink)
                Text(subtitle).font(.system(size: 14)).foregroundStyle(Palette.muted)
            }
            Spacer()
            HStack(spacing: 0) {
                ForEach(Span.allCases, id: \.self) { s in
                    Button { span = s } label: {
                        Text(s.title).font(.system(size: 13, weight: span == s ? .semibold : .regular)).foregroundStyle(span == s ? Palette.ink : Palette.muted2)
                            .padding(.horizontal, 14).padding(.vertical, 6)
                            .background(RoundedRectangle(cornerRadius: 8, style: .continuous).fill(span == s ? Palette.tile : .clear).shadow(color: .black.opacity(span == s ? 0.12 : 0), radius: 2, y: 1))
                            .contentShape(Rectangle())
                    }.buttonStyle(.plain)
                }
            }
            .padding(3).background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Palette.soft))
        }
    }

    private var title: String {
        let f = DateFormatter()
        switch span {
        case .day: return "Today"
        case .week: return "This week"
        case .month: f.dateFormat = "LLLL"; return f.string(from: Date())
        case .year: f.dateFormat = "yyyy"; return f.string(from: Date())
        }
    }

    private var subtitle: String {
        let f = DateFormatter()
        switch span {
        case .day: f.dateFormat = "EEEE, d MMMM"; return f.string(from: Date())
        case .week:
            f.dateFormat = "d MMM"
            return "\(f.string(from: period.start)) to \(f.string(from: Date(timeIntervalSince1970: Double(period.to - 1) / 1000)))"
        case .month: return "Your café this month, and where the time went"
        case .year: return "Your café this year, month by month"
        }
    }

    private var stats: some View {
        let m = Insights.minutes(blocks)
        let home = Insights.home(blocks), gone = Insights.wandered(blocks)
        let o = Insights.outcomes(blocks)
        let goal = store.engineSettings.goal
        let prev = Insights.compare(m, Insights.minutes(before))
        let streak = Insights.streak(store.state.history)
        return HStack(spacing: 14) {
            if span == .day {
                tile(Insights.hours(m), goal > 0 ? "focused, goal \(Insights.hours(goal))" : "focused")
                tile("\(home)", home == 1 ? "cat came home" : "cats came home")
                tile("\(Insights.drifts(blocks))", Insights.drifts(blocks) == 1 ? "drifted" : "drifted")
                tile(streak == 1 ? "1 day" : "\(streak) days", "in a row")
            } else {
                tile(Insights.hours(m), prev.map { "focused · \($0) on \(before.isEmpty ? "before" : previousName)" } ?? "focused")
                tile("\(home)", home == 1 ? "cat came home" : "cats came home")
                tile("\(gone)", "wandered off")
                tile("\(o.done) · \(o.partly) · \(o.notReally)", "done · partly · not really")
            }
        }
    }

    private var previousName: String {
        let f = DateFormatter()
        switch span {
        case .week: return "last week"
        case .month: f.dateFormat = "LLLL"; return f.string(from: Insights.before(period).start)
        case .year: f.dateFormat = "yyyy"; return f.string(from: Insights.before(period).start)
        case .day: return "yesterday"
        }
    }

    private func tile(_ big: String, _ small: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(big).font(clockFont(26, weight: .bold)).foregroundStyle(Palette.ink)
            Text(small).font(.system(size: 13)).foregroundStyle(Palette.muted)
        }
        .padding(.horizontal, 16).padding(.vertical, 14).frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Palette.tile))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Palette.tileLine, lineWidth: 1))
    }

    private func panel<C: View>(@ViewBuilder _ c: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 10) { c() }
            .padding(.horizontal, 16).padding(.vertical, 14).frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Palette.tile))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(Palette.tileLine, lineWidth: 1))
    }

    // ---------------------------------------------------------------- the café

    private var cafeCard: some View {
        VStack(spacing: 0) {
            HStack {
                Text(span == .day ? "Your café today" : "\(Insights.home(blocks)) cats this \(span.rawValue)").font(.system(size: 13, weight: .bold)).foregroundStyle(Palette.ink)
                Spacer()
                if span == .day { Button("Open the café", action: showCafe).buttonStyle(.plain).font(.system(size: 13)).foregroundStyle(Palette.accentText) }
                else {
                    HStack(spacing: 10) {
                        Button { cafe.spin(-1) } label: { Image(systemName: "arrow.counterclockwise") }.buttonStyle(.plain).accessibilityLabel("Turn the café left")
                        Text("Turn the café").font(.system(size: 13)).foregroundStyle(Palette.accentText)
                        Button { cafe.spin(1) } label: { Image(systemName: "arrow.clockwise") }.buttonStyle(.plain).accessibilityLabel("Turn the café right")
                    }.foregroundStyle(Palette.accentText)
                }
            }.padding(.horizontal, 16).padding(.top, 14)
            CafeView(data: CafeData.make(store: store, blocks: blocks, span: span, dark: scheme == .dark, seed: hash(span.rawValue) &+ period.from / 1000), controller: cafe)
                .padding(.horizontal, 10).padding(.bottom, 10)
                .overlay { if Insights.home(blocks) == 0 { Text(blocks.contains { $0.kitten == "gone" } ? "No cats stayed. Next time, stay to the end." : "No cats came home yet. Finish a block and one will.").font(.system(size: 13)).foregroundStyle(Palette.muted).padding(.horizontal, 30).multilineTextAlignment(.center) } }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(scheme == .dark ? Color.black : Color(hex: 0xF2F2F7)))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(Palette.tileLine, lineWidth: 1))
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private func hash(_ s: String) -> Int { s.unicodeScalars.reduce(7) { Int(UInt32(truncatingIfNeeded: $0 &* 31 &+ Int($1.value))) } }

    // ---------------------------------------------------------------- the day

    private func tagColor(_ tag: String?) -> Color {
        guard let t = tag, !t.isEmpty else { return Palette.muted }
        let i = store.engineSettings.tags.firstIndex(of: t) ?? abs(t.hashValue % 6)
        return listColors[i % listColors.count]
    }

    private var timeline: some View {
        panel {
            Text("Timeline").font(.system(size: 13, weight: .bold)).foregroundStyle(Palette.ink)
            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: 10) {
                    ForEach(blocks.sorted { $0.start < $1.start }, id: \.id) { b in
                        HStack(alignment: .top, spacing: 12) {
                            Capsule().fill(tagColor(b.tag)).frame(width: 4)
                            VStack(alignment: .leading, spacing: 3) {
                                HStack { Text(b.title).font(.system(size: 14, weight: .bold)).foregroundStyle(Palette.ink).lineLimit(1); Spacer(); Text("\(clockTime(b.start)) – \(clockTime(b.start + b.actual * 60_000))").font(.system(size: 12)).foregroundStyle(Palette.muted) }
                                Text(detail(b)).font(.system(size: 12)).foregroundStyle(Palette.muted)
                            }
                        }.fixedSize(horizontal: false, vertical: true)
                    }
                    if let a = store.active {
                        HStack(spacing: 12) {
                            CatImage(path: Art.nap(a.cat)).frame(width: 30, height: 30)
                            (Text(a.title).bold() + Text(" · now, \(minLeft(max(0, a.end - nowMs()))) min left")).font(.system(size: 13)).foregroundStyle(Palette.ink)
                            Spacer()
                            Text("FOCUS").font(.system(size: 12, weight: .semibold)).foregroundStyle(Palette.accentText)
                        }
                        .padding(.horizontal, 10).padding(.vertical, 8).background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Palette.accent.opacity(0.08)))
                    }
                    if blocks.isEmpty && store.active == nil { Text("Nothing yet today.").font(.system(size: 13)).foregroundStyle(Palette.muted) }
                }
            }
        }
    }

    private func detail(_ b: Hist) -> String {
        var parts: [String] = []
        if let t = b.tag, !t.isEmpty { parts.append(t) }
        parts.append(b.stopped ? "Stopped early" : b.outcome == "done" ? "Done" : b.outcome == "partly" ? "Partly" : b.outcome == "notreally" ? "Not really" : "\(b.actual) min")
        let name = store.cat(b.cat).name
        parts.append(b.kitten == "home" ? "\(name) came home" : b.kitten == "gone" ? "\(name) wandered off" : "")
        return parts.filter { !$0.isEmpty }.joined(separator: " · ")
    }

    private var hours: some View {
        let h = Insights.byHour(blocks)
        let slice = Array(8...18)
        let maxV = max(1, slice.map { h[$0] }.max() ?? 1)
        let best = slice.max { h[$0] < h[$1] }
        return panel {
            HStack {
                Text("When you focus").font(.system(size: 13, weight: .bold)).foregroundStyle(Palette.ink)
                Spacer()
                if let b = best, h[b] > 0 { Text("most around \(hourName(b))").font(.system(size: 13)).foregroundStyle(Palette.muted) }
            }
            HStack(alignment: .bottom, spacing: 6) {
                ForEach(slice, id: \.self) { i in
                    RoundedRectangle(cornerRadius: 3).fill(h[i] > 0 ? Palette.accent : Palette.ringTrack).frame(height: max(8, CGFloat(h[i]) / CGFloat(maxV) * 72))
                }
            }.frame(height: 76, alignment: .bottom)
            HStack { Text("8 AM"); Spacer(); Text("12 PM"); Spacer(); Text("6 PM") }.font(.system(size: 11)).foregroundStyle(Palette.muted)
        }
    }

    private func hourName(_ h: Int) -> String { h == 0 ? "12 AM" : h < 12 ? "\(h) AM" : h == 12 ? "12 PM" : "\(h - 12) PM" }

    // ---------------------------------------------------------------- the week, the month, the year

    private var chart: some View {
        let slots = Insights.slots(blocks, in: period)
        let maxV = max(1, slots.max() ?? 1)
        let bestIndex = slots.enumerated().max { $0.element < $1.element }?.offset
        let f = DateFormatter()
        return panel {
            HStack {
                Text(span == .year ? "Each month" : "Each day").font(.system(size: 13, weight: .bold)).foregroundStyle(Palette.ink)
                Spacer()
                if let i = bestIndex, slots[i] > 0 { Text("best: \(bestName(i, f)), \(Insights.hours(slots[i]))").font(.system(size: 13)).foregroundStyle(Palette.muted) }
            }
            HStack(alignment: .bottom, spacing: span == .year ? 8 : 3) {
                ForEach(0..<slots.count, id: \.self) { i in
                    VStack(spacing: 4) {
                        RoundedRectangle(cornerRadius: 2).fill(slots[i] > 0 ? Palette.accent : Palette.ringTrack).frame(height: max(8, CGFloat(slots[i]) / CGFloat(maxV) * 84))
                        if span != .month { Text(slotLabel(i)).font(.system(size: 10)).foregroundStyle(Palette.muted) }
                    }
                }
            }.frame(height: span == .month ? 84 : 100, alignment: .bottom)
        }
    }

    private func slotLabel(_ i: Int) -> String {
        span == .week ? ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"][i] : ["J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D"][i]
    }

    private func bestName(_ i: Int, _ f: DateFormatter) -> String {
        switch span {
        case .week: return ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"][i]
        case .month: return "the \(i + 1)\(ordinal(i + 1))"
        default: f.dateFormat = "LLLL"; return f.monthSymbols[i]
        }
    }
    private func ordinal(_ n: Int) -> String { (11...13).contains(n % 100) ? "th" : ["th", "st", "nd", "rd", "th", "th", "th", "th", "th", "th"][n % 10] }

    private var tags: some View {
        let list = Insights.byTag(blocks)
        let total = max(1, list.reduce(0) { $0 + $1.minutes })
        return panel {
            Text("By tag").font(.system(size: 13, weight: .bold)).foregroundStyle(Palette.ink)
            if list.isEmpty { Text("No blocks yet.").font(.system(size: 13)).foregroundStyle(Palette.muted) }
            ForEach(Array(list.prefix(5).enumerated()), id: \.offset) { i, e in
                HStack(spacing: 10) {
                    Text(e.tag).font(.system(size: 13)).foregroundStyle(Palette.ink).frame(width: 74, alignment: .leading).lineLimit(1)
                    GeometryReader { g in
                        ZStack(alignment: .leading) {
                            Capsule().fill(Palette.ringTrack)
                            Capsule().fill(tagColor(e.tag == "Untagged" ? nil : e.tag)).frame(width: g.size.width * CGFloat(e.minutes) / CGFloat(total))
                        }
                    }.frame(height: 8)
                    Text(Insights.hours(e.minutes)).font(.system(size: 12)).foregroundStyle(Palette.muted).frame(width: 64, alignment: .trailing)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- the café page

struct CafePage: View {
    @ObservedObject var store: Store
    @State private var span: Span = .week
    @State private var blocks: [Hist] = []
    @StateObject private var cafe = CafeController()
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let p = Insights.period(span, containing: Date())
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .bottom) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Café").font(.system(size: 30, weight: .bold)).foregroundStyle(Palette.ink)
                    Text("\(Insights.home(blocks)) cats came home \(span == .day ? "today" : "this \(span.rawValue)"). Drag to turn the room, tap a cat.").font(.system(size: 14)).foregroundStyle(Palette.muted)
                }
                Spacer()
                Picker("", selection: $span) { ForEach([Span.day, .week, .month, .year], id: \.self) { Text($0.title).tag($0) } }.pickerStyle(.segmented).frame(width: 260).labelsHidden()
                HStack(spacing: 6) {
                    Button { cafe.spin(-1) } label: { Image(systemName: "arrow.counterclockwise") }.buttonStyle(IconButton(size: 28)).accessibilityLabel("Turn left")
                    Button { cafe.spin(1) } label: { Image(systemName: "arrow.clockwise") }.buttonStyle(IconButton(size: 28)).accessibilityLabel("Turn right")
                }
            }
            CafeView(data: CafeData.make(store: store, blocks: blocks, span: span, dark: scheme == .dark, seed: Int(p.from / 1000)), controller: cafe)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(scheme == .dark ? Color.black : Color(hex: 0xF2F2F7)))
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .padding(.init(top: 34, leading: 36, bottom: 28, trailing: 36))
        .onAppear { blocks = store.blocks(from: p.from, to: p.to) }
        .onChange(of: span) { s in let q = Insights.period(s, containing: Date()); blocks = store.blocks(from: q.from, to: q.to) }
    }
}

// ---------------------------------------------------------------- History and Later

struct HistoryView: View {
    @ObservedObject var store: Store

    var body: some View {
        let days = Dictionary(grouping: store.state.history, by: { Calendar.current.startOfDay(for: Date(timeIntervalSince1970: Double($0.start) / 1000)) })
        Scrolled(title: "History", subtitle: "Every block, newest first.") {
            if store.state.history.isEmpty { Text("No blocks yet. Your first one shows up here.").foregroundStyle(Palette.muted) }
            ForEach(days.keys.sorted(by: >), id: \.self) { day in
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text(dayName(day)).font(.system(size: 13, weight: .bold)).foregroundStyle(Palette.ink)
                        Spacer()
                        Text(fmtMin(days[day]!.reduce(0) { $0 + $1.actual })).font(.system(size: 12)).foregroundStyle(Palette.muted)
                    }
                    VStack(spacing: 0) {
                        ForEach(Array(days[day]!.sorted { $0.start > $1.start }.enumerated()), id: \.element.id) { i, b in
                            HStack(spacing: 12) {
                                CatImage(path: b.kitten == "gone" ? Art.gone : Art.kitten(b.cat)).frame(width: 30, height: 30)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(b.title).font(.system(size: 14, weight: .semibold)).foregroundStyle(Palette.ink).lineLimit(1)
                                    Text([b.tag ?? "", b.stopped ? "Stopped early" : b.outcome.map { $0 == "notreally" ? "Not really" : $0.capitalized } ?? "", b.note].filter { !$0.isEmpty }.joined(separator: " · ")).font(.system(size: 12)).foregroundStyle(Palette.muted).lineLimit(1)
                                }
                                Spacer()
                                VStack(alignment: .trailing, spacing: 2) {
                                    Text(fmtMin(b.actual)).font(.system(size: 13)).foregroundStyle(Palette.ink)
                                    Text(clockTime(b.start)).font(.system(size: 12)).foregroundStyle(Palette.muted)
                                }
                            }
                            .padding(.horizontal, 14).padding(.vertical, 10)
                            if i < days[day]!.count - 1 { Rectangle().fill(Palette.tileLine).frame(height: 1).padding(.leading, 56) }
                        }
                    }
                    .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Palette.tile))
                    .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Palette.tileLine, lineWidth: 1))
                }
            }
        }
    }

    private func dayName(_ d: Date) -> String {
        if Calendar.current.isDateInToday(d) { return "Today" }
        if Calendar.current.isDateInYesterday(d) { return "Yesterday" }
        let f = DateFormatter(); f.dateFormat = "EEEE, d MMMM"; return f.string(from: d)
    }
}

struct LaterView: View {
    @ObservedObject var store: Store
    @State private var text = ""

    var body: some View {
        Scrolled(title: "Later", subtitle: "Thoughts parked from anywhere with \(store.settings.parkKey.label), and things for later.") {
            HStack {
                TitleField(placeholder: "Add something for later", text: $text, size: 14, center: false, onSubmit: add)
                Button("Add", action: add).buttonStyle(Chip(fill: Palette.accent, text: Palette.onAccent, size: 14, radius: 10, padding: EdgeInsets(top: 9, leading: 16, bottom: 9, trailing: 16)))
            }.frame(maxWidth: 520)
            if store.state.view.later.isEmpty { Text("Nothing here yet.").foregroundStyle(Palette.muted) }
            VStack(spacing: 0) {
                ForEach(Array(store.state.view.later.enumerated()), id: \.element.id) { i, l in
                    HStack(spacing: 12) {
                        Button { store.act("doneLater", ["id": l.id]) } label: { Image(systemName: "circle").foregroundStyle(Palette.muted) }.buttonStyle(.plain).accessibilityLabel("Done: \(l.text)")
                        Text(l.text).font(.system(size: 14)).foregroundStyle(Palette.ink)
                        Spacer()
                        if store.active == nil {
                            Button("Start a block") {
                                var f: [String: Any] = ["intention": l.text, "minutes": store.settings.focusMinutes, "style": store.engineSettings.style, "mode": store.preset.stopwatch ? "stopwatch" : "timer", "fromLater": l.id]
                                f["cat"] = store.state.catsOn ? (store.state.companion as Any) : (NSNull() as Any)
                                store.act("start", f)
                            }.buttonStyle(Chip(size: 12, radius: 8, padding: EdgeInsets(top: 5, leading: 10, bottom: 5, trailing: 10)))
                        }
                    }.padding(.horizontal, 14).padding(.vertical, 11)
                    if i < store.state.view.later.count - 1 { Rectangle().fill(Palette.tileLine).frame(height: 1).padding(.leading, 44) }
                }
            }
            .frame(maxWidth: 520)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Palette.tile))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Palette.tileLine, lineWidth: 1))
        }
    }

    private func add() {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        store.act("addLater", ["text": t])
        text = ""
    }
}
