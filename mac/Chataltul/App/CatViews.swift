import AppKit
import SwiftUI

// ---------------------------------------------------------------- the cat in the corner

struct CatButton: Identifiable {
    let id = UUID()
    var label: String
    var primary = false
    var symbol: String? = nil
    var action: () -> Void
}

/// What the cat in the corner says: a check-in, a nudge, the end of a block, the end of a break.
struct CatCardModel {
    var label: String?
    var cat: String
    /// A picture to show instead of the cat's portrait (the paw prints of a cat that left).
    var picture: String? = nil
    var title: String
    var body: String?
    var buttons: [CatButton] = []
    var hint: String?
    var close: (() -> Void)?
    /// A ring that counts down to this time (an eye break's 20 seconds).
    var countdownUntil: Int? = nil
    var countdownTotalMs: Int = 20_000
}

/// The cat peeking up from the corner of the screen with a card beside it (screen 3).
struct CatCardView: View {
    var model: CatCardModel
    @State private var up = false

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            card
                .padding(.bottom, 84).padding(.trailing, 6)
            Breathing { CatImage(path: model.picture ?? Art.portrait(model.cat)) }
                .frame(width: 150, height: 150)
                .frame(width: 150, height: 104, alignment: .top)
                .clipped()
                .shadow(color: .black.opacity(0.25), radius: 8, y: 3)
                .padding(.trailing, 34)
        }
        .frame(width: 400)
        .offset(y: up ? 0 : 190)
        .opacity(up ? 1 : 0)
        .onAppear { withAnimation(.spring(response: 0.5, dampingFraction: 0.78)) { up = true } }
    }

    private var card: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let l = model.label { Label11(text: l, color: Palette.accentText, spacing: 1) }
            Text(model.title).font(.system(size: 19, weight: .semibold)).foregroundStyle(Palette.ink).fixedSize(horizontal: false, vertical: true)
            if let b = model.body, !b.isEmpty { Text(b).font(.system(size: 13)).foregroundStyle(Palette.muted).fixedSize(horizontal: false, vertical: true) }
            if let until = model.countdownUntil {
                TimelineView(.animation(minimumInterval: 0.1)) { _ in
                    let left = max(0, until - nowMs())
                    HStack(spacing: 12) {
                        ZStack {
                            Circle().stroke(Palette.ringTrack, lineWidth: 5)
                            Circle().trim(from: 0, to: max(0.001, CGFloat(left) / CGFloat(max(1, model.countdownTotalMs)))).stroke(Palette.accent, style: StrokeStyle(lineWidth: 5, lineCap: .round)).rotationEffect(.degrees(-90))
                            Text("\((left + 999) / 1000)").font(clockFont(18, weight: .bold)).foregroundStyle(Palette.ink)
                        }.frame(width: 52, height: 52)
                        Spacer(minLength: 0)
                    }
                }
            }
            if !model.buttons.isEmpty {
                HStack(spacing: 8) {
                    ForEach(model.buttons) { b in
                        Button(action: b.action) {
                            HStack(spacing: 7) {
                                if let s = b.symbol { Image(systemName: s).font(.system(size: 13, weight: .bold)) }
                                Text(b.label)
                            }.frame(maxWidth: .infinity)
                        }
                        .buttonStyle(Chip(fill: b.primary ? Palette.accent : Palette.soft, text: b.primary ? Palette.onAccent : Palette.ink, size: 14, radius: 12, padding: EdgeInsets(top: 11, leading: 10, bottom: 11, trailing: 10)))
                        .probe("cat.\(b.label)")
                    }
                }
            }
            if let h = model.hint { Text(h).font(.system(size: 12)).foregroundStyle(Palette.muted) }
        }
        .padding(18)
        .frame(width: 330, alignment: .leading)
        .cardBackground(radius: 18)
        .overlay(alignment: .topTrailing) {
            if let c = model.close {
                Button(action: c) { Image(systemName: "xmark").font(.system(size: 10, weight: .bold)) }
                    .buttonStyle(.plain).foregroundStyle(Palette.muted).padding(10).accessibilityLabel("Close")
            }
        }
    }
}

/// A gentle breathing: the painting rises and settles about once every four seconds. (The painted cats
/// get their living motion, blink and tail included, with the rig in a later version.)
struct Breathing<Content: View>: View {
    var content: () -> Content
    init(@ViewBuilder _ c: @escaping () -> Content) { content = c }
    var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 30)) { ctx in
            let t = ctx.date.timeIntervalSinceReferenceDate
            content().scaleEffect(x: 1 - 0.006 * sin(t * 1.6), y: 1 + 0.014 * sin(t * 1.6), anchor: .bottom)
        }
    }
}

// ---------------------------------------------------------------- the speed bump

/// "Leave anyway": counts only when held for two seconds. It fills as it is held; a plain tap only says
/// "Hold to leave".
final class HoldModel: ObservableObject {
    @Published var progress = 0.0
    @Published var hint = false
    private var tracker = HoldTracker()
    private var timer: Timer?
    var onHeld: () -> Void = {}

    func down() {
        guard timer == nil else { return }
        tracker.down(now: nowMs())
        timer = Timer.scheduledTimer(withTimeInterval: 1.0 / 30, repeats: true) { [weak self] _ in
            guard let self else { return }
            let n = nowMs()
            self.progress = self.tracker.progress(now: n)
            if self.tracker.check(now: n) {
                self.timer?.invalidate(); self.timer = nil
                self.onHeld()
            }
        }
        RunLoop.main.add(timer!, forMode: .common)
    }

    func up() {
        timer?.invalidate(); timer = nil
        tracker.up(now: nowMs())
        hint = tracker.hint
        progress = 0
    }
}

struct SpeedBumpView: View {
    @ObservedObject var model: BumpModel
    @StateObject private var hold = HoldModel()

    var body: some View {
        ZStack {
            Color.black.opacity(0.42)
            if let card = model.card { cardView(card) }
            else if let other = model.other { otherView(other) }
        }
        .ignoresSafeArea()
    }

    private func cardView(_ c: BumpCard) -> some View {
        TimelineView(.animation(minimumInterval: 1 / 20)) { ctx in
            let now = nowMs()
            let grace = model.graceMs
            VStack(spacing: 14) {
                Text("Boo! You're focusing.").font(.system(size: 28, weight: .bold)).foregroundStyle(Palette.ink)
                Text(c.line(now: now, graceMs: grace)).font(.system(size: 16)).foregroundStyle(Palette.muted2).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
                ZStack {
                    Circle().stroke(Palette.ringTrack, lineWidth: 6)
                    Circle().trim(from: 0, to: max(0.001, c.fraction(now: now, graceMs: grace))).stroke(Palette.orange, style: StrokeStyle(lineWidth: 6, lineCap: .round)).rotationEffect(.degrees(-90))
                    Text("\(c.secondsLeft(now: now, graceMs: grace))").font(clockFont(26, weight: .bold)).foregroundStyle(Palette.ink)
                }.frame(width: 76, height: 76).padding(.vertical, 4)
                HStack {
                    Text(c.title).font(.system(size: 14, weight: .bold)).foregroundStyle(Palette.ink).lineLimit(1)
                    Spacer()
                    Text(left(c, now: now)).font(.system(size: 14)).foregroundStyle(Palette.muted2)
                }
                .padding(.horizontal, 14).padding(.vertical, 12)
                .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Palette.soft))
                Button(action: model.backToWork) { Text("Back to work").frame(maxWidth: .infinity) }
                    .buttonStyle(Chip(fill: Palette.accent, text: Palette.onAccent, size: 17, radius: 14, padding: EdgeInsets(top: 15, leading: 12, bottom: 15, trailing: 12)))
                    .probe("back")
                holdButton
                Text("Hold for 2 seconds. \(c.catName) wanders off, and the block keeps going without \(c.catName).")
                    .font(.system(size: 12)).foregroundStyle(Palette.muted).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true).padding(.top, -4)
                Rectangle().fill(Palette.hairline).frame(height: 1)
                HStack(spacing: 4) {
                    Text("\(c.target.label) is on your list until \(clockTime(c.blockEnd)) ·").font(.system(size: 12)).foregroundStyle(Palette.muted)
                    Button("Edit the list", action: model.editList).buttonStyle(.plain).font(.system(size: 12)).foregroundStyle(Palette.accentText)
                }
            }
            .padding(.init(top: 92, leading: 32, bottom: 26, trailing: 32))
            .frame(width: 460)
            .cardBackground(radius: 24)
            .shadow(color: .black.opacity(0.35), radius: 40, y: 20)
            .overlay(alignment: .top) {
                Breathing { CatImage(path: Art.hasPose(c.cat, "walk") ? Art.pose(c.cat, "walk") : Art.portrait(c.cat)) }
                    .frame(width: 190, height: 190)
                    .offset(x: -28, y: -90)
                    .shadow(color: .black.opacity(0.28), radius: 8, y: 6)
            }
            .offset(y: -30)
            .onAppear { hold.onHeld = model.leaveHeld }
        }
    }

    /// Wait it out, locked, used up: the cat, the words, and one way out, back to work.
    private func otherView(_ c: OtherCard) -> some View {
        TimelineView(.animation(minimumInterval: 1 / 20)) { _ in
            let now = nowMs()
            VStack(spacing: 14) {
                Text(c.title).font(.system(size: 28, weight: .bold)).foregroundStyle(Palette.ink).multilineTextAlignment(.center)
                Text(c.line(now: now)).font(.system(size: 16)).foregroundStyle(Palette.muted2).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
                ZStack {
                    Circle().stroke(Palette.ringTrack, lineWidth: 6)
                    if c.kind == .waitItOut {
                        Circle().trim(from: 0, to: max(0.001, c.fraction(now: now))).stroke(Palette.accent, style: StrokeStyle(lineWidth: 6, lineCap: .round)).rotationEffect(.degrees(-90))
                        Text("\(c.secondsLeft(now: now))").font(clockFont(26, weight: .bold)).foregroundStyle(Palette.ink)
                    } else {
                        Image(systemName: "lock.fill").font(.system(size: 24)).foregroundStyle(Palette.muted2)
                    }
                }.frame(width: 76, height: 76).padding(.vertical, 4)
                Button(action: model.backToWorkOther) { Text("Back to work").frame(maxWidth: .infinity) }
                    .buttonStyle(Chip(fill: Palette.accent, text: Palette.onAccent, size: 17, radius: 14, padding: EdgeInsets(top: 15, leading: 12, bottom: 15, trailing: 12)))
                    .probe("back")
                Rectangle().fill(Palette.hairline).frame(height: 1)
                HStack(spacing: 4) {
                    Text("\(c.target.label) is on your \(c.listName) list ·").font(.system(size: 12)).foregroundStyle(Palette.muted)
                    Button("Edit the list", action: model.editList).buttonStyle(.plain).font(.system(size: 12)).foregroundStyle(Palette.accentText)
                }
            }
            .padding(.init(top: 92, leading: 32, bottom: 26, trailing: 32))
            .frame(width: 460)
            .cardBackground(radius: 24)
            .shadow(color: .black.opacity(0.35), radius: 40, y: 20)
            .overlay(alignment: .top) {
                Breathing { CatImage(path: Art.pose(c.cat, "walk")) }
                    .frame(width: 190, height: 190).offset(x: -28, y: -90)
                    .shadow(color: .black.opacity(0.28), radius: 8, y: 6)
            }
            .offset(y: -30)
        }
    }

    private func left(_ c: BumpCard, now: Int) -> String {
        let m = max(0, c.blockEnd - now)
        return "\(minLeft(m)) min left"
    }

    private var holdButton: some View {
        ZStack {
            GeometryReader { g in
                Rectangle().fill(Palette.orange.opacity(0.35)).frame(width: g.size.width * hold.progress)
            }
            Text(hold.hint ? "Hold to leave" : "Leave anyway").font(.system(size: 14, weight: .semibold)).foregroundStyle(Palette.muted2)
        }
        .frame(maxWidth: .infinity).frame(height: 40)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Palette.card))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(Palette.fieldLine, lineWidth: 1))
        .contentShape(Rectangle())
        .gesture(DragGesture(minimumDistance: 0).onChanged { _ in hold.down() }.onEnded { _ in hold.up() })
        .accessibilityElement()
        .accessibilityLabel("Leave anyway. Hold for two seconds.")
        .accessibilityAddTraits(.isButton)
        .probe("hold")
    }
}

/// What the speed bump window shows, and what its buttons do.
final class BumpModel: ObservableObject {
    @Published var card: BumpCard?
    /// Wait it out, locked or used up: the same window, with no way to leave early.
    @Published var other: OtherCard?
    var backToWorkOther: () -> Void = {}
    var graceMs = SpeedBump.defaultGraceMs
    var backToWork: () -> Void = {}
    var leaveHeld: () -> Void = {}
    var editList: () -> Void = {}
}

// ---------------------------------------------------------------- park a thought

struct ParkView: View {
    @ObservedObject var store: Store
    var done: () -> Void
    @State private var text = ""
    @FocusState private var focused: Bool

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 14) {
                Image(systemName: "lightbulb").font(.system(size: 22, weight: .regular)).foregroundStyle(Palette.accent)
                TextField("A thought to park", text: $text)
                    .textFieldStyle(.plain).font(.system(size: 22)).foregroundStyle(Palette.ink).focused($focused).onSubmit(park)
                Button(action: park) { HStack(spacing: 6) { Text("↩"); Text("Park") } }
                    .buttonStyle(Chip(fill: Palette.accent, text: Palette.onAccent, size: 14, radius: 10, padding: EdgeInsets(top: 6, leading: 12, bottom: 6, trailing: 12)))
            }
            .padding(.horizontal, 20).padding(.vertical, 18)
            if !store.state.view.later.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    Label11(text: "ALREADY IN LATER", spacing: 0.7)
                    ForEach(store.state.view.later.prefix(4), id: \.id) { l in
                        HStack(spacing: 10) {
                            Circle().strokeBorder(Palette.muted.opacity(0.6), lineWidth: 1.5).frame(width: 14, height: 14)
                            Text(l.text).font(.system(size: 14)).foregroundStyle(Palette.ink).lineLimit(1)
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 20).padding(.top, 12).padding(.bottom, 16)
                .overlay(alignment: .top) { Rectangle().fill(Palette.hairline).frame(height: 1) }
            }
            HStack {
                Text(store.active != nil ? "It waits in Later. Your block keeps going." : "It waits in Later.")
                Spacer()
                Text("\(store.settings.parkKey.label) from any app")
            }
            .font(.system(size: 12)).foregroundStyle(Palette.muted)
            .padding(.horizontal, 20).padding(.vertical, 10)
            .background(Palette.soft)
        }
        .frame(width: 640)
        .cardBackground(radius: 18)
        .onAppear { focused = true }
        .onExitCommand(perform: done)
    }

    private func park() {
        if store.park(text) { text = ""; done() }
    }
}
