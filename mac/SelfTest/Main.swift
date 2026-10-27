import AppKit
import SwiftUI

// The app's own windows drawn to PNG files, with no window on the screen and nothing in the menu bar:
// every screen of stage 1 in light and dark, built from the real views and the real engine. selftest.sh ui
// builds this in place of App/Main.swift, runs it as xyz.tiresia.chataltul.selftest in a folder of its own,
// and reads the PASS/FAIL lines.

var failed = 0
var passed = 0
func check(_ name: String, _ cond: Bool) {
    if cond { passed += 1; print("PASS \(name)") } else { failed += 1; print("FAIL \(name)") }
}

let outDir = URL(fileURLWithPath: UserDefaults.standard.string(forKey: "SelfTestOut") ?? NSTemporaryDirectory()).appendingPathComponent("shots", isDirectory: true)

private let delegate = SelfTestDelegate()

@main
enum SelfTestMain {
    static func main() {
        installQuietExit()
        let app = NSApplication.shared
        app.setActivationPolicy(.accessory)
        app.delegate = delegate
        app.run()
    }
}

final class SelfTestDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ n: Notification) {
        // from a timer, not the main queue: a block on the main queue would keep the main queue from
        // running the blocks the app's own async code needs while these tests wait on it
        let t = Timer(timeInterval: 0.05, repeats: false) { _ in
            if let base = UserDefaults.standard.string(forKey: "SelfTestSync") {
                // against a sandbox copy of the web server on this Mac, never the real one
                do { try SyncScenarios.run(base: base, outbox: URL(fileURLWithPath: UserDefaults.standard.string(forKey: "SelfTestOutbox") ?? "/nonexistent")) } catch { print("FAIL sync scenarios: \(error)"); failed += 1 }
            } else {
                do { try UIScenarios.run() } catch { print("FAIL scenarios: \(error)"); failed += 1 }
                do { try LiveScenarios.run() } catch { print("FAIL live scenarios: \(error)"); failed += 1 }
            }
            print("\(passed) passed, \(failed) failed")
            print(failed == 0 ? "SELFTEST passed" : "SELFTEST FAILED")
            fflush(stdout)
            _exit(failed == 0 ? 0 : 1)
        }
        RunLoop.main.add(t, forMode: .common)
    }
}

// ---------------------------------------------------------------- drawing a view to a file

enum Shot {
    /// Draws a view the way a window would, in light or dark, over the café wall, and saves a PNG.
    @discardableResult
    static func render<V: View>(_ view: V, name: String, dark: Bool, pad: CGFloat = 28, wall: Bool = true, minWidth: CGFloat = 0, scale: CGFloat = 2) -> URL {
        try? FileManager.default.createDirectory(at: outDir, withIntermediateDirectories: true)
        let content = view.padding(pad).frame(minWidth: minWidth).background(wall ? AnyView(Wall()) : AnyView(Color.clear))
            .environment(\.colorScheme, dark ? .dark : .light)
        let host = NSHostingView(rootView: content)
        host.appearance = NSAppearance(named: dark ? .darkAqua : .aqua)
        let size = host.fittingSize
        let window = NSWindow(contentRect: NSRect(x: -30000, y: -30000, width: size.width, height: size.height), styleMask: .borderless, backing: .buffered, defer: false)
        window.appearance = host.appearance
        window.isOpaque = false
        window.backgroundColor = .clear
        window.contentView = host
        host.frame = NSRect(origin: .zero, size: size)
        window.orderFrontRegardless()
        RunLoop.current.run(until: Date().addingTimeInterval(0.35))
        host.layoutSubtreeIfNeeded()
        let url = outDir.appendingPathComponent("\(name)-\(dark ? "dark" : "light").png")
        let w = Int(size.width * scale), h = Int(size.height * scale)
        guard let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: w, pixelsHigh: h, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0) else { return url }
        rep.size = size
        host.cacheDisplay(in: host.bounds, to: rep)
        if let data = rep.representation(using: .png, properties: [:]) { try? data.write(to: url) }
        window.orderOut(nil)
        return url
    }

    /// A real panel of the app, as it is on screen (far off it, in a test), drawn to a PNG.
    @discardableResult
    static func renderPanel(_ panel: NSWindow?, name: String, dark: Bool, scale: CGFloat = 2) -> URL {
        let url = outDir.appendingPathComponent("\(name)-\(dark ? "dark" : "light").png")
        guard let panel, let view = panel.contentView else { return url }
        panel.appearance = NSAppearance(named: dark ? .darkAqua : .aqua)
        RunLoop.current.run(until: Date().addingTimeInterval(0.4))
        view.layoutSubtreeIfNeeded()
        let size = view.bounds.size
        guard let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(size.width * scale), pixelsHigh: Int(size.height * scale), bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0) else { return url }
        rep.size = size
        view.cacheDisplay(in: view.bounds, to: rep)
        if let data = rep.representation(using: .png, properties: [:]) { try? data.write(to: url) }
        panel.appearance = nil
        return url
    }

    static func exists(_ url: URL) -> Bool {
        ((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) > 2000
    }
}

extension Store {
    /// A new, empty doc, for the next scene.
    func resetForTest() {
        try? host.load(nil)
        awayPrompt = nil
        act("tick")
    }
}
