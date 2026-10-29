using Microsoft.Win32;

namespace Chataltul;

/// <summary>
/// Is the PC busy with a call or something full screen (a film, a game, a presentation)? Then eye breaks and
/// check-ins wait. Windows says when an app is full screen or presenting; a call is a microphone in use, which
/// Windows keeps a note of for its own privacy icon. The Mac's Busy.swift.
/// </summary>
static class Busy
{
    /// <summary>The self-test says what it is.</summary>
    public static Func<bool>? Override { get; set; }

    public static bool Now() => Override?.Invoke() ?? (FullScreen() || MicrophoneInUse());

    public static bool FullScreen()
    {
        try
        {
            return Native.SHQueryUserNotificationState(out var s) == 0
                && s is Native.QUNS_BUSY or Native.QUNS_RUNNING_D3D_FULL_SCREEN or Native.QUNS_PRESENTATION_MODE;
        }
        catch (Exception) { return false; }
    }

    const string Mic = @"Software\Microsoft\Windows\CurrentVersion\CapabilityAccessManager\ConsentStore\microphone";

    /// <summary>An app has the microphone now: its last use has a start and no end yet.</summary>
    public static bool MicrophoneInUse()
    {
        try
        {
            using var root = Registry.CurrentUser.OpenSubKey(Mic);
            if (root == null) return false;
            if (InUse(root)) return true;
            using var unpackaged = root.OpenSubKey("NonPackaged");
            return unpackaged != null && InUse(unpackaged);
        }
        catch (Exception) { return false; }
    }

    static bool InUse(RegistryKey parent)
    {
        foreach (var name in parent.GetSubKeyNames())
        {
            if (name == "NonPackaged") continue;
            using var app = parent.OpenSubKey(name);
            if (app?.GetValue("LastUsedTimeStart") is long start && start > 0 && app.GetValue("LastUsedTimeStop") is long stop && stop == 0) return true;
        }
        return false;
    }
}
