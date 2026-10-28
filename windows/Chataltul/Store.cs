using Chataltul.Core;

namespace Chataltul;

/// <summary>The time, as the app sees it: the PC's clock, which the self-test moves on instead of waiting.</summary>
static class Clock
{
    public static long Offset;
    public static long Now => Format.NowMs() + Offset;
}

/// <summary>
/// The app's one model: the engine's state, the doc on disk, this PC's settings, and the clock that moves time
/// on. Everything runs on the UI thread (the engine answers in a few milliseconds), so a change and what follows
/// it can't cross. The Mac's Store.swift.
/// </summary>
sealed class Store
{
    public string Folder { get; }
    readonly EngineHost host;
    readonly DocStore docs;
    readonly SettingsStore settingsStore;
    readonly PingTracker tracker = new();

    public UiState State { get; private set; }
    public WinSettings Settings { get; private set; }

    /// <summary>"You were away from the PC": waiting for Count them or Take them off.</summary>
    public AwayReturn? AwayPrompt { get; private set; }
    /// <summary>A break longer than the engine allows (5, 10 or 15 minutes) runs on past the engine's own: the app keeps its end.</summary>
    public long? LongBreakEnd { get; private set; }
    long longBreakStart;

    /// <summary>Pings that came due, for whoever shows them (the cat, a notification).</summary>
    public event Action<List<Ping>>? Due;
    /// <summary>Everything that follows a change: the tray, the cat, the windows.</summary>
    public event Action<UiState>? Changed;
    public event Action? SettingsChanged;
    public event Action? AwayPromptChanged;

    public Store(string folder, string js)
    {
        Folder = folder;
        Directory.CreateDirectory(folder);
        host = new EngineHost(js);
        docs = new DocStore(folder);
        settingsStore = new SettingsStore(folder);
        Settings = settingsStore.Read();
        // the doc: loaded, or if it doesn't read, kept aside (never written over) and started anew
        try { host.Load(docs.Read()); }
        catch (Exception e)
        {
            Log.Write($"doc.json doesn't load, kept aside: {e.Message}");
            docs.SetAside();
            host.Load(null);
        }
        var o = host.Run("tick", now: Clock.Now);
        State = o.State;
        Art.Learn(o.State.Cats);
        if (o.Changed) Persist();
        tracker.Update(o.State.Pings, o.State.Now);
    }

    /// <summary>For the self-test: a new doc and the settings a new install has, on its own folder.</summary>
    public void ResetForTest(WinSettings? settings = null)
    {
        host.Load(null);
        Settings = settings ?? new WinSettings();
        LongBreakEnd = null;
        AwayPrompt = null;
        Act("tick");
    }

    // ---------- one change at a time

    public Out? Act(string op, Dictionary<string, object?>? fields = null, long? now = null)
    {
        try
        {
            var o = host.Run(op, fields, now ?? Clock.Now);
            if (o.Error != null && op != "takeOff") Log.Write($"engine: {op}: {o.Error}");
            Finish(o);
            return o;
        }
        catch (Exception e)
        {
            Log.Write($"engine: {op}: {e.Message}");
            return null;
        }
    }

    void Finish(Out o)
    {
        if (o.Changed) Persist();
        Art.Learn(o.State.Cats);
        State = o.State;
        var due = tracker.Update(o.State.Pings, o.State.Now);
        if (due.Count > 0) Due?.Invoke(due);
        Changed?.Invoke(o.State);
    }

    void Persist()
    {
        try { docs.Write(host.Dump()); }
        catch (Exception e) { Log.Write($"the doc didn't save: {e.Message}"); }
    }

    public void ChangeSettings(Action<WinSettings> edit)
    {
        edit(Settings);
        try { settingsStore.Write(Settings); } catch (Exception e) { Log.Write($"the settings didn't save: {e.Message}"); }
        SettingsChanged?.Invoke();
    }

    // ---------- the clock

    /// <summary>Once a second: the engine is woken when it has something to do by itself (a ping, a pause ending).</summary>
    public void Second(long now)
    {
        if (NextWake is { } w && now >= w) Act("tick", now: now);
        if (LongBreakEnd is { } e && now >= e) { LongBreakEnd = null; Act("stopBreak", now: now); }
    }

    public long? NextWake
    {
        get
        {
            long? a = tracker.Next, b = Active?.PauseEnds;
            return a == null ? b : b == null ? a : Math.Min(a.Value, b.Value);
        }
    }

    // ---------- what the screens ask

    public Block? Active => State.View.Active;
    public Block? Awaiting => State.View.Awaiting;
    public bool Running => Active is { Paused: null };

    /// <summary>The break that is on, with the app's longer end if it asked for one.</summary>
    public Brk? OnBreak
    {
        get
        {
            var n = Clock.Now;
            if (State.View.OnBreak is { } b)
                return LongBreakEnd is { } e && e > b.End ? b with { End = e, Minutes = Math.Max(b.Minutes, (int)((e - b.Start) / 60_000)) } : b;
            if (LongBreakEnd is { } end && end > n) return new Brk((int)((end - longBreakStart) / 60_000), longBreakStart, end);
            return null;
        }
    }

    public CatInfo Cat(string? id) => State.Cat(id);
    /// <summary>The cat of the block on screen, else the one waiting to be invited.</summary>
    public CatInfo ShownCat => Cat(Active?.Cat ?? Awaiting?.Cat ?? State.Companion);

    /// <summary>Blocks that ran to their end today, for "2 of 6 blocks".</summary>
    public int BlocksToday => State.History.Count(h => Format.IsToday(h.Start, Clock.Now) && !h.Stopped);
    public int CatsHomeToday => State.History.Count(h => Format.IsToday(h.Start, Clock.Now) && !h.Stopped && h.Kitten == "home");
    public Core.Settings EngineSettings => State.View.Settings;

    // ---------- the changes

    public void Start(string title, int? minutes = null, bool stopwatch = false)
    {
        var s = State.View.Settings;
        Act("start", new()
        {
            ["intention"] = title, ["minutes"] = minutes ?? Settings.FocusMinutes, ["style"] = s.Style,
            ["mode"] = stopwatch ? "stopwatch" : "timer", ["tag"] = s.Tag, ["cat"] = State.CatsOn ? State.Companion : null,
        });
    }

    /// <summary>Stop: inside the first ten seconds it takes the block back, with no harm done.</summary>
    public bool Stop()
    {
        if (Active is not { } b) return false;
        if (Clock.Now <= b.CancelUntil) { Act("cancel"); return true; }
        Act("stop");
        return true;
    }

    public void Pause() => Act("pause");
    public void Resume() => Act("resume");

    public void Answer(string answer, string? why = null)
    {
        if (Active is not { Pending: { } n } b) return;
        var f = new Dictionary<string, object?> { ["id"] = b.Id, ["n"] = n, ["answer"] = answer };
        if (why != null) f["why"] = why;
        Act("answer", f);
    }

    public void Outcome(string id, string? outcome) => Act("outcome", new() { ["id"] = id, ["outcome"] = outcome });
    public void Note(string id, string text) => Act("edit", new() { ["id"] = id, ["patch"] = new Dictionary<string, object?> { ["note"] = text } });

    /// <summary>On a running block the thought is kept on the block too; otherwise it only waits in Later.</summary>
    public bool Park(string text)
    {
        var t = text.Trim();
        if (t.Length == 0) return false;
        return Act(Active != null || Awaiting != null ? "park" : "addLater", new() { ["text"] = t })?.Error == null;
    }

    /// <summary>
    /// Starts a break. The engine only knows breaks of 5, 10 and 15 minutes; a longer one starts the longest of
    /// those and the app keeps its own end (mac/docs/engine-takeoff.md asks the web engine for more lengths).
    /// </summary>
    public void StartBreak(int minutes)
    {
        var engine = new[] { 5, 10, 15 }.Where(m => m <= minutes).DefaultIfEmpty(5).Max();
        var n = Clock.Now;
        if (Act("startBreak", new() { ["minutes"] = engine }, n)?.Error == null && minutes > engine)
        {
            longBreakStart = n;
            LongBreakEnd = n + minutes * 60_000L;
        }
        else LongBreakEnd = null;
        Changed?.Invoke(State);
    }

    public void StopBreak()
    {
        LongBreakEnd = null;
        Act("stopBreak");
    }

    /// <summary>The break to offer after this block: a long one after every few.</summary>
    public int BreakAfter() => Settings.Cycle.IsLong(BlocksToday) ? Settings.Cycle.LongBreak : Settings.Cycle.ShortBreak;

    public void SetEngine(Dictionary<string, object?> patch) => Act("settings", new() { ["patch"] = patch });

    /// <summary>The kitten wanders off because the person left (the speed bump).</summary>
    public bool KittenLeaves(long at, long grace) =>
        Act("away", new() { ["at"] = at, ["grace"] = grace }, Math.Max(Clock.Now, at + grace))?.HasResult == true;

    // ---------- away

    public void SetAwayPrompt(AwayReturn? a)
    {
        AwayPrompt = a;
        AwayPromptChanged?.Invoke();
    }

    public void CountAway() => SetAwayPrompt(null);

    public void TakeAwayOff()
    {
        if (AwayPrompt is not { } a) return;
        SetAwayPrompt(null);
        Act("takeOff", new() { ["from"] = a.From, ["to"] = a.To });
    }
}
