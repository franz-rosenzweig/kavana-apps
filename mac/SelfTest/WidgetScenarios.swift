import AppKit
import SwiftUI

/// The desktop widgets, drawn from what the app writes for them (widget/now.json and the pictures beside it) in each state,
/// size and appearance. The faces are the extension's own views (Widgets/WidgetViews.swift), so these pictures are what the
/// desktop shows.
enum WidgetScenarios {
    static let MIN = 60_000

    static func run() throws {
        let store = try Store()
        let feed = WidgetFeed(store: store)
        feed.tellWidgetKit = false
        let file = feed.folder.appendingPathComponent(WidgetSnapshot.file)

        func written() -> WidgetSnapshot? { (try? Data(contentsOf: file)).flatMap { WidgetSnapshot.decode($0) } }
        func fresh() -> Int {
            store.resetForTest()
            let now = nowMs()
            for (k, title) in ["Outline the talk", "Read chapter 4"].enumerated() {
                let start = now - (2 - k) * 70 * MIN
                store.act("start", ["intention": title, "minutes": 25, "style": "ask", "cat": "miso"], now: start)
                store.act("tick", now: start + 26 * MIN)
                if let w = store.awaiting { store.act("outcome", ["id": w.id, "outcome": "done"], now: start + 27 * MIN) }
            }
            for t in ["Book the dentist", "Look up that podcast episode", "Return the library books", "Call the bank about the card"] { store.act("addLater", ["text": t], now: now - 3600_000) }
            return now
        }

        // ---- ready, with a site in the time log that must never reach the widgets
        var now = fresh()
        store.addTiming(block: "b-test", start: now - 30 * MIN, bundleID: "com.google.Chrome", name: "Chrome", ms: 60_000, site: (domain: "news.ycombinator.com", browser: "Chrome"))
        check("widgets: the app writes what they show", feed.write())
        check("widgets: nothing new, nothing written again", !feed.write())
        guard let ready = written() else { check("widgets: now.json reads back", false); return }
        let text = String(decoding: (try? Data(contentsOf: file)) ?? Data(), as: UTF8.self)
        check("widgets: no site, browser or web address in now.json", !text.contains("ycombinator") && !text.contains("Chrome") && !text.contains("http"))
        check("widgets: ready says ready", ready.phase == .ready && ready.run == nil)
        check("widgets: today's numbers are the app's", ready.blocksToday == store.blocksToday && ready.todayMin == store.state.stats.today && ready.catsHome == store.catsHomeToday)
        check("widgets: all four of Later's thoughts", ready.laterCount == 4 && ready.later.count == 4)
        check("widgets: seven days of the week", ready.week.count == 7 && ready.weekLetters.count == 7)
        let pics = WidgetPictures.load(ready, folder: feed.folder)
        check("widgets: the cat's four paintings are copied beside it", ready.art.values.allSatisfy { pics.image($0) != nil } && ready.art.count == 4)
        check("widgets: the café's picture, light and dark", pics.image(ready.cafeLight) != nil && pics.image(ready.cafeDark) != nil)
        for kind in WKind.allCases {
            for size in kind.sizes {
                for dark in [false, true] { check("widget-\(kind.rawValue)-\(size) ready \(dark ? "dark" : "light") drawn", shot(kind, size, ready, pics, name: "widget-\(kind.rawValue)-\(size)", dark: dark)) }
            }
        }

        // ---- a block on, twelve minutes in
        now = fresh()
        store.act("start", ["intention": "Draft the newsletter", "minutes": 25, "style": "ask", "cat": "miso"], now: now - 12 * MIN)
        feed.write()
        let focus = written()
        check("widgets: focusing, with the block's title and end", focus?.phase == .focus && focus?.run?.title == "Draft the newsletter" && focus?.run?.end == store.active?.end)
        check("widgets: twelve minutes in, the cat is a kitten", focus?.stage(at: nowMs()) == .kitten)
        if let f = focus {
            let p = WidgetPictures.load(f, folder: feed.folder)
            for size in WSize.allCases { for dark in [false, true] { check("widget-focus-\(size) focusing \(dark ? "dark" : "light") drawn", shot(.focus, size, f, p, name: "widget-focus-\(size)-on", dark: dark)) } }
            for dark in [false, true] { check("widget-quick focusing \(dark ? "dark" : "light") drawn", shot(.quick, .medium, f, p, name: "widget-quick-medium-on", dark: dark)) }
        }

        // ---- a check-in waiting, paused, on a break, and a cat that came home
        func one(_ name: String, _ phase: WidgetSnapshot.Phase, _ setUp: (Int) -> Void) {
            let n = fresh()
            setUp(n)
            feed.write()
            let s = written()
            check("widgets: \(name) is \(phase.rawValue)", s?.phase == phase)
            if let s { for dark in [false, true] { check("widget-focus-medium \(name) \(dark ? "dark" : "light") drawn", shot(.focus, .medium, s, WidgetPictures.load(s, folder: feed.folder), name: "widget-focus-medium-\(name)", dark: dark)) } }
        }
        one("checkin", .checkin) { n in
            store.act("start", ["intention": "Draft the newsletter", "minutes": 25, "style": "ask", "cat": "miso"], now: n - 16 * MIN)
            store.act("tick", now: n)
        }
        one("paused", .paused) { n in
            store.act("start", ["intention": "Draft the newsletter", "minutes": 25, "style": "ask", "cat": "miso"], now: n - 9 * MIN)
            store.act("pause", now: n - 2 * MIN)
        }
        one("break", .onBreak) { _ in store.startBreak(10) }
        one("home", .done) { n in
            store.act("start", ["intention": "Draft the newsletter", "minutes": 50, "style": "ask", "cat": "miso"], now: n - 51 * MIN)
            store.act("tick", now: n)
        }
        store.stopBreak()
        store.resetForTest()
    }

    /// One widget at its size, the way the desktop draws it: its face over its background, with the corners rounded.
    static func shot(_ kind: WKind, _ size: WSize, _ s: WidgetSnapshot, _ pics: WidgetPictures, name: String, dark: Bool) -> Bool {
        let p = size.points
        let face = WidgetFace(kind: kind, size: size, snap: s, pics: pics, now: Date())
            .frame(width: p.width, height: p.height)
            .background(WidgetBackground(kind: kind))
            .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
            .shadow(color: .black.opacity(0.14), radius: 7, y: 2)
        return Shot.exists(Shot.render(face, name: name, dark: dark, pad: 16, wall: false))
    }
}
