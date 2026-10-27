import AppKit

private let delegate = AppDelegate()

@main
enum Main {
    static func main() {
        let app = NSApplication.shared
        app.delegate = delegate
        app.run()
    }
}
