import AppKit
import SwiftUI

/// The Breaks settings (screen 16): the cycle, how firm the breaks are, eye breaks, and the ideas.
struct BreaksView: View {
    @ObservedObject var store: Store
    @State private var idea = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            VStack(alignment: .leading, spacing: 8) {
                Label11(text: "CYCLE")
                HStack(spacing: 8) {
                    ForEach(CyclePreset.allCases, id: \.self) { p in presetCard(p) }
                }
            }
            Section_(nil) {
                PickRow(title: "Long break after", options: [2, 3, 4, 5, 6].map { ($0, "\($0) blocks") }, value: $store.settings.cycle.longAfter)
                PickRow(title: "Long break is", options: [10, 15, 20, 25, 30].map { ($0, "\($0) min") }, value: $store.settings.cycle.longBreak)
                PickRow(title: "When a block is filed", detail: "Offered in the popover, or started as soon as the kitten is home.", options: [(BreakStart.offer, "Offer the break"), (.auto, "Start it myself")], value: $store.settings.breaks.start)
                Row(title: "Full-screen breaks", detail: "The screen goes calm, with \(store.cat(nil).name) stretching.") { Switch(on: $store.settings.breaks.fullScreen) }
                Row(title: "Strict breaks", detail: "No Postpone and no Skip.") { Switch(on: $store.settings.breaks.strict) }
                Row(title: "Eye breaks during a block", detail: "Every \(store.settings.breaks.eyeEveryMin) min, \(store.settings.breaks.eyeSeconds) seconds looking far away.") { Switch(on: $store.settings.breaks.eyeBreaks) }
                Row(title: "Wait during calls and full-screen video", detail: "Eye breaks and check-ins hold until you're done.") { Switch(on: $store.settings.breaks.waitDuringCalls) }
                PickRow(title: "When you're away from the Mac", detail: "More than \(store.settings.awayAfterMin) min in the middle of a block", last: true,
                        options: [(AwayAction.ask, "Ask if it counts"), (.count, "Always count it")] + (store.state.canTakeOff ? [(.takeOff, "Take it off")] : []),
                        value: $store.settings.awayAction)
            }
            Section_("Away after", note: "Being away is not drifting: the kitten stays. This only asks whether those minutes count.") {
                PickRow(title: "Ask after", last: true, options: [(2, "2 minutes"), (3, "3 minutes"), (5, "5 minutes"), (10, "10 minutes"), (15, "15 minutes")], value: $store.settings.awayAfterMin)
            }
            Section_("Eye breaks") {
                PickRow(title: "Every", options: [15, 20, 30, 45].map { ($0, "\($0) min") }, value: $store.settings.breaks.eyeEveryMin)
                PickRow(title: "For", options: [10, 20, 30].map { ($0, "\($0) seconds") }, value: $store.settings.breaks.eyeSeconds)
                PickRow(title: "Postpone a break by", last: true, options: [3, 5, 10].map { ($0, "\($0) min") }, value: $store.settings.breaks.postponeMin)
            }
            VStack(alignment: .leading, spacing: 8) {
                Label11(text: "BREAK IDEAS")
                FlowLayout {
                    ForEach(Array(store.settings.breaks.ideas.enumerated()), id: \.offset) { i, text in
                        HStack(spacing: 8) {
                            Text(text).font(.system(size: 13)).foregroundStyle(Palette.ink)
                            Button { store.settings.breaks.ideas.remove(at: i) } label: { Image(systemName: "xmark").font(.system(size: 9, weight: .bold)) }
                                .buttonStyle(.plain).foregroundStyle(Palette.muted).accessibilityLabel("Remove \(text)")
                        }
                        .padding(.horizontal, 12).padding(.vertical, 7).background(Capsule().fill(Palette.soft))
                    }
                }
                HStack {
                    TitleField(placeholder: "+ Add your own", text: $idea, size: 13, center: false, onSubmit: addIdea).frame(width: 260)
                    Button("Add", action: addIdea).buttonStyle(Chip(fill: Palette.accent, text: Palette.onAccent, size: 13, radius: 9, padding: EdgeInsets(top: 8, leading: 14, bottom: 8, trailing: 14)))
                    Spacer()
                    Button("Back to the usual ones") { store.settings.breaks.ideas = BreakSettings.defaultIdeas }.buttonStyle(.plain).font(.system(size: 12)).foregroundStyle(Palette.accentText)
                }
            }
        }
    }

    private func presetCard(_ p: CyclePreset) -> some View {
        let on = store.preset == p
        return Button { store.apply(p) } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text(p.title).font(.system(size: 14, weight: .bold)).foregroundStyle(Palette.ink)
                Text(p.summary).font(.system(size: 12)).foregroundStyle(Palette.muted2)
            }
            .padding(12).frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(on ? Palette.accent.opacity(0.10) : Palette.card))
            .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(on ? Palette.accent : Palette.ringTrack, lineWidth: on ? 2 : 1))
            .contentShape(Rectangle())
        }.buttonStyle(.plain)
    }

    private func addIdea() {
        let t = idea.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty, !store.settings.breaks.ideas.contains(t) else { idea = ""; return }
        store.settings.breaks.ideas.append(t)
        idea = ""
    }
}
