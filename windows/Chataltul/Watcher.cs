using System.Collections.Concurrent;
using System.Diagnostics;
using System.Runtime.InteropServices;
using Chataltul.Core;
using UIA = Interop.UIAutomationClient;

namespace Chataltul;

/// <summary>The window in front: its program, by file and name. A Store app is found inside its frame.</summary>
sealed record FrontApp(IntPtr Hwnd, int Pid, string Exe, string Name)
{
    static readonly Dictionary<string, string> Names = new(StringComparer.OrdinalIgnoreCase);

    public static FrontApp? Of(IntPtr hwnd)
    {
        if (hwnd == IntPtr.Zero) return null;
        var (pid, path) = Native.ProcessOf(hwnd);
        if (path == null) return null;
        if (Path.GetFileName(path).Equals("ApplicationFrameHost.exe", StringComparison.OrdinalIgnoreCase))
        {
            // a Store app: the frame belongs to Windows, the app is the child window from another process
            (int, string?) inner = (0, null);
            Native.EnumChildWindows(hwnd, (child, _) =>
            {
                var (p, file) = Native.ProcessOf(child);
                if (p != pid && file != null) { inner = (p, file); return false; }
                return true;
            }, IntPtr.Zero);
            if (inner.Item2 != null) (pid, path) = inner;
        }
        return new FrontApp(hwnd, pid, ListedApp.NormalizeExe(path), NameOf(path));
    }

    /// <summary>"Discord", from the program's own description, else its file name.</summary>
    public static string NameOf(string path)
    {
        lock (Names)
        {
            if (Names.TryGetValue(path, out var n)) return n;
            string? name = null;
            try
            {
                var v = FileVersionInfo.GetVersionInfo(path);
                name = new[] { v.FileDescription, v.ProductName }.FirstOrDefault(s => !string.IsNullOrWhiteSpace(s))?.Trim();
            }
            catch (Exception) { }
            if (string.IsNullOrEmpty(name) || name.Length > 40) name = Path.GetFileNameWithoutExtension(path);
            Names[path] = name;
            return name;
        }
    }
}

/// <summary>
/// Which app is in front, and for a browser, which site. The front window comes from
/// SetWinEventHook(EVENT_SYSTEM_FOREGROUND), and is looked at again each second while a block runs; a
/// browser's address from UI Automation, read on a thread of its own. Only the site's name is kept. The Mac's
/// Watcher.swift (which needs AppleScript and a permission for each browser).
/// </summary>
sealed class Watcher : IDisposable
{
    readonly Store store;
    readonly Native.WinEventProc proc;
    readonly IntPtr hook;
    readonly BrowserReader reader = new();
    readonly int ownPid = Environment.ProcessId;
    readonly Dictionary<IntPtr, (string Url, long At)> lastUrl = [];

    /// <summary>A look at the front: the listed thing it matches (or null), and the app in front.</summary>
    public event Action<GuardHit?, FrontApp>? Front;
    /// <summary>A browser was read for the first time: the cat says so, once.</summary>
    public event Action<Browser>? FirstBrowser;

    /// <summary>The self-test never reads the real front window or any browser of whoever runs it.</summary>
    public bool Disabled { get; set; }
    /// <summary>Apps seen in front, for "Add an app" in Settings.</summary>
    public readonly Dictionary<string, string> Seen = [];
    public FrontApp? Current { get; private set; }

    public Watcher(Store store)
    {
        this.store = store;
        proc = (_, _, _, _, _, _, _) => Look();
        hook = Native.SetWinEventHook(Native.EVENT_SYSTEM_FOREGROUND, Native.EVENT_SYSTEM_FOREGROUND, IntPtr.Zero, proc, 0, 0,
            Native.WINEVENT_OUTOFCONTEXT | Native.WINEVENT_SKIPOWNPROCESS);
        reader.Read += OnRead;
    }

    public void Dispose()
    {
        if (hook != IntPtr.Zero) Native.UnhookWinEvent(hook);
        reader.Dispose();
    }

    /// <summary>Looks at what is in front now.</summary>
    public void Look()
    {
        if (Disabled) return;
        if (FrontApp.Of(Native.GetForegroundWindow()) is not { } app) return;
        // Chataltul's own windows in front (its popover, the cat, Settings) change nothing
        if (app.Pid == ownPid) return;
        Current = app;
        Seen[app.Exe] = app.Name;
        Judge(app);
    }

    /// <summary>What the list says about this app in front (the self-test calls this with apps it makes up).</summary>
    public void Judge(FrontApp app)
    {
        var list = store.Settings.Blocklist;
        if (list.Hit(app.Exe) is { } h) { Front?.Invoke(h, app); return; }
        if (Browser.With(app.Exe) is { } browser && store.Settings.ReadBrowsers && list.WatchesSites && !Disabled)
        {
            // the last address read in this window answers now; a fresh one is asked for
            if (lastUrl.TryGetValue(app.Hwnd, out var last) && Clock.Now - last.At < 5_000)
                Front?.Invoke(list.Hit(last.Url, browser.Name), app);
            else Front?.Invoke(null, app);
            reader.Ask(app.Hwnd, browser);
            return;
        }
        Front?.Invoke(null, app);
    }

    void OnRead(IntPtr hwnd, Browser browser, string? url)
    {
        if (url == null || !Browser.LooksLikeAddress(url)) return;
        if (!store.Settings.BrowserNoticeShown) FirstBrowser?.Invoke(browser);
        lastUrl[hwnd] = (url, Clock.Now);
        if (lastUrl.Count > 50) lastUrl.Remove(lastUrl.MinBy(kv => kv.Value.At).Key);
        // only if that browser is still the window in front
        if (Native.GetForegroundWindow() != hwnd || Current is not { } app || app.Hwnd != hwnd) return;
        Front?.Invoke(store.Settings.Blocklist.Hit(url, browser.Name), app);
    }

    /// <summary>Brings a window back to the front.</summary>
    public static void Activate(IntPtr hwnd)
    {
        if (hwnd == IntPtr.Zero || !Native.IsWindow(hwnd)) return;
        if (Native.IsIconic(hwnd)) Native.ShowWindow(hwnd, Native.SW_RESTORE);
        Native.SetForegroundWindow(hwnd);
    }
}

/// <summary>
/// A browser's address box, read with UI Automation on a thread of its own (UI Automation must not be asked
/// from the thread that draws the app's windows). The box is found once for each window and kept. While the
/// person is typing in it, it isn't read, so a half-typed address doesn't count.
/// </summary>
sealed class BrowserReader : IDisposable
{
    const int TreeScope_Descendants = 4;
    const int UIA_ControlTypePropertyId = 30003, UIA_AutomationIdPropertyId = 30011, UIA_HasKeyboardFocusPropertyId = 30008,
        UIA_ValueValuePropertyId = 30045, UIA_EditControlTypeId = 50004;
    static readonly Guid CUIAutomation8 = new("e22ad333-b25f-460c-83d0-0581107395c9");

    readonly BlockingCollection<(IntPtr Hwnd, Browser Browser)> asks = new(new ConcurrentQueue<(IntPtr, Browser)>(), 8);
    readonly Thread thread;
    readonly Dictionary<IntPtr, UIA.IUIAutomationElement> boxes = [];
    UIA.IUIAutomation? uia;

    /// <summary>On the UI thread: the window, the browser, and what its address box held (null if it couldn't be read).</summary>
    public event Action<IntPtr, Browser, string?>? Read;

    public BrowserReader()
    {
        thread = new Thread(Work) { IsBackground = true, Name = "Chataltul browser reader" };
        thread.SetApartmentState(ApartmentState.MTA);
        thread.Start();
    }

    public void Ask(IntPtr hwnd, Browser browser)
    {
        // one ask at a time for a window is plenty: the next look asks again
        if (asks.Any(a => a.Hwnd == hwnd)) return;
        asks.TryAdd((hwnd, browser));
    }

    public void Dispose() => asks.CompleteAdding();

    void Work()
    {
        try { uia = (UIA.IUIAutomation)Activator.CreateInstance(Type.GetTypeFromCLSID(CUIAutomation8)!)!; }
        catch (Exception e) { Log.Write($"UI Automation isn't there: {e.Message}"); return; }
        foreach (var (hwnd, browser) in asks.GetConsumingEnumerable())
        {
            string? url = null;
            try { url = Address(hwnd, browser); }
            catch (Exception e) { boxes.Remove(hwnd); Log.Write($"reading {browser.Name}: {e.Message}"); }
            Ui.Post(() => Read?.Invoke(hwnd, browser, url));
        }
    }

    /// <summary>What the front window's address box holds. Chromium's is its first text box; Firefox's has its own name.</summary>
    public string? Address(IntPtr hwnd, Browser browser)
    {
        if (uia == null) return null;
        for (var attempt = 0; attempt < 2; attempt++)
        {
            if (!boxes.TryGetValue(hwnd, out var box))
            {
                var root = uia.ElementFromHandle(hwnd);
                if (root == null) return null;
                var condition = browser.Gecko
                    ? uia.CreatePropertyCondition(UIA_AutomationIdPropertyId, "urlbar-input")
                    : uia.CreatePropertyCondition(UIA_ControlTypePropertyId, UIA_EditControlTypeId);
                box = root.FindFirst((UIA.TreeScope)TreeScope_Descendants, condition);
                if (box == null) return null;
                boxes[hwnd] = box;
                if (boxes.Count > 20) boxes.Remove(boxes.Keys.First());
            }
            try
            {
                if (box.GetCurrentPropertyValue(UIA_HasKeyboardFocusPropertyId) is true) return null;
                return box.GetCurrentPropertyValue(UIA_ValueValuePropertyId) as string;
            }
            catch (COMException)
            {
                // the window was closed or redrawn: find its box again
                boxes.Remove(hwnd);
            }
        }
        return null;
    }
}
