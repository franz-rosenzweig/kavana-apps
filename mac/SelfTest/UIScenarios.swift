import AppKit
import SwiftUI

/// Every stage-1 screen, drawn from the real views over a real engine state.
enum UIScenarios {
    static let MIN = 60_000

    static func run() throws {
        let store = try Store()
        let actions = PopoverActions()

        // an earlier day's worth of blocks, so "2 of 6" and "3 cats home" are true
        func finishedBlocks(_ n: Int, now: Int) {
            for k in 0..<n {
                let start = now - (n - k) * 70 * MIN
                store.act("start", ["intention": ["Outline the talk", "Read chapter 4", "Spanish practice"][k % 3], "minutes": 25, "style": "ask"], now: start)
                store.act("tick", now: start + 26 * MIN)
                if let w = store.awaiting { store.act("outcome", ["id": w.id, "outcome": "done"], now: start + 27 * MIN) }
            }
        }
        func fresh(blocksBefore: Int = 2) -> Int {
            store.resetForTest()
            let now = nowMs()
            finishedBlocks(blocksBefore, now: now)
            for t in ["Book the dentist", "Look up that podcast episode", "Return the library books"] { store.act("addLater", ["text": t], now: now - 3600_000) }
            return now
        }

        // ---- ready
        var now = fresh()
        check("ready: nothing is running", store.active == nil && store.awaiting == nil)
        check("ready: two blocks done today", store.blocksToday == 2)
        for dark in [false, true] { check("popover-ready \(dark ? "dark" : "light") drawn", Shot.exists(Shot.render(PopoverRoot(store: store, actions: actions), name: "popover-ready", dark: dark))) }

        // ---- focusing
        now = fresh()
        store.act("start", ["intention": "Draft the newsletter", "minutes": 25, "style": "ask", "cat": "miso"], now: now - 7 * MIN)
        check("focus: a block runs with a growing kitten", store.active?.kitten == "growing")
        for dark in [false, true] { check("popover-focus \(dark ? "dark" : "light") drawn", Shot.exists(Shot.render(PopoverRoot(store: store, actions: actions), name: "popover-focus", dark: dark))) }

        // ---- a check-in waiting
        now = fresh()
        store.act("start", ["intention": "Draft the newsletter", "minutes": 25, "style": "ask", "cat": "miso"], now: now - 16 * MIN)
        store.act("tick", now: now)
        check("checkin: the check-in is pending", store.active?.pending == 1)
        for dark in [false, true] {
            check("popover-checkin \(dark ? "dark" : "light") drawn", Shot.exists(Shot.render(PopoverRoot(store: store, actions: actions), name: "popover-checkin", dark: dark)))
            let model = CatCardModel(label: "CHECK-IN", cat: "miso", title: "Boo! Still on: Draft the newsletter?", body: "9 min left · answer here, no need to switch apps",
                buttons: [CatButton(label: "Yes", primary: true, symbol: "checkmark") {}, CatButton(label: "Drifted", symbol: "arrow.triangle.2.circlepath") {}],
                hint: "Or press ⌃⌥Y for Yes, ⌃⌥D for Drifted")
            check("cat-checkin \(dark ? "dark" : "light") drawn", Shot.exists(Shot.render(CatCardView(model: model), name: "cat-checkin", dark: dark, pad: 24)))
        }
        // the menu bar says so
        let sc = StatusController(store: store)
        if case .focusing(_, _, let pending) = sc.menuState(now: nowMs()) { check("menu bar: the orange dot shows while a check-in waits", pending) } else { check("menu bar: focusing state", false) }

        // ---- paused
        now = fresh()
        store.act("start", ["intention": "Draft the newsletter", "minutes": 25, "style": "ask", "cat": "miso"], now: now - 9 * MIN)
        store.act("pause", now: now - 2 * MIN)
        check("paused: the engine says when it ends", store.active?.pauseEnds != nil)
        for dark in [false, true] { check("popover-paused \(dark ? "dark" : "light") drawn", Shot.exists(Shot.render(PopoverRoot(store: store, actions: actions), name: "popover-paused", dark: dark))) }

        // ---- came home
        now = fresh()
        store.act("start", ["intention": "Draft the newsletter", "minutes": 50, "style": "ask", "cat": "miso"], now: now - 51 * MIN)
        store.act("tick", now: now)
        check("home: the kitten came home", store.awaiting?.kitten == "home")
        for dark in [false, true] { check("popover-home \(dark ? "dark" : "light") drawn", Shot.exists(Shot.render(PopoverRoot(store: store, actions: actions), name: "popover-home", dark: dark))) }
        store.act("outcome", ["id": store.awaiting!.id, "outcome": "done"], now: nowMs())

        // ---- a kitten that wandered off
        now = fresh()
        store.act("start", ["intention": "Spanish practice", "minutes": 25, "style": "ask", "cat": "miso"], now: now - 20 * MIN)
        store.act("away", ["at": now - 15 * MIN, "grace": 0], now: now - 15 * MIN)
        store.act("tick", now: now + 6 * MIN)
        check("wandered: the kitten left and the block ended", store.awaiting?.kitten == "gone")
        for dark in [false, true] { check("popover-wandered \(dark ? "dark" : "light") drawn", Shot.exists(Shot.render(PopoverRoot(store: store, actions: actions), name: "popover-wandered", dark: dark))) }

        // ---- back from being away
        now = fresh()
        store.act("start", ["intention": "Read chapter 4", "minutes": 50, "style": "ask", "cat": "miso"], now: now - 20 * MIN)
        store.awayPrompt = AwayReturn(from: now - 12 * MIN, to: now)
        for dark in [false, true] { check("popover-away \(dark ? "dark" : "light") drawn", Shot.exists(Shot.render(PopoverRoot(store: store, actions: actions), name: "popover-away", dark: dark))) }
        store.awayPrompt = nil

        // ---- a break
        now = fresh()
        store.act("startBreak", ["minutes": 10], now: now - 3 * MIN)
        check("break: the engine has a break", store.onBreak != nil)
        for dark in [false, true] { check("popover-break \(dark ? "dark" : "light") drawn", Shot.exists(Shot.render(PopoverRoot(store: store, actions: actions), name: "popover-break", dark: dark))) }

        // ---- sounds
        store.resetForTest()
        store.settings.mix = [MixEntry(id: "rain"), MixEntry(id: "purr")]
        for dark in [false, true] { check("popover-sounds \(dark ? "dark" : "light") drawn", Shot.exists(Shot.render(SoundsPane(store: store, done: {}).frame(width: 340).cardBackground(), name: "popover-sounds", dark: dark))) }
        store.settings.mix = []

        // ---- the other cat cards
        for dark in [false, true] {
            let nudge = CatCardModel(cat: "miso", title: "One thing at a time.", body: "Draft the newsletter")
            check("cat-nudge \(dark ? "dark" : "light") drawn", Shot.exists(Shot.render(CatCardView(model: nudge), name: "cat-nudge", dark: dark, pad: 24)))
            let end = CatCardModel(cat: "miso", title: "Boo! Time's up. How did it go?", body: "Draft the newsletter\nMiso came home to your café. +25 treats",
                buttons: [CatButton(label: "Done", primary: true) {}, CatButton(label: "Partly") {}, CatButton(label: "Not really") {}])
            check("cat-end \(dark ? "dark" : "light") drawn", Shot.exists(Shot.render(CatCardView(model: end), name: "cat-end", dark: dark, pad: 24)))
            let gone = CatCardModel(cat: "miso", picture: Art.gone, title: "Miso wandered off.", body: "Your block keeps going.")
            check("cat-wandered \(dark ? "dark" : "light") drawn", Shot.exists(Shot.render(CatCardView(model: gone), name: "cat-wandered", dark: dark, pad: 24)))
            let ask = CatCardModel(label: "ONE QUESTION", cat: "miso", title: "May I see which page is open in Chrome?",
                body: "Your list has websites on it. macOS will ask once. I only look at the address of the front tab, and nothing leaves this Mac. If you say no, I just won't watch Chrome.",
                buttons: [CatButton(label: "Ask macOS", primary: true) {}, CatButton(label: "Not now") {}])
            check("cat-access \(dark ? "dark" : "light") drawn", Shot.exists(Shot.render(CatCardView(model: ask), name: "cat-access", dark: dark, pad: 24)))
        }

        // ---- the speed bump, over a page
        for dark in [false, true] {
            let model = BumpModel()
            let n = nowMs()
            model.card = BumpCard(target: .site(domain: "youtube.com", browser: "Chrome"), shownAt: n - 3_000, blockID: "x", cat: "miso", catName: "Miso", title: "Draft the newsletter", blockEnd: n + 31 * MIN)
            let page = ZStack {
                Color(white: dark ? 0.12 : 0.97)
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 22), count: 4), spacing: 22) {
                    ForEach(0..<12, id: \.self) { _ in RoundedRectangle(cornerRadius: 10).fill(Color(white: dark ? 0.2 : 0.9)).frame(height: 150) }
                }.padding(28)
                SpeedBumpView(model: model)
            }.frame(width: 1100, height: 820)
            check("speedbump \(dark ? "dark" : "light") drawn", Shot.exists(Shot.render(page, name: "speedbump", dark: dark, pad: 0, wall: false)))
        }

        // ---- park a thought
        now = fresh()
        for dark in [false, true] { check("park \(dark ? "dark" : "light") drawn", Shot.exists(Shot.render(ParkView(store: store, done: {}), name: "park", dark: dark, pad: 30))) }

        // ---- the menu bar item in every state
        func strip(_ dark: Bool) -> some View {
            let states: [(String, MenuState, String)] = [
                ("Ready", .ready, ""), ("Focusing", .focusing(remaining: 0.62, text: "31:48", pending: false), "31:48"),
                ("Check-in waiting", .focusing(remaining: 0.48, text: "24:06", pending: true), "24:06"), ("Paused", .paused(remaining: 0.58, text: "4:58"), "4:58"),
                ("On a break", .breakTime(remaining: 0.6, text: "08:42"), "08:42"), ("Came home", .home, "Home"),
            ]
            return HStack(spacing: 14) {
                ForEach(0..<states.count, id: \.self) { i in
                    VStack(spacing: 6) {
                        HStack(spacing: 2) {
                            Image(nsImage: MenuIcon.image(states[i].1))
                            Text(states[i].2).font(Font(StatusController.clockFont(13)))
                                .foregroundStyle(Color(nsColor: MenuIcon.textColor(orange: { if case .paused = states[i].1 { return true } else { return false } }())))
                        }
                        .frame(height: 30).padding(.horizontal, 10)
                        .background(RoundedRectangle(cornerRadius: 8).fill(dark ? Color(hex: 0x221D27) : Color(hex: 0xF3ECE2)))
                        Text(states[i].0).font(.system(size: 11)).foregroundStyle(Palette.muted)
                    }
                }
            }
        }
        for dark in [false, true] { check("menubar \(dark ? "dark" : "light") drawn", Shot.exists(Shot.render(strip(dark), name: "menubar", dark: dark, pad: 20))) }


        // ================================================================ stage 2
        // ---- a café worth looking at: two weeks of long blocks, things bought with the treats, today's blocks
        func richDoc() {
            store.resetForTest()
            let cal = Calendar.current
            let startOfToday = cal.startOfDay(for: Date())
            let cats = ["miso", "pepper", "mochi", "pickles", "earl", "biscuit", "miso", "pepper", "mochi", "pickles", "miso", "earl"]
            for (i, d) in (1...12).reversed().enumerated() {
                let day = cal.date(byAdding: .day, value: -d, to: startOfToday)!
                let start = Int(day.addingTimeInterval(10 * 3600).timeIntervalSince1970 * 1000)
                store.act("start", ["intention": "Earlier \(d)", "minutes": 90, "style": "ask", "cat": cats[i % cats.count], "tag": ["Writing", "Work", "Reading", "Study"][i % 4]], now: start)
                store.act("tick", now: start + 91 * MIN)
                if let w = store.awaiting { store.act("outcome", ["id": w.id, "outcome": "done"], now: start + 92 * MIN) }
            }
            let n = nowMs()
            for id in ["cushion", "rug", "window", "clock", "lamp", "plant", "bookshelf", "cattree", "counter", "armchair", "box", "fireplace"] { store.act("buy", ["id": id], now: n - 3 * 3600_000) }
            // today
            let t = [(n - 230 * MIN, 50, "Outline the talk", "Work", "pepper", true), (n - 150 * MIN, 40, "Read chapter 4", "Reading", "earl", true), (n - 80 * MIN, 9, "Spanish practice", "Study", "pickles", false)]
            for (start, mins, title, tag, cat, finish) in t {
                store.act("start", ["intention": title, "minutes": mins, "style": "ask", "cat": cat, "tag": tag], now: start)
                if finish { store.act("tick", now: start + (mins + 1) * MIN); if let w = store.awaiting { store.act("outcome", ["id": w.id, "outcome": "done"], now: start + (mins + 2) * MIN) } }
                else { store.act("away", ["at": start + 4 * MIN, "grace": 0], now: start + 4 * MIN); store.act("stop", now: start + mins * MIN); if let w = store.awaiting { store.act("outcome", ["id": w.id, "outcome": nil as Any? ?? NSNull()], now: start + (mins + 1) * MIN) } }
            }
            store.act("start", ["intention": "Draft the newsletter", "minutes": 25, "style": "ask", "cat": "miso", "tag": "Writing"], now: n - 7 * MIN)
            for t in ["Book the dentist", "Look up that podcast episode", "Return the library books", "Find the photos from March"] { store.act("addLater", ["text": t], now: n - 3600_000) }
        }
        richDoc()
        check("a rich doc: things are owned", store.state.things.count >= 6)
        check("a rich doc: many blocks", store.state.history.count >= 12)
        let sync2 = SyncController(store: store)
        let watch2 = Watcher(store: store)
        func windowShot(_ section: WindowSection, _ name: String, dark: Bool, span: Span? = nil) {
            let nav = WindowNav(section)
            let root = WindowRoot(store: store, nav: nav, sync: sync2, watcher: watch2, openSettings: { _ in }, startBlock: {}).frame(width: 1240, height: 800)
            check("\(name) \(dark ? "dark" : "light") drawn", Shot.exists(Shot.render(root, name: name, dark: dark, pad: 0, wall: false)))
        }
        for dark in [false, true] {
            windowShot(.today, "window-today", dark: dark)
            windowShot(.cafe, "window-cafe", dark: dark)
            windowShot(.history, "window-history", dark: dark)
            windowShot(.later, "window-later", dark: dark)
            windowShot(.blocklist, "window-blocklist", dark: dark)
            windowShot(.breaks, "window-breaks", dark: dark)
        }
        // the month, with the span set the way a click would set it
        for dark in [false, true] {
            let month = TodayView(store: store, showCafe: {}, initialSpan: .month).frame(width: 1008, height: 800).background(Palette.page)
            check("window-month \(dark ? "dark" : "light") drawn", Shot.exists(Shot.render(month, name: "window-month", dark: dark, pad: 0, wall: false)))
            let week = TodayView(store: store, showCafe: {}, initialSpan: .week).frame(width: 1008, height: 800).background(Palette.page)
            check("window-week \(dark ? "dark" : "light") drawn", Shot.exists(Shot.render(week, name: "window-week", dark: dark, pad: 0, wall: false)))
        }

        // ---- a list with every setting on show
        var rich = Blocklist(lists: [
            GuardList(name: "Distractions", color: 0, apps: [ListedApp(bundleID: "com.apple.mail", name: "Mail"), ListedApp(bundleID: "com.apple.MobileSMS", name: "Messages"), ListedApp(bundleID: "com.apple.news", name: "News")], sites: ["youtube.com", "reddit.com", "x.com", "instagram.com", "news.ycombinator.com"], mode: .speedBump, when: GuardWhen(.blocks)),
            GuardList(name: "News", color: 1, sites: ["bbc.co.uk", "theguardian.com"], mode: .waitItOut, when: GuardWhen(.weekdays), allowanceMin: 15),
            GuardList(name: "Messages", color: 2, apps: [ListedApp(bundleID: "net.whatsapp.WhatsApp", name: "WhatsApp")], mode: .locked, when: GuardWhen(.always)),
        ])
        let savedList = store.settings.blocklist
        store.settings.blocklist = rich
        store.addUsage(rich.lists[1].id, ms: 6 * 60_000)
        for dark in [false, true] { windowShot(.blocklist, "window-blocklist-news", dark: dark) }
        rich = savedList
        store.settings.blocklist = savedList

        // ---- the ready-made lists: some here, one coming, one that failed, one added by link
        func fakeDomains(_ n: Int, _ tag: String) -> [String] { (0..<n).map { "\(tag)\($0).test" } }
        let mineList = Catalog.custom(name: "Work distractions", link: "https://lists.example.net/work.txt")!
        store.settings.customLists = [mineList]
        store.settings.blocklist = Blocklist(lists: [
            GuardList(name: "Distractions", color: 0, apps: [ListedApp(bundleID: "com.apple.MobileSMS", name: "Messages")], sites: ["youtube.com", "news.ycombinator.com"],
                      categories: ["adult", "social", "dating", "gambling", mineList.id], allow: ["linkedin.com"]),
            GuardList(name: "News", color: 1, sites: ["bbc.co.uk"], categories: ["news"]),
        ])
        store.categories.install(fakeDomains(90_862, "a"), source: "hagezi-nsfw")
        store.categories.install(fakeDomains(76_874, "b"), source: "stevenblack-porn-only")
        store.categories.install(fakeDomains(715, "c") + ["tiktok.com", "m.reddit.com"], source: "ut1-social_networks")
        store.categories.install(fakeDomains(3_808, "d"), source: "stevenblack-social-only")
        store.categories.simulate(source: "ut1-dating", working: true)
        store.categories.install(fakeDomains(6_723, "e"), source: "stevenblack-gambling-only")
        store.categories.simulate(source: "ut1-gambling", error: "Couldn't reach it")
        store.categories.install(fakeDomains(1_240, "f"), source: mineList.sources[0].id)
        store.categories.install(fakeDomains(4_644, "g"), source: "ut1-press")
        check("lists: a category is ready when all its lists are here", { if case .ready(let n, _) = store.categories.state(of: "adult") { return n == 167_736 } else { return false } }())
        check("lists: one being fetched says so", store.categories.state(of: "dating") == .working)
        check("lists: a failed one keeps the sites it had", store.categories.state(of: "gambling") == .failed("Couldn't reach it", sites: 6_723))
        check("lists: one added by link is here", { if case .ready(let n, _) = store.categories.state(of: mineList.id) { return n == 1_240 } else { return false } }())
        check("lists: one not asked for is not here", store.categories.state(of: "games") == .none(sizeKB: 200))
        check("lists: a site on a switched-on list is found", store.settings.blocklist.hit(url: "https://www.tiktok.com/@x", browser: "Safari", matcher: store.categories)?.target == .site(domain: "tiktok.com", browser: "Safari"))
        check("lists: an allowed site is not", store.settings.blocklist.hit(url: "https://www.linkedin.com/in/me", browser: "Safari", matcher: store.categories) == nil)
        check("lists: the typed ones still are", store.settings.blocklist.hit(url: "https://youtube.com/watch", browser: "Safari", matcher: store.categories)?.list.name == "Distractions")
        for dark in [false, true] {
            let nav = WindowNav(.blocklist)
            let root = WindowRoot(store: store, nav: nav, sync: sync2, watcher: watch2, openSettings: { _ in }, startBlock: {}).frame(width: 1240, height: 1500)
            check("window-blocklist-lists \(dark ? "dark" : "light") drawn", Shot.exists(Shot.render(root, name: "window-blocklist-lists", dark: dark, pad: 0, wall: false)))
        }
        store.settings.customLists = []
        store.settings.blocklist = savedList

        // ---- the full-screen break, and the eye break card
        let model = BreakModel(brk: Brk(minutes: 10, start: nowMs() - 80_000, end: nowMs() + 520_000), idea: BreakSettings().ideaFor(0), strict: false, postponeMin: 5, cat: "miso", eyeTip: true)
        check("break-screen drawn", Shot.exists(Shot.render(BreakScreenView(model: model).frame(width: 1440, height: 900), name: "break-screen", dark: true, pad: 0, wall: false)))
        let strict = BreakModel(brk: Brk(minutes: 15, start: nowMs() - 80_000, end: nowMs() + 820_000), idea: BreakSettings().ideaFor(1), strict: true, postponeMin: 5, cat: "pepper", eyeTip: false)
        check("break-screen strict drawn", Shot.exists(Shot.render(BreakScreenView(model: strict).frame(width: 1440, height: 900), name: "break-screen-strict", dark: true, pad: 0, wall: false)))
        for dark in [false, true] {
            var eye = CatCardModel(label: "EYE BREAK", cat: "miso", title: "Look at something far away.", body: "For 20 seconds. Your eyes will thank you.", buttons: [CatButton(label: "Skip") {}])
            eye.countdownUntil = nowMs() + 14_000
            check("cat-eye \(dark ? "dark" : "light") drawn", Shot.exists(Shot.render(CatCardView(model: eye), name: "cat-eye", dark: dark, pad: 24)))
        }

        // ---- wait it out, locked, used up
        for dark in [false, true] {
            for (kind, name) in [(OtherKind.waitItOut, "wait"), (.locked, "locked"), (.usedUp, "usedup")] {
                let bm = BumpModel()
                let n = nowMs()
                bm.other = OtherCard(kind: kind, target: .site(domain: "bbc.co.uk", browser: "Safari"), listName: "News", shownAt: n - 7_000, waitMs: 30_000, until: n + 31 * MIN, cat: "miso", catName: "Miso")
                let page = ZStack { Color(white: dark ? 0.12 : 0.97); SpeedBumpView(model: bm) }.frame(width: 1100, height: 820)
                check("guard-\(name) \(dark ? "dark" : "light") drawn", Shot.exists(Shot.render(page, name: "guard-\(name)", dark: dark, pad: 0, wall: false)))
            }
        }


        // ================================================================ stage 3
        // where the time went: the blocks of today and the earlier ones get app by app records
        let wnow = nowMs()
        let todayStart = Calendar.current.startOfDay(for: Date())
        for b in store.state.history.prefix(20) {
            let apps: [(String, String, Int)] = [("com.apple.TextEdit", "Writer", 41), ("com.apple.Safari", "Safari", 6), ("com.apple.mail", "Mail", 3), ("com.apple.iBooksX", "Books", 8)]
            for (id, name, min) in apps { for _ in 0..<min { store.addTiming(block: b.id, start: b.start, bundleID: id, name: name, ms: 60_000, site: id == "com.apple.Safari" ? (min > 5 ? "youtube.com" : "library.example", "Safari") : nil) } }
        }
        _ = wnow; _ = todayStart
        check("timing: where it went is remembered", store.wentRows(for: store.state.history.prefix(3).map { $0.id }).first?.name == "Writer")
        // the popover after a block, with where it went
        store.resetForTest()
        let n3 = nowMs()
        store.act("start", ["intention": "Draft the newsletter", "minutes": 50, "style": "ask", "cat": "miso"], now: n3 - 51 * MIN)
        store.act("tick", now: n3)
        if let w = store.awaiting {
            for (id, name, min) in [("com.apple.TextEdit", "Writer", 41), ("com.apple.Safari", "Safari", 6), ("com.apple.mail", "Mail", 3)] { for _ in 0..<min { store.addTiming(block: w.id, start: w.start, bundleID: id, name: name, ms: 60_000, site: id == "com.apple.Safari" ? ("youtube.com", "Safari") : nil) } }
            check("popover-home: where it went is there", store.wentRows(for: [w.id]).count == 3)
        }
        for dark in [false, true] { check("popover-home-went \(dark ? "dark" : "light") drawn", Shot.exists(Shot.render(PopoverRoot(store: store, actions: actions), name: "popover-home-went", dark: dark))) }
        store.act("outcome", ["id": store.awaiting!.id, "outcome": "done"], now: nowMs())

        // the week in review, with drift reasons and a paused day
        richDoc()
        for (i, b) in store.state.history.prefix(12).enumerated() where i % 3 == 0 {
            store.act("answer", ["id": b.id, "n": 1, "answer": "drifted", "why": ["web", "phone", "messages", "web"][i % 4]], now: nowMs())
        }
        for b in store.state.history.prefix(40) {
            for (id, name, min) in [("com.apple.TextEdit", "Writer", 62), ("com.apple.iBooksX", "Books", 20), ("com.apple.Safari", "Safari", 11), ("com.apple.mail", "Mail", 7)] {
                for _ in 0..<min { store.addTiming(block: b.id, start: b.start, bundleID: id, name: name, ms: 6_000, site: id == "com.apple.Safari" ? ("youtube.com", "Safari") : nil) }
            }
        }
        store.settings.recordTiming = true
        let weekNav = WindowNav(.today); weekNav.openSpan = .week
        for dark in [false, true] {
            let root = WindowRoot(store: store, nav: weekNav, sync: sync2, watcher: watch2, openSettings: { _ in }, startBlock: {}).frame(width: 1240, height: 800)
            check("window-review \(dark ? "dark" : "light") drawn", Shot.exists(Shot.render(root, name: "window-review", dark: dark, pad: 0, wall: false)))
        }
        let monthWent = TodayView(store: store, showCafe: {}, initialSpan: .month).frame(width: 1008, height: 800).background(Palette.page)
        check("window-month-went drawn", Shot.exists(Shot.render(monthWent, name: "window-month-went", dark: true, pad: 0, wall: false)))
        // the reason card after Drifted
        for dark in [false, true] {
            var reasons = CatCardModel(label: "DRIFTED", cat: "miso", title: "No worries. What pulled you away?", body: nil, buttons: Reason.quick.map { id in CatButton(label: Reason.label(id)) {} } + [CatButton(label: "Other") {}])
            reasons.compactButtons = true
            check("cat-reason \(dark ? "dark" : "light") drawn", Shot.exists(Shot.render(CatCardView(model: reasons), name: "cat-reason", dark: dark, pad: 24)))
        }

        // ---- Settings
        let sync = SyncController(store: store)
        let watcher = Watcher(store: store)
        for dark in [false, true] {
            for pane in SettingsPane.allCases {
                let name = "settings-\(pane.rawValue)"
                check("\(name) \(dark ? "dark" : "light") drawn", Shot.exists(Shot.render(SettingsView(store: store, nav: SettingsNav(pane), sync: sync, watcher: watcher).frame(width: 900, height: 640), name: name, dark: dark, pad: 0, wall: false)))
            }
            // the Blocklist at the window's smallest, and all of it at the usual width
            check("settings-blocklist-small \(dark ? "dark" : "light") drawn", Shot.exists(Shot.render(SettingsView(store: store, nav: SettingsNav(.blocklist), sync: sync, watcher: watcher).frame(width: SettingsView.minSize.width, height: SettingsView.minSize.height), name: "settings-blocklist-small", dark: dark, pad: 0, wall: false)))
            check("settings-blocklist-all \(dark ? "dark" : "light") drawn", Shot.exists(Shot.render(SettingsView(store: store, nav: SettingsNav(.blocklist), sync: sync, watcher: watcher).frame(width: 900, height: 1900), name: "settings-blocklist-all", dark: dark, pad: 0, wall: false)))
        }
    }
}
