import AppKit
import SwiftUI

/// The sky in the round window: afternoon, going to dusk, or night in dark mode.
struct SkyBackground: View {
    @Environment(\.colorScheme) private var scheme
    var progress: Double = 0
    var deep = false

    var body: some View {
        ZStack {
            if scheme == .dark {
                LinearGradient(colors: deep ? [Palette.skyDeepTop, Palette.skyDeepBottom] : [Palette.skyNightTop, Palette.skyNightBottom], startPoint: .top, endPoint: .bottom)
                Stars()
            } else {
                LinearGradient(colors: [Palette.skyDayTop, Palette.skyDayBottom], startPoint: .top, endPoint: .bottom)
                LinearGradient(colors: [Palette.skyDuskTop, Palette.skyDuskBottom], startPoint: .top, endPoint: .bottom).opacity(max(0, progress - 0.4) / 0.6 * 0.8)
            }
        }
    }
}

private struct Stars: View {
    var body: some View {
        Canvas { ctx, size in
            for (x, y, r, a) in [(0.30, 0.22, 1.0, 0.9), (0.70, 0.30, 1.0, 0.7), (0.55, 0.12, 1.0, 0.8), (0.82, 0.52, 1.0, 0.6), (0.18, 0.48, 1.0, 0.6), (0.40, 0.40, 0.8, 0.5)] as [(Double, Double, Double, Double)] {
                ctx.fill(Path(ellipseIn: CGRect(x: x * size.width - r, y: y * size.height - r, width: r * 2, height: r * 2)), with: .color(.white.opacity(a)))
            }
        }
    }
}

/// The round window with the cat in it and a ring around it. `progress` nil: no ring (ready, away).
struct CatWindow: View {
    var cat: String
    var stage: Art.Stage = .nap
    var small = false
    var size: CGFloat = 208
    /// The share of the block that is left, 1 down to 0, for the ring around the window.
    var remaining: Double? = nil
    var growth: Double = 0.8
    var skyProgress: Double = 0
    var deepSky = false
    var paused = false

    var body: some View {
        let inner = size * 0.865
        ZStack {
            Circle().stroke(Palette.ringTrack, lineWidth: 4)
            if let r = remaining {
                Circle().trim(from: 0, to: max(0.001, r))
                    .stroke(paused ? Palette.orange : Palette.accent, style: StrokeStyle(lineWidth: 4, lineCap: .round))
                    .rotationEffect(.degrees(-90))
            }
            ZStack(alignment: .bottom) {
                SkyBackground(progress: skyProgress, deep: deepSky)
                // the window's bars
                Rectangle().fill(Palette.rim).opacity(0.75).frame(width: 3, height: inner / 3).frame(maxHeight: .infinity, alignment: .top)
                Rectangle().fill(Palette.rim).opacity(0.75).frame(height: 3).frame(maxHeight: .infinity, alignment: .top).padding(.top, inner / 3)
                CatImage(path: Art.path(for: cat, stage: stage, small: small))
                    .frame(width: inner * 0.78 * growth, height: inner * 0.78 * growth)
                    .padding(.bottom, inner * 0.03)
            }
            .frame(width: inner, height: inner)
            .clipShape(Circle())
            .overlay(Circle().strokeBorder(Palette.rim, lineWidth: 6))
        }
        .frame(width: size, height: size)
    }
}

/// The dashes under the title: one for each block of the day.
struct Dashes: View {
    var total: Int
    var done: Int
    /// 0...1 of the block that is running, shown in the dash after the finished ones.
    var current: Double? = nil

    var body: some View {
        HStack(spacing: 5) {
            ForEach(0..<max(total, done + (current == nil ? 0 : 1)), id: \.self) { i in
                GeometryReader { g in
                    ZStack(alignment: .leading) {
                        Capsule().fill(Palette.ringTrack)
                        if i < done { Capsule().fill(Palette.accent) }
                        else if i == done, let c = current { Capsule().fill(Palette.accent).frame(width: g.size.width * c) }
                    }
                }
                .frame(height: 5)
            }
        }
    }
}

/// Small section label: TODAY, FOCUSING, READY.
struct Label11: View {
    var text: String
    var color: Color = Palette.muted
    var spacing: CGFloat = 1.2
    var body: some View {
        Text(text).font(.system(size: 11, weight: .bold)).tracking(spacing).foregroundStyle(color)
    }
}

/// Plain text, the muted way.
struct Muted: View {
    var text: String
    var size: CGFloat = 12
    init(_ t: String, size: CGFloat = 12) { text = t; self.size = size }
    var body: some View { Text(text).font(.system(size: size)).foregroundStyle(Palette.muted) }
}

/// The title field, drawn by hand so it looks the same in both appearances.
struct TitleField: View {
    var placeholder: String
    @Binding var text: String
    var size: CGFloat = 14
    var center = true
    var focused: FocusState<Bool>.Binding? = nil
    var onSubmit: () -> Void = {}

    var body: some View {
        field.onSubmit(onSubmit)
    }

    @ViewBuilder private var field: some View {
        if let f = focused { styled.focused(f) } else { styled }
    }

    private var styled: some View {
        TextField(placeholder, text: $text)
            .textFieldStyle(.plain)
            .multilineTextAlignment(center ? .center : .leading)
            .font(.system(size: size))
            .foregroundStyle(Palette.ink)
            .padding(.horizontal, 12).padding(.vertical, 9)
            .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Palette.field))
            .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).strokeBorder(Palette.fieldLine, lineWidth: 1))
    }
}

extension View {
    /// A card as the popover draws it.
    func cardBackground(radius: CGFloat = 16) -> some View {
        background(RoundedRectangle(cornerRadius: radius, style: .continuous).fill(Palette.card))
            .overlay(RoundedRectangle(cornerRadius: radius, style: .continuous).strokeBorder(Palette.ringTrack, lineWidth: 1))
    }
}

/// Symbols used on buttons, drawn as the mockups draw them (stroke icons).
enum Symbol {
    static func gear() -> some View { Image(systemName: "gearshape").font(.system(size: 14, weight: .medium)) }
    static func note() -> some View { Image(systemName: "speaker.wave.2").font(.system(size: 14, weight: .medium)) }
    static func park() -> some View { Image(systemName: "lightbulb").font(.system(size: 14, weight: .medium)) }
    static func stop() -> some View { Image(systemName: "stop.fill").font(.system(size: 12, weight: .medium)) }
    static func play() -> some View { Image(systemName: "play.fill").font(.system(size: 12, weight: .medium)) }
    static func pause() -> some View { Image(systemName: "pause.fill").font(.system(size: 12, weight: .medium)) }
    static func check() -> some View { Image(systemName: "checkmark").font(.system(size: 14, weight: .bold)) }
    static func drift() -> some View { Image(systemName: "arrow.triangle.2.circlepath").font(.system(size: 14, weight: .semibold)) }
}

/// Where a control is, for the live tests that press it with a real mouse event. It only records anything in
/// a test run (when windows are kept off the screen), and costs nothing otherwise.
enum Probe {
    static var frames: [String: CGRect] = [:]
    static let on = ProcessInfo.processInfo.environment["CHATALTUL_OFFSCREEN"] != nil
}

extension View {
    @ViewBuilder func probe(_ name: String) -> some View {
        if Probe.on {
            background(GeometryReader { g in
                Color.clear
                    .onAppear { Probe.frames[name] = g.frame(in: .global) }
                    .onChange(of: g.frame(in: .global)) { f in Probe.frames[name] = f }
            })
        } else { self }
    }
}
