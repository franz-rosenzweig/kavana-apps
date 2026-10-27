import AppKit
import CoreAudio
import CoreMediaIO

/// Is the Mac busy with something that shouldn't be interrupted: a call (the microphone or the camera is in
/// use), or full-screen video (the front app has a window as big as the screen)? Eye breaks and check-ins wait
/// until it isn't, when "Wait during calls and full-screen video" is on. None of this needs a permission:
/// it asks whether a device is running, never what it hears or sees.
enum Busy {
    /// Tests say what the Mac is doing.
    static var override: (() -> Bool)?

    static func now() -> Bool {
        if let o = override { return o() }
        return micInUse() || cameraInUse() || fullScreenFront()
    }

    static func micInUse() -> Bool {
        var addr = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyDevices, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        var size: UInt32 = 0
        guard AudioObjectGetPropertyDataSize(AudioObjectID(kAudioObjectSystemObject), &addr, 0, nil, &size) == noErr, size > 0 else { return false }
        var ids = [AudioDeviceID](repeating: 0, count: Int(size) / MemoryLayout<AudioDeviceID>.size)
        guard AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &addr, 0, nil, &size, &ids) == noErr else { return false }
        for id in ids {
            var streams = AudioObjectPropertyAddress(mSelector: kAudioDevicePropertyStreams, mScope: kAudioObjectPropertyScopeInput, mElement: kAudioObjectPropertyElementMain)
            var s: UInt32 = 0
            guard AudioObjectGetPropertyDataSize(id, &streams, 0, nil, &s) == noErr, s > 0 else { continue }   // not an input device
            var running: UInt32 = 0
            var rs = UInt32(MemoryLayout<UInt32>.size)
            var ra = AudioObjectPropertyAddress(mSelector: kAudioDevicePropertyDeviceIsRunningSomewhere, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
            if AudioObjectGetPropertyData(id, &ra, 0, nil, &rs, &running) == noErr, running != 0 { return true }
        }
        return false
    }

    static func cameraInUse() -> Bool {
        var addr = CMIOObjectPropertyAddress(mSelector: CMIOObjectPropertySelector(kCMIOHardwarePropertyDevices), mScope: CMIOObjectPropertyScope(kCMIOObjectPropertyScopeGlobal), mElement: CMIOObjectPropertyElement(kCMIOObjectPropertyElementMain))
        var size: UInt32 = 0
        guard CMIOObjectGetPropertyDataSize(CMIOObjectID(kCMIOObjectSystemObject), &addr, 0, nil, &size) == noErr, size > 0 else { return false }
        var count: UInt32 = 0
        var ids = [CMIOObjectID](repeating: 0, count: Int(size) / MemoryLayout<CMIOObjectID>.size)
        guard CMIOObjectGetPropertyData(CMIOObjectID(kCMIOObjectSystemObject), &addr, 0, nil, size, &count, &ids) == noErr else { return false }
        for id in ids {
            var running: UInt32 = 0
            var used: UInt32 = 0
            var ra = CMIOObjectPropertyAddress(mSelector: CMIOObjectPropertySelector(kCMIODevicePropertyDeviceIsRunningSomewhere), mScope: CMIOObjectPropertyScope(kCMIOObjectPropertyScopeGlobal), mElement: CMIOObjectPropertyElement(kCMIOObjectPropertyElementMain))
            if CMIOObjectGetPropertyData(id, &ra, 0, nil, UInt32(MemoryLayout<UInt32>.size), &used, &running) == noErr, running != 0 { return true }
        }
        return false
    }

    /// The front app has a window the size of a screen (native full screen, which is what video in a browser or
    /// a player becomes). The sizes and owners of windows are not secret: no Screen Recording permission.
    static func fullScreenFront() -> Bool {
        guard let app = NSWorkspace.shared.frontmostApplication, app.bundleIdentifier != Bundle.main.bundleIdentifier,
              let info = CGWindowListCopyWindowInfo([.optionOnScreenOnly, .excludeDesktopElements], kCGNullWindowID) as? [[String: Any]] else { return false }
        let sizes = NSScreen.screens.map { $0.frame.size }
        for w in info {
            guard (w[kCGWindowOwnerPID as String] as? Int32) == app.processIdentifier, (w[kCGWindowLayer as String] as? Int) == 0,
                  let b = w[kCGWindowBounds as String] as? [String: CGFloat], let W = b["Width"], let H = b["Height"] else { continue }
            if sizes.contains(where: { abs($0.width - W) < 2 && abs($0.height - H) < 2 }) { return true }
        }
        return false
    }
}
