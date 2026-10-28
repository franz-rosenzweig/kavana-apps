import AppKit
import SwiftUI

/// What the full-screen break shows and does.
final class BreakModel: ObservableObject {
    @Published var brk: Brk
    @Published var idea: String
    @Published var then: String?
    @Published var others: [String]
    var strict: Bool
    var postponeMin: Int
    var cat: String
    var eyeTip: Bool
    var skip: () -> Void = {}
    var postpone: () -> Void = {}
    var editIdeas: () -> Void = {}

    init(brk: Brk, idea: (main: String, others: [String]), strict: Bool, postponeMin: Int, cat: String, eyeTip: Bool) {
        self.brk = brk; self.idea = idea.main; others = idea.others; then = idea.others.first
        self.strict = strict; self.postponeMin = postponeMin; self.cat = cat; self.eyeTip = eyeTip
    }
}

/// A soft full-screen break (screen 13): the night sky, the cat stretching, an idea, the time left.
struct BreakScreenView: View {
    @ObservedObject var model: BreakModel
    var primary = true

    var body: some View {
        ZStack {
            LinearGradient(colors: [Color(hex: 0x141A3E), Color(hex: 0x2B2E5E), Color(hex: 0x3A396B)], startPoint: .top, endPoint: .bottom)
            Stars().opacity(0.9)
            if primary { content }
        }
        .environment(\.colorScheme, .dark)
        .ignoresSafeArea()
    }

    private var content: some View {
        ZStack {
            VStack(spacing: 18) {
                Label11(text: "BREAK · \(model.brk.minutes) MIN", color: Palette.tealLight, spacing: 1.6)
                Breathing { CatImage(path: Art.pose(model.cat, "stretch")) }
                    .frame(width: 300, height: 300)
                    .shadow(color: .black.opacity(0.45), radius: 20, y: 14)
                Text("\(model.idea).").font(.system(size: 46, weight: .bold)).foregroundStyle(Color(hex: 0xF2EDE6)).multilineTextAlignment(.center)
                Text(sentence).font(.system(size: 19)).foregroundStyle(Color(hex: 0xCFC6BD)).multilineTextAlignment(.center).frame(maxWidth: 620)
                TimelineView(.periodic(from: .now, by: 1)) { _ in
                    let left = max(0, model.brk.end - nowMs())
                    Text(fmtClock(left)).font(clockFont(36)).foregroundStyle(Color(hex: 0xF2EDE6))
                        .frame(width: 130, height: 130)
                        .background(Circle().stroke(Color.white.opacity(0.14), lineWidth: 5))
                        .overlay(Circle().trim(from: 0, to: CGFloat(Double(left) / Double(max(1, model.brk.end - model.brk.start)))).stroke(Palette.tealLight, style: StrokeStyle(lineWidth: 5, lineCap: .round)).rotationEffect(.degrees(-90)))
                }
                if !model.strict {
                    HStack(spacing: 10) {
                        Button("Postpone \(model.postponeMin) min", action: model.postpone).probe("postpone")
                            .buttonStyle(Chip(fill: Color.white.opacity(0.12), text: Color(hex: 0xF2EDE6), size: 15, radius: 12, padding: EdgeInsets(top: 11, leading: 20, bottom: 11, trailing: 20)))
                        Button("Skip this break", action: model.skip).probe("skip")
                            .buttonStyle(Chip(fill: .clear, text: Color(hex: 0xF2EDE6), size: 15, radius: 12, padding: EdgeInsets(top: 11, leading: 20, bottom: 11, trailing: 20)))
                            .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(Color.white.opacity(0.18), lineWidth: 1))
                    }.padding(.top, 4)
                    Text("Strict breaks are off, so you can skip. Turn them on in Settings and these buttons go away.").font(.system(size: 13)).foregroundStyle(Color(hex: 0xA59C93))
                }
            }
            .padding(.bottom, 50)
            VStack {
                HStack {
                    Spacer()
                    if model.eyeTip { eyeCard }
                }
                Spacer()
                HStack(spacing: 10) {
                    Text("Other ideas:").foregroundStyle(Color(hex: 0xA59C93))
                    ForEach(model.others, id: \.self) { o in
                        Text(o).padding(.horizontal, 12).padding(.vertical, 6).background(Capsule().fill(Color.white.opacity(0.08)))
                    }
                    Button("Edit ideas", action: model.editIdeas).buttonStyle(.plain).foregroundStyle(Palette.tealLight)
                }
                .font(.system(size: 13)).foregroundStyle(Color(hex: 0xCFC6BD)).padding(.bottom, 36)
            }
            .padding(24)
        }
    }

    private var sentence: String {
        let rest = "The screen stays like this until the break is over, and your next block is ready when you're back."
        guard let t = model.then, !t.isEmpty else { return rest }
        return "Then \(t.prefix(1).lowercased() + t.dropFirst()). \(rest)"
    }

    private var eyeCard: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("And during a block: eye breaks").font(.system(size: 13, weight: .bold)).foregroundStyle(Color(hex: 0xF2EDE6))
            Text("Every 20 min a 20-second card: look at something far away. It waits during calls and full-screen video.").font(.system(size: 12)).foregroundStyle(Color(hex: 0xA59C93)).fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 16).padding(.vertical, 14).frame(width: 300, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color(hex: 0x1F1C23).opacity(0.92)))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Color.white.opacity(0.09), lineWidth: 1))
    }
}

private struct Stars: View {
    var body: some View {
        Canvas { ctx, size in
            for (x, y, r, a) in [(0.12, 0.18, 1.2, 0.8), (0.26, 0.09, 1.0, 0.6), (0.41, 0.22, 1.0, 0.7), (0.63, 0.12, 1.2, 0.8), (0.78, 0.26, 1.0, 0.6), (0.90, 0.08, 1.0, 0.7), (0.08, 0.44, 1.0, 0.5), (0.94, 0.40, 1.0, 0.5), (0.55, 0.05, 0.9, 0.5), (0.33, 0.35, 0.9, 0.4), (0.7, 0.5, 0.9, 0.4)] as [(Double, Double, Double, Double)] {
                ctx.fill(Path(ellipseIn: CGRect(x: x * size.width - r, y: y * size.height - r, width: r * 2, height: r * 2)), with: .color(.white.opacity(a)))
            }
        }
    }
}

/// The break, on every screen: the cat and its words on the main one, the sky on the others.
final class BreakController {
    private var panels: [FloatingPanel] = []
    private(set) var model: BreakModel?
    var isShown: Bool { !panels.isEmpty }

    func show(_ m: BreakModel) {
        hide()
        model = m
        for (i, screen) in NSScreen.screens.enumerated() {
            let primary = screen == NSScreen.main || (NSScreen.main == nil && i == 0)
            let p = FloatingPanel(level: .screenSaver, takesKey: primary)
            p.hasShadow = false
            p.setFrame(screen.frame, display: true)
            p.contentView = NSHostingView(rootView: BreakScreenView(model: m, primary: primary))
            if primary { p.makeKeyAndOrderFront(nil) } else { p.orderFrontRegardless() }
            panels.append(p)
        }
    }

    func hide() {
        for p in panels { p.orderOut(nil) }
        panels = []
        model = nil
    }

    var primaryPanel: NSWindow? { panels.first { $0.takesKey } ?? panels.first }
}
