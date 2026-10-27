import AppKit

/// A test copy must not pop a crash report on the screen of whoever is working: it writes what happened to
/// its output and leaves quietly. An ordinary launch still crashes the ordinary way.
func installQuietExit() {
    NSSetUncaughtExceptionHandler { e in
        FileHandle.standardError.write(Data("UNCAUGHT \(e.name.rawValue): \(e.reason ?? "")\n\(e.callStackSymbols.joined(separator: "\n"))\n".utf8))
        _exit(70)
    }
    for s in [SIGSEGV, SIGABRT, SIGILL, SIGBUS, SIGTRAP] {
        signal(s) { n in
            FileHandle.standardError.write(Data("SIGNAL \(n)\n".utf8))
            _exit(70)
        }
    }
}
