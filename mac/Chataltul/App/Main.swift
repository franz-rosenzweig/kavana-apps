import AppKit

private let delegate = AppDelegate()

@main
enum Main {
    static func main() {
        let env = ProcessInfo.processInfo.environment
        if env["CHATALTUL_QUIET_EXIT"] != nil { installQuietExit() }
        let app = NSApplication.shared
        app.delegate = delegate
        // a test launch: start, show nothing, say so and leave
        if let s = env["CHATALTUL_QUIT_AFTER"], let secs = Double(s) {
            DispatchQueue.main.asyncAfter(deadline: .now() + secs) { print("LAUNCH ok"); fflush(stdout); exit(0) }
        }
        app.run()
    }
}
