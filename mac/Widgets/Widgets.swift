import SwiftUI
import WidgetKit

// The widget extension: six widgets drawn from widget/now.json, which the app writes (App/WidgetFeed.swift). It is
// sandboxed, and its entitlements let it read that one folder of the app's and nothing else of the person's. It sends
// nothing anywhere. A click on a widget is a chataltul:// link, which the app takes (AppDelegate.openLink).

/// The app's widget folder. In the sandbox NSHomeDirectory is the extension's own container, so the home folder comes from
/// the user database. The app's bundle id is this one's without ".widgets".
enum Feed {
    static var folder: URL {
        var home = NSHomeDirectory()
        if let pw = getpwuid(getuid()), let d = pw.pointee.pw_dir { home = String(cString: d) }
        let me = Bundle.main.bundleIdentifier ?? ""
        let app = me.hasSuffix(".widgets") ? String(me.dropLast(".widgets".count)) : "xyz.tiresia.chataltul"
        return URL(fileURLWithPath: home).appendingPathComponent("Library/Application Support/\(app)/widget", isDirectory: true)
    }

    static func read() -> WidgetSnapshot? {
        guard let d = try? Data(contentsOf: folder.appendingPathComponent(WidgetSnapshot.file)) else { return nil }
        return WidgetSnapshot.decode(d)
    }

    /// What the gallery shows before the app has written anything.
    static var sample: WidgetSnapshot {
        var s = WidgetSnapshot()
        s.day = WidgetSnapshot.dayKey(nowMs())
        s.todayMin = 80; s.blocksToday = 2; s.catsHome = 2; s.streak = 5; s.goalMin = 120
        s.week = [true, false, true, true, true, true, false]
        s.later = ["Book the dentist", "Look up that podcast episode"]; s.laterCount = 2
        return s
    }
}

struct Entry: TimelineEntry {
    let date: Date
    let snap: WidgetSnapshot?
    let pics: WidgetPictures
}

struct Provider: TimelineProvider {
    var kind: WKind

    func placeholder(in context: Context) -> Entry { Entry(date: Date(), snap: Feed.sample, pics: WidgetPictures()) }

    func getSnapshot(in context: Context, completion: @escaping (Entry) -> Void) {
        let s = Feed.read()
        completion(Entry(date: Date(), snap: s ?? Feed.sample, pics: WidgetPictures.load(s, folder: Feed.folder)))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<Entry>) -> Void) {
        let now = nowMs()
        guard let s = Feed.read() else {
            completion(Timeline(entries: [Entry(date: Date(), snap: nil, pics: WidgetPictures())], policy: .after(Date().addingTimeInterval(15 * 60))))
            return
        }
        let pics = WidgetPictures.load(s, folder: Feed.folder)
        // only the Focus widget moves by itself (the ring and the cat, each minute); its clock counts down on its own
        let times = kind == .focus ? s.moments(from: now) : [now]
        let entries = times.map { Entry(date: Date(timeIntervalSince1970: Double($0) / 1000), snap: s, pics: pics) }
        completion(Timeline(entries: entries, policy: .after(Date(timeIntervalSince1970: Double(s.refresh(after: now)) / 1000))))
    }
}

struct FaceView: View {
    var kind: WKind
    var entry: Entry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        WidgetFace(kind: kind, size: family == .systemLarge ? .large : family == .systemMedium ? .medium : .small, snap: entry.snap, pics: entry.pics, now: entry.date)
            .widgetURL(WidgetFace.tap(kind, entry.snap))
            .containerBackground(for: .widget) { WidgetBackground(kind: kind) }
    }
}

func configuration(_ kind: WKind) -> some WidgetConfiguration {
    StaticConfiguration(kind: kind.rawValue, provider: Provider(kind: kind)) { e in FaceView(kind: kind, entry: e) }
        .configurationDisplayName(kind.name)
        .description(kind.about)
        .supportedFamilies(kind.sizes.map { $0 == .large ? .systemLarge : $0 == .medium ? .systemMedium : .systemSmall })
        .contentMarginsDisabled()
}

struct FocusWidget: Widget { var body: some WidgetConfiguration { configuration(.focus) } }
struct QuickWidget: Widget { var body: some WidgetConfiguration { configuration(.quick) } }
struct TodayWidget: Widget { var body: some WidgetConfiguration { configuration(.today) } }
struct StreakWidget: Widget { var body: some WidgetConfiguration { configuration(.streak) } }
struct LaterWidget: Widget { var body: some WidgetConfiguration { configuration(.later) } }
struct CafeWidget: Widget { var body: some WidgetConfiguration { configuration(.cafe) } }

@main
struct ChataltulWidgets: WidgetBundle {
    var body: some Widget {
        FocusWidget()
        QuickWidget()
        TodayWidget()
        StreakWidget()
        LaterWidget()
        CafeWidget()
    }
}
