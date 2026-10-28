import AppKit
import SwiftUI

/// What the popover can ask the rest of the app to do.
struct PopoverActions {
    var openSettings: (String?) -> Void = { _ in }
    var openCafe: () -> Void = {}
    var park: () -> Void = {}
    var quit: () -> Void = {}
    var allowBrowsers: () -> Void = {}
    /// Yes or Drifted, through the app (Drifted asks what pulled the person away).
    var answer: (String) -> Void = { _ in }
    var openWeek: () -> Void = {}
}

enum BlockKind: String { case focus, short, long }

/// The popover: ready, focusing, came home, back from being away, or on a break.
struct PopoverRoot: View {
    @ObservedObject var store: Store
    var actions: PopoverActions
    @State private var sounds = false

    var body: some View {
        content
            .frame(width: 340)
            .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Palette.card))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(Palette.ringTrack, lineWidth: 1))
    }

    @ViewBuilder private var content: some View {
        if sounds {
            SoundsPane(store: store, done: { sounds = false })
        } else if let away = store.awayPrompt, store.active != nil {
            AwayPane(store: store, away: away)
        } else if let brk = store.onBreak, store.active == nil {
            BreakPane(store: store, actions: actions, brk: brk)
        } else if let b = store.awaiting {
            HomePane(store: store, actions: actions, block: b)
        } else if store.active != nil {
            FocusPane(store: store, actions: actions, sounds: { sounds = true })
        } else {
            ReadyPane(store: store, actions: actions, sounds: { sounds = true })
        }
    }
}

// ---------------------------------------------------------------- shared pieces

private struct Header: View {
    var center: String
    var actions: PopoverActions
    var body: some View {
        HStack {
            Button { actions.openWeek() } label: { Image(systemName: "chart.bar").font(.system(size: 14, weight: .medium)) }
                .buttonStyle(IconButton()).accessibilityLabel("Your week")
            Spacer()
            Text(center).font(.system(size: 12, weight: .semibold)).foregroundStyle(Palette.muted)
            Spacer()
            Button { actions.openSettings(nil) } label: { Symbol.gear() }
                .buttonStyle(IconButton()).accessibilityLabel("Settings")
        }
    }
}

private struct Footer: View {
    var left: String
    var actions: PopoverActions
    var quit = false
    var body: some View {
        VStack(spacing: 0) {
            Rectangle().fill(Palette.hairline).frame(height: 1)
            HStack {
                Text(left).font(.system(size: 12)).foregroundStyle(Palette.muted).lineLimit(1)
                Spacer()
                HStack(spacing: 12) {
                    Button("Open the café", action: actions.openCafe).buttonStyle(.plain).foregroundStyle(Palette.accentText)
                    if quit { Button("Quit", action: actions.quit).buttonStyle(.plain).foregroundStyle(Palette.muted) }
                }.font(.system(size: 12))
            }.padding(.top, 10)
        }
    }
}

private extension Store {
    var perDay: Int { settings.cycle.perDay }
    var todayLine: String { "\(fmtMin(state.stats.today)) today · \(catsHomeToday) cat\(catsHomeToday == 1 ? "" : "s") home" }
}

// ---------------------------------------------------------------- ready

struct ReadyPane: View {
    @ObservedObject var store: Store
    var actions: PopoverActions
    var sounds: () -> Void
    @State private var kind: BlockKind = .focus
    @State private var title = ""
    @FocusState private var titleFocus: Bool

    private var minutes: Int {
        switch kind {
        case .focus: return store.preset.stopwatch ? 0 : store.settings.focusMinutes
        case .short: return store.settings.cycle.shortBreak
        case .long: return store.settings.cycle.longBreak
        }
    }

    var body: some View {
        VStack(spacing: 14) {
            Header(center: "\(store.blocksToday) of \(store.perDay) blocks", actions: actions)
            VStack(alignment: .leading, spacing: 6) {
                Label11(text: "TODAY")
                if let next = store.state.view.later.first { Text("Up next: \(next.text)").font(.system(size: 15, weight: .bold)).foregroundStyle(Palette.ink).lineLimit(1) }
                Dashes(total: store.perDay, done: store.blocksToday).padding(.top, 4)
            }.frame(maxWidth: .infinity, alignment: .leading)
            if let offer = store.state.view.breakOffer { OfferedBreak(store: store, minutes: offer.minutes) }
            CatWindow(cat: store.shownCat.id, stage: .nap, size: 208, remaining: nil, growth: 0.82)
            VStack(spacing: 2) {
                Text(fmtClock(minutes * 60_000)).font(clockFont(46)).foregroundStyle(Palette.ink)
                Label11(text: kind == .focus ? "READY" : "BREAK", spacing: 1.6)
            }.padding(.top, -4)
            if kind == .focus {
                TitleField(placeholder: "Add a title (optional)", text: $title, focused: $titleFocus, onSubmit: start)
            }
            KindPicker(kind: $kind, store: store)
            HStack(spacing: 8) {
                Button(action: start) {
                    HStack(spacing: 8) { Symbol.play(); Text(kind == .focus ? "Start block" : "Start break") }
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(Chip(fill: Palette.accent, text: Palette.onAccent, size: 15, radius: 11, padding: EdgeInsets(top: 11, leading: 12, bottom: 11, trailing: 12)))
                Button(action: sounds) { Symbol.note() }.buttonStyle(IconButton(size: 42)).accessibilityLabel("Sound")
            }
            Text(summary).font(.system(size: 12)).foregroundStyle(Palette.muted).padding(.top, -6)
            Footer(left: store.todayLine, actions: actions, quit: true)
        }
        .padding(.init(top: 16, leading: 18, bottom: 14, trailing: 18))
        .onReceive(NotificationCenter.default.publisher(for: .focusTitle)) { _ in kind = .focus; titleFocus = true }
    }

    private var summary: String {
        let s = store.engineSettings
        let check = s.style == "ask" ? "check in every \(s.interval) min" : "nudges every \(s.interval) min"
        return "\(store.preset.title) · \(check) · \(store.mixLabel)"
    }

    private func start() {
        switch kind {
        case .focus: store.start(title: title.trimmingCharacters(in: .whitespacesAndNewlines), stopwatch: store.preset.stopwatch); title = ""
        case .short: store.startBreak(store.settings.cycle.shortBreak)
        case .long: store.startBreak(store.settings.cycle.longBreak)
        }
    }
}

/// "Break · 5 min" after a block was filed, one tap to start it.
private struct OfferedBreak: View {
    @ObservedObject var store: Store
    var minutes: Int
    var body: some View {
        let long = store.preset != .flow && store.settings.cycle.isLong(afterBlocks: store.blocksToday)
        let mins = store.breakAfter(nil) == 0 ? minutes : store.breakAfter(nil)
        HStack(spacing: 10) {
            CatImage(path: Art.nap(store.state.companion)).frame(width: 34, height: 34)
            VStack(alignment: .leading, spacing: 1) {
                Text(long ? "Long break · \(mins) min" : "Break · \(mins) min").font(.system(size: 13, weight: .bold)).foregroundStyle(Palette.ink)
                Text("Stand up and stretch.").font(.system(size: 12)).foregroundStyle(Palette.muted).lineLimit(1)
            }
            Spacer(minLength: 0)
            Button("Start") { store.startBreak(mins) }
                .buttonStyle(Chip(fill: Palette.accent, text: Palette.onAccent, size: 13, radius: 9, padding: EdgeInsets(top: 7, leading: 12, bottom: 7, trailing: 12)))
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Palette.accent.opacity(0.10)))
    }
}

/// Focus, Break, Long: the three lengths of the cycle. The selected one's length can be changed.
private struct KindPicker: View {
    @Binding var kind: BlockKind
    @ObservedObject var store: Store
    private let presets: [BlockKind: [Int]] = [.focus: [15, 20, 25, 30, 45, 50, 52, 60, 90], .short: [5, 10, 15, 17, 20], .long: [10, 15, 20, 25, 30]]

    var body: some View {
        HStack(spacing: 2) {
            tab(.focus, "Focus", store.preset.stopwatch ? 0 : store.settings.focusMinutes)
            tab(.short, "Break", store.settings.cycle.shortBreak)
            tab(.long, "Long", store.settings.cycle.longBreak)
        }
        .padding(3)
        .background(RoundedRectangle(cornerRadius: 11, style: .continuous).fill(Palette.soft))
    }

    private func tab(_ k: BlockKind, _ name: String, _ mins: Int) -> some View {
        let on = kind == k
        return VStack(spacing: 0) {
            Text(name).font(.system(size: 13, weight: on ? .bold : .semibold))
            if mins == 0 {
                Text("stopwatch").font(.system(size: 12)).foregroundStyle(on ? Palette.accentText : Palette.muted)
            } else if on {
                Menu {
                    ForEach(presets[k] ?? [], id: \.self) { m in Button("\(m) min") { set(k, m) } }
                } label: { Text("\(mins)m").font(.system(size: 12, weight: .semibold)).foregroundStyle(Palette.accentText) }
                .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize()
            } else {
                Text("\(mins)m").font(.system(size: 12))
            }
        }
        .foregroundStyle(on ? Palette.ink : Palette.muted2)
        .frame(maxWidth: .infinity).padding(.vertical, 6)
        .background(RoundedRectangle(cornerRadius: 8, style: .continuous).fill(on ? Palette.card : .clear).shadow(color: .black.opacity(on ? 0.12 : 0), radius: 2, y: 1))
        .contentShape(Rectangle())
        .onTapGesture { kind = k }
    }

    private func set(_ k: BlockKind, _ m: Int) {
        switch k {
        case .focus: store.settings.focusMinutes = m
        case .short: store.settings.cycle.shortBreak = m
        case .long: store.settings.cycle.longBreak = m
        }
    }
}

// ---------------------------------------------------------------- focusing

struct FocusPane: View {
    @ObservedObject var store: Store
    var actions: PopoverActions
    var sounds: () -> Void
    @State private var sure = false

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { _ in
            if let b = store.active { content(b, now: nowMs()) }
        }
    }

    @ViewBuilder private func content(_ b: Block, now: Int) -> some View {
        let at = b.paused ?? now
        let total = max(1, b.end - b.start)
        let p = max(0, min(1, Double(at - b.start) / Double(total)))
        let (stage, prog) = Art.stage(b, now: at)
        let left = max(0, b.end - at)
        VStack(spacing: 14) {
            Header(center: "Block \(min(store.blocksToday + 1, max(store.perDay, store.blocksToday + 1))) of \(store.perDay)", actions: actions)
            VStack(alignment: .leading, spacing: 6) {
                Label11(text: b.paused != nil ? "PAUSED" : "FOCUSING")
                Text(b.title).font(.system(size: 17, weight: .bold)).foregroundStyle(Palette.ink).lineLimit(1)
                Dashes(total: store.perDay, done: store.blocksToday, current: p).padding(.top, 4)
            }.frame(maxWidth: .infinity, alignment: .leading)
            CatWindow(cat: b.cat, stage: stage, small: Art.smallBlock(b, now: now), size: 208, remaining: b.stopwatch ? nil : 1 - p, growth: Art.size(stage, prog), skyProgress: p, paused: b.paused != nil)
            VStack(spacing: 2) {
                Text(fmtClock(b.stopwatch ? at - b.start : left)).font(clockFont(46)).foregroundStyle(b.paused != nil ? Palette.orange : Palette.ink)
                Label11(text: b.paused != nil ? "PAUSED" : "FOCUS", color: b.paused != nil ? Palette.orange : Palette.accent, spacing: 1.6)
            }.padding(.top, -4)
            if b.pending != nil { checkin }
            else { Text(nextLine(b, now: now)).font(.system(size: 12)).foregroundStyle(Palette.muted) }
            buttons(b, now: now)
            Footer(left: guarding(b), actions: actions)
        }
        .padding(.init(top: 16, leading: 18, bottom: 14, trailing: 18))
    }

    private var checkin: some View {
        VStack(spacing: 8) {
            HStack(spacing: 6) {
                Circle().fill(Palette.orange).frame(width: 8, height: 8)
                Text("A check-in is waiting").font(.system(size: 13, weight: .semibold)).foregroundStyle(Palette.ink)
            }
            HStack(spacing: 8) {
                Button { actions.answer("yes") } label: { HStack { Symbol.check(); Text("Yes") }.frame(maxWidth: .infinity) }
                    .buttonStyle(Chip(fill: Palette.accent, text: Palette.onAccent, size: 14, radius: 10))
                Button { actions.answer("drifted") } label: { HStack { Symbol.drift(); Text("Drifted") }.frame(maxWidth: .infinity) }
                    .buttonStyle(Chip(size: 14, radius: 10))
            }
        }
    }

    private func nextLine(_ b: Block, now: Int) -> String {
        var parts: [String] = []
        if b.paused != nil { parts.append("Starts again by itself in \(minLeft(max(0, (b.pauseEnds ?? now) - now))) min") }
        else if let n = b.next, n > now { parts.append("Next \(b.style == "ask" ? "check-in" : "nudge") in \(minLeft(n - now)) min") }
        else if b.style == "ask" && b.next == nil { parts.append("Check-in at the end") }
        if !store.playableMix.isEmpty { parts.append(store.mixLabel) }
        return parts.joined(separator: " · ")
    }

    private func buttons(_ b: Block, now: Int) -> some View {
        HStack(spacing: 8) {
            if b.paused != nil {
                Button { store.resume() } label: { HStack(spacing: 8) { Symbol.play(); Text("Resume") }.frame(maxWidth: .infinity) }
                    .buttonStyle(Chip(fill: Palette.orange, text: Palette.orangeInk, size: 15, radius: 11, padding: EdgeInsets(top: 11, leading: 12, bottom: 11, trailing: 12)))
            } else if store.state.canPause && !b.stopwatch {
                Button { store.pause() } label: { HStack(spacing: 8) { Symbol.pause(); Text("Pause") }.frame(maxWidth: .infinity) }
                    .buttonStyle(Chip(fill: Palette.orange, text: Palette.orangeInk, size: 15, radius: 11, padding: EdgeInsets(top: 11, leading: 12, bottom: 11, trailing: 12)))
            } else {
                Button { sounds() } label: { HStack(spacing: 8) { Symbol.note(); Text(store.mixLabel) }.frame(maxWidth: .infinity) }
                    .buttonStyle(Chip(size: 15, radius: 11, padding: EdgeInsets(top: 11, leading: 12, bottom: 11, trailing: 12)))
            }
            Button { actions.park() } label: { Symbol.park() }.buttonStyle(IconButton(size: 42)).accessibilityLabel("Park a thought")
            if let until = store.lockedUntil {
                // a locked list is on: the block can't be stopped, which would be the way round it
                Image(systemName: "lock.fill").font(.system(size: 13)).foregroundStyle(Palette.muted)
                    .frame(width: 42, height: 42).background(RoundedRectangle(cornerRadius: 11, style: .continuous).fill(Palette.soft))
                    .help("Locked until \(clockTime(until))").accessibilityLabel("Locked until \(clockTime(until))")
            } else {
                Button {
                    if now <= b.cancelUntil || sure { store.stop(); sure = false }
                    else { sure = true; DispatchQueue.main.asyncAfter(deadline: .now() + 3) { sure = false } }
                } label: {
                    if sure { Text("Sure?").font(.system(size: 12, weight: .bold)) } else { Symbol.stop() }
                }
                .buttonStyle(IconButton(size: 42)).accessibilityLabel(now <= b.cancelUntil ? "Cancel" : "Stop")
            }
        }
    }

    private func guarding(_ b: Block) -> String {
        let n = store.settings.blocklist.count, k = store.settings.blocklist.categoryCount
        if let u = store.lockedUntil { return "Locked until \(clockTime(u))" }
        guard store.settings.speedBump, n > 0 || k > 0 else { return store.mixLabel == "Sound" ? "" : store.mixLabel }
        var parts = [String]()
        if n > 0 { parts.append("\(n) apps and sites") }
        if k > 0 { parts.append(k == 1 ? "1 ready-made list" : "\(k) ready-made lists") }
        return "Watching \(parts.joined(separator: " and ")) until \(clockTime(b.end))"
    }
}

// ---------------------------------------------------------------- came home

struct HomePane: View {
    @ObservedObject var store: Store
    var actions: PopoverActions
    var block: Block
    @State private var note = ""

    var body: some View {
        let cat = store.cat(block.cat)
        let home = block.kitten == "home"
        VStack(spacing: 14) {
            HStack(spacing: 14) {
                ZStack {
                    SkyBackground(deep: true)
                    CatImage(path: home ? Art.path(for: block.cat, stage: .home, small: block.minutes < 25) : Art.gone).frame(width: 76, height: 76).padding(.top, 14)
                }
                .frame(width: 96, height: 96).clipShape(Circle()).overlay(Circle().strokeBorder(Palette.rim, lineWidth: 5))
                VStack(alignment: .leading, spacing: 4) {
                    Label11(text: home ? "CAME HOME" : "WANDERED OFF", color: Palette.accent, spacing: 1)
                    Text(home ? "\(cat.name) came home to your café" : "\(cat.name) wandered off").font(.system(size: 17, weight: .bold)).foregroundStyle(Palette.ink).fixedSize(horizontal: false, vertical: true)
                    Text(sub).font(.system(size: 13)).foregroundStyle(Palette.muted).lineLimit(2).fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
            }
            VStack(alignment: .leading, spacing: 8) {
                Text("How did it go?").font(.system(size: 13, weight: .semibold)).foregroundStyle(Palette.ink)
                TitleField(placeholder: "A note for later (optional)", text: $note, size: 13, center: false, onSubmit: { file("done") })
                HStack(spacing: 6) {
                    ForEach([("done", "Done"), ("partly", "Partly"), ("notreally", "Not really")], id: \.0) { id, label in
                        Button { file(id) } label: { Text(label).frame(maxWidth: .infinity) }
                            .buttonStyle(Chip(fill: id == "done" ? Palette.accent : Palette.soft, text: id == "done" ? Palette.onAccent : Palette.ink, radius: 10, padding: EdgeInsets(top: 10, leading: 0, bottom: 10, trailing: 0)))
                    }
                }
            }
            WhereItWent(rows: store.settings.recordTiming ? store.wentRows(for: [block.id]) : [], limit: 3)
            breakOffer(store.settings.cycle.shortBreak)
            Footer(left: "\(store.blocksToday) of \(store.perDay) blocks · \(fmtMin(store.state.stats.today)) today", actions: actions)
        }
        .padding(.init(top: 16, leading: 18, bottom: 14, trailing: 18))
    }

    private var sub: String {
        var parts = [block.title, fmtMin(block.minutes)]
        if block.treats > 0 { parts.append("+\(block.treats) treats") }
        return parts.joined(separator: " · ")
    }

    private func breakOffer(_ minutes: Int) -> some View {
        let long = store.preset != .flow && store.settings.cycle.isLong(afterBlocks: store.blocksToday)
        let mins = store.breakAfter(block)
        return HStack(spacing: 10) {
            CatImage(path: Art.nap(block.cat)).frame(width: 40, height: 40)
            VStack(alignment: .leading, spacing: 1) {
                Text(long ? "Long break · \(mins) min" : "Break · \(mins) min").font(.system(size: 13, weight: .bold)).foregroundStyle(Palette.ink)
                Text("Stand up and stretch.").font(.system(size: 12)).foregroundStyle(Palette.muted).lineLimit(1)
            }
            Spacer(minLength: 0)
            Button("Start") { file(nil); store.startBreak(mins) }
                .buttonStyle(Chip(fill: Palette.accent, text: Palette.onAccent, size: 13, radius: 9, padding: EdgeInsets(top: 7, leading: 12, bottom: 7, trailing: 12)))
        }
        .padding(.horizontal, 12).padding(.vertical, 10)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Palette.accent.opacity(0.10)))
    }

    /// Files the block with how it went (and the note, if one was typed).
    private func file(_ outcome: String?) {
        let n = note.trimmingCharacters(in: .whitespacesAndNewlines)
        if !n.isEmpty { store.note(block.id, n) }
        store.outcome(block.id, outcome)
        note = ""
    }
}

/// "Where it went": the apps the block spent its time in, kept only on this Mac (screen 9).
struct WhereItWent: View {
    var rows: [WentRow]
    var limit = 3
    private let palette: [Color] = [Palette.tealLight, Color(hex: 0xFFCF8A), Color(hex: 0xA99BE8), Color(hex: 0x7FB1F5), Color(hex: 0xF29BB0), Color(hex: 0x8A8A95)]

    var body: some View {
        if !rows.isEmpty {
            let shown = Array(rows.prefix(limit))
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text("WHERE IT WENT").font(.system(size: 12, weight: .bold)).tracking(0.9)
                    Spacer()
                    Text("only on this Mac").font(.system(size: 12))
                }.foregroundStyle(Palette.muted)
                GeometryReader { g in
                    let total = max(0.001, shown.reduce(0) { $0 + $1.share })
                    HStack(spacing: 2) {
                        ForEach(Array(shown.enumerated()), id: \.offset) { i, r in
                            RoundedRectangle(cornerRadius: 4).fill(r.distraction ? Color(hex: 0xFFCF8A) : palette[i % palette.count]).frame(width: max(4, (g.size.width - 4) * CGFloat(r.share / total)))
                        }
                    }
                }.frame(height: 8)
                VStack(spacing: 5) {
                    ForEach(Array(shown.enumerated()), id: \.offset) { i, r in
                        HStack(spacing: 8) {
                            RoundedRectangle(cornerRadius: 2).fill(r.distraction ? Color(hex: 0xFFCF8A) : palette[i % palette.count]).frame(width: 8, height: 8)
                            Text(r.label).font(.system(size: 13)).foregroundStyle(Palette.ink).lineLimit(1)
                            Spacer(minLength: 6)
                            Text("\(r.minutes) min").font(.system(size: 13)).foregroundStyle(Palette.muted)
                        }
                    }
                }
            }
            .padding(12).frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Palette.softer))
        }
    }
}

// ---------------------------------------------------------------- away

struct AwayPane: View {
    @ObservedObject var store: Store
    var away: AwayReturn

    var body: some View {
        let b = store.active
        VStack(spacing: 14) {
            ZStack(alignment: .bottom) {
                SkyBackground(progress: 0.7)
                CatImage(path: Art.nap(b?.cat ?? store.state.companion)).frame(width: 114, height: 114).padding(.bottom, 4)
            }
            .frame(width: 150, height: 150).clipShape(Circle()).overlay(Circle().strokeBorder(Palette.rim, lineWidth: 6))
            VStack(spacing: 6) {
                Label11(text: "WELCOME BACK")
                Text("You were away from the Mac for \(away.minutes) minute\(away.minutes == 1 ? "" : "s").").font(.system(size: 19, weight: .bold)).foregroundStyle(Palette.ink).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
                Text("Being away isn't drifting, so \(store.cat(b?.cat).name) is still here. Should those minutes count for \(b?.title ?? "this block")?")
                    .font(.system(size: 14)).foregroundStyle(Palette.muted2).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
            }
            HStack(spacing: 8) {
                Button { store.countAway() } label: { Text("Count them").frame(maxWidth: .infinity) }
                    .buttonStyle(Chip(fill: Palette.accent, text: Palette.onAccent, size: 14, radius: 11, padding: EdgeInsets(top: 11, leading: 0, bottom: 11, trailing: 0)))
                if store.state.canTakeOff {
                    Button { store.takeAwayOff() } label: { Text("Take them off").frame(maxWidth: .infinity) }
                        .buttonStyle(Chip(size: 14, radius: 11, padding: EdgeInsets(top: 11, leading: 0, bottom: 11, trailing: 0)))
                }
            }
            if store.state.canTakeOff, let b {
                Text("Taking them off moves the end to \(clockTime(b.end + away.ms)). Away less than \(store.settings.awayAfterMin) min never asks.")
                    .font(.system(size: 12)).foregroundStyle(Palette.muted).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
            } else {
                Text("Away less than \(store.settings.awayAfterMin) min never asks.").font(.system(size: 12)).foregroundStyle(Palette.muted)
            }
        }
        .padding(18)
    }
}

// ---------------------------------------------------------------- on a break

struct BreakPane: View {
    @ObservedObject var store: Store
    var actions: PopoverActions
    var brk: Brk

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { _ in
            let now = nowMs()
            let total = max(1, brk.end - brk.start)
            let left = max(0, brk.end - now)
            VStack(spacing: 14) {
                Header(center: "Break", actions: actions)
                CatWindow(cat: store.state.companion, stage: .nap, size: 190, remaining: Double(left) / Double(total), growth: 0.82, skyProgress: 0.6)
                VStack(spacing: 2) {
                    Text(fmtClock(left)).font(clockFont(46)).foregroundStyle(Palette.ink)
                    Label11(text: "BREAK", color: Palette.accent, spacing: 1.6)
                }
                Text(store.settings.breaks.ideaFor(store.catsHomeToday + store.blocksToday).main + ".").font(.system(size: 13)).foregroundStyle(Palette.muted)
                Button { store.stopBreak() } label: { Text("End break").frame(maxWidth: .infinity) }
                    .buttonStyle(Chip(size: 14, radius: 11, padding: EdgeInsets(top: 11, leading: 12, bottom: 11, trailing: 12)))
                Footer(left: store.todayLine, actions: actions)
            }
            .padding(.init(top: 16, leading: 18, bottom: 14, trailing: 18))
        }
    }
}

// ---------------------------------------------------------------- sounds

struct SoundsPane: View {
    @ObservedObject var store: Store
    var done: () -> Void

    var body: some View {
        VStack(spacing: 10) {
            HStack {
                Text("Sounds").font(.system(size: 17, weight: .bold)).foregroundStyle(Palette.ink)
                Spacer()
                if !store.settings.mix.isEmpty { Button("Off") { store.settings.mix = [] }.buttonStyle(.plain).foregroundStyle(Palette.accentText) }
                Button("Done", action: done).buttonStyle(Chip(fill: Palette.accent, text: Palette.onAccent, size: 13, radius: 9, padding: EdgeInsets(top: 6, leading: 12, bottom: 6, trailing: 12)))
            }
            Muted("Up to two at once.").frame(maxWidth: .infinity, alignment: .leading)
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    ForEach(store.catalog.groups, id: \.id) { g in
                        VStack(alignment: .leading, spacing: 4) {
                            Label11(text: g.name.uppercased())
                            ForEach(store.catalog.kinds(in: g.id), id: \.id) { k in row(k) }
                        }
                    }
                }
            }
            .frame(height: 360)
        }
        .padding(.init(top: 16, leading: 18, bottom: 14, trailing: 18))
    }

    private func row(_ k: SoundKind) -> some View {
        let owned = store.catalog.owned(k, shop: store.state.owned)
        let on = store.settings.mix.first { $0.id == k.id }
        return VStack(spacing: 4) {
            Button {
                if owned { store.settings.mix = SoundCatalog.toggle(k.id, in: store.settings.mix) }
            } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(k.name).font(.system(size: 13, weight: .medium)).foregroundStyle(owned ? Palette.ink : Palette.muted)
                        if let a = k.about { Text(a).font(.system(size: 11)).foregroundStyle(Palette.muted).lineLimit(1) }
                    }
                    Spacer()
                    if on != nil { Image(systemName: "checkmark").foregroundStyle(Palette.accent) }
                    else if !owned { Text("In the shop").font(.system(size: 11)).foregroundStyle(Palette.muted) }
                }
                .padding(.vertical, 5).padding(.horizontal, 8).contentShape(Rectangle())
            }.buttonStyle(.plain)
            if let on {
                Slider(value: Binding(get: { on.vol }, set: { v in
                    if let i = store.settings.mix.firstIndex(where: { $0.id == k.id }) { store.settings.mix[i].vol = v }
                }), in: 0.05...1).padding(.horizontal, 8).controlSize(.small)
            }
        }
        .background(RoundedRectangle(cornerRadius: 8).fill(on != nil ? Palette.soft : .clear))
    }
}
