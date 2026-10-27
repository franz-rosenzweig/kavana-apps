import AppKit
import SwiftUI

/// The look from the mockups: the café wall in cream with its dot pattern, the teal, and the dark wall.
enum Palette {
    static func dyn(_ light: UInt32, _ dark: UInt32, lightAlpha: CGFloat = 1, darkAlpha: CGFloat = 1) -> Color {
        Color(nsColor: NSColor(name: nil) { a in
            let isDark = a.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
            return isDark ? NSColor(hex: dark, alpha: darkAlpha) : NSColor(hex: light, alpha: lightAlpha)
        })
    }

    static let teal = Color(hex: 0x2C8A7E)
    static let tealDeep = Color(hex: 0x1F6B61)
    static let tealLight = Color(hex: 0x5FD0C0)
    /// The teal that reads on the card in each appearance.
    static let accent = dyn(0x2C8A7E, 0x5FD0C0)
    static let accentText = dyn(0x1F6B61, 0x8FE3D6)
    static let onAccent = dyn(0xFFFFFF, 0x10302B)

    static let ink = dyn(0x2B211A, 0xF2EDE6)
    static let muted = dyn(0x6B6158, 0xA59C93)
    static let muted2 = dyn(0x574D44, 0xCFC6BD)
    /// The popover's card.
    static let card = dyn(0xFFFCF7, 0x1F1C23)
    /// A quiet fill for buttons and chips.
    static let soft = dyn(0x2B211A, 0xFFFFFF, lightAlpha: 0.06, darkAlpha: 0.08)
    static let softer = dyn(0x2B211A, 0xFFFFFF, lightAlpha: 0.04, darkAlpha: 0.05)
    static let hairline = dyn(0xEFE7DC, 0xFFFFFF, lightAlpha: 1, darkAlpha: 0.08)
    static let field = dyn(0xFFFFFF, 0x000000, lightAlpha: 1, darkAlpha: 0.2)
    static let fieldLine = dyn(0x2B211A, 0xFFFFFF, lightAlpha: 0.12, darkAlpha: 0.10)
    static let orange = dyn(0xE98A3C, 0xFFCF8A)
    static let orangeInk = dyn(0x3A2510, 0x3A2510)
    static let ringTrack = dyn(0x2B211A, 0xFFFFFF, lightAlpha: 0.09, darkAlpha: 0.10)
    static let rim = dyn(0xFFF8EE, 0x3A3340)
    /// The app window: its sidebar, its page and its cards.
    static let sidebar = dyn(0xF3EBE0, 0x252128)
    static let page = dyn(0xFBF8F3, 0x1C1A1F)
    static let tile = dyn(0xFFFFFF, 0x262229)
    static let tileLine = dyn(0xEFE6DA, 0xFFFFFF, lightAlpha: 1, darkAlpha: 0.06)

    static let wallTop = dyn(0xFDF5E8, 0x2E2733)
    static let wallBottom = dyn(0xF5E4CB, 0x221D27)
    static let wallDot = dyn(0xC49668, 0xFFDCAA, lightAlpha: 0.26, darkAlpha: 0.10)
    static let skyDayTop = Color(hex: 0x8FCDF0), skyDayBottom = Color(hex: 0xE7F6FF)
    static let skyDuskTop = Color(hex: 0x9FD2EE), skyDuskBottom = Color(hex: 0xFDF0D8)
    static let skyNightTop = Color(hex: 0x243A66), skyNightBottom = Color(hex: 0x454F7E)
    static let skyDeepTop = Color(hex: 0x141A3E), skyDeepBottom = Color(hex: 0x2B2E5E)
}

extension NSColor {
    convenience init(hex: UInt32, alpha: CGFloat = 1) {
        self.init(srgbRed: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255, alpha: alpha)
    }
}

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255, opacity: alpha)
    }
}

/// The clock's face: the system's rounded font, with digits of one width so the time doesn't wobble.
func clockFont(_ size: CGFloat, weight: Font.Weight = .heavy) -> Font {
    .system(size: size, weight: weight, design: .rounded).monospacedDigit()
}

/// The café wall: cream with a dot pattern, or the dark wall.
struct Wall: View {
    var body: some View {
        ZStack {
            LinearGradient(colors: [Palette.wallTop, Palette.wallBottom], startPoint: .top, endPoint: .bottom)
            Canvas { ctx, size in
                let step: CGFloat = 26
                var y: CGFloat = 13
                while y < size.height {
                    var x: CGFloat = 13
                    while x < size.width {
                        ctx.fill(Path(ellipseIn: CGRect(x: x - 1.8, y: y - 1.8, width: 3.6, height: 3.6)), with: .color(Palette.wallDot))
                        x += step
                    }
                    y += step
                }
            }
        }
    }
}

/// A button in the app's own plain style (the system's are drawn by AppKit and don't take the mockup's look).
struct Chip: ButtonStyle {
    var fill: Color = Palette.soft
    var text: Color = Palette.ink
    var weight: Font.Weight = .semibold
    var size: CGFloat = 13
    var radius: CGFloat = 11
    var padding: EdgeInsets = EdgeInsets(top: 9, leading: 12, bottom: 9, trailing: 12)

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: size, weight: weight))
            .foregroundStyle(text)
            .padding(padding)
            .background(RoundedRectangle(cornerRadius: radius, style: .continuous).fill(fill))
            .opacity(configuration.isPressed ? 0.7 : 1)
            .contentShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
    }
}

struct IconButton: ButtonStyle {
    var size: CGFloat = 30
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(Palette.muted2)
            .frame(width: size, height: size)
            .background(RoundedRectangle(cornerRadius: 8, style: .continuous).fill(Palette.soft))
            .opacity(configuration.isPressed ? 0.7 : 1)
            .contentShape(Rectangle())
    }
}
