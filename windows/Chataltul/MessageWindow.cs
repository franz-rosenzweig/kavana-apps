using System.Runtime.InteropServices;

namespace Chataltul;

/// <summary>
/// A window nobody sees, for what Windows tells a tray app: clicks on its icon, its shortcuts, the screen
/// locking and the PC sleeping, the theme changing, Explorer starting again, and a second start of the app.
/// </summary>
sealed class MessageWindow
{
    public static string ClassName => App.Options.SelfTest ? "Chataltul.Messages.SelfTest" : "Chataltul.Messages";
    public const uint WM_TRAY = Native.WM_APP + 1, WM_WAKE = Native.WM_APP + 2;
    static readonly uint TaskbarCreated = Native.RegisterWindowMessage("TaskbarCreated");
    static Guid ConsoleDisplayState = new("6FE69556-704A-47A0-8F24-C28D936FDA47");

    readonly Native.WndProc proc;   // kept, or the collector takes what Windows calls
    public IntPtr Hwnd { get; }

    /// <summary>The tray icon: what happened (NIN_SELECT, WM_CONTEXTMENU), and where.</summary>
    public event Action<int, int, int>? Tray;
    public event Action<int>? Hotkey;
    /// <summary>True: the screen locked, the PC is going to sleep or the display went off. False: it's back.</summary>
    public event Action<bool>? Offline;
    public event Action? TaskbarRestarted;
    public event Action? Wake;
    public event Action? ThemeChanged;
    /// <summary>Windows is signing out or shutting down, or an installer asks the app to close.</summary>
    public event Action? Ending;

    public MessageWindow()
    {
        proc = Proc;
        var c = new Native.WNDCLASSEX
        {
            cbSize = Marshal.SizeOf<Native.WNDCLASSEX>(),
            lpfnWndProc = Marshal.GetFunctionPointerForDelegate(proc),
            hInstance = Native.GetModuleHandle(null),
            lpszClassName = ClassName,
        };
        Native.RegisterClassEx(ref c);
        Hwnd = Native.CreateWindowEx(0, ClassName, "Chataltul", 0, 0, 0, 0, 0, IntPtr.Zero, IntPtr.Zero, c.hInstance, IntPtr.Zero);
        if (Hwnd == IntPtr.Zero) throw new InvalidOperationException($"no message window ({Marshal.GetLastPInvokeError()})");
        Native.WTSRegisterSessionNotification(Hwnd, Native.NOTIFY_FOR_THIS_SESSION);
        Native.RegisterPowerSettingNotification(Hwnd, ref ConsoleDisplayState, 0);
    }

    /// <summary>
    /// A second start: the first one opens its popover, or does the jump list's task (<c>command</c>), with the right
    /// to come to the front.
    /// </summary>
    public static void WakeTheOther(string? command = null)
    {
        var h = Native.FindWindow(ClassName, null);
        if (h == IntPtr.Zero) return;
        Native.GetWindowThreadProcessId(h, out var pid);
        Native.AllowSetForegroundWindow(pid);
        if (command == null) { Native.PostMessage(h, WM_WAKE, IntPtr.Zero, IntPtr.Zero); return; }
        var text = Marshal.StringToHGlobalUni(command);
        try
        {
            var data = new Native.COPYDATASTRUCT { dwData = new IntPtr(1), cbData = (command.Length + 1) * 2, lpData = text };
            Native.SendMessage(h, Native.WM_COPYDATA, IntPtr.Zero, ref data);
        }
        finally { Marshal.FreeHGlobal(text); }
    }

    /// <summary>A task from the jump list, handed over by a second start.</summary>
    public event Action<string>? Command;

    IntPtr Proc(IntPtr h, uint msg, IntPtr w, IntPtr l)
    {
        try
        {
            switch (msg)
            {
                case WM_TRAY:
                    {
                        var ev = (int)(l.ToInt64() & 0xFFFF);
                        var x = (short)(w.ToInt64() & 0xFFFF);
                        var y = (short)(w.ToInt64() >> 16 & 0xFFFF);
                        Tray?.Invoke(ev, x, y);
                        return IntPtr.Zero;
                    }
                case Native.WM_HOTKEY:
                    Hotkey?.Invoke((int)w.ToInt64());
                    return IntPtr.Zero;
                case Native.WM_WTSSESSION_CHANGE:
                    if (w.ToInt64() == Native.WTS_SESSION_LOCK) Offline?.Invoke(true);
                    else if (w.ToInt64() == Native.WTS_SESSION_UNLOCK) Offline?.Invoke(false);
                    break;
                case Native.WM_POWERBROADCAST:
                    switch ((int)w.ToInt64())
                    {
                        case Native.PBT_APMSUSPEND: Offline?.Invoke(true); break;
                        case Native.PBT_APMRESUMEAUTOMATIC or Native.PBT_APMRESUMESUSPEND: Offline?.Invoke(false); break;
                        case Native.PBT_POWERSETTINGCHANGE when l != IntPtr.Zero:
                            // POWERBROADCAST_SETTING: the setting's GUID, the data's length, then the display's state (0 off, 1 on, 2 dimmed)
                            var setting = Marshal.PtrToStructure<Guid>(l);
                            if (setting == ConsoleDisplayState)
                            {
                                var state = Marshal.ReadInt32(l, 20);
                                if (state == 0) Offline?.Invoke(true);
                                else if (state == 1) Offline?.Invoke(false);
                            }
                            break;
                    }
                    return new IntPtr(1);
                case Native.WM_SETTINGCHANGE:
                    if (l != IntPtr.Zero && Marshal.PtrToStringUni(l) == "ImmersiveColorSet") ThemeChanged?.Invoke();
                    break;
                case WM_WAKE:
                    Wake?.Invoke();
                    return IntPtr.Zero;
                case Native.WM_COPYDATA when l != IntPtr.Zero:
                    {
                        var data = Marshal.PtrToStructure<Native.COPYDATASTRUCT>(l);
                        if (data.dwData == new IntPtr(1) && data.lpData != IntPtr.Zero && data.cbData is > 0 and < 512)
                        {
                            var command = Marshal.PtrToStringUni(data.lpData, data.cbData / 2 - 1);
                            // the sender waits for this to return: the task runs after, on the UI thread's own time
                            Ui.Post(() => Command?.Invoke(command));
                        }
                        return new IntPtr(1);
                    }
                case 0x0011:   // WM_QUERYENDSESSION: yes, it may end
                    return new IntPtr(1);
                case 0x0016:   // WM_ENDSESSION
                    if (w != IntPtr.Zero) Ending?.Invoke();
                    return IntPtr.Zero;
                case 0x0010:   // WM_CLOSE, from an installer's Restart Manager
                    Ending?.Invoke();
                    return IntPtr.Zero;
                default:
                    if (msg == TaskbarCreated) TaskbarRestarted?.Invoke();
                    break;
            }
        }
        catch (Exception e) { Log.Write($"message {msg:x}: {e}"); }
        return Native.DefWindowProc(h, msg, w, l);
    }
}

/// <summary>The kitten in the notification area (the Mac's menu bar item).</summary>
sealed class Tray
{
    const int Id = 1;
    readonly MessageWindow window;
    IntPtr icon;
    bool added;
    string key = "", tip = "";
    Core.TrayState state = new Core.TrayState.Ready();
    bool dark = true;

    public event Action<int, int>? Click;
    public event Action<int, int>? Menu;
    public bool Added => added;
    /// <summary>The last picture drawn, for the self-test.</summary>
    public byte[]? Pixels { get; private set; }
    public int Size { get; private set; }

    public Tray(MessageWindow w)
    {
        window = w;
        w.Tray += (ev, x, y) =>
        {
            if (ev is Native.NIN_SELECT or Native.NIN_KEYSELECT) Click?.Invoke(x, y);
            else if (ev == Native.WM_CONTEXTMENU) Menu?.Invoke(x, y);
        };
        w.TaskbarRestarted += () => { added = false; Add(); };
    }

    Native.NOTIFYICONDATA Data(int flags) => new()
    {
        cbSize = Marshal.SizeOf<Native.NOTIFYICONDATA>(), hWnd = window.Hwnd, uID = Id, uFlags = flags,
        uCallbackMessage = (int)MessageWindow.WM_TRAY, hIcon = icon, szTip = tip, szInfo = "", szInfoTitle = "",
    };

    /// <summary>Puts the icon in the notification area. False if there is no taskbar to put it in.</summary>
    public bool Add()
    {
        if (added) return true;
        Draw(force: true);
        var d = Data(Native.NIF_MESSAGE | Native.NIF_ICON | Native.NIF_TIP | Native.NIF_SHOWTIP);
        if (!Native.Shell_NotifyIcon(Native.NIM_ADD, ref d)) return false;
        d.uVersion = Native.NOTIFYICON_VERSION_4;
        Native.Shell_NotifyIcon(Native.NIM_SETVERSION, ref d);
        added = true;
        return true;
    }

    public void Remove()
    {
        if (!added) return;
        var d = Data(0);
        Native.Shell_NotifyIcon(Native.NIM_DELETE, ref d);
        added = false;
    }

    /// <summary>Called once a second and on every change: the picture is only made again when what it shows has moved.</summary>
    public void Show(Core.TrayState s, string newTip, bool darkTaskbar)
    {
        state = s;
        dark = darkTaskbar;
        var t = newTip.Length > 127 ? newTip[..127] : newTip;
        var drew = Draw(force: false);
        if (!drew && t == tip) return;
        tip = t;
        if (!added) return;
        var d = Data(Native.NIF_ICON | Native.NIF_TIP | Native.NIF_SHOWTIP);
        Native.Shell_NotifyIcon(Native.NIM_MODIFY, ref d);
    }

    bool Draw(bool force)
    {
        var size = Native.GetSystemMetricsForDpi(Native.SM_CXSMICON, Native.GetDpiForSystem());
        if (size <= 0) size = 16;
        var k = $"{state.Key}:{dark}:{size}";
        if (!force && k == key) return false;
        key = k;
        Size = size;
        Pixels = Core.TrayArt.Draw(state, size, dark);
        var old = icon;
        icon = Native.Icon(Pixels, size);
        if (old != IntPtr.Zero) Native.DestroyIcon(old);
        return true;
    }

    /// <summary>Where the icon is on the screen, if Windows says (not while it waits in the hidden icons).</summary>
    public Native.RECT? Rect()
    {
        var id = new Native.NOTIFYICONIDENTIFIER { cbSize = Marshal.SizeOf<Native.NOTIFYICONIDENTIFIER>(), hWnd = window.Hwnd, uID = Id };
        return Native.Shell_NotifyIconGetRect(ref id, out var r) == 0 && r.Width > 0 ? r : null;
    }

    /// <summary>The menu on a right-click. Returns the chosen item's number, or 0.</summary>
    public int PopUp(int x, int y, params (int Id, string? Text)[] items)
    {
        var m = Native.CreatePopupMenu();
        try
        {
            foreach (var (itemId, text) in items)
                Native.AppendMenu(m, text == null ? Native.MF_SEPARATOR : Native.MF_STRING, new IntPtr(itemId), text);
            // the menu goes away when the person clicks elsewhere only if its window is in front
            Native.SetForegroundWindow(window.Hwnd);
            var chosen = Native.TrackPopupMenuEx(m, Native.TPM_RETURNCMD | Native.TPM_RIGHTBUTTON | Native.TPM_BOTTOMALIGN, x, y, window.Hwnd, IntPtr.Zero);
            Native.PostMessage(window.Hwnd, Native.WM_NULL, IntPtr.Zero, IntPtr.Zero);
            return chosen;
        }
        finally { Native.DestroyMenu(m); }
    }
}

/// <summary>Shortcuts that work in every app (RegisterHotKey), each by a name.</summary>
sealed class Hotkeys
{
    readonly MessageWindow window;
    readonly Dictionary<string, (int Id, Core.KeyCombo Combo, Action Action)> byName = [];
    int next = 1;

    public Hotkeys(MessageWindow w)
    {
        window = w;
        w.Hotkey += id =>
        {
            Action? a = null;
            foreach (var e in byName.Values) if (e.Id == id) a = e.Action;
            a?.Invoke();
        };
    }

    /// <summary>Registers (or moves) the shortcut called <c>name</c>. False if Windows won't give it (another app has it).</summary>
    public bool Set(string name, Core.KeyCombo combo, Action action)
    {
        if (byName.TryGetValue(name, out var have) && have.Combo == combo) { byName[name] = (have.Id, combo, action); return true; }
        Clear(name);
        if (!combo.IsValid) return false;
        var id = next++;
        if (!Native.RegisterHotKey(window.Hwnd, id, (uint)combo.Mods | Native.MOD_NOREPEAT, (uint)combo.Vk))
        {
            Log.Write($"the shortcut {combo.Label} for {name} is taken");
            return false;
        }
        byName[name] = (id, combo, action);
        return true;
    }

    public void Clear(string name)
    {
        if (byName.Remove(name, out var e)) Native.UnregisterHotKey(window.Hwnd, e.Id);
    }

    public void ClearAll()
    {
        foreach (var n in byName.Keys.ToList()) Clear(n);
    }

    public bool IsSet(string name) => byName.ContainsKey(name);
}
