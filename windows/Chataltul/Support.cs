using Microsoft.UI.Dispatching;

namespace Chataltul;

/// <summary>
/// The command line: nothing for a person (or --login, when Windows starts it at sign-in); the rest is for
/// windows/selftest.ps1 and the workflow.
/// </summary>
public sealed record Options(bool SelfTest, bool Login, string? Data, string? Out, string? Shots, bool OnScreen, bool Browser, int? QuitAfter)
{
    public static Options Parse(string[] args)
    {
        string? Value(string name)
        {
            var i = Array.IndexOf(args, name);
            return i >= 0 && i + 1 < args.Length ? args[i + 1] : null;
        }
        return new(args.Contains("--selftest"), args.Contains("--login"), Value("--data"), Value("--out"), Value("--shots"),
            args.Contains("--onscreen"), args.Contains("--browser"), int.TryParse(Value("--quit-after"), out var q) ? q : null);
    }
}

/// <summary>chataltul.log, beside the doc: what went wrong where nobody saw it.</summary>
static class Log
{
    static readonly object Gate = new();

    public static void Write(string line)
    {
        System.Diagnostics.Debug.WriteLine(line);
        try
        {
            lock (Gate)
            {
                var path = Path.Join(App.DataDir.Length > 0 ? App.DataDir : Path.GetTempPath(), "chataltul.log");
                var info = new FileInfo(path);
                if (info.Exists && info.Length > 2_000_000) File.Move(path, path + ".old", overwrite: true);
                File.AppendAllText(path, $"{DateTime.Now:O} {line}{Environment.NewLine}");
            }
        }
        catch (Exception) { }
    }
}

/// <summary>The browser, for the web app. The self-test only notes it.</summary>
static class Outside
{
    public static readonly List<string> Opened = [];

    public static void Open(string url)
    {
        Opened.Add(url);
        if (App.Options.SelfTest) return;
        try { System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(url) { UseShellExecute = true }); }
        catch (Exception e) { Log.Write($"open {url}: {e.Message}"); }
    }
}

/// <summary>The UI thread's clock and queue.</summary>
static class Ui
{
    public static DispatcherQueue Queue { get; private set; } = null!;

    public static void Init() => Queue = DispatcherQueue.GetForCurrentThread();

    /// <summary>Runs this on the UI thread, soon.</summary>
    public static void Post(Action a) => Queue.TryEnqueue(() =>
    {
        try { a(); } catch (Exception e) { Log.Write($"posted: {e}"); }
    });

    public static DispatcherQueueTimer Every(TimeSpan interval, Action tick)
    {
        var t = Queue.CreateTimer();
        t.Interval = interval;
        t.IsRepeating = true;
        t.Tick += (_, _) =>
        {
            try { tick(); } catch (Exception e) { Log.Write($"timer: {e}"); }
        };
        t.Start();
        return t;
    }

    public static DispatcherQueueTimer After(TimeSpan delay, Action then)
    {
        var t = Queue.CreateTimer();
        t.Interval = delay;
        t.IsRepeating = false;
        t.Tick += (_, _) =>
        {
            t.Stop();
            try { then(); } catch (Exception e) { Log.Write($"timer: {e}"); }
        };
        t.Start();
        return t;
    }
}

/// <summary>Open at sign-in: the per-user Run key, which needs no administrator.</summary>
static class Startup
{
    const string Key = @"Software\Microsoft\Windows\CurrentVersion\Run";
    const string Name = "Chataltul";

    static string Command => $"\"{Environment.ProcessPath}\" --login";

    public static bool IsOn
    {
        get
        {
            using var k = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(Key);
            return k?.GetValue(Name) is string s && s.Length > 0;
        }
    }

    public static void Set(bool on)
    {
        if (App.Options.SelfTest) return;
        try
        {
            using var k = Microsoft.Win32.Registry.CurrentUser.CreateSubKey(Key);
            if (on) k.SetValue(Name, Command);
            else k.DeleteValue(Name, false);
        }
        catch (Exception e) { Log.Write($"open at sign-in: {e.Message}"); }
    }

    /// <summary>An installed copy that moved (an update to another folder) still starts the one that runs now.</summary>
    public static void Refresh()
    {
        if (App.Options.SelfTest || !IsOn) return;
        Set(true);
    }
}
