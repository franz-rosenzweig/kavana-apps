import AppKit
import SwiftUI

/// "Where your blocks went": the apps the blocks spent their time in, kept only on this Mac.
struct WentPanel: View {
    var rows: [WentRow]
    private let palette: [Color] = [Palette.accent, Color(hex: 0x7FB1F5), Color(hex: 0xFFCF8A), Color(hex: 0xA99BE8), Color(hex: 0xF29BB0), Color(hex: 0x8A8A95)]

    var body: some View {
        if !rows.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Text("Where your blocks went").font(.system(size: 13, weight: .bold)).foregroundStyle(Palette.ink)
                    Spacer()
                    Text("only on this Mac").font(.system(size: 13)).foregroundStyle(Palette.muted)
                }
                GeometryReader { g in
                    HStack(spacing: 2) {
                        ForEach(Array(rows.enumerated()), id: \.offset) { i, r in
                            RoundedRectangle(cornerRadius: 5).fill(palette[i % palette.count]).frame(width: max(4, (g.size.width - CGFloat(rows.count) * 2) * CGFloat(r.share)))
                        }
                    }
                }.frame(height: 10)
                FlowLayout(spacing: 14) {
                    ForEach(Array(rows.enumerated()), id: \.offset) { i, r in
                        HStack(spacing: 5) {
                            RoundedRectangle(cornerRadius: 2).fill(palette[i % palette.count]).frame(width: 8, height: 8)
                            Text("\(r.name) \(Int((r.share * 100).rounded()))%").font(.system(size: 12)).foregroundStyle(Palette.muted2)
                        }
                    }
                }
            }
            .padding(.horizontal, 16).padding(.vertical, 14).frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Palette.tile))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(Palette.tileLine, lineWidth: 1))
        }
    }
}

/// The week in review (screen 15): a few words about the week made on this Mac, each day by tag against the
/// goal, a question box, the streak with its pause, how the blocks went, and what pulled you away.
struct WeekReview: View {
    @ObservedObject var store: Store
    var blocks: [Hist]
    var period: Period
    @Binding var question: String
    @Binding var answer: String
    @Binding var asking: Bool
    var ask: () -> Void

    private var facts: WeekFacts { Review.facts(blocks: blocks, period: period, goalMin: store.engineSettings.goal, streak: store.streak) }

    var body: some View {
        let f = facts
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                summaryCard(f)
                HStack(alignment: .top, spacing: 16) {
                    VStack(spacing: 16) { eachDay; askCard; WentPanel(rows: store.settings.recordTiming ? store.wentRows(for: blocks.map { $0.id }) : []) }.frame(maxWidth: .infinity)
                    VStack(spacing: 16) { streakCard(f); outcomes(f); pulled(f) }.frame(width: 330)
                }
            }
        }
    }

    private func card<C: View>(@ViewBuilder _ c: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 10) { c() }
            .padding(.horizontal, 18).padding(.vertical, 16).frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Palette.tile))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(Palette.tileLine, lineWidth: 1))
    }

    // ---------------------------------------------------------------- the words

    private func summaryCard(_ f: WeekFacts) -> some View {
        card {
            HStack(spacing: 18) {
                CatImage(path: Art.kitten(store.state.companion)).frame(width: 76, height: 76)
                VStack(alignment: .leading, spacing: 6) {
                    Text("YOUR WEEK, IN A FEW WORDS · MADE ON THIS MAC").font(.system(size: 11, weight: .bold)).tracking(1).foregroundStyle(Palette.muted)
                    Text(Review.summary(f)).font(.system(size: 16)).foregroundStyle(Palette.ink).fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }

    // ---------------------------------------------------------------- each day

    private func tagColor(_ tag: String) -> Color {
        if tag.isEmpty { return Palette.muted }
        let i = store.engineSettings.tags.firstIndex(of: tag) ?? abs(tag.hashValue % 6)
        return listColors[i % listColors.count]
    }

    private var eachDay: some View {
        let stacks = Insights.slotsByTag(blocks, in: period)
        let totals = stacks.map { $0.values.reduce(0, +) }
        let goal = store.engineSettings.goal
        let maxV = max(1, max(totals.max() ?? 1, goal))
        let tags = Array(Set(stacks.flatMap { $0.keys })).sorted()
        return card {
            HStack {
                Text("Each day").font(.system(size: 13, weight: .bold)).foregroundStyle(Palette.ink)
                Spacer()
                if goal > 0 { Text("goal \(Insights.hours(goal)) a day").font(.system(size: 13)).foregroundStyle(Palette.muted) }
            }
            ZStack(alignment: .bottom) {
                HStack(alignment: .bottom, spacing: 16) {
                    ForEach(0..<7, id: \.self) { i in
                        VStack(spacing: 2) {
                            Spacer(minLength: 0)
                            if totals[i] == 0 { RoundedRectangle(cornerRadius: 4).fill(Palette.ringTrack).frame(height: 6) }
                            else {
                                ForEach(stacks[i].sorted { $0.key < $1.key }, id: \.key) { tag, m in
                                    Rectangle().fill(tagColor(tag)).frame(height: max(3, CGFloat(m) / CGFloat(maxV) * 140))
                                }
                            }
                        }.frame(maxWidth: .infinity)
                    }
                }.frame(height: 150)
                if goal > 0 {
                    Rectangle().fill(Palette.muted.opacity(0.5)).frame(height: 1).offset(y: -CGFloat(goal) / CGFloat(maxV) * 150 + 0)
                        .overlay(alignment: .trailing) { Text("goal").font(.system(size: 10)).foregroundStyle(Palette.muted).offset(y: -CGFloat(goal) / CGFloat(maxV) * 150 - 8) }
                }
            }
            HStack(spacing: 16) { ForEach(Review.days, id: \.self) { Text(String($0.prefix(3))).font(.system(size: 12)).foregroundStyle(Palette.muted).frame(maxWidth: .infinity) } }
            FlowLayout(spacing: 16) {
                ForEach(tags, id: \.self) { t in
                    HStack(spacing: 5) { Rectangle().fill(tagColor(t)).frame(width: 9, height: 9); Text(t.isEmpty ? "Untagged" : t).font(.system(size: 12)).foregroundStyle(Palette.muted2) }
                }
            }
        }
    }

    // ---------------------------------------------------------------- ask

    private var askCard: some View {
        card {
            HStack(spacing: 10) {
                TitleField(placeholder: "Ask about your week, like: when do I focus best?", text: $question, size: 14, center: false, onSubmit: ask)
                Button(action: ask) { Text("Ask") }
                    .buttonStyle(Chip(fill: Palette.accent, text: Palette.onAccent, size: 14, radius: 10, padding: EdgeInsets(top: 10, leading: 16, bottom: 10, trailing: 16)))
                    .disabled(asking)
            }
            if asking { ProgressView().controlSize(.small) }
            else if !answer.isEmpty {
                Text(answer).font(.system(size: 14)).foregroundStyle(Palette.ink).fixedSize(horizontal: false, vertical: true)
                Text(OnDevice.available ? "Answered on this Mac, from the week's numbers." : "Answered from the week's numbers. With Apple Intelligence on, the model on this Mac answers instead.").font(.system(size: 11)).foregroundStyle(Palette.muted)
            }
        }
    }

    // ---------------------------------------------------------------- the streak, the outcomes, what pulled away

    private func streakCard(_ f: WeekFacts) -> some View {
        let cal = Calendar.current
        var mon = cal; mon.firstWeekday = 2
        let focused = Set(blocks.map { cal.startOfDay(for: Date(timeIntervalSince1970: Double($0.start) / 1000)) })
        let paused = Set(f.streak.pausedDays)
        let today = cal.startOfDay(for: Date())
        let note = f.streak.pausedDays.first(where: { period.from <= Int($0.timeIntervalSince1970 * 1000) && Int($0.timeIntervalSince1970 * 1000) < period.to }).map { "\(Review.days[(cal.component(.weekday, from: $0) + 5) % 7]) used the week's pause, so the streak kept going." }
        return card {
            HStack {
                Text(f.streak.days == 1 ? "1 day in a row" : "\(f.streak.days) days in a row").font(.system(size: 13, weight: .bold)).foregroundStyle(Palette.ink)
                Spacer()
                Text(f.streak.pausesLeft > 0 ? "1 pause left this week" : "this week's pause is used").font(.system(size: 13)).foregroundStyle(Palette.muted)
            }
            HStack(spacing: 8) {
                ForEach(0..<7, id: \.self) { i in
                    let day = cal.date(byAdding: .day, value: i, to: period.start)!
                    let on = focused.contains(day), pz = paused.contains(day), future = day > today
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .fill(on ? Palette.accent : pz ? Color(hex: 0xFFCF8A) : Palette.ringTrack).opacity(future ? 0.5 : 1)
                        .frame(height: 28)
                        .overlay { if pz { Image(systemName: "pause.fill").font(.system(size: 11)).foregroundStyle(Palette.orangeInk) } }
                        .accessibilityLabel(on ? "Focused" : pz ? "The pause" : "No block")
                }
            }
            if let n = note { Text(n).font(.system(size: 12)).foregroundStyle(Palette.muted) }
        }
    }

    private func outcomes(_ f: WeekFacts) -> some View {
        card {
            Text("How the blocks went").font(.system(size: 13, weight: .bold)).foregroundStyle(Palette.ink)
            HStack(spacing: 8) {
                tile("\(f.done)", "Done", Palette.accent.opacity(0.16))
                tile("\(f.partly)", "Partly", Color(hex: 0xFFCF8A).opacity(0.25))
                tile("\(f.notReally)", "Not really", Color(hex: 0xF29BB0).opacity(0.22))
            }
        }
    }

    private func tile(_ n: String, _ label: String, _ fill: Color) -> some View {
        VStack(spacing: 2) {
            Text(n).font(clockFont(22, weight: .bold)).foregroundStyle(Palette.ink)
            Text(label).font(.system(size: 12)).foregroundStyle(Palette.muted2)
        }
        .padding(.vertical, 10).frame(maxWidth: .infinity)
        .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(fill))
    }

    private func pulled(_ f: WeekFacts) -> some View {
        card {
            Text("What pulled you away").font(.system(size: 13, weight: .bold)).foregroundStyle(Palette.ink)
            if f.reasons.isEmpty { Text("Nothing noted. When you answer Drifted, the cat asks what it was.").font(.system(size: 12)).foregroundStyle(Palette.muted).fixedSize(horizontal: false, vertical: true) }
            ForEach(Array(f.reasons.prefix(5).enumerated()), id: \.offset) { _, r in
                HStack { Text(Reason.label(r.id)).font(.system(size: 14)).foregroundStyle(Palette.ink); Spacer(); Text("\(r.times) time\(r.times == 1 ? "" : "s")").font(.system(size: 13)).foregroundStyle(Palette.muted) }
            }
        }
    }
}
