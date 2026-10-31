import AppKit
import SwiftUI

// The widgets' faces. The widget extension draws them from widget/now.json, and the self-test draws the same views to
// PNG files, so the pictures in the README are what the desktop shows. Nothing here needs WidgetKit: the extension adds
// the background and the click target (Widgets.swift).

enum WKind: String, CaseIterable {
    case focus, quick, today, streak, later, cafe

    var name: String {
        switch self {
        case .focus: return "Focus"
        case .quick: return "Quick start"
        case .today: return "Today"
        case .streak: return "Streak and goal"
        case .later: return "Later"
        case .cafe: return "Café"
        }
    }
    var about: String {
        switch self {
        case .focus: return "The block that's on, counting down, with its cat."
        case .quick: return "Start a block of 25, 50 or 90 minutes."
        case .today: return "Today's focus, blocks and cats."
        case .streak: return "Days in a row, and today's goal."
        case .later: return "The thoughts you parked."
        case .cafe: return "Your café today."
        }
    }
    var sizes: [WSize] {
        switch self {
        case .quick, .streak: return [.small, .medium]
        default: return [.small, .medium, .large]
        }
    }
}

enum WSize: CaseIterable {
    case small, medium, large
    /// The sizes macOS gives each family, for the self-test's pictures.
    var points: CGSize {
        switch self {
        case .small: return CGSize(width: 170, height: 170)
        case .medium: return CGSize(width: 364, height: 170)
        case .large: return CGSize(width: 364, height: 382)
        }
    }
}

/// The paintings a face needs, read from widget/ (the cat's from art/, the café's beside now.json).
struct WidgetPictures {
    var images: [String: NSImage] = [:]

    static func load(_ s: WidgetSnapshot?, folder: URL) -> WidgetPictures {
        var p = WidgetPictures()
        guard let s else { return p }
        for name in s.art.values { if let i = NSImage(contentsOf: folder.appendingPathComponent("art/\(name)")) { p.images[name] = i } }
        for name in [s.cafeLight, s.cafeDark].compactMap({ $0 }) { if let i = NSImage(contentsOf: folder.appendingPathComponent(name)) { p.images[name] = i } }
        return p
    }
    func image(_ name: String?) -> NSImage? { name.flatMap { images[$0] } }
}

// ---------------------------------------------------------------- the colours (App/Theme.swift's, for either appearance)

struct WTheme {
    var dark: Bool
    private func c(_ hex: UInt32, _ a: Double = 1) -> Color {
        Color(.sRGB, red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255, opacity: a)
    }
    var ink: Color { dark ? c(0xF2EDE6) : c(0x2B211A) }
    var muted: Color { dark ? c(0xA59C93) : c(0x6B6158) }
    var accent: Color { dark ? c(0x5FD0C0) : c(0x2C8A7E) }
    var accentText: Color { dark ? c(0x8FE3D6) : c(0x1F6B61) }
    var onAccent: Color { dark ? c(0x10302B) : c(0xFFFFFF) }
    var orange: Color { dark ? c(0xFFCF8A) : c(0xE98A3C) }
    var orangeText: Color { dark ? c(0xFFCF8A) : c(0xC4651E) }
    var soft: Color { dark ? c(0xFFFFFF, 0.09) : c(0x2B211A, 0.07) }
    var track: Color { dark ? c(0xFFFFFF, 0.12) : c(0x2B211A, 0.10) }
    var rim: Color { dark ? c(0x3A3340) : c(0xFFF8EE) }
    var wallTop: Color { dark ? c(0x2E2733) : c(0xFDF5E8) }
    var wallBottom: Color { dark ? c(0x221D27) : c(0xF5E4CB) }
    var wallDot: Color { dark ? c(0xFFDCAA, 0.10) : c(0xC49668, 0.26) }
    var card: Color { dark ? c(0x1F1C23) : c(0xFFFCF7) }
    var skyTop: Color { dark ? c(0x243A66) : c(0x8FCDF0) }
    var skyBottom: Color { dark ? c(0x454F7E) : c(0xE7F6FF) }
    var duskTop: Color { c(0x9FD2EE) }
    var duskBottom: Color { c(0xFDF0D8) }
}

private func cf(_ size: CGFloat, _ weight: Font.Weight = .heavy) -> Font { .system(size: size, weight: weight, design: .rounded).monospacedDigit() }
private func rf(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font { .system(size: size, weight: weight, design: .rounded) }

/// "1 h 20", "45 min": short enough for a small widget.
func shortMinutes(_ m: Int) -> String { m < 60 ? "\(m) min" : "\(m / 60) h" + (m % 60 != 0 ? " \(m % 60)" : "") }

// ---------------------------------------------------------------- the background

/// The café wall in cream with its dots, or the dark wall: the widgets' own background.
struct WidgetBackground: View {
    var kind: WKind
    @Environment(\.colorScheme) private var scheme
    var body: some View {
        let t = WTheme(dark: scheme == .dark)
        if kind == .cafe {
            t.card
        } else {
            ZStack {
                LinearGradient(colors: [t.wallTop, t.wallBottom], startPoint: .top, endPoint: .bottom)
                Canvas { ctx, size in
                    var y: CGFloat = 11
                    while y < size.height {
                        var x: CGFloat = 11
                        while x < size.width { ctx.fill(Path(ellipseIn: CGRect(x: x - 1.5, y: y - 1.5, width: 3, height: 3)), with: .color(t.wallDot)); x += 22 }
                        y += 22
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- one face

/// A widget's face for a size, at the entry's time. `tap` is where a click anywhere on it goes.
struct WidgetFace: View {
    var kind: WKind
    var size: WSize
    var snap: WidgetSnapshot?
    var pics: WidgetPictures
    var now: Date
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let t = WTheme(dark: scheme == .dark)
        Group {
            if let s = snap {
                switch kind {
                case .focus: FocusFace(s: s, size: size, pics: pics, now: now, t: t)
                case .quick: QuickFace(s: s, size: size, pics: pics, now: now, t: t)
                case .today: TodayFace(s: s, size: size, now: now, t: t)
                case .streak: StreakFace(s: s, size: size, now: now, t: t)
                case .later: LaterFace(s: s, size: size, t: t)
                case .cafe: CafeFace(s: s, size: size, pics: pics, now: now, t: t, dark: scheme == .dark)
                }
            } else {
                VStack(spacing: 6) {
                    Image(systemName: "pawprint.fill").font(.system(size: 22)).foregroundStyle(t.accent)
                    Text("Open Chataltul").font(rf(14, .semibold)).foregroundStyle(t.ink)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
    }

    /// Where a click on the face goes. Small widgets have only this one; the bigger ones also have their own buttons.
    static func tap(_ kind: WKind, _ s: WidgetSnapshot?) -> URL {
        guard let s else { return WidgetSnapshot.link("open") }
        switch kind {
        case .focus, .quick: return s.phase == .ready ? WidgetSnapshot.startLink(kind == .quick ? s.quick.first ?? 25 : s.focusMinutes) : WidgetSnapshot.link("open")
        case .today, .streak: return WidgetSnapshot.link("window/today")
        case .later: return WidgetSnapshot.link("window/later")
        case .cafe: return WidgetSnapshot.link("window/cafe")
        }
    }
}

// ---------------------------------------------------------------- pieces

/// The round window with the cat in it, and the ring of the block around it.
private struct CatWin: View {
    var image: NSImage?
    var remaining: Double?
    var paused = false
    var dusk: Double = 0
    var growth: Double = 0.85
    var d: CGFloat
    var t: WTheme

    var body: some View {
        let inner = d * 0.86
        ZStack {
            Circle().stroke(t.track, lineWidth: max(3, d * 0.03))
            if let r = remaining {
                Circle().trim(from: 0, to: max(0.001, r))
                    .stroke(paused ? t.orange : t.accent, style: StrokeStyle(lineWidth: max(3, d * 0.03), lineCap: .round))
                    .rotationEffect(.degrees(-90))
            }
            ZStack(alignment: .bottom) {
                LinearGradient(colors: [t.skyTop, t.skyBottom], startPoint: .top, endPoint: .bottom)
                if !t.dark { LinearGradient(colors: [t.duskTop, t.duskBottom], startPoint: .top, endPoint: .bottom).opacity(max(0, dusk - 0.4) / 0.6 * 0.8) }
                Rectangle().fill(t.rim).opacity(0.75).frame(width: 2, height: inner / 3).frame(maxHeight: .infinity, alignment: .top)
                Rectangle().fill(t.rim).opacity(0.75).frame(height: 2).frame(maxHeight: .infinity, alignment: .top).padding(.top, inner / 3)
                if let i = image {
                    Image(nsImage: i).resizable().interpolation(.high).aspectRatio(contentMode: .fit)
                        .frame(width: inner * 0.8 * growth, height: inner * 0.8 * growth)
                        .padding(.bottom, inner * 0.03)
                }
            }
            .frame(width: inner, height: inner)
            .clipShape(Circle())
            .overlay(Circle().strokeBorder(t.rim, lineWidth: max(3, d * 0.035)))
        }
        .frame(width: d, height: d)
    }
}

/// A button in a widget: a link the app takes.
private struct Pill: View {
    var label: String
    var url: URL
    var primary = false
    var symbol: String?
    var t: WTheme
    var font: CGFloat = 13
    var body: some View {
        Link(destination: url) {
            HStack(spacing: 5) {
                if let s = symbol { Image(systemName: s).font(.system(size: font - 2, weight: .bold)) }
                Text(label).font(rf(font, .bold))
            }
            .foregroundStyle(primary ? t.onAccent : t.ink)
            .padding(.horizontal, 12).frame(height: font + 15)
            .background(Capsule().fill(primary ? t.accent : t.soft))
        }
    }
}

private struct WLabel: View {
    var text: String
    var color: Color
    var body: some View { Text(text.uppercased()).font(.system(size: 10, weight: .bold)).kerning(0.6).foregroundStyle(color).lineLimit(1) }
}

/// One dash per block of the day.
private struct WDashes: View {
    var total: Int
    var done: Int
    var t: WTheme
    var body: some View {
        HStack(spacing: 4) {
            ForEach(0..<max(1, max(total, done)), id: \.self) { i in Capsule().fill(i < done ? t.accent : t.track).frame(height: 4) }
        }
    }
}

/// Today's minutes against the goal, or the blocks against the day's when there is no goal.
private struct GoalRing: View {
    var value: Double
    var big: String
    var small: String
    var d: CGFloat
    var t: WTheme
    var body: some View {
        ZStack {
            Circle().stroke(t.track, lineWidth: d * 0.075)
            Circle().trim(from: 0, to: max(0.001, min(1, value)))
                .stroke(t.accent, style: StrokeStyle(lineWidth: d * 0.075, lineCap: .round)).rotationEffect(.degrees(-90))
            VStack(spacing: 0) {
                Text(big).font(cf(d * 0.2)).foregroundStyle(t.ink).minimumScaleFactor(0.6).lineLimit(1)
                Text(small).font(rf(d * 0.1)).foregroundStyle(t.muted).lineLimit(1)
            }
            .padding(.horizontal, d * 0.12)
        }
        .frame(width: d, height: d)
    }
}

// ---------------------------------------------------------------- Focus

/// What the Focus widget says at a time.
private struct FocusLook {
    var label: String
    var labelOrange = false
    var title: String
    var time: AnyView?
    var remaining: Double?
    var paused = false
    var stage: WidgetSnapshot.Stage = .nap
    var growth: Double = 0.85

    init(_ s: WidgetSnapshot, now: Date, t: WTheme, big: CGFloat) {
        let ms = Int(now.timeIntervalSince1970 * 1000)
        title = s.run?.title ?? ""
        stage = s.stage(at: ms)
        let p = s.progress(at: ms)
        growth = stage == .nap ? 0.62 + 0.15 * (p / 0.25) : stage == .kitten ? 0.75 + 0.15 * min(1, max(0, (p - 0.25) / 0.5)) : 0.95
        func clockText(_ v: some View) -> AnyView { AnyView(v.font(cf(big)).foregroundStyle(t.ink).lineLimit(1).minimumScaleFactor(0.5)) }
        if s.isStale(at: ms) || (s.phase == .onBreak && (s.breakEnd ?? 0) <= ms) {
            label = "Chataltul"; title = "Open it to go on"; stage = .nap; growth = 0.7
            return
        }
        switch (s.phase, s.run) {
        case (.ready, _), (.focus, nil), (.checkin, nil), (.paused, nil):
            label = "Ready"; title = "\(s.focusMinutes) minutes"; stage = .nap; growth = 0.7
        case (.focus, let r?), (.checkin, let r?):
            label = s.phase == .checkin ? "Check-in" : "Focusing"
            labelOrange = s.phase == .checkin
            remaining = r.stopwatch ? nil : 1 - p
            let start = Date(timeIntervalSince1970: Double(r.start) / 1000), end = Date(timeIntervalSince1970: Double(r.end) / 1000)
            time = r.stopwatch ? clockText(Text(start, style: .timer)) : clockText(Text(timerInterval: start...max(start, end), countsDown: true))
        case (.paused, let r?):
            label = "Paused"; labelOrange = true; paused = true
            remaining = r.stopwatch ? nil : 1 - p
            time = clockText(Text(fmtClock(r.stopwatch ? (r.pausedAt ?? ms) - r.start : r.end - (r.pausedAt ?? ms))))
        case (.onBreak, _):
            label = "Break"; title = s.catName + " is resting"; stage = .nap; growth = 0.7
            let e = Date(timeIntervalSince1970: Double(s.breakEnd ?? ms) / 1000)
            time = clockText(Text(timerInterval: now...max(now, e), countsDown: true))
        case (.done, _):
            let home = s.run?.kitten == "home"
            label = home ? "\(s.catName) came home" : "\(s.catName) wandered off"
            title = home ? "Nice, \(max(1, ((s.run?.end ?? 0) - (s.run?.start ?? 0)) / 60_000)) minutes" : "Your block ended"
            remaining = home ? 1 : nil
        }
    }
}

private struct FocusFace: View {
    var s: WidgetSnapshot
    var size: WSize
    var pics: WidgetPictures
    var now: Date
    var t: WTheme

    var body: some View {
        let big: CGFloat = size == .small ? 26 : size == .medium ? 34 : 48
        let f = FocusLook(s, now: now, t: t, big: big)
        let img = s.catsOn ? pics.image(s.artName(f.stage)) : nil
        let dusk = 1 - (f.remaining ?? 1)
        switch size {
        case .small:
            VStack(spacing: 4) {
                CatWin(image: img, remaining: f.remaining, paused: f.paused, dusk: dusk, growth: f.growth, d: 92, t: t)
                if let time = f.time { time } else if s.phase == .ready {
                    Text("Start \(s.focusMinutes)").font(rf(14, .bold)).foregroundStyle(t.onAccent)
                        .padding(.horizontal, 16).frame(height: 30).background(Capsule().fill(t.accent))
                } else { WLabel(text: f.label, color: f.labelOrange ? t.orangeText : t.accentText) }
                Text(f.time == nil && s.phase == .ready ? " " : f.title).font(rf(11)).foregroundStyle(t.muted).lineLimit(1)
                    .opacity(f.time == nil && s.phase == .ready ? 0 : 1)
            }
            .padding(12)
        case .medium:
            HStack(spacing: 14) {
                CatWin(image: img, remaining: f.remaining, paused: f.paused, dusk: dusk, growth: f.growth, d: 128, t: t)
                VStack(alignment: .leading, spacing: 3) {
                    WLabel(text: f.label, color: f.labelOrange ? t.orangeText : t.accentText)
                    if let time = f.time { time } else {
                        Text(s.phase == .ready ? "\(s.catName) is waiting" : f.title).font(rf(19, .bold)).foregroundStyle(t.ink).lineLimit(2).minimumScaleFactor(0.8)
                    }
                    if f.time != nil || s.phase != .ready {
                        Text(f.time != nil ? f.title : s.phase == .done ? (s.run?.title ?? "") : "").font(rf(13)).foregroundStyle(t.muted).lineLimit(1)
                    }
                    Spacer(minLength: 4)
                    buttons(f)
                }
                Spacer(minLength: 0)
            }
            .padding(16)
        case .large:
            VStack(spacing: 6) {
                CatWin(image: img, remaining: f.remaining, paused: f.paused, dusk: dusk, growth: f.growth, d: 176, t: t)
                WLabel(text: f.label, color: f.labelOrange ? t.orangeText : t.accentText).padding(.top, 4)
                if let time = f.time { time } else {
                    Text(s.phase == .ready ? "\(s.catName) is waiting" : f.title).font(rf(22, .bold)).foregroundStyle(t.ink).lineLimit(1).minimumScaleFactor(0.7)
                }
                if f.time != nil || s.phase == .done { Text(f.time != nil ? f.title : s.run?.title ?? "").font(rf(14)).foregroundStyle(t.muted).lineLimit(1) }
                Spacer(minLength: 2)
                buttons(f)
                let d = s.dayNumbers(at: Int(now.timeIntervalSince1970 * 1000))
                VStack(spacing: 5) {
                    WDashes(total: s.perDay, done: d.blocks, t: t)
                    Text("\(d.blocks) of \(s.perDay) blocks · \(shortMinutes(d.minutes)) today").font(rf(12)).foregroundStyle(t.muted)
                }
                .padding(.top, 6)
            }
            .padding(18)
        }
    }

    @ViewBuilder private func buttons(_ f: FocusLook) -> some View {
        switch s.phase {
        case .ready:
            HStack(spacing: 6) {
                Pill(label: "Start", url: WidgetSnapshot.startLink(s.focusMinutes), primary: true, symbol: "play.fill", t: t)
                ForEach(s.quick.filter { $0 != s.focusMinutes }.prefix(size == .large ? 2 : 1), id: \.self) { m in Pill(label: "\(m)", url: WidgetSnapshot.startLink(m), t: t) }
            }
        case .checkin:
            Pill(label: "Answer", url: WidgetSnapshot.link("open"), primary: true, t: t)
        case .done:
            Pill(label: "How did it go?", url: WidgetSnapshot.link("open"), primary: true, t: t)
        default:
            Pill(label: "Open", url: WidgetSnapshot.link("open"), t: t)
        }
    }
}

// ---------------------------------------------------------------- Quick start

private struct QuickFace: View {
    var s: WidgetSnapshot
    var size: WSize
    var pics: WidgetPictures
    var now: Date
    var t: WTheme

    var body: some View {
        let ms = Int(now.timeIntervalSince1970 * 1000)
        let busy = s.phase != .ready && !s.isStale(at: ms)
        let img = s.catsOn ? pics.image(s.artName(.kitten)) : nil
        switch size {
        case .small, .large:
            VStack(spacing: 8) {
                if let i = img { Image(nsImage: i).resizable().aspectRatio(contentMode: .fit).frame(height: 76) }
                else { Image(systemName: "timer").font(.system(size: 40)).foregroundStyle(t.accent).frame(height: 76) }
                if busy { Text(busyText).font(rf(13, .semibold)).foregroundStyle(t.muted).lineLimit(1) }
                else {
                    Text("Start \(s.quick.first ?? 25)").font(rf(15, .bold)).foregroundStyle(t.onAccent)
                        .padding(.horizontal, 18).frame(height: 34).background(Capsule().fill(t.accent))
                }
            }
            .padding(14)
        case .medium:
            HStack(spacing: 10) {
                if let i = img { Image(nsImage: i).resizable().aspectRatio(contentMode: .fit).frame(width: 84) }
                VStack(alignment: .leading, spacing: 8) {
                    WLabel(text: busy ? busyText : "Start a block", color: t.accentText)
                    HStack(spacing: 7) {
                        ForEach(Array(s.quick.enumerated()), id: \.offset) { k, m in
                            Link(destination: busy ? WidgetSnapshot.link("open") : WidgetSnapshot.startLink(m)) {
                                VStack(spacing: 0) {
                                    Text("\(m)").font(cf(22)).foregroundStyle(k == 0 && !busy ? t.onAccent : t.ink)
                                    Text("min").font(rf(10, .semibold)).foregroundStyle(k == 0 && !busy ? t.onAccent.opacity(0.85) : t.muted)
                                }
                                .frame(width: 62, height: 62)
                                .background(Circle().fill(k == 0 && !busy ? t.accent : t.soft))
                            }
                        }
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(16)
        }
    }

    private var busyText: String {
        switch s.phase {
        case .onBreak: return "On a break"
        case .done: return "\(s.catName) is back"
        case .paused: return "Paused"
        default: return "Focusing"
        }
    }
}

// ---------------------------------------------------------------- Today

private struct TodayFace: View {
    var s: WidgetSnapshot
    var size: WSize
    var now: Date
    var t: WTheme

    var body: some View {
        let d = s.dayNumbers(at: Int(now.timeIntervalSince1970 * 1000))
        switch size {
        case .small:
            VStack(alignment: .leading, spacing: 4) {
                WLabel(text: "Today", color: t.accentText)
                Text(shortMinutes(d.minutes)).font(cf(30)).foregroundStyle(t.ink).lineLimit(1).minimumScaleFactor(0.6)
                Text("focused").font(rf(12)).foregroundStyle(t.muted)
                Spacer(minLength: 2)
                WDashes(total: s.perDay, done: d.blocks, t: t)
                Text("\(d.blocks) of \(s.perDay) blocks").font(rf(12, .semibold)).foregroundStyle(t.ink)
                Text(cats(d.home)).font(rf(12)).foregroundStyle(t.muted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
        case .medium:
            HStack(spacing: 16) {
                GoalRing(value: Double(d.blocks) / Double(max(1, s.perDay)), big: "\(d.blocks)", small: "of \(s.perDay)", d: 118, t: t)
                VStack(alignment: .leading, spacing: 3) {
                    WLabel(text: "Today", color: t.accentText)
                    Text(shortMinutes(d.minutes)).font(cf(28)).foregroundStyle(t.ink).lineLimit(1).minimumScaleFactor(0.7)
                    Text("focused · " + cats(d.home)).font(rf(12)).foregroundStyle(t.muted).lineLimit(1)
                    Spacer(minLength: 4)
                    ForEach(Array(d.list.prefix(2).enumerated()), id: \.offset) { _, b in row(b) }
                    if d.list.isEmpty { Text("No blocks yet").font(rf(12)).foregroundStyle(t.muted) }
                }
                Spacer(minLength: 0)
            }
            .padding(16)
        case .large:
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .center, spacing: 16) {
                    GoalRing(value: Double(d.blocks) / Double(max(1, s.perDay)), big: "\(d.blocks)", small: "of \(s.perDay)", d: 104, t: t)
                    VStack(alignment: .leading, spacing: 3) {
                        WLabel(text: "Today", color: t.accentText)
                        Text(shortMinutes(d.minutes)).font(cf(30)).foregroundStyle(t.ink).lineLimit(1)
                        Text("focused · " + cats(d.home)).font(rf(12)).foregroundStyle(t.muted)
                    }
                    Spacer(minLength: 0)
                }
                Rectangle().fill(t.track).frame(height: 1)
                if d.list.isEmpty { Text("No blocks yet").font(rf(13)).foregroundStyle(t.muted) }
                ForEach(Array(d.list.prefix(6).enumerated()), id: \.offset) { _, b in row(b) }
                Spacer(minLength: 0)
                // the week under it, a paw for each day with a block that ran to its end
                HStack(alignment: .bottom, spacing: 0) {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(s.streak == 1 ? "1 day" : "\(s.streak) days").font(rf(15, .bold)).foregroundStyle(t.ink)
                        Text("in a row").font(rf(11)).foregroundStyle(t.muted)
                    }
                    Spacer(minLength: 8)
                    ForEach(Array(s.week.enumerated()), id: \.offset) { k, on in
                        VStack(spacing: 3) {
                            Image(systemName: on ? "pawprint.fill" : "pawprint").font(.system(size: 12)).foregroundStyle(on ? t.accent : t.track)
                            Text(k < s.weekLetters.count ? s.weekLetters[k] : "").font(rf(9, .semibold)).foregroundStyle(t.muted)
                        }
                        .frame(width: 24)
                    }
                }
            }
            .padding(18)
        }
    }

    private func cats(_ n: Int) -> String { n == 1 ? "1 cat home" : "\(n) cats home" }

    private func row(_ b: WidgetSnapshot.Done) -> some View {
        HStack(spacing: 7) {
            Image(systemName: b.home ? "pawprint.fill" : "pawprint").font(.system(size: 10)).foregroundStyle(b.home ? t.accent : t.muted)
            Text(b.title).font(rf(12)).foregroundStyle(t.ink).lineLimit(1)
            Spacer(minLength: 4)
            Text(shortMinutes(b.minutes)).font(rf(12)).foregroundStyle(t.muted).monospacedDigit()
        }
    }
}

// ---------------------------------------------------------------- Streak and goal

private struct StreakFace: View {
    var s: WidgetSnapshot
    var size: WSize
    var now: Date
    var t: WTheme

    var body: some View {
        let d = s.dayNumbers(at: Int(now.timeIntervalSince1970 * 1000))
        let goal = s.goalMin > 0
        let ring = GoalRing(value: goal ? Double(d.minutes) / Double(s.goalMin) : Double(d.blocks) / Double(max(1, s.perDay)),
                            big: goal ? shortMinutes(d.minutes) : "\(d.blocks)", small: goal ? "of \(shortMinutes(s.goalMin))" : "of \(s.perDay) blocks",
                            d: size == .small ? 92 : 122, t: t)
        switch size {
        case .small, .large:
            VStack(spacing: 6) {
                ring
                Text(days + " in a row").font(rf(13, .bold)).foregroundStyle(t.ink).lineLimit(1).minimumScaleFactor(0.8)
            }
            .padding(14)
        case .medium:
            HStack(spacing: 18) {
                ring
                VStack(alignment: .leading, spacing: 4) {
                    Text(days).font(rf(22, .bold)).foregroundStyle(t.ink)
                    Text("in a row").font(rf(12)).foregroundStyle(t.muted)
                    HStack(spacing: 7) {
                        ForEach(Array(s.week.enumerated()), id: \.offset) { k, on in
                            VStack(spacing: 3) {
                                Image(systemName: on ? "pawprint.fill" : "pawprint").font(.system(size: 12)).foregroundStyle(on ? t.accent : t.track)
                                Text(k < s.weekLetters.count ? s.weekLetters[k] : "").font(rf(9, .semibold)).foregroundStyle(t.muted)
                            }
                        }
                    }
                    .padding(.top, 6)
                    if s.pausesLeft > 0 { Text("1 pause left this week").font(rf(11)).foregroundStyle(t.muted).padding(.top, 2) }
                }
                Spacer(minLength: 0)
            }
            .padding(16)
        }
    }

    private var days: String { s.streak == 1 ? "1 day" : "\(s.streak) days" }
}

// ---------------------------------------------------------------- Later

private struct LaterFace: View {
    var s: WidgetSnapshot
    var size: WSize
    var t: WTheme

    var body: some View {
        let rows = size == .small ? 4 : size == .medium ? 3 : 8
        VStack(alignment: .leading, spacing: size == .small ? 5 : 0) {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text("Later").font(rf(size == .small ? 15 : 17, .bold)).foregroundStyle(t.ink)
                Text(s.laterCount == 1 ? "1 thing" : "\(s.laterCount) things").font(rf(12)).foregroundStyle(t.muted)
                Spacer(minLength: 0)
                if size != .small { Pill(label: "Park a thought", url: WidgetSnapshot.link("park"), symbol: "plus", t: t, font: 12) }
            }
            .padding(.bottom, size == .small ? 2 : 8)
            if s.later.isEmpty {
                Text("Nothing parked").font(rf(13)).foregroundStyle(t.muted).padding(.top, 6)
            }
            ForEach(Array(s.later.prefix(rows).enumerated()), id: \.offset) { k, text in
                if size == .small {
                    HStack(alignment: .top, spacing: 6) {
                        Circle().stroke(t.muted, lineWidth: 1.2).frame(width: 9, height: 9).padding(.top, 3)
                        Text(text).font(rf(12)).foregroundStyle(t.ink).lineLimit(1)
                    }
                } else {
                    if k > 0 { Rectangle().fill(t.track).frame(height: 1) }
                    HStack(spacing: 10) {
                        Circle().stroke(t.muted, lineWidth: 1.5).frame(width: 14, height: 14)
                        Text(text).font(rf(13)).foregroundStyle(t.ink).lineLimit(1)
                        Spacer(minLength: 0)
                    }
                    .frame(height: 30)
                }
            }
            if s.laterCount > rows { Text("+ \(s.laterCount - rows) more").font(rf(11)).foregroundStyle(t.muted).padding(.top, 4) }
            Spacer(minLength: 0)
        }
        .padding(size == .small ? 14 : 16)
    }
}

// ---------------------------------------------------------------- Café

private struct CafeFace: View {
    var s: WidgetSnapshot
    var size: WSize
    var pics: WidgetPictures
    var now: Date
    var t: WTheme
    var dark: Bool

    var body: some View {
        let d = s.dayNumbers(at: Int(now.timeIntervalSince1970 * 1000))
        ZStack(alignment: .bottomLeading) {
            if let i = pics.image(dark ? s.cafeDark : s.cafeLight) {
                Image(nsImage: i).resizable().interpolation(.high).aspectRatio(contentMode: .fit)
                    .scaleEffect(size == .small ? 1.25 : 1.05)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .offset(y: size == .small ? -6 : -10)
            }
            VStack(alignment: .leading, spacing: 1) {
                Text(size == .small ? "Café" : "Your café today").font(rf(size == .small ? 12 : 14, .bold)).foregroundStyle(t.ink)
                Text(d.home == 0 ? "No cats yet" : d.home == 1 ? "1 cat came home" : "\(d.home) cats came home").font(rf(11)).foregroundStyle(t.muted)
            }
            .padding(size == .small ? 10 : 14)
        }
        .clipped()
    }
}
