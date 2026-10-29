using System.Text.Json;
using System.Text.Json.Serialization;

namespace Chataltul.Core;

/// <summary>A shortcut that works in every app: a virtual-key code and RegisterHotKey's modifiers.</summary>
public sealed record KeyCombo
{
    public const int Alt = 0x1, Control = 0x2, Shift = 0x4, Win = 0x8;

    public int Vk { get; set; }
    public int Mods { get; set; }

    public KeyCombo() { }
    public KeyCombo(int vk, int mods) { Vk = vk; Mods = mods; }

    /// <summary>Shift alone isn't enough: it would take a capital letter from every app.</summary>
    [JsonIgnore] public bool IsValid => (Mods & (Control | Alt | Win)) != 0 && Vk != 0 && !IsModifierKey(Vk);

    /// <summary>"Ctrl+Alt+Space", "Ctrl+Alt+P".</summary>
    [JsonIgnore] public string Label
    {
        get
        {
            var parts = new List<string>();
            if ((Mods & Control) != 0) parts.Add("Ctrl");
            if ((Mods & Alt) != 0) parts.Add("Alt");
            if ((Mods & Shift) != 0) parts.Add("Shift");
            if ((Mods & Win) != 0) parts.Add("Win");
            parts.Add(Name(Vk));
            return string.Join("+", parts);
        }
    }

    public static bool IsModifierKey(int vk) => vk is 0x10 or 0x11 or 0x12 or 0x5B or 0x5C or (>= 0xA0 and <= 0xA5);

    public static string Name(int vk) => vk switch
    {
        >= 0x41 and <= 0x5A => ((char)vk).ToString(),
        >= 0x30 and <= 0x39 => ((char)vk).ToString(),
        >= 0x70 and <= 0x87 => $"F{vk - 0x6F}",
        >= 0x60 and <= 0x69 => $"Num {vk - 0x60}",
        0x20 => "Space", 0x0D => "Enter", 0x09 => "Tab", 0x1B => "Esc", 0x08 => "Backspace", 0x2E => "Delete", 0x2D => "Insert",
        0x24 => "Home", 0x23 => "End", 0x21 => "Page Up", 0x22 => "Page Down",
        0x25 => "←", 0x26 => "↑", 0x27 => "→", 0x28 => "↓",
        0xBA => ";", 0xBB => "=", 0xBC => ",", 0xBD => "-", 0xBE => ".", 0xBF => "/", 0xC0 => "`",
        0xDB => "[", 0xDC => "\\", 0xDD => "]", 0xDE => "'",
        _ => $"Key {vk}",
    };

    const int CA = Control | Alt;
    public static KeyCombo StartDefault => new(0x20, CA);    // Ctrl+Alt+Space
    public static KeyCombo ParkDefault => new(0x50, CA);     // Ctrl+Alt+P
    public static KeyCombo YesDefault => new(0x59, CA);      // Ctrl+Alt+Y
    public static KeyCombo DriftedDefault => new(0x44, CA);  // Ctrl+Alt+D
}

public enum AwayAction { Ask, Count, TakeOff }

/// <summary>A widget on the desktop: which one, its size, and where its top left is (in pixels, on the virtual screen).</summary>
public sealed class WidgetPlace
{
    public string Kind { get; set; } = "window";
    public string Size { get; set; } = "medium";
    public int X { get; set; }
    public int Y { get; set; }
    public string Id { get; set; } = Guid.NewGuid().ToString("N")[..8];
}

/// <summary>The plain Pomodoro cycle the popover steps through. It lives on this PC only (the engine has no cycles), so it isn't synced.</summary>
public sealed class Cycle
{
    public int Focus { get; set; } = 25;
    public int ShortBreak { get; set; } = 5;
    public int LongBreak { get; set; } = 15;
    /// <summary>A long break after this many blocks in a day.</summary>
    public int LongAfter { get; set; } = 4;
    /// <summary>The dashes in the popover: how many blocks make a day.</summary>
    public int PerDay { get; set; } = 6;
    /// <summary>Pomodoro, 52 / 17, Deep work or Flowtime (a stopwatch, with a break a fifth as long).</summary>
    public CyclePreset Preset { get; set; } = CyclePreset.Pomodoro;

    /// <summary>Is the break after this many blocks today the long one?</summary>
    public bool IsLong(int afterBlocks) => LongAfter > 0 && afterBlocks > 0 && afterBlocks % LongAfter == 0;
}

/// <summary>Everything the Windows app keeps for itself, apart from the doc: windows.json in the app's folder (the Mac's mac.json).</summary>
public sealed class WinSettings
{
    public Blocklist Blocklist { get; set; } = Blocklist.Starter();
    /// <summary>Lists from the web the person added by their link, next to the ready-made ones.</summary>
    public List<ListCategory> CustomLists { get; set; } = [];
    /// <summary>How breaks start, how firm they are, the eye breaks, the ideas.</summary>
    public BreakSettings Breaks { get; set; } = new();
    /// <summary>The sounds to focus to, at most two at once, each with its volume.</summary>
    public List<MixEntry> Mix { get; set; } = [];
    /// <summary>The speed bump on or off. On, but with an empty list, it never comes up.</summary>
    public bool SpeedBump { get; set; } = true;
    /// <summary>Read the address of the front tab in a browser, to find the sites on the list.</summary>
    public bool ReadBrowsers { get; set; } = true;
    /// <summary>The cat has said once, plainly, that it reads which site is open.</summary>
    public bool BrowserNoticeShown { get; set; }
    public int AwayAfterMin { get; set; } = 2;
    public AwayAction AwayAction { get; set; } = AwayAction.Ask;
    public Cycle Cycle { get; set; } = new();
    public KeyCombo StartKey { get; set; } = KeyCombo.StartDefault;
    public KeyCombo ParkKey { get; set; } = KeyCombo.ParkDefault;
    public KeyCombo YesKey { get; set; } = KeyCombo.YesDefault;
    public KeyCombo DriftedKey { get; set; } = KeyCombo.DriftedDefault;
    /// <summary>The cat peeks up in the corner of the screen; off, check-ins come as notifications only.</summary>
    public bool CatInCorner { get; set; } = true;
    /// <summary>Which minutes the popover's Focus tab starts, remembered between launches.</summary>
    public int FocusMinutes { get; set; } = 25;
    /// <summary>Seen the first-run welcome.</summary>
    public bool Welcomed { get; set; }
    /// <summary>How big the text is in Settings and the app window (Ctrl+Plus, Ctrl+Minus, Ctrl+0, Ctrl+wheel).</summary>
    public float TextZoom { get; set; } = 1;
    /// <summary>The web app's server, for the account and its sync (the self-test points it at a sandbox on the runner).</summary>
    public string Server { get; set; } = WebApp;
    /// <summary>The widgets on the desktop, each with its place.</summary>
    public List<WidgetPlace> Widgets { get; set; } = [];
    /// <summary>"Hide block titles": the widgets show the time, never what a block is called.</summary>
    public bool HideTitles { get; set; }

    public const string WebApp = "https://stuffs.tiresia.xyz/kavana/";

    public WinSettings Copy() => JsonSerializer.Deserialize<WinSettings>(JsonSerializer.Serialize(this, J.Files), J.Files)!;

    public string ToJson() => JsonSerializer.Serialize(this, J.Files);
}

/// <summary>windows.json, read and written whole. One that doesn't read is kept aside, like the doc.</summary>
public sealed class SettingsStore
{
    public string File { get; }

    public SettingsStore(string folder)
    {
        Directory.CreateDirectory(folder);
        File = Path.Join(folder, "windows.json");
    }

    public WinSettings Read()
    {
        string text;
        try { text = System.IO.File.ReadAllText(File); }
        catch (Exception) { return new WinSettings(); }
        try { return JsonSerializer.Deserialize<WinSettings>(text, J.Files) ?? new WinSettings(); }
        catch (Exception)
        {
            try { System.IO.File.Copy(File, $"{File}.bad-{Format.NowMs()}"); } catch (Exception) { }
            return new WinSettings();
        }
    }

    public void Write(WinSettings s) => Files.WriteWhole(File, s.ToJson());
}
