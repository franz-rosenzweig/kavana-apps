import Foundation

/// The time now, in milliseconds since 1970 (what the engine counts in).
public func nowMs() -> Int { Int((Date().timeIntervalSince1970 * 1000).rounded()) }

/// A block's name where one has to be shown: its title, which is optional (like Forest's session title),
/// else its tag, else "Focus".
public func titleOf(_ intention: String, _ tag: String?) -> String {
    if !intention.isEmpty { return intention }
    if let t = tag, !t.isEmpty { return t }
    return "Focus"
}

/// "25 min", "1 h 5 min" (web/app.js fmtMin).
public func fmtMin(_ m: Int) -> String {
    if m < 60 { return "\(m) min" }
    return "\(m / 60) h" + (m % 60 != 0 ? " \(m % 60) min" : "")
}

/// "04:59", "1:02:03" (web/app.js fmtClock).
public func fmtClock(_ ms: Int) -> String {
    let s = max(0, (ms + 999) / 1000)
    let h = s / 3600, m = (s % 3600) / 60, sec = s % 60
    return h > 0 ? String(format: "%d:%02d:%02d", h, m, sec) : String(format: "%02d:%02d", m, sec)
}

/// Whole minutes left, never under one.
public func minLeft(_ ms: Int) -> Int { max(1, Int((Double(ms) / 60000).rounded())) }

/// "3:58 PM" in the user's own clock style.
public func clockTime(_ ms: Int) -> String {
    let f = DateFormatter()
    f.timeStyle = .short
    f.dateStyle = .none
    return f.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
}
