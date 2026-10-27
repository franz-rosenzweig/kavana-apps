import AppKit
import Combine
import SwiftUI

/// A window that floats over everything, on every Space, above full-screen apps, without taking the
/// front from the app the person is working in. It only becomes the key window (to take typing) when asked.
final class FloatingPanel: NSPanel {
    /// Tests put every window far off the screen, so nothing shows on the desk of whoever is working.
    static let offscreen = ProcessInfo.processInfo.environment["CHATALTUL_OFFSCREEN"] != nil
    var takesKey = false
    override var canBecomeKey: Bool { takesKey }
    override var canBecomeMain: Bool { false }
    override func constrainFrameRect(_ frameRect: NSRect, to screen: NSScreen?) -> NSRect { frameRect }
    override func setFrame(_ frameRect: NSRect, display flag: Bool) {
        var r = frameRect
        if Self.offscreen { r.origin = NSPoint(x: frameRect.minX - 40_000, y: frameRect.minY - 40_000) }
        super.setFrame(r, display: flag)
    }

    init(level: NSWindow.Level, takesKey: Bool = false) {
        super.init(contentRect: NSRect(x: 0, y: 0, width: 100, height: 100), styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
        self.takesKey = takesKey
        isOpaque = false
        backgroundColor = .clear
        hasShadow = true
        self.level = level
        collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]
        isReleasedWhenClosed = false
        hidesOnDeactivate = false
        animationBehavior = .none
    }
}

/// A hosting view that answers to its content's size.
final class FitHostingView<Content: View>: NSHostingView<Content> {
    var onSize: ((NSSize) -> Void)?
    override func layout() {
        super.layout()
        let s = fittingSize
        if s.width > 0, s.height > 0 { onSize?(s) }
    }
}

// ---------------------------------------------------------------- the popover

final class PopoverController {
    let store: Store
    let panel = FloatingPanel(level: .popUpMenu, takesKey: true)
    private let host: FitHostingView<PopoverRoot>
    private var mouse: Any?
    private var keys: Any?
    private var top: CGFloat = 0
    private var midX: CGFloat = 0
    var anchor: () -> NSRect? = { nil }
    var onHide: () -> Void = {}

    var isShown: Bool { panel.isVisible }

    init(store: Store, actions: PopoverActions) {
        self.store = store
        host = FitHostingView(rootView: PopoverRoot(store: store, actions: actions))
        panel.contentView = host
        host.onSize = { [weak self] s in self?.resize(s) }
    }

    func toggle() { isShown ? hide() : show(key: true) }

    func show(key: Bool) {
        if panel.isVisible { if key { panel.makeKey() }; return }
        guard let a = anchor() else { return }
        midX = a.midX
        top = a.minY - 4
        let s = host.fittingSize
        resize(s)
        if key { panel.makeKeyAndOrderFront(nil) } else { panel.orderFrontRegardless() }
        panel.invalidateShadow()
        mouse = NSEvent.addGlobalMonitorForEvents(matching: [.leftMouseDown, .rightMouseDown]) { [weak self] _ in self?.hide() }
        keys = NSEvent.addLocalMonitorForEvents(matching: [.keyDown, .leftMouseDown]) { [weak self] e in
            guard let self else { return e }
            if e.type == .keyDown, e.keyCode == 53 { self.hide(); return nil }   // Esc
            if e.type == .leftMouseDown, e.window !== self.panel, e.window is FloatingPanel { self.hide() }
            return e
        }
    }

    func hide() {
        guard panel.isVisible else { return }
        panel.orderOut(nil)
        if let m = mouse { NSEvent.removeMonitor(m); mouse = nil }
        if let k = keys { NSEvent.removeMonitor(k); keys = nil }
        onHide()
    }

    private func resize(_ s: NSSize) {
        guard panel.isVisible || s.width > 0 else { return }
        let screen = NSScreen.screens.first { $0.frame.contains(NSPoint(x: midX, y: top)) } ?? NSScreen.main
        let vis = screen?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1440, height: 900)
        var x = midX - s.width / 2
        x = max(vis.minX + 8, min(x, vis.maxX - s.width - 8))
        let frame = NSRect(x: x, y: top - s.height, width: s.width, height: s.height)
        if panel.frame != frame { panel.setFrame(frame, display: true) }
        panel.invalidateShadow()
    }
}

// ---------------------------------------------------------------- the cat in the corner

final class CatController {
    private(set) var panel: FloatingPanel?
    private var timer: Timer?
    private(set) var kind: String?
    var onTimeout: [String: () -> Void] = [:]

    /// Puts a cat up. A new one takes the place of the old. `seconds`: it goes by itself after this long.
    func show(_ kind: String, model: CatCardModel, seconds: TimeInterval, timeout: (() -> Void)? = nil) {
        hide()
        self.kind = kind
        var m = model
        if m.close == nil { m.close = { [weak self] in self?.hide(kind) } }
        let panel = FloatingPanel(level: .popUpMenu)
        let host = FitHostingView(rootView: CatCardView(model: m))
        panel.contentView = host
        let size = host.fittingSize
        let screen = Self.screen()
        let vis = screen.visibleFrame
        panel.setFrame(NSRect(x: vis.maxX - size.width - 12, y: vis.minY, width: size.width, height: size.height), display: true)
        panel.orderFrontRegardless()
        self.panel = panel
        timer = Timer.scheduledTimer(withTimeInterval: seconds, repeats: false) { [weak self] _ in
            self?.hide(kind)
            timeout?()
        }
    }

    func hide(_ only: String? = nil) {
        if let only, kind != only { return }
        timer?.invalidate(); timer = nil
        panel?.orderOut(nil)
        panel = nil
        kind = nil
    }

    /// The screen the person is on: the one with the pointer.
    static func screen() -> NSScreen {
        let p = NSEvent.mouseLocation
        return NSScreen.screens.first { $0.frame.contains(p) } ?? NSScreen.main ?? NSScreen.screens[0]
    }
}

// ---------------------------------------------------------------- the speed bump window

final class BumpController {
    private(set) var panel: FloatingPanel?
    let model = BumpModel()

    func show() {
        if panel == nil {
            let p = FloatingPanel(level: NSWindow.Level(rawValue: NSWindow.Level.popUpMenu.rawValue + 1), takesKey: true)
            p.hasShadow = false
            let screen = CatController.screen()
            // everything under the menu bar: the person can still reach Chataltul's own menu bar item
            let bar = screen.frame.maxY - screen.visibleFrame.maxY
            var f = screen.frame
            f.size.height -= max(bar, 0)
            p.setFrame(f, display: true)
            p.contentView = NSHostingView(rootView: SpeedBumpView(model: model))
            p.makeKeyAndOrderFront(nil)   // takes the keyboard too: a key press must not reach the page underneath
            panel = p
        }
    }

    func hide() {
        panel?.orderOut(nil)
        panel = nil
        model.card = nil
        model.other = nil
    }

    var isShown: Bool { panel != nil }
}

// ---------------------------------------------------------------- park a thought

final class ParkController {
    private(set) var panel: FloatingPanel?
    let store: Store
    init(store: Store) { self.store = store }

    var isShown: Bool { panel != nil }

    func toggle() { isShown ? hide() : show() }

    func show() {
        guard panel == nil else { return }
        let p = FloatingPanel(level: .popUpMenu, takesKey: true)
        let host = FitHostingView(rootView: ParkView(store: store, done: { [weak self] in self?.hide() }))
        p.contentView = host
        let size = host.fittingSize
        let screen = CatController.screen()
        let vis = screen.visibleFrame
        p.setFrame(NSRect(x: vis.midX - size.width / 2, y: vis.maxY - size.height - vis.height * 0.18, width: size.width, height: size.height), display: true)
        p.makeKeyAndOrderFront(nil)
        panel = p
        NotificationCenter.default.addObserver(self, selector: #selector(lost), name: NSWindow.didResignKeyNotification, object: p)
    }

    @objc private func lost() { hide() }

    func hide() {
        guard let p = panel else { return }
        NotificationCenter.default.removeObserver(self, name: NSWindow.didResignKeyNotification, object: p)
        p.orderOut(nil)
        panel = nil
    }
}
