import AppKit
import SwiftUI

/// Chips that wrap onto the next line.
struct FlowLayout: Layout {
    var spacing: CGFloat = 8
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let w = proposal.width ?? 400
        var x: CGFloat = 0, y: CGFloat = 0, row: CGFloat = 0
        for v in subviews {
            let s = v.sizeThatFits(.unspecified)
            if x + s.width > w, x > 0 { x = 0; y += row + spacing; row = 0 }
            x += s.width + spacing; row = max(row, s.height)
        }
        return CGSize(width: w, height: y + row)
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, row: CGFloat = 0
        for v in subviews {
            let s = v.sizeThatFits(.unspecified)
            if x + s.width > bounds.maxX, x > bounds.minX { x = bounds.minX; y += row + spacing; row = 0 }
            v.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(s))
            x += s.width + spacing; row = max(row, s.height)
        }
    }
}

/// The colors that mark a list.
let listColors: [Color] = [Color(hex: 0xE98A3C), Color(hex: 0x3D7BE0), Color(hex: 0x8A5CD6), Color(hex: 0xE0566B), Color(hex: 0x2FAE6A), Color(hex: 0xF4A340)]

/// The Blocklist (screen 14): lists of apps and sites, what happens when one is opened, and when.
struct BlocklistView: View {
    @ObservedObject var store: Store
    var watcher: Watcher
    @State private var selected: String?
    @State private var site = ""
    @State private var siteError = false
    @State private var addingList = false
    @State private var customName = ""
    @State private var customLink = ""
    @State private var customError = false
    @State private var allowText = ""
    @State private var allowError = false
    /// Too narrow for the lists in a column and the cards side by side (the Settings window, a small app window).
    @State private var narrow = false

    private var lists: [GuardList] { store.settings.blocklist.lists }
    private var index: Int { lists.firstIndex { $0.id == (selected ?? lists.first?.id) } ?? 0 }

    var body: some View {
        let layout = narrow ? AnyLayout(VStackLayout(alignment: .leading, spacing: 18)) : AnyLayout(HStackLayout(alignment: .top, spacing: 22))
        layout {
            if narrow { listChips } else { column }
            if lists.isEmpty {
                Text("No lists yet.").foregroundStyle(Palette.muted)
            } else {
                detail(binding(for: index)).frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .onGeometryChange(for: Bool.self) { $0.size.width < 840 } action: { narrow = $0 }
    }

    /// Side by side when there is room, else one under the other.
    private func pair<C: View>(_ spacing: CGFloat, @ViewBuilder _ content: () -> C) -> some View {
        let layout = narrow ? AnyLayout(VStackLayout(alignment: .leading, spacing: spacing)) : AnyLayout(HStackLayout(alignment: .top, spacing: spacing))
        return layout(content)
    }

    private func binding(for i: Int) -> Binding<GuardList> {
        Binding(get: { store.settings.blocklist.lists.indices.contains(i) ? store.settings.blocklist.lists[i] : GuardList(name: "") },
                set: { if store.settings.blocklist.lists.indices.contains(i) { store.settings.blocklist.lists[i] = $0 } })
    }

    // ---------------------------------------------------------------- the lists

    private var column: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label11(text: "LISTS")
            ForEach(lists) { l in
                let on = l.id == (selected ?? lists.first?.id)
                Button { selected = l.id } label: {
                    HStack(spacing: 10) {
                        Circle().fill(listColors[l.color % listColors.count]).frame(width: 8, height: 8)
                        Text(l.name).font(.system(size: 13, weight: on ? .semibold : .regular)).lineLimit(1)
                        Spacer(minLength: 4)
                        Text("\(l.count)").font(.system(size: 12)).foregroundStyle(Palette.muted)
                    }
                    .foregroundStyle(Palette.ink)
                    .padding(.horizontal, 10).padding(.vertical, 7)
                    .background(RoundedRectangle(cornerRadius: 9, style: .continuous).fill(on ? Palette.card : .clear))
                    .overlay(RoundedRectangle(cornerRadius: 9, style: .continuous).strokeBorder(on ? Palette.ringTrack : .clear, lineWidth: 1))
                    .contentShape(Rectangle())
                }.buttonStyle(.plain)
            }
            Button(action: newList) { Text("+ New list").font(.system(size: 13)).foregroundStyle(Palette.accentText).padding(.horizontal, 10).padding(.vertical, 7) }.buttonStyle(.plain)
        }
        .frame(width: 170, alignment: .leading)
    }

    /// The lists in a row, when there's no room for the column.
    private var listChips: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label11(text: "LISTS")
            FlowLayout {
                ForEach(lists) { l in
                    let on = l.id == (selected ?? lists.first?.id)
                    Button { selected = l.id } label: {
                        HStack(spacing: 8) {
                            Circle().fill(listColors[l.color % listColors.count]).frame(width: 8, height: 8)
                            Text(l.name).font(.system(size: 13, weight: on ? .semibold : .regular)).lineLimit(1)
                            Text("\(l.count)").font(.system(size: 12)).foregroundStyle(Palette.muted)
                        }
                        .foregroundStyle(Palette.ink)
                        .padding(.horizontal, 10).padding(.vertical, 6)
                        .background(RoundedRectangle(cornerRadius: 9, style: .continuous).fill(on ? Palette.card : .clear))
                        .overlay(RoundedRectangle(cornerRadius: 9, style: .continuous).strokeBorder(on ? Palette.ringTrack : .clear, lineWidth: 1))
                        .contentShape(Rectangle())
                    }.buttonStyle(.plain)
                }
                Button(action: newList) { Text("+ New list").font(.system(size: 13)).foregroundStyle(Palette.accentText).padding(.horizontal, 10).padding(.vertical, 6) }.buttonStyle(.plain)
            }
        }
    }

    private func newList() {
        let n = GuardList(name: "New list", color: lists.count % listColors.count)
        store.settings.blocklist.lists.append(n)
        selected = n.id
    }

    // ---------------------------------------------------------------- one list

    private func detail(_ list: Binding<GuardList>) -> some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 4) {
                    TextField("Name", text: list.name).textFieldStyle(.plain).font(.system(size: 24, weight: .bold)).foregroundStyle(Palette.ink)
                    Text("The apps and sites the cat walks over while you focus.").font(.system(size: 13)).foregroundStyle(Palette.muted)
                }
                Spacer()
                Toggle("", isOn: $store.settings.speedBump).toggleStyle(.switch).labelsHidden().tint(Palette.teal).help("Watching on or off, for every list")
            }
            pair(16) {
                appsCard(list).frame(maxWidth: narrow ? .infinity : 330)
                sitesCard(list)
            }
            VStack(alignment: .leading, spacing: 10) {
                Text("What happens when you open one").font(.system(size: 13, weight: .bold)).foregroundStyle(Palette.ink)
                pair(12) {
                    modeCard(list, .speedBump, "Speed bump", "The cat walks over it. Come back within 15 seconds, or hold to leave and \(store.cat(nil).name) wanders off.")
                    modeCard(list, .waitItOut, "Wait it out", "A 30-second countdown with the cat before it opens, every time. Most urges pass by then.")
                    modeCard(list, .locked, "Locked", "It won't open until the block ends, and the block can't be stopped. Chataltul refuses to quit meanwhile.")
                }.fixedSize(horizontal: false, vertical: true)
            }
            pair(16) {
                whenCard(list)
                allowanceCard(list)
            }
            if lists.count > 1 {
                Button("Delete this list") {
                    let id = list.wrappedValue.id
                    store.settings.blocklist.lists.removeAll { $0.id == id }
                    selected = nil
                }.buttonStyle(.plain).font(.system(size: 13)).foregroundStyle(Palette.orange)
            }
            browsers
        }
    }

    private func card<C: View>(@ViewBuilder _ c: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 10) { c() }
            .padding(.horizontal, 16).padding(.vertical, 14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Palette.card))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Palette.ringTrack, lineWidth: 1))
    }

    private func appsCard(_ list: Binding<GuardList>) -> some View {
        card {
            Text("Apps").font(.system(size: 13, weight: .bold)).foregroundStyle(Palette.ink)
            FlowLayout {
                ForEach(list.wrappedValue.apps, id: \.bundleID) { a in
                    HStack(spacing: 8) {
                        AppIcon(bundleID: a.bundleID)
                        Text(a.name).font(.system(size: 13)).foregroundStyle(Palette.ink)
                        Button { list.wrappedValue.apps.removeAll { $0 == a } } label: { Image(systemName: "xmark").font(.system(size: 9, weight: .bold)) }
                            .buttonStyle(.plain).foregroundStyle(Palette.muted).accessibilityLabel("Remove \(a.name)")
                    }
                    .padding(.horizontal, 10).padding(.vertical, 6)
                    .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Palette.soft))
                }
                Button { chooseApp(list) } label: { Text("+ Add an app").font(.system(size: 13)).foregroundStyle(Palette.accentText) }
                    .buttonStyle(.plain).padding(.horizontal, 10).padding(.vertical, 6)
                    .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).strokeBorder(style: StrokeStyle(lineWidth: 1, dash: [3, 3])).foregroundStyle(Palette.muted.opacity(0.6)))
            }
        }
    }

    private func sitesCard(_ list: Binding<GuardList>) -> some View {
        card {
            Text("Websites").font(.system(size: 13, weight: .bold)).foregroundStyle(Palette.ink)
            FlowLayout {
                ForEach(list.wrappedValue.sites, id: \.self) { s in
                    HStack(spacing: 6) {
                        Text(s).font(.system(size: 13)).foregroundStyle(Palette.ink)
                        Button { list.wrappedValue.sites.removeAll { $0 == s } } label: { Image(systemName: "xmark").font(.system(size: 9, weight: .bold)) }
                            .buttonStyle(.plain).foregroundStyle(Palette.muted).accessibilityLabel("Remove \(s)")
                    }
                    .padding(.horizontal, 10).padding(.vertical, 6)
                    .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Palette.soft))
                }
            }
            HStack {
                TitleField(placeholder: "Add a site, like tiktok.com", text: $site, size: 13, center: false, onSubmit: { addSite(list) })
                Button("Add") { addSite(list) }.buttonStyle(Chip(fill: Palette.accent, text: Palette.onAccent, size: 13, radius: 9, padding: EdgeInsets(top: 8, leading: 14, bottom: 8, trailing: 14)))
            }
            if siteError { Text("That isn't a website address.").font(.system(size: 12)).foregroundStyle(Palette.orange) }
            Divider().overlay(Palette.hairline)
            readyMade(list)
            Text("Covers subdomains. Safari, Chrome, Brave, Arc and Edge are read by the Mac app. Firefox and Zen can't be, so list the app itself if you want it guarded.")
                .font(.system(size: 12)).foregroundStyle(Palette.muted).fixedSize(horizontal: false, vertical: true)
        }
    }

    // ---------------------------------------------------------------- the ready-made lists

    private func readyMade(_ list: Binding<GuardList>) -> some View {
        let _ = store.listsRevision   // redraws when a download finishes
        let custom = store.settings.customLists
        return VStack(alignment: .leading, spacing: 9) {
            Label11(text: "READY-MADE")
            ForEach(Catalog.all) { c in categoryRow(c, list) }
            ForEach(custom) { c in categoryRow(c, list) }
            if addingList { addListForm(list) }
            else {
                Button { addingList = true } label: { Text("+ Add a list from a link").font(.system(size: 13)).foregroundStyle(Palette.accentText) }.buttonStyle(.plain)
            }
            if !list.wrappedValue.categories.isEmpty { allowBlock(list) }
            Text("Public lists by UT1 Toulouse (CC BY-SA 4.0), StevenBlack (MIT) and Hagezi (GPL-3.0). This Mac downloads them and looks again about once a week. Nothing about you is sent.")
                .font(.system(size: 12)).foregroundStyle(Palette.muted).fixedSize(horizontal: false, vertical: true)
        }
    }

    private func categoryRow(_ c: ListCategory, _ list: Binding<GuardList>) -> some View {
        let on = list.wrappedValue.categories.contains(c.id)
        let state = store.categories.state(of: c.id)
        let (text, problem) = stateText(state)
        var kept = [String]()
        if case .failed(_, let sites) = state, sites > 0 { kept = ["\(count(sites)) sites kept from before"] }
        return HStack(spacing: 10) {
            Toggle("", isOn: Binding(get: { on }, set: { store.setCategory(c.id, on: $0, in: list.wrappedValue.id) }))
                .toggleStyle(.switch).controlSize(.small).labelsHidden().tint(Palette.teal)
                .accessibilityLabel(c.name)
            Text(c.name).font(.system(size: 13, weight: on ? .semibold : .regular)).foregroundStyle(Palette.ink).lineLimit(1)
            Spacer(minLength: 6)
            Text(text).font(.system(size: 12)).foregroundStyle(problem ? Palette.orange : Palette.muted).lineLimit(1)
            if case .failed = state {
                Button("Retry") { store.categories.refresh([c.id], force: true) }
                    .buttonStyle(Chip(size: 12, radius: 8, padding: EdgeInsets(top: 4, leading: 9, bottom: 4, trailing: 9)))
            }
            if c.custom {
                Button { store.removeCustomList(c.id) } label: { Image(systemName: "xmark").font(.system(size: 9, weight: .bold)) }
                    .buttonStyle(.plain).foregroundStyle(Palette.muted).accessibilityLabel("Remove \(c.name)")
            }
        }
        .help((c.sources.map { $0.license.isEmpty ? "\($0.name)" : "\($0.name) · \($0.license)" } + kept).joined(separator: "\n"))
    }

    private func stateText(_ state: CategoryState) -> (String, Bool) {
        switch state {
        case .none(let kb): return (kb > 0 ? "About \(sizeText(kb))" : "Not downloaded", false)
        case .working: return ("Downloading…", false)
        case .ready(let sites, let updated): return ("\(count(sites)) sites · \(ago(updated))", false)
        case .failed(let why, _): return (why, true)
        }
    }
    private func sizeText(_ kb: Int) -> String { kb < 1000 ? "\(kb) KB" : String(format: "%.1f MB", Double(kb) / 1000) }
    private func count(_ n: Int) -> String { NumberFormatter.localizedString(from: NSNumber(value: n), number: .decimal) }
    private func ago(_ ms: Int) -> String {
        let days = max(0, (nowMs() - ms) / 86_400_000)
        return days == 0 ? "today" : days == 1 ? "yesterday" : "\(days) days ago"
    }

    private func addListForm(_ list: Binding<GuardList>) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            TitleField(placeholder: "Name, like Work distractions", text: $customName, size: 13, center: false, onSubmit: { addCustom(list) })
            TitleField(placeholder: "https:// link to a hosts, domain or AdGuard list", text: $customLink, size: 13, center: false, onSubmit: { addCustom(list) })
            HStack {
                Button("Add") { addCustom(list) }.buttonStyle(Chip(fill: Palette.accent, text: Palette.onAccent, size: 13, radius: 9, padding: EdgeInsets(top: 8, leading: 14, bottom: 8, trailing: 14)))
                Button("Cancel") { addingList = false; customName = ""; customLink = ""; customError = false }
                    .buttonStyle(Chip(size: 13, radius: 9, padding: EdgeInsets(top: 8, leading: 14, bottom: 8, trailing: 14)))
            }
            if customError { Text("That needs to be an https link.").font(.system(size: 12)).foregroundStyle(Palette.orange) }
        }
    }

    private func allowBlock(_ list: Binding<GuardList>) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Label11(text: "ALWAYS ALLOW")
            if !list.wrappedValue.allow.isEmpty {
                FlowLayout {
                    ForEach(list.wrappedValue.allow, id: \.self) { s in
                        HStack(spacing: 6) {
                            Text(s).font(.system(size: 13)).foregroundStyle(Palette.ink)
                            Button { list.wrappedValue.allow.removeAll { $0 == s } } label: { Image(systemName: "xmark").font(.system(size: 9, weight: .bold)) }
                                .buttonStyle(.plain).foregroundStyle(Palette.muted).accessibilityLabel("Stop allowing \(s)")
                        }
                        .padding(.horizontal, 10).padding(.vertical, 6)
                        .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Palette.soft))
                    }
                }
            }
            HStack {
                TitleField(placeholder: "A site to leave out, like linkedin.com", text: $allowText, size: 13, center: false, onSubmit: { addAllow(list) })
                Button("Add") { addAllow(list) }.buttonStyle(Chip(fill: Palette.accent, text: Palette.onAccent, size: 13, radius: 9, padding: EdgeInsets(top: 8, leading: 14, bottom: 8, trailing: 14)))
            }
            if allowError { Text("That isn't a website address.").font(.system(size: 12)).foregroundStyle(Palette.orange) }
        }
    }

    private func modeCard(_ list: Binding<GuardList>, _ mode: GuardMode, _ title: String, _ text: String) -> some View {
        let on = list.wrappedValue.mode == mode
        return Button { list.wrappedValue.mode = mode } label: {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text(title).font(.system(size: 15, weight: .bold)).foregroundStyle(Palette.ink)
                    Spacer()
                    if on { Image(systemName: "checkmark.circle.fill").foregroundStyle(Palette.accent) }
                }
                Text(text).font(.system(size: 13)).foregroundStyle(Palette.muted2).fixedSize(horizontal: false, vertical: true).multilineTextAlignment(.leading)
            }
            .padding(16).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Palette.card))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(on ? Palette.accent : Palette.ringTrack, lineWidth: on ? 2 : 1))
            .contentShape(Rectangle())
        }.buttonStyle(.plain)
    }

    private func whenCard(_ list: Binding<GuardList>) -> some View {
        let w = list.wrappedValue.when
        return card {
            radio("During every block", w.kind == .blocks) { list.wrappedValue.when.kind = .blocks }
            Divider().overlay(Palette.hairline)
            radio("And on weekdays, \(hourLabel(w.fromMin)) to \(hourLabel(w.toMin))", w.kind == .weekdays) { list.wrappedValue.when.kind = .weekdays }
            if w.kind == .weekdays {
                HStack {
                    Picker("From", selection: list.when.fromMin) { ForEach(Array(stride(from: 5 * 60, through: 22 * 60, by: 30)), id: \.self) { Text(hourLabel($0)).tag($0) } }.fixedSize()
                    Picker("To", selection: list.when.toMin) { ForEach(Array(stride(from: 6 * 60, through: 23 * 60, by: 30)), id: \.self) { Text(hourLabel($0)).tag($0) } }.fixedSize()
                }.font(.system(size: 13))
            }
            Divider().overlay(Palette.hairline)
            radio("Always", w.kind == .always) { list.wrappedValue.when.kind = .always }
        }
    }

    private func allowanceCard(_ list: Binding<GuardList>) -> some View {
        let l = list.wrappedValue
        let used = store.usedMs(l.id) / 60_000
        return card {
            HStack {
                Text(l.allowanceMin > 0 ? "Outside blocks, allow \(l.allowanceMin) min a day" : "Outside blocks, allow some time a day").font(.system(size: 14)).foregroundStyle(Palette.ink)
                Spacer()
                Toggle("", isOn: Binding(get: { l.allowanceMin > 0 }, set: { list.wrappedValue.allowanceMin = $0 ? 15 : 0 })).toggleStyle(.switch).labelsHidden().tint(Palette.teal)
            }
            if l.allowanceMin > 0 {
                HStack {
                    Picker("", selection: list.allowanceMin) { ForEach([5, 10, 15, 20, 30, 45, 60], id: \.self) { Text("\($0) min").tag($0) } }.labelsHidden().fixedSize()
                    Spacer()
                }
                Divider().overlay(Palette.hairline)
                HStack { Text("Used today").font(.system(size: 14)).foregroundStyle(Palette.ink); Spacer(); Text("\(min(used, l.allowanceMin)) of \(l.allowanceMin) min").font(.system(size: 14)).foregroundStyle(Palette.muted) }
            }
            Text(l.when.kind == .blocks ? "The allowance counts outside blocks, inside the times \(narrow ? "above" : "on the left"), so it needs weekdays or Always." : "After it's used up, it asks for \(l.mode == .locked ? "the lock" : "the wait") for the rest of the day.")
                .font(.system(size: 12)).foregroundStyle(Palette.muted).fixedSize(horizontal: false, vertical: true)
        }
    }

    private func radio(_ text: String, _ on: Bool, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Text(text).font(.system(size: 14)).foregroundStyle(Palette.ink)
                Spacer()
                Image(systemName: on ? "largecircle.fill.circle" : "circle").foregroundStyle(on ? Palette.accent : Palette.muted)
            }.contentShape(Rectangle())
        }.buttonStyle(.plain)
    }

    private func hourLabel(_ minutes: Int) -> String {
        let h = minutes / 60, m = minutes % 60
        let f = DateFormatter(); f.timeStyle = .short; f.dateStyle = .none
        return f.string(from: Calendar.current.date(bySettingHour: h, minute: m, second: 0, of: Date())!)
    }

    // ---------------------------------------------------------------- the browsers

    private var browsers: some View {
        card {
            Text("Reading the page in front").font(.system(size: 13, weight: .bold)).foregroundStyle(Palette.ink)
            let installed = ReadableBrowser.all.filter { NSWorkspace.shared.urlForApplication(withBundleIdentifier: $0.bundleID) != nil }
            if installed.isEmpty { Text("No browser it can read is installed.").font(.system(size: 13)).foregroundStyle(Palette.muted) }
            ForEach(installed, id: \.bundleID) { b in BrowserRow(browser: b, watcher: watcher) }
            Text("macOS asks once for each browser (Automation). Say no and Chataltul simply doesn't watch that browser's sites.").font(.system(size: 12)).foregroundStyle(Palette.muted)
        }
    }

    // ---------------------------------------------------------------- actions

    private func addSite(_ list: Binding<GuardList>) {
        siteError = false
        guard let d = Blocklist.normalizeDomain(site) else { if !site.trimmingCharacters(in: .whitespaces).isEmpty { siteError = true }; return }
        if !store.settings.blocklist.sites.contains(d) { list.wrappedValue.sites.append(d) }
        site = ""
    }

    private func addCustom(_ list: Binding<GuardList>) {
        if store.addCustomList(name: customName, link: customLink, to: list.wrappedValue.id) {
            addingList = false; customName = ""; customLink = ""; customError = false
        } else { customError = true }
    }

    private func addAllow(_ list: Binding<GuardList>) {
        allowError = false
        guard let d = Blocklist.normalizeDomain(allowText) else { if !allowText.trimmingCharacters(in: .whitespaces).isEmpty { allowError = true }; return }
        if !list.wrappedValue.allow.contains(d) { list.wrappedValue.allow.append(d) }
        allowText = ""
    }

    private func chooseApp(_ list: Binding<GuardList>) {
        let p = NSOpenPanel()
        p.allowedContentTypes = [.application]
        p.directoryURL = URL(fileURLWithPath: "/Applications")
        p.allowsMultipleSelection = true
        p.prompt = "Add"
        guard p.runModal() == .OK else { return }
        for url in p.urls {
            guard let b = Bundle(url: url), let id = b.bundleIdentifier, !store.settings.blocklist.apps.contains(where: { $0.bundleID == id }) else { continue }
            let name = (b.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String) ?? (b.object(forInfoDictionaryKey: "CFBundleName") as? String) ?? url.deletingPathExtension().lastPathComponent
            list.wrappedValue.apps.append(ListedApp(bundleID: id, name: name))
        }
    }
}

/// An app's own icon, small.
struct AppIcon: View {
    var bundleID: String
    var body: some View {
        if let url = NSWorkspace.shared.urlForApplication(withBundleIdentifier: bundleID) {
            Image(nsImage: NSWorkspace.shared.icon(forFile: url.path)).resizable().frame(width: 20, height: 20)
        } else {
            RoundedRectangle(cornerRadius: 5).fill(Palette.muted.opacity(0.3)).frame(width: 20, height: 20)
        }
    }
}

struct BrowserRow: View {
    var browser: ReadableBrowser
    var watcher: Watcher
    @State private var status: BrowserAccess = .unknown

    var body: some View {
        HStack(spacing: 10) {
            AppIcon(bundleID: browser.bundleID)
            VStack(alignment: .leading, spacing: 1) {
                Text(browser.name).font(.system(size: 14)).foregroundStyle(Palette.ink)
                Text(detail).font(.system(size: 12)).foregroundStyle(Palette.muted).fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 8)
            switch status {
            case .allowed: Text("Allowed").font(.system(size: 13, weight: .semibold)).foregroundStyle(Palette.accentText)
            case .denied:
                Button("Open System Settings") { NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Automation")!) }
                    .buttonStyle(Chip(size: 12, radius: 9, padding: EdgeInsets(top: 6, leading: 10, bottom: 6, trailing: 10)))
            case .notRunning: Text("Not open").font(.system(size: 13)).foregroundStyle(Palette.muted)
            case .unknown:
                Button("Allow…") { watcher.requestAccess(browser) { status = $0 } }
                    .buttonStyle(Chip(fill: Palette.accent, text: Palette.onAccent, size: 12, radius: 9, padding: EdgeInsets(top: 6, leading: 12, bottom: 6, trailing: 12)))
            }
        }
        .onAppear { watcher.refreshAccess(browser) { status = $0 } }
    }

    private var detail: String {
        switch status {
        case .allowed: return "Chataltul can see the address of the front tab."
        case .denied: return "Not allowed, so its sites aren't watched. Turn it on under Privacy → Automation."
        case .notRunning: return "Open it, and this shows whether it is allowed."
        case .unknown: return "Not asked yet."
        }
    }
}
