import AppKit
import Carbon.HIToolbox

/// Global shortcuts, with Carbon's RegisterEventHotKey: they work in every app and need no permission.
final class Hotkeys {
    private struct Entry { let ref: EventHotKeyRef; let combo: KeyCombo; let action: () -> Void }
    private var entries: [UInt32: Entry] = [:]
    private var names: [String: UInt32] = [:]
    private var next: UInt32 = 1
    private var handler: EventHandlerRef?
    static var shared: Hotkeys?

    init() {
        Hotkeys.shared = self
        var spec = EventTypeSpec(eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyPressed))
        InstallEventHandler(GetApplicationEventTarget(), { _, event, _ in
            var id = EventHotKeyID()
            GetEventParameter(event, EventParamName(kEventParamDirectObject), EventParamType(typeEventHotKeyID), nil, MemoryLayout<EventHotKeyID>.size, nil, &id)
            DispatchQueue.main.async { Hotkeys.shared?.entries[id.id]?.action() }
            return noErr
        }, 1, &spec, nil, &handler)
    }

    /// Registers (or moves) the shortcut called `name`. False if the system won't give it (someone else has it).
    @discardableResult
    func set(_ name: String, _ combo: KeyCombo, action: @escaping () -> Void) -> Bool {
        clear(name)
        guard combo.isValid else { return false }
        var ref: EventHotKeyRef?
        let id = next; next += 1
        let status = RegisterEventHotKey(UInt32(combo.code), UInt32(combo.mods), EventHotKeyID(signature: OSType(0x4348_4154), id: id), GetApplicationEventTarget(), 0, &ref)
        guard status == noErr, let ref else { return false }
        entries[id] = Entry(ref: ref, combo: combo, action: action)
        names[name] = id
        return true
    }

    func clear(_ name: String) {
        guard let id = names.removeValue(forKey: name), let e = entries.removeValue(forKey: id) else { return }
        UnregisterEventHotKey(e.ref)
    }

    func isSet(_ name: String) -> Bool { names[name] != nil }
}

/// Turns a key press into a shortcut, for the Shortcuts settings.
func keyCombo(from event: NSEvent) -> KeyCombo? {
    var mods = 0
    let f = event.modifierFlags
    if f.contains(.control) { mods |= KeyCombo.control }
    if f.contains(.option) { mods |= KeyCombo.option }
    if f.contains(.shift) { mods |= KeyCombo.shift }
    if f.contains(.command) { mods |= KeyCombo.command }
    let c = KeyCombo(code: Int(event.keyCode), mods: mods)
    return c.isValid ? c : nil
}
