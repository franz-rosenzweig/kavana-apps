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

    /// <summary>The ready-made lists on this PC (lists\), which the blocklist's categories are matched against.</summary>
    public CategoryStore Categories { get; }
    /// <summary>What each list's allowance has seen today (usage.json).</summary>
    public DayUsage Usage { get; private set; }
    /// <summary>The web's sounds (the Mac's sounds.json), and its meows.</summary>
    public SoundCatalog Sounds { get; }
    /// <summary>A break that was postponed comes back at this time, with this many minutes.</summary>
    public (long At, int Minutes)? Postponed { get; set; }

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
    /// <summary>The doc changed here (not by a sync): with an account, it goes up in a moment.</summary>
    public event Action? DocChanged;

    /// <summary>The account's own ops: what they change came from the server, so it isn't sent back for itself.</summary>
    static readonly HashSet<string> SyncOps = ["merge", "empty", "squeeze", "size"];

    public Store(string folder, string js)
    {
        Folder = folder;
        Directory.CreateDirectory(folder);
        host = new EngineHost(js);
        docs = new DocStore(folder);
        settingsStore = new SettingsStore(folder);
        Settings = settingsStore.Read();
        // the self-test never goes to the web for a list: it puts its own in place
        Categories = new CategoryStore(Path.Join(folder, "lists"), network: !App.Options.SelfTest) { Custom = Settings.CustomLists };
        Usage = DayUsage.Read(folder);
        var soundsFile = Path.Join(AppContext.BaseDirectory, "sounds.json");
        try { Sounds = SoundCatalog.Load(soundsFile); }
        catch (Exception e) { Log.Write($"sounds.json: {e.Message}"); Sounds = new SoundCatalog(); }
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
        Categories.Custom = Settings.CustomLists;
        Usage = new DayUsage();
        LongBreakEnd = null;
        AwayPrompt = null;
        Postponed = null;
        Act("tick");
    }

    // ---------- one change at a time

    public Out? Act(string op, Dictionary<string, object?>? fields = null, long? now = null)
    {
        try
        {
            var o = host.Run(op, fields, now ?? Clock.Now);
            if (o.Error != null && op != "takeOff") Log.Write($"engine: {op}: {o.Error}");
            Finish(o, op);
            return o;
        }
        catch (Exception e)
        {
            Log.Write($"engine: {op}: {e.Message}");
            return null;
        }
    }

    /// <summary>An op already written as JSON (a merge carries the server's whole doc).</summary>
    public Out? ActJson(string json, string op)
    {
        try
        {
            var o = host.RunJson(json, Clock.Now);
            if (o.Error != null) Log.Write($"engine: {op}: {o.Error}");
            Finish(o, op);
            return o;
        }
        catch (Exception e)
        {
            Log.Write($"engine: {op}: {e.Message}");
            return null;
        }
    }

    /// <summary>
    /// The blocks that started in a span of time, for the window's Today and the Café (the state carries only the
    /// newest 400). A read: it changes nothing and tells nobody.
    /// </summary>
    public List<Hist> Blocks(long from, long to)
    {
        // the newest 400 the state has reach back far enough (a day, a week): no need to ask the engine
        var have = State.History;
        if (have.Count < 400 || have[^1].Start < from) return have.Where(b => b.Start >= from && b.Start < to).ToList();
        try
        {
            var o = host.Run("period", new Dictionary<string, object?> { ["from"] = from, ["to"] = to }, Clock.Now);
            return o.Result is { ValueKind: System.Text.Json.JsonValueKind.Array } a ? a.EnumerateArray().Select(x => new Hist(x)).ToList() : [];
        }
        catch (Exception e)
        {
            Log.Write($"engine: period: {e.Message}");
            return [];
        }
    }

    /// <summary>The doc as the server keeps it.</summary>
    public string DocJson()
    {
        try { return host.Dump(); } catch (Exception e) { Log.Write($"engine: dump: {e.Message}"); return "{}"; }
    }

    void Finish(Out o, string op)
    {
        if (o.Changed)
        {
            Persist();
            if (!SyncOps.Contains(op)) DocChanged?.Invoke();
        }
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

    /// <summary><c>quiet</c>: a control that shows the change already (a slider being dragged) isn't built again under it.</summary>
    public void ChangeSettings(Action<WinSettings> edit, bool quiet = false)
    {
        edit(Settings);
        Categories.Custom = Settings.CustomLists;
        try { settingsStore.Write(Settings); } catch (Exception e) { Log.Write($"the settings didn't save: {e.Message}"); }
        if (!quiet) SettingsChanged?.Invoke();
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

    /// <summary>A block. <c>fromLater</c>: the Later item it was started from, which the engine ticks off.</summary>
    public void Start(string title, int? minutes = null, bool stopwatch = false, string? fromLater = null)
    {
        var s = State.View.Settings;
        var f = new Dictionary<string, object?>
        {
            ["intention"] = title, ["minutes"] = minutes ?? Settings.FocusMinutes, ["style"] = s.Style,
            ["mode"] = stopwatch ? "stopwatch" : "timer", ["tag"] = s.Tag, ["cat"] = State.CatsOn ? State.Companion : null,
        };
        if (fromLater != null) f["fromLater"] = fromLater;
        Act("start", f);
    }

    public void AddLater(string text) { if (text.Trim().Length > 0) Act("addLater", new() { ["text"] = text.Trim() }); }
    public void DoneLater(string id) => Act("doneLater", new() { ["id"] = id });

    /// <summary>Collects a reward that's done: its treats.</summary>
    public void Claim(string id) => Act("claim", new() { ["id"] = id });

    /// <summary>The streak, from the newest blocks the state carries (the days in a row end well inside them).</summary>
    public int Streak => State.Stats.Streak;

    /// <summary>
    /// Stop: inside the first ten seconds it takes the block back, with no harm done. After that a locked list keeps
    /// the block going: stopping it would be the way round the lock.
    /// </summary>
    public bool Stop()
    {
        if (Active is not { } b) return false;
        if (Clock.Now <= b.CancelUntil) { Act("cancel"); return true; }
        if (LockedUntil != null) return false;
        Act("stop");
        return true;
    }

    /// <summary>When a locked list lets go: the end of a running block, if any list is locked (and its first ten seconds are over).</summary>
    public long? LockedUntil =>
        Active is { } b && GuardPolicy.LocksTheBlock(Settings.Blocklist) && Clock.Now > b.CancelUntil ? b.End : null;

    // ---------- the lists' allowances

    public long UsedMs(string listId) => Usage.Used(listId, DateTime.Now);
    public void AddUsage(string listId, long ms) => Usage.Add(listId, ms, DateTime.Now);
    public void SaveUsage() => Usage.Write(Folder);

    /// <summary>Fetches the ready-made lists that are switched on and not here yet, or last looked at over a week ago.</summary>
    public void RefreshLists(bool force = false) => _ = Categories.Refresh(Settings.Blocklist.CategoryIds, force);

    // ---------- the cycle and the sounds

    public CyclePreset Preset => Settings.Cycle.Preset;

    /// <summary>Chooses a cycle: its focus and break lengths become the popover's.</summary>
    public void ApplyPreset(CyclePreset p) => ChangeSettings(s =>
    {
        s.Cycle.Preset = p;
        if (Cycles.Minutes(p) is { } m) { s.FocusMinutes = m.Focus; s.Cycle.ShortBreak = m.Rest; }
    });

    /// <summary>What in the mix can play now (owned, two at most), and its name: "Rain + Purring".</summary>
    public List<MixEntry> PlayableMix => Sounds.Playable(Settings.Mix, State.Owned);
    public string MixLabel => Sounds.Label(Settings.Mix, State.Owned);

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
        Postponed = null;
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

    /// <summary>The break to offer after this block: a long one after every few, a fifth of the stretch in Flowtime.</summary>
    public int BreakAfter(Block? b = null)
    {
        if (Preset == CyclePreset.Flow) return Cycles.FlowBreak(b is { } x ? (int)(Math.Max(1, (x.Ended ?? Clock.Now) - x.Start) / 60_000) : 25);
        return Settings.Cycle.IsLong(BlocksToday) ? Settings.Cycle.LongBreak : Settings.Cycle.ShortBreak;
    }

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
