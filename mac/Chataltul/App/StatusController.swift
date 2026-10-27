import AppKit
import Combine

/// What the menu bar item shows (screen 6): the kitten, a ring that empties, and the time.
enum MenuState: Equatable {
    case ready
    case focusing(remaining: Double, text: String, pending: Bool)
    case paused(remaining: Double, text: String)
    case breakTime(remaining: Double, text: String)
    case home
}

/// Draws the menu bar icon. One 18-point picture per state; colors follow the menu bar's own appearance.
enum MenuIcon {
    private static func headPath(filled: Bool) -> NSBezierPath {
        // the kitten's head from the mockups, in an 18 x 18 box (y down)
        let p = NSBezierPath()
        if filled {
            p.move(to: NSPoint(x: 5.6, y: 8.2)); p.line(to: NSPoint(x: 5.6, y: 5.4)); p.line(to: NSPoint(x: 7.4, y: 6.9))
            p.line(to: NSPoint(x: 10.6, y: 6.9)); p.line(to: NSPoint(x: 12.4, y: 5.4)); p.line(to: NSPoint(x: 12.4, y: 8.2))
            p.curve(to: NSPoint(x: 9, y: 12.4), controlPoint1: NSPoint(x: 12.4, y: 10.6), controlPoint2: NSPoint(x: 11, y: 12.4))
            p.curve(to: NSPoint(x: 5.6, y: 8.2), controlPoint1: NSPoint(x: 7, y: 12.4), controlPoint2: NSPoint(x: 5.6, y: 10.6))
        } else {
            p.move(to: NSPoint(x: 4.6, y: 8.4)); p.line(to: NSPoint(x: 4.6, y: 4.6)); p.line(to: NSPoint(x: 7.1, y: 6.6))
            p.line(to: NSPoint(x: 10.9, y: 6.6)); p.line(to: NSPoint(x: 13.4, y: 4.6)); p.line(to: NSPoint(x: 13.4, y: 8.4))
            p.curve(to: NSPoint(x: 9, y: 13.6), controlPoint1: NSPoint(x: 13.4, y: 11.4), controlPoint2: NSPoint(x: 11.6, y: 13.6))
            p.curve(to: NSPoint(x: 4.6, y: 8.4), controlPoint1: NSPoint(x: 6.4, y: 13.6), controlPoint2: NSPoint(x: 4.6, y: 11.4))
        }
        p.close()
        return p
    }

    static func isDark() -> Bool { NSAppearance.currentDrawing().bestMatch(from: [.darkAqua, .aqua]) == .darkAqua }

    struct Colors { let ring: NSColor; let text: NSColor; let orange: NSColor }
    static func colors(dark: Bool) -> Colors {
        dark ? Colors(ring: NSColor(hex: 0x5FD0C0), text: NSColor(hex: 0x8FE3D6), orange: NSColor(hex: 0xFFCF8A))
             : Colors(ring: NSColor(hex: 0x2C8A7E), text: NSColor(hex: 0x1F6B61), orange: NSColor(hex: 0xE98A3C))
    }

    /// The text color, resolved when it is drawn so it follows the menu bar.
    static func textColor(orange: Bool) -> NSColor {
        NSColor(name: nil) { a in
            let dark = a.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
            let c = colors(dark: dark)
            return orange ? (dark ? c.orange : NSColor(hex: 0xB9621F)) : c.text
        }
    }

    static func image(_ state: MenuState) -> NSImage {
        let img = NSImage(size: NSSize(width: 20, height: 18), flipped: true) { _ in
            let dark = isDark()
            let c = colors(dark: dark)
            let ctx = NSGraphicsContext.current!.cgContext
            ctx.saveGState()
            ctx.translateBy(x: 1, y: 0)
            func ring(_ remaining: Double, _ color: NSColor, track: Bool) {
                let rect = NSRect(x: 1.4, y: 1.4, width: 15.2, height: 15.2)
                if track {
                    color.withAlphaComponent(0.25).setStroke()
                    let t = NSBezierPath(ovalIn: rect); t.lineWidth = 1.6; t.stroke()
                }
                color.setStroke()
                let a = NSBezierPath()
                // clockwise from the top; in a flipped view the angles run the other way round
                a.appendArc(withCenter: NSPoint(x: 9, y: 9), radius: 7.6, startAngle: -90, endAngle: -90 + 360 * CGFloat(max(0.001, remaining)), clockwise: false)
                a.lineWidth = 1.6; a.lineCapStyle = .round; a.stroke()
            }
            switch state {
            case .ready:
                NSColor.black.setStroke()
                let h = headPath(filled: false); h.lineWidth = 1.3; h.lineJoinStyle = .round; h.stroke()
            case .focusing(let r, _, let pending):
                ring(r, c.ring, track: true)
                c.ring.setFill(); headPath(filled: true).fill()
                if pending { c.orange.setFill(); NSBezierPath(ovalIn: NSRect(x: 12.6, y: 0.2, width: 5.4, height: 5.4)).fill() }
            case .paused(let r, _):
                ring(r, c.orange, track: false)
                c.orange.setStroke()
                let bars = NSBezierPath(); bars.move(to: NSPoint(x: 7.2, y: 6.2)); bars.line(to: NSPoint(x: 7.2, y: 11.8)); bars.move(to: NSPoint(x: 10.8, y: 6.2)); bars.line(to: NSPoint(x: 10.8, y: 11.8))
                bars.lineWidth = 1.7; bars.lineCapStyle = .round; bars.stroke()
            case .breakTime(let r, _):
                let blue = dark ? NSColor(hex: 0x8CC4F0) : NSColor(hex: 0x3D7BE0)
                ring(r, blue, track: true)
                blue.setFill(); headPath(filled: true).fill()
            case .home:
                c.ring.setFill(); headPath(filled: true).fill()
                (dark ? NSColor(hex: 0x221D27) : NSColor.white).setFill()
                let heart = NSBezierPath()
                heart.move(to: NSPoint(x: 9, y: 12.2))
                heart.curve(to: NSPoint(x: 6.6, y: 9.5), controlPoint1: NSPoint(x: 7.4, y: 11.2), controlPoint2: NSPoint(x: 6.6, y: 10.4))
                heart.curve(to: NSPoint(x: 9, y: 9.1), controlPoint1: NSPoint(x: 6.6, y: 8.2), controlPoint2: NSPoint(x: 8.5, y: 8.2))
                heart.curve(to: NSPoint(x: 11.4, y: 9.5), controlPoint1: NSPoint(x: 9.5, y: 8.2), controlPoint2: NSPoint(x: 11.4, y: 8.2))
                heart.curve(to: NSPoint(x: 9, y: 12.2), controlPoint1: NSPoint(x: 11.4, y: 10.4), controlPoint2: NSPoint(x: 10.6, y: 11.2))
                heart.fill()
            }
            ctx.restoreGState()
            return true
        }
        img.isTemplate = (state == .ready)
        return img
    }
}

/// The menu bar item and the popover it opens.
final class StatusController: NSObject {
    let store: Store
    let item: NSStatusItem
    private var last: MenuState?
    private var lastTitle = ""

    var onClick: () -> Void = {}
    var onMenu: () -> NSMenu = { NSMenu() }

    init(store: Store) {
        self.store = store
        item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        super.init()
        if ProcessInfo.processInfo.environment["CHATALTUL_OFFSCREEN"] != nil { item.isVisible = false }
        if let b = item.button {
            b.target = self
            b.action = #selector(clicked)
            b.sendAction(on: [.leftMouseUp, .rightMouseUp])
            b.imagePosition = .imageLeft
            b.setAccessibilityLabel("Chataltul")
        }
        refresh(now: nowMs())
    }

    @objc private func clicked() {
        if NSApp.currentEvent?.type == .rightMouseUp || NSApp.currentEvent?.modifierFlags.contains(.control) == true {
            let m = onMenu()
            item.menu = m
            item.button?.performClick(nil)
            item.menu = nil
        } else { onClick() }
    }

    /// Where the popover hangs: just under the item.
    var buttonFrameOnScreen: NSRect? {
        guard let b = item.button, let w = b.window else { return nil }
        return w.convertToScreen(b.convert(b.bounds, to: nil))
    }

    func menuState(now: Int) -> MenuState {
        if store.awaiting != nil { return .home }
        if let b = store.active {
            let at = b.paused ?? now
            let total = max(1, b.end - b.start)
            let remaining = b.stopwatch ? 1.0 : 1 - max(0, min(1, Double(at - b.start) / Double(total)))
            let text = fmtClock(b.stopwatch ? at - b.start : max(0, b.end - at))
            if b.paused != nil { return .paused(remaining: remaining, text: text) }
            return .focusing(remaining: remaining, text: text, pending: b.pending != nil)
        }
        if let k = store.onBreak {
            let total = max(1, k.end - k.start)
            return .breakTime(remaining: Double(max(0, k.end - now)) / Double(total), text: fmtClock(max(0, k.end - now)))
        }
        return .ready
    }

    /// Called once a second and on every change.
    func refresh(now: Int) {
        let s = menuState(now: now)
        guard let b = item.button else { return }
        var title = ""
        var orange = false
        switch s {
        case .ready: break
        case .focusing(_, let t, _): title = t
        case .paused(_, let t): title = t; orange = true
        case .breakTime(_, let t): title = t
        case .home: title = "Home"
        }
        // the icon only changes when the ring has moved enough to show (once a second is more than the eye needs)
        if s != last {
            b.image = MenuIcon.image(s)
            last = s
        }
        if title != lastTitle {
            lastTitle = title
            if title.isEmpty { b.attributedTitle = NSAttributedString(string: "") }
            else {
                b.attributedTitle = NSAttributedString(string: " " + title, attributes: [
                    .font: Self.clockFont(13), .foregroundColor: MenuIcon.textColor(orange: orange),
                ])
            }
        }
        b.setAccessibilityValue(title.isEmpty ? "Ready" : title)
    }

    static func clockFont(_ size: CGFloat) -> NSFont {
        let base = NSFont.systemFont(ofSize: size, weight: .semibold)
        let rounded = base.fontDescriptor.withDesign(.rounded) ?? base.fontDescriptor
        let tabular = rounded.addingAttributes([.featureSettings: [[NSFontDescriptor.FeatureKey.typeIdentifier: kNumberSpacingType, NSFontDescriptor.FeatureKey.selectorIdentifier: kMonospacedNumbersSelector]]])
        return NSFont(descriptor: tabular, size: size) ?? base
    }
}
