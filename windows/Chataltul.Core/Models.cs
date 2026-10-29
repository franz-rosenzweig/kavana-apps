using System.Text.Json;

namespace Chataltul.Core;

// What bridge.js hands back (KB.run). The shapes are engine.js's own: E.view, E.history, E.pings, E.missions.
// Fields the Windows app doesn't draw (the shop's shelves, friends) are left out.
// Times are milliseconds since 1970, as the engine keeps them. The Mac's is Models.swift.

public sealed class Out
{
    public bool Changed { get; }
    /// <summary>What the op returned, if anything (a block's id, the drifts so far, a cat).</summary>
    public JsonElement? Result { get; }
    public string? Error { get; }
    public List<string> Meets { get; }
    public List<DoneNote> Done { get; }
    public UiState State { get; }

    public Out(JsonElement e)
    {
        Changed = e.Bool("changed");
        Result = e.Get("result")?.Clone();
        Error = e.OptStr("error");
        Meets = e.Strings("meets");
        Done = e.Items("done", x => new DoneNote(x));
        State = new UiState(e.Get("state") ?? default);
    }

    /// <summary>The op gave something back (not null): the away op does when a kitten left.</summary>
    public bool HasResult => Result is { ValueKind: not (JsonValueKind.Null or JsonValueKind.Undefined) };
}

public sealed class DoneNote(JsonElement e)
{
    public string Kind { get; } = e.Str("kind");
    public string Text { get; } = e.Str("text");
    public int Pay { get; } = e.Int("pay");
    public string? Id { get; } = e.OptStr("id");
}

public sealed class UiState
{
    public long Now { get; }
    public ViewState View { get; }
    public List<Hist> History { get; }
    public Treats Treats { get; }
    public Stats Stats { get; }
    public List<CatInfo> Cats { get; }
    public string Companion { get; }
    public List<Ping> Pings { get; }
    public bool CanPause { get; }
    public List<string> Owned { get; }
    public Dictionary<string, int> Prices { get; }
    /// <summary>The things for the café (cats.js ITEMS) that this account owns, with their side and back paintings.</summary>
    public List<CafeThing> Things { get; }
    public Dictionary<string, int> Tally { get; }
    /// <summary>What this engine can do beyond the web's (bridge.js, marked MAC).</summary>
    public bool CanTakeOff { get; }
    /// <summary>Today's and this week's missions.</summary>
    public Missions Missions { get; }
    public List<Reward> Achievements { get; }
    public Starter Starter { get; }
    /// <summary>This month's challenge, after last month's if that one is done and waiting to be collected.</summary>
    public List<Reward> Monthly { get; }

    public UiState(JsonElement e)
    {
        Missions = new Missions(e.Get("missions") ?? default);
        Achievements = e.Items("achievements", x => new Reward(x));
        Starter = new Starter(e.Get("starter") ?? default);
        Monthly = e.Items("monthly", x => new Reward(x));
        Now = e.Long("now");
        View = e.Get("view") is { } v ? new ViewState(v) : new ViewState(default);
        History = e.Items("history", x => new Hist(x));
        Treats = new Treats(e.Get("treats") ?? default);
        Stats = new Stats(e.Get("stats") ?? default);
        Cats = e.Items("cats", x => new CatInfo(x));
        Companion = e.Str("companion", "miso");
        Pings = e.Items("pings", x => Ping.From(x));
        CanPause = e.Bool("canPause", true);
        var shop = e.Get("shop") ?? default;
        Owned = shop.Strings("owned");
        Prices = shop.IntMap("prices");
        Things = shop.Items("items", x => new CafeThing(x)).Where(t => Owned.Contains(t.Id)).ToList();
        Tally = e.IntMap("tally");
        CanTakeOff = (e.Get("caps") ?? default).Bool("takeOff");
    }

    /// <summary>The cat with this id, else the first one, else Miso.</summary>
    public CatInfo Cat(string? id) => Cats.FirstOrDefault(c => c.Id == id) ?? Cats.FirstOrDefault() ?? new CatInfo("miso", "Miso");
    public bool CatsOn => View.Settings.Cat;
    public bool Owns(string id) => Owned.Contains(id);

    /// <summary>Rewards that are done and not collected yet (achievements, the starter challenge, the month): "Collect +20".</summary>
    public List<Reward> Collectable
    {
        get
        {
            var out_ = Achievements.Where(a => a.Done && !a.Claimed).Concat(Starter.Steps.Where(s => s.Done && !s.Claimed)).ToList();
            if (Starter.All is { Done: true, Claimed: false }) out_.Add(Starter.All);
            out_.AddRange(Monthly.Where(m => m.Done && !m.Claimed));
            return out_;
        }
    }
}

/// <summary>A thing for the café: its id and, where painted, its side and back.</summary>
public sealed class CafeThing(JsonElement e)
{
    public string Id { get; } = e.Str("id");
    public string Name { get; } = e.Str("name");
    public string? Side { get; } = (e.Get("views") ?? default).OptStr("side");
    public string? Back { get; } = (e.Get("views") ?? default).OptStr("back");
}

/// <summary>A mission: "Bring 3 kittens home", 2 of 3, 15 treats.</summary>
public sealed class Mission(JsonElement e)
{
    public string Id { get; } = e.Str("id");
    public string Text { get; } = e.Str("text");
    public int Need { get; } = e.Int("need", 1);
    public string? Unit { get; } = e.OptStr("unit");
    public int Pay { get; } = e.Int("pay");
    public int Have { get; } = e.Int("have");
    public bool Done { get; } = e.Bool("done");

    /// <summary>"Focus for 1 h: 40 min of 1 h", or the text alone for a mission of one.</summary>
    public string Progress => Need <= 1 ? Text : $"{Text}: {(Unit == "min" ? $"{Format.FmtMin(Have)} of {Format.FmtMin(Need)}" : $"{Have} of {Need}")}";
}

public sealed class Missions(JsonElement e)
{
    public List<Mission> Day { get; } = e.Items("day", x => new Mission(x));
    public List<Mission> Week { get; } = e.Items("week", x => new Mission(x));
    public long DayEnds { get; } = e.Long("dayEnds");
    public long WeekEnds { get; } = e.Long("weekEnds");
}

/// <summary>An achievement, a starter step or a month's challenge: done or not, collected or not, and what it pays.</summary>
public sealed class Reward(JsonElement e)
{
    public string Id { get; } = e.Str("id");
    public string Name { get; } = e.Str("name", e.Str("text"));
    public string Text { get; } = e.Str("text");
    public int Have { get; } = e.Int("have");
    public int Goal { get; } = e.Int("goal", e.Int("need", 1));
    public bool Done { get; } = e.Bool("done");
    public bool Claimed { get; } = e.Bool("claimed");
    public int Pay { get; } = e.Int("reward");
}

public sealed class Starter(JsonElement e)
{
    public List<Reward> Steps { get; } = e.Items("steps", x => new Reward(x));
    public Reward All { get; } = new(e.Get("all") ?? default);
    public bool Finished { get; } = e.Bool("finished");
}

public sealed class ViewState(JsonElement e)
{
    public long Now { get; } = e.Long("now");
    public Settings Settings { get; } = new(e.Get("settings") ?? default);
    public Block? Active { get; } = e.Get("active") is { } a ? new Block(a) : null;
    public Block? Awaiting { get; } = e.Get("awaiting") is { } a ? new Block(a) : null;
    public Brk? OnBreak { get; } = e.Get("onBreak") is { } b ? Brk.From(b) : null;
    public BreakOffer? BreakOffer { get; } = e.Get("breakOffer") is { } o ? new BreakOffer(o) : null;
    public List<string> Recent { get; } = e.Strings("recent");
    public List<LaterItem> Later { get; } = e.Items("later", x => new LaterItem(x));
}

/// <summary>The engine's own settings (doc.settings): the ones the web and the phone share.</summary>
public sealed class Settings(JsonElement e)
{
    public int Interval { get; } = e.Int("interval", 15);
    public string Style { get; } = e.Str("style", "ask");
    public int Goal { get; } = e.Int("goal");
    public bool Cat { get; } = e.Bool("cat", true);
    public string Companion { get; } = e.Str("companion", "miso");
    public List<string> Tags { get; } = e.Strings("tags");
    public string Tag { get; } = e.Str("tag");
    public string Mode { get; } = e.Str("mode", "timer");
    public string Pause { get; } = e.Str("pause", "five");
    public bool Overtime { get; } = e.Bool("overtime");
    public bool CloseCounts { get; } = e.Bool("closeCounts", true);
    public string Theme { get; } = e.Str("theme", "classic");
}

public sealed class Block(JsonElement e)
{
    public string Id { get; } = e.Str("id");
    public string Intention { get; } = e.Str("intention");
    public int Minutes { get; } = e.Int("minutes");
    public int Interval { get; } = e.Int("interval");
    public long Start { get; } = e.Long("start");
    public long End { get; } = e.Long("end");
    public long? Ended { get; } = e.OptLong("ended");
    public bool Stopped { get; } = e.Bool("stopped");
    public int Drifts { get; } = e.Int("drifts");
    /// <summary>The check-in waiting for an answer, by its number.</summary>
    public int? Pending { get; } = e.OptInt("pending");
    public long? Next { get; } = e.OptLong("next");
    public string Style { get; } = e.Str("style", "ask");
    /// <summary>When it was paused, while it is.</summary>
    public long? Paused { get; } = e.OptLong("paused");
    public List<string> Parked { get; } = e.Strings("parked");
    public string Cat { get; } = e.Str("cat", "miso");
    public string? Tag { get; } = e.OptStr("tag");
    /// <summary>"growing", "home" or "gone".</summary>
    public string Kitten { get; } = e.Str("kitten", "growing");
    public string? GoneWhy { get; } = e.OptStr("goneWhy");
    public int Treats { get; } = e.Int("treats");
    public string Mode { get; } = e.Str("mode", "timer");
    public long? Over { get; } = e.OptLong("over");
    /// <summary>Until then, Stop takes the block back instead.</summary>
    public long CancelUntil { get; } = e.Long("cancelUntil");
    public string Note { get; } = e.Str("note");
    public long? PauseEnds { get; } = e.OptLong("pauseEnds");
    public int Pauses { get; } = e.Int("pauses");
    /// <summary>How long it runs, less its pauses (the end moves with a pause).</summary>
    public long Total { get; } = e.Long("total");
    /// <summary>Treats for napping together, on top of its own.</summary>
    public int Bonus { get; } = e.Int("bonus");

    public bool Stopwatch => Mode == "stopwatch";
    public string Title => Format.TitleOf(Intention, Tag);
}

public sealed record Brk(int Minutes, long Start, long End)
{
    public static Brk From(JsonElement e) => new(e.Int("minutes"), e.Long("start"), e.Long("end"));
}

public sealed class BreakOffer(JsonElement e)
{
    public string Id { get; } = e.Str("id");
    public int Minutes { get; } = e.Int("minutes", 5);
}

public sealed class LaterItem(JsonElement e)
{
    public string Id { get; } = e.Str("id");
    public string Text { get; } = e.Str("text");
}

public sealed class Hist(JsonElement e)
{
    public string Id { get; } = e.Str("id");
    public long Start { get; } = e.Long("start");
    public string Intention { get; } = e.Str("intention");
    public int Minutes { get; } = e.Int("minutes");
    public int Actual { get; } = e.Int("actual", 1);
    public bool Stopped { get; } = e.Bool("stopped");
    public string? Outcome { get; } = e.OptStr("outcome");
    public int Drifts { get; } = e.Int("drifts");
    public string Cat { get; } = e.Str("cat", "miso");
    public string Kitten { get; } = e.Str("kitten", "home");
    public int Treats { get; } = e.Int("treats");
    public string? Tag { get; } = e.OptStr("tag");
    public string Mode { get; } = e.Str("mode", "timer");
    public string Note { get; } = e.Str("note");
    /// <summary>What pulled the person away at each Drifted check-in that was given a reason (engine ids: phone, web, ...).</summary>
    public List<string> Whys { get; } = e.Strings("whys");

    public string Title => Format.TitleOf(Intention, Tag);
}

public sealed class Treats(JsonElement e)
{
    public int Earned { get; } = e.Int("earned");
    public int Spent { get; } = e.Int("spent");
    public int Left { get; } = e.Int("left");
}

public sealed class Stats(JsonElement e)
{
    public int Today { get; } = e.Int("today");
    public int Week { get; } = e.Int("week");
    public int WeekBlocks { get; } = e.Int("weekBlocks");
    public int Streak { get; } = e.Int("streak");
    public int Answered { get; } = e.Int("answered");
    public int Drifted { get; } = e.Int("drifted");
}

public sealed class CatInfo
{
    public string Id { get; }
    public string Name { get; }
    public string About { get; }
    public bool Met { get; }
    public bool Adopt { get; }
    public string? Portrait { get; }
    public string? Kitten { get; }
    public string? Nap { get; }
    /// <summary>walk, loaf, stretch, wash, back: paintings some cats have.</summary>
    public Dictionary<string, string> Poses { get; }

    public CatInfo(string id, string name)
    {
        Id = id; Name = name; About = ""; Met = true; Poses = [];
    }

    public CatInfo(JsonElement e)
    {
        Id = e.Str("id", "miso"); Name = e.Str("name", "Miso"); About = e.Str("about");
        Met = e.Bool("met"); Adopt = e.Bool("adopt");
        Portrait = e.OptStr("portrait"); Kitten = e.OptStr("kitten"); Nap = e.OptStr("nap");
        Poses = e.StrMap("poses");
    }
}

public sealed record Ping(long At, string Kind, string Tag = "", string Title = "", string Body = "", string? Block = null, int? N = null)
{
    public static Ping From(JsonElement e) =>
        new(e.Long("at"), e.Str("kind"), e.Str("tag"), e.Str("title"), e.Str("body"), e.OptStr("block"), e.OptInt("n"));

    /// <summary>The same ping in two lists (a check-in is its block and number; the end, its block and time).</summary>
    public string Key => $"{Kind}:{Block ?? ""}:{(N is { } n ? n.ToString() : "")}:{At}";
}
