// The core tests (windows/README.md): the web's engine headless in Jint, with the Mac's bridge (a block, its pings,
// the cat names in them, the away op, the engine change we ask for), and the rules of the speed bump, the hold,
// the away prompt, the blocklist, the settings and the tray icon, all with no window. The Mac's mac/Tests/main.swift,
// check for check where the two apps do the same thing.
//   dotnet run --project windows/Tests [-- --repo <a clone of kavana-apps>]
// Prints PASS and FAIL lines and "SELFTEST passed" at the end; the exit code is 0 only if everything passed.
using System.Text.Json;
using Chataltul.Core;

var passed = 0;
var failed = 0;
void Check(string name, bool ok, string detail = "")
{
    if (ok) { passed++; Console.WriteLine($"PASS {name}"); }
    else { failed++; Console.WriteLine($"FAIL {name}{(detail.Length > 0 ? $": {detail}" : "")}"); }
}
void Equal<T>(string name, T got, T want)
{
    var ok = EqualityComparer<T>.Default.Equals(got, want);
    Check(name, ok, ok ? "" : $"got {Show(got)}, wanted {Show(want)}");
}
void Same<T>(string name, IEnumerable<T> got, IEnumerable<T> want)
{
    var ok = got.SequenceEqual(want);
    Check(name, ok, ok ? "" : $"got [{string.Join(", ", got.Select(x => Show(x)))}], wanted [{string.Join(", ", want.Select(x => Show(x)))}]");
}
static string Show(object? o) => o == null ? "null" : o is string s ? $"\"{s}\"" : o.ToString() ?? "";
void Section(string s) => Console.WriteLine($"--- {s}");

string? Arg(string name) { var i = Array.IndexOf(args, name); return i >= 0 && i + 1 < args.Length ? args[i + 1] : null; }
var repo = Arg("--repo") ?? FindRepo(AppContext.BaseDirectory);
static string FindRepo(string from)
{
    for (var d = new DirectoryInfo(from); d != null; d = d.Parent)
        if (Directory.Exists(Path.Join(d.FullName, "mac", "Chataltul", "Resources", "js"))) return d.FullName;
    throw new Exception("can't find the repo (mac/Chataltul/Resources/js): pass --repo");
}
var js = Path.Join(repo, "mac", "Chataltul", "Resources", "js");
const long MIN = 60_000;
var started = DateTime.Now;

// ---------------------------------------------------------------- the engine and its pings
Section("engine");
EngineHost host;
try { host = new EngineHost(js); }
catch (Exception e) { Console.WriteLine($"FAIL the engine didn't load: {e.Message}"); return 1; }

var t = Format.Ms(new DateTime(2026, 10, 26, 10, 0, 0, DateTimeKind.Local));
host.Load(null);
var r = host.Run("tick", now: t);
Equal("a new doc has no running block", r.State.View.Active == null, true);
Equal("Miso is the companion", r.State.Companion, "miso");
Equal("the cats come with their names", r.State.Cat("pepper").Name, "Milo");
Check("every cat has a portrait", r.State.Cats.All(c => c.Portrait != null), string.Join(", ", r.State.Cats.Where(c => c.Portrait == null).Select(c => c.Id)));

var sw = System.Diagnostics.Stopwatch.StartNew();
r = host.Run("start", new Dictionary<string, object?> { ["intention"] = "Draft the newsletter", ["minutes"] = 25, ["style"] = "ask" }, t);
var ms = sw.ElapsedMilliseconds;
var id1 = r.Result?.GetProperty("id").GetString() ?? "";
Check("start gives a block id", id1.Length > 0);
Check("an op takes the engine well under a second", ms < 1000, $"{ms} ms");
Equal("the kitten is growing", r.State.View.Active?.Kitten, "growing");
Same("the pings are a check-in and the end", r.State.Pings.Select(p => p.Kind), ["checkin", "end"]);
Equal("the check-in comes at 15 minutes", r.State.Pings.FirstOrDefault()?.At, t + 15 * MIN);
Check("the check-in names the title", r.State.Pings.FirstOrDefault()?.Title.Contains("Still on: Draft the newsletter?") == true, r.State.Pings.FirstOrDefault()?.Title ?? "");

Section("pings come due");
var tracker = new PingTracker();
Equal("the first look has nothing due", tracker.Update(r.State.Pings, t).Count, 0);
r = host.Run("tick", now: t + 10 * MIN);
Equal("nothing is due before its time", tracker.Update(r.State.Pings, t + 10 * MIN).Count, 0);
r = host.Run("tick", now: t + 15 * MIN + 1000);
var due = tracker.Update(r.State.Pings, t + 15 * MIN + 1000);
Same("the check-in is due at 15 minutes", due.Select(p => p.Kind), ["checkin"]);
Equal("and the engine has it pending", r.State.View.Active?.Pending, 1);
Equal("it isn't told twice", tracker.Update(r.State.Pings, t + 15 * MIN + 2000).Count, 0);
Equal("the next wake is the end", tracker.Next, t + 25 * MIN);

Section("check-in answers");
r = host.Run("answer", new Dictionary<string, object?> { ["id"] = id1, ["n"] = 1, ["answer"] = "drifted", ["why"] = "phone" }, t + 15 * MIN + 5000);
Equal("Drifted counts", r.Result?.GetProperty("drifts").GetInt32(), 1);
Equal("the check-in is answered", r.State.View.Active?.Pending, null);

Section("the end");
r = host.Run("tick", now: t + 25 * MIN + 1000);
var endDue = tracker.Update(r.State.Pings, t + 25 * MIN + 1000);
Same("the end ping is due", endDue.Select(p => p.Kind), ["end"]);
Equal("the kitten came home", r.State.View.Awaiting?.Kitten, "home");
Equal("with 25 treats", r.State.View.Awaiting?.Treats, 25);
r = host.Run("outcome", new Dictionary<string, object?> { ["id"] = id1, ["outcome"] = "done" }, t + 26 * MIN);
Equal("Done files it", r.State.History.FirstOrDefault()?.Outcome, "done");
Same("the reason for the drift is kept with it", r.State.History.FirstOrDefault()?.Whys ?? [], ["phone"]);
Equal("a break is offered", r.State.View.BreakOffer?.Minutes, 5);
Equal("a day's stats count it", r.State.Stats.Today, 25);

Section("a sleeping PC: old pings are dropped, the end is kept");
var sleepy = new PingTracker();
List<Ping> old = [new(t, "checkin", Block: "b", N: 1), new(t + 5 * MIN, "checkin", Block: "b", N: 2), new(t + 30 * MIN, "end", Block: "b")];
sleepy.Update(old, t - 1000);
Same("only the end is told after a long sleep", sleepy.Update([], t + 45 * MIN).Select(p => p.Kind), ["end"]);

Section("cat names in pings (the end ping said the cat's id once)");
foreach (var (style, extra) in new (string, Dictionary<string, object?>)[] { ("extra time", new Dictionary<string, object?> { ["overtime"] = true }), ("timer", new Dictionary<string, object?>()) })
{
    host.Load(null);
    var n = t;
    host.Run("tick", now: n);
    host.Run("settings", new Dictionary<string, object?> { ["patch"] = With(extra, "companion", "pepper") }, n);
    // pepper has to be met first: finishing a block meets it
    host.Run("start", new Dictionary<string, object?> { ["intention"] = "First", ["minutes"] = 5 }, n);
    n += 6 * MIN;
    r = host.Run("tick", now: n);
    if (r.State.View.Awaiting is { } aw) host.Run("outcome", new Dictionary<string, object?> { ["id"] = aw.Id, ["outcome"] = "done" }, n + 1000);
    n += 10 * MIN;
    host.Run("settings", new Dictionary<string, object?> { ["patch"] = With(extra, "companion", "pepper") }, n);
    r = host.Run("start", new Dictionary<string, object?> { ["intention"] = "Read", ["minutes"] = 25 }, n);
    Equal($"the block's cat is Milo's id ({style})", r.State.View.Active?.Cat, "pepper");
    var end = r.State.Pings.FirstOrDefault(p => p.Kind == "end");
    if (style == "extra time") Check("the end ping says Milo, not pepper", end?.Title.Contains("Milo") == true && end.Title.Contains("pepper") == false, end?.Title ?? "");
    else Check("the end ping has no cat id in it", end != null && !end.Title.Contains("pepper") && !end.Body.Contains("pepper"));
}
static Dictionary<string, object?> With(Dictionary<string, object?> d, string k, object v) => new(d) { [k] = v };

Section("away: the kitten wanders off, the block goes on");
host.Load(null);
t += 86_400_000;
host.Run("tick", now: t);
host.Run("start", new Dictionary<string, object?> { ["intention"] = "Away test", ["minutes"] = 25 }, t);
r = host.Run("away", new Dictionary<string, object?> { ["at"] = t + 5 * MIN, ["grace"] = 15_000 }, t + 5 * MIN + 16_000);
Equal("the kitten is gone", r.State.View.Active?.Kitten, "gone");
Equal("because of leaving", r.State.View.Active?.GoneWhy, "away");
Equal("the block keeps going", r.State.View.Active != null, true);
Equal("away returns the cat's id", r.Result?.GetProperty("cat").GetString(), "miso");
r = host.Run("away", new Dictionary<string, object?> { ["at"] = t + 6 * MIN, ["grace"] = 0 }, t + 6 * MIN);
Equal("a second away does nothing more", r.HasResult, false);
r = host.Run("tick", now: t + 25 * MIN + 1000);
Equal("a kitten that left does not come home", r.State.View.Awaiting?.Kitten, "gone");
Equal("and earns no treats", r.State.View.Awaiting?.Treats, 0);

Section("away with no grace is right away (Leave anyway)");
host.Load(null);
t += 86_400_000;
host.Run("tick", now: t);
host.Run("start", new Dictionary<string, object?> { ["minutes"] = 25 }, t);
r = host.Run("away", new Dictionary<string, object?> { ["at"] = t + 3 * MIN, ["grace"] = 0 }, t + 3 * MIN);
Equal("held Leave anyway sends the kitten off at once", r.State.View.Active?.Kitten, "gone");

Section("the engine can't take minutes off yet (E.takeOff is the engine change we need)");
r = host.Run("takeOff", new Dictionary<string, object?> { ["from"] = t, ["to"] = t + 3 * MIN }, t + 4 * MIN);
Equal("takeOff says so when the engine lacks it", r.Error, "takeOff");
Equal("the state says so too", r.State.CanTakeOff, false);

Section("the doc survives a dump and a load");
var dump = host.Dump();
host.Load(dump);
r = host.Run("tick", now: t + 5 * MIN);
Check("the running block is still there", r.State.View.Active != null);
r = host.Run("nope", now: t + 5 * MIN);
Equal("an unknown op is an error, not a crash", r.Error, "op");

Section("park a thought");
r = host.Run("park", new Dictionary<string, object?> { ["text"] = "Reply to that email" }, t + 6 * MIN);
Same("it waits on the block", r.State.View.Active?.Parked ?? [], ["Reply to that email"]);
Equal("and in Later", r.State.View.Later.FirstOrDefault()?.Text, "Reply to that email");

Section("pause and stop");
host.Load(null);
t += 86_400_000;
host.Run("tick", now: t);
r = host.Run("start", new Dictionary<string, object?> { ["minutes"] = 25 }, t);
var end0 = r.State.View.Active!.End;
r = host.Run("pause", now: t + 2 * MIN);
Check("a pause holds the block", r.State.View.Active?.Paused == t + 2 * MIN);
Check("no pings while paused", r.State.Pings.Count == 0);
r = host.Run("resume", now: t + 4 * MIN);
Equal("the end moves by the pause", r.State.View.Active?.End, end0 + 2 * MIN);
r = host.Run("cancel", now: t + 5 * MIN);
Check("after ten seconds the block can't be taken back", r.Error == "cancel" && r.State.View.Active != null, r.Error ?? "");
r = host.Run("stop", now: t + 6 * MIN);
Check("Stop ends it, stopped", r.State.View.Awaiting is { Stopped: true } || r.State.History.FirstOrDefault()?.Stopped == true);

Section("breaks");
host.Load(null);
r = host.Run("startBreak", new Dictionary<string, object?> { ["minutes"] = 5 }, t);
Equal("a break of 5", r.State.View.OnBreak?.Minutes, 5);
Equal("its end ping", r.State.Pings.FirstOrDefault()?.Kind, "break-over");
r = host.Run("startBreak", new Dictionary<string, object?> { ["minutes"] = 20 }, t);
Equal("the engine only knows 5, 10 and 15 (the app keeps a longer end itself)", r.Error, "break");

Section("a year of blocks: the engine is quick enough to run on every press");
{
    // one real block, copied into 1500 days back, then loaded as a doc the way the app loads doc.json
    host.Load(null);
    var y0 = t;
    host.Run("tick", now: y0);
    var made = host.Run("start", new Dictionary<string, object?> { ["intention"] = "A year", ["minutes"] = 25 }, y0);
    host.Run("tick", now: y0 + 26 * MIN);
    host.Run("outcome", new Dictionary<string, object?> { ["id"] = made.Result?.GetProperty("id").GetString(), ["outcome"] = "done" }, y0 + 27 * MIN);
    var doc = System.Text.Json.Nodes.JsonNode.Parse(host.Dump())!.AsObject();
    var blocks = doc["blocks"]!.AsArray();
    var one = blocks[0]!.ToJsonString();
    blocks.Clear();
    for (var i = 1; i <= 1500; i++)
    {
        var b = System.Text.Json.Nodes.JsonNode.Parse(one)!.AsObject();
        var shift = i * 86_400_000L / 4;
        b["id"] = $"year{i:0000}x";
        foreach (var key in new[] { "start", "end", "ended", "u", "home" })
            if (b[key] is System.Text.Json.Nodes.JsonValue v && v.TryGetValue(out double at)) b[key] = at - shift;
        blocks.Add(b);
    }
    var big = doc.ToJsonString();
    var clock = System.Diagnostics.Stopwatch.StartNew();
    host.Load(big);
    var loaded = clock.ElapsedMilliseconds;
    clock.Restart();
    r = host.Run("tick", now: y0 + 30 * MIN);
    var tick = clock.ElapsedMilliseconds;
    clock.Restart();
    host.Run("start", new Dictionary<string, object?> { ["minutes"] = 25 }, y0 + 31 * MIN);
    var start = clock.ElapsedMilliseconds;
    Console.WriteLine($"INFO {r.State.History.Count} blocks in the state (of {blocks.Count}, {big.Length / 1024} KB): load {loaded} ms, tick {tick} ms, start {start} ms");
    Check("a year of blocks loads", r.State.History.Count >= 400, $"{r.State.History.Count}");
    Check("and an op on it takes under two seconds", tick < 2000 && start < 2000, $"tick {tick} ms, start {start} ms");
    // where an op's time goes, part by part, to know what to make faster
    var parts = host.Evaluate(@"(function () {
      const E = Kavana, doc = E.normalize(JSON.parse(KB.dump())), now = Date.now(), out = {};
      const time = (name, f) => { const t = Date.now(); for (let i = 0; i < 3; i++) f(); out[name] = Math.round((Date.now() - t) / 3); };
      time('history', () => E.history(doc));
      time('view', () => E.view(doc, now));
      time('pings', () => E.pings(doc, now));
      time('missions', () => E.missions(doc, now));
      time('achievements', () => E.achievements(doc));
      time('starter', () => E.starter(doc));
      time('monthly', () => E.monthly(doc, now));
      time('treats', () => E.treats(doc));
      time('tick', () => E.tick(doc, now));
      time('stringify', () => JSON.stringify(doc));
      return JSON.stringify(out);
    })()");
    Console.WriteLine($"INFO the engine's parts on {blocks.Count} blocks, ms each: {parts}");
    Check("the engine's parts can be timed", parts.StartsWith('{'), parts);
}

// ---------------------------------------------------------------- what the widgets and Today draw from the engine
Section("missions, rewards and the café's things");
host.Load(null);
var m0 = t + 500 * 86_400_000L;
r = host.Run("tick", now: m0);
Check("the day has its missions", r.State.Missions.Day.Count == 3, $"{r.State.Missions.Day.Count}");
Check("and the week one", r.State.Missions.Week.Count == 1);
Check("a mission says how far along it is", r.State.Missions.Day.All(m => m.Progress.Length > 0 && m.Pay > 0));
Check("the achievements are there, none done", r.State.Achievements.Count > 20 && r.State.Achievements.All(a => !a.Done));
Equal("nothing to collect yet", r.State.Collectable.Count, 0);
var first = host.Run("start", new Dictionary<string, object?> { ["intention"] = "First", ["minutes"] = 25, ["cat"] = "miso" }, m0);
host.Run("tick", now: m0 + 26 * MIN);
r = host.Run("outcome", new Dictionary<string, object?> { ["id"] = first.Result?.GetProperty("id").GetString(), ["outcome"] = "done" }, m0 + 27 * MIN);
Check("a first kitten home: First Kitten and the starter's first step wait to be collected",
    r.State.Collectable.Any(x => x.Id == "first") && r.State.Collectable.Any(x => x.Id == "starter-first"), string.Join(", ", r.State.Collectable.Select(x => x.Id)));
var before = r.State.Treats.Left;
r = host.Run("claim", new Dictionary<string, object?> { ["id"] = "first" }, m0 + 28 * MIN);
Check("collecting pays its treats", r.State.Treats.Left == before + 10 && r.State.Collectable.All(x => x.Id != "first"), $"{before} then {r.State.Treats.Left}");
Check("a block's total and bonus come with it", host.Run("start", new Dictionary<string, object?> { ["minutes"] = 25 }, m0 + 30 * MIN).State.View.Active is { Total: 25 * MIN, Bonus: 0 });
Equal("no café things owned yet", r.State.Things.Count, 0);

Section("Today's numbers (Insights)");
var day0 = new DateTime(2026, 10, 7, 0, 0, 0, DateTimeKind.Local);   // a Wednesday
var dayP = Insights.PeriodOf(Span.Day, day0.AddHours(15));
Equal("a day runs midnight to midnight", (dayP.From, dayP.To), (Format.Ms(day0), Format.Ms(day0.AddDays(1))));
var weekP = Insights.PeriodOf(Span.Week, day0.AddHours(15));
Equal("a week starts on Monday", weekP.Start, new DateTime(2026, 10, 5));
Equal("the month", Insights.PeriodOf(Span.Month, day0).Start, new DateTime(2026, 10, 1));
Equal("the month before", Insights.Before(Insights.PeriodOf(Span.Month, day0)).Start, new DateTime(2026, 9, 1));
Equal("the year", Insights.PeriodOf(Span.Year, day0).Start, new DateTime(2026, 1, 1));
Equal("a month has its days", Insights.SlotCount(Insights.PeriodOf(Span.Month, new DateTime(2026, 2, 3))), 28);
JsonElement HistJson(string id, DateTime start, int actual, string? tag, string kitten = "home", string? outcome = "done", int drifts = 0) =>
    JsonDocument.Parse(JsonSerializer.Serialize(new { id, start = Format.Ms(start), intention = id, minutes = actual, actual, tag, kitten, outcome, drifts, cat = "miso" })).RootElement;
var hs = new List<Hist>
{
    new(HistJson("a", day0.AddHours(9).AddMinutes(45), 30, "Write")),
    new(HistJson("b", day0.AddHours(14), 50, "Read", outcome: "partly", drifts: 2)),
    new(HistJson("c", day0.AddDays(1).AddHours(10), 25, null, kitten: "gone", outcome: null)),
};
var dayBlocks = Insights.In(hs, dayP);
Equal("the day's blocks", dayBlocks.Count, 2);
Equal("its minutes", Insights.Minutes(dayBlocks), 80);
Equal("cats home and wandered off", (Insights.Home(hs), Insights.Wandered(hs)), (2, 1));
Equal("done, partly, not really", Insights.Outcomes(hs), (1, 1, 0));
Equal("drifts", Insights.Drifts(hs), 2);
var hours = Insights.Slots(dayBlocks, dayP);
Equal("a block over the hour splits between the hours", (hours[9], hours[10]), (15, 15));
var weekSlots = Insights.Slots(hs, weekP);
Equal("the week's days, Monday first", (weekSlots[2], weekSlots[3]), (80, 25));
Same("by tag, biggest first, no tag is Untagged", Insights.ByTag(hs).Select(x => x.Tag), ["Read", "Write", "Untagged"]);
Equal("against the period before", Insights.Compare(130, 70), "+1 h");
Equal("less than before", Insights.Compare(30, 70), "−40 min");
Equal("nothing before: nothing to compare", Insights.Compare(30, 0), null);
Equal("days in a row, until today ends", Insights.Streak(hs, day0.AddDays(2)), 2);
Equal("a gap ends it", Insights.Streak(hs, day0.AddDays(4)), 0);
Equal("hours by name", (Insights.HourName(0), Insights.HourName(12), Insights.HourName(18)), ("12 AM", "12 PM", "6 PM"));
Equal("ordinals", (Insights.Ordinal(1), Insights.Ordinal(12), Insights.Ordinal(22), Insights.Ordinal(23)), ("1st", "12th", "22nd", "23rd"));

Section("the widgets' rules");
Check("every widget has its sizes", Widgets.All.All(k => k.Sizes.Length > 0) && Widgets.All.Select(k => k.Id).Distinct().Count() == Widgets.All.Length);
Equal("a medium widget's size", Widgets.SizeOf("medium"), (360.0, 172.0));
host.Load(null);
r = host.Run("start", new Dictionary<string, object?> { ["minutes"] = 25 }, m0);
Equal("a running block is focusing", Widgets.ModeOf(r.State.View.Active, null, null, true), WMode.Focusing);
r = host.Run("tick", now: m0 + 15 * MIN + 1000);
Equal("a check-in waiting", Widgets.ModeOf(r.State.View.Active, null, null, true), WMode.Checkin);
r = host.Run("pause", now: m0 + 16 * MIN);
Equal("paused", Widgets.ModeOf(r.State.View.Active, null, null, true), WMode.Paused);
Equal("nothing running: ready", Widgets.ModeOf(null, null, null, true), WMode.Ready);
Equal("a break", Widgets.ModeOf(null, null, new Brk(5, m0, m0 + 5 * MIN), true), WMode.Break);
Equal("the greeting", (Widgets.Greeting(8), Widgets.Greeting(13), Widgets.Greeting(19), Widgets.Greeting(23)), ("GOOD MORNING", "GOOD AFTERNOON", "GOOD EVENING", "NIGHT OWL"));
Equal("the cat's line at night", Widgets.CatLine(WMode.Ready, "Miso", 23), "Miso is asleep on the cushion. Shh.");
Equal("hours, as the widgets write them", (Widgets.Hours(130), Widgets.Hours(45)), ("2 h 10", "45 min"));
var days7 = Widgets.Days(hs, day0.AddDays(1), 7);
Equal("seven days, today last", (days7.Count, days7[^1].Date, days7[^2].Minutes), (7, day0.AddDays(1), 80));
Equal("a day's cat is its longest block's, and the hour it started", (days7[^2].Cat, days7[^2].Hour), ("miso", 14));
Same("this week's paws, Monday first", Widgets.WeekPaws(hs, day0.AddDays(1)), [false, false, true, false, false, false, false]);
Equal("this month's stars", Widgets.Stars(hs, day0.AddDays(3)).Count, 2);
Check("a star is in the sky, the same each time", Widgets.StarAt(5, 30, 14, 31) == Widgets.StarAt(5, 30, 14, 31)
    && Enumerable.Range(0, 31).All(d => Widgets.StarAt(d, 25 + d, d % 24, 31) is var (x, y) && x is > 0 and < 1 && y is > 0 and < 1));

Section("the account's client");
Equal("a session cookie is read", SyncClient.ReadCookie("kavana=abc123; Path=/; Max-Age=2592000; HttpOnly"), "abc123");
Equal("one that ends it is empty", SyncClient.ReadCookie("kavana=; Path=/; Max-Age=0"), "");
Equal("another cookie isn't ours", SyncClient.ReadCookie("other=1; Path=/"), null);
Equal("a wrong code, in words", SyncClient.Words(new ApiError(401, "bad code", null), "code"), "That code didn't work. Ask for a new one.");
Equal("too many tries", SyncClient.Words(new ApiError(429, null, null)), "Too many tries. Wait a few minutes.");
Equal("the server's own word, as a sentence", SyncClient.Words(new ApiError(400, "name taken", null)), "Name taken.");
Equal("an account from /api/me", Account.From(System.Text.Json.Nodes.JsonNode.Parse("{\"name\":\"u1\",\"email\":\"a@b.test\",\"hasPassword\":false}")),
    new Account("u1", null, "a@b.test", false));
Equal("an account from before email has a password", Account.From(System.Text.Json.Nodes.JsonNode.Parse("{\"name\":\"old\"}"))?.HasPassword, true);
Equal("its label is the email", new Account("u1", "Dana", "a@b.test").Label, "a@b.test");
Check("only a server on this PC is local", new SyncClient("http://127.0.0.1:8787/", new FileSession(Path.GetTempFileName())).IsLocal
    && !new SyncClient(WinSettings.WebApp, new FileSession(Path.GetTempFileName())).IsLocal);
var sessionFile = Path.Join(Path.GetTempPath(), $"chataltul-session-{Guid.NewGuid():N}");
var fs = new FileSession(sessionFile) { Cookie = "xyz" };
Equal("a test session is kept in its file", new FileSession(sessionFile).Cookie, "xyz");
fs.Cookie = null;
Check("and forgotten", !File.Exists(sessionFile));
Equal("the settings know the web app's server", new WinSettings().Server, "https://stuffs.tiresia.xyz/kavana/");

// ---------------------------------------------------------------- the reports (the Mac's tests)
Section("where it went");
var wlog = new TimingLog();
for (var i = 0; i < 41; i++) wlog.Add("b1", t, "writer.exe", "Writer", 60_000);
for (var i = 0; i < 4; i++) wlog.Add("b1", t, "msedge.exe", "Microsoft Edge", 60_000, ("youtube.com", "Microsoft Edge"));
for (var i = 0; i < 2; i++) wlog.Add("b1", t, "msedge.exe", "Microsoft Edge", 60_000, ("library.example", "Microsoft Edge"));
for (var i = 0; i < 3; i++) wlog.Add("b1", t, "olk.exe", "Outlook", 60_000);
Equal("a block's time adds up by app", wlog.Blocks["b1"].TotalMs, 50 * MIN);
Equal("and by site", wlog.Blocks["b1"].Sites["youtube.com"].Ms, 4 * MIN);
var wrows = Went.Rows(wlog.For(["b1", "nope"]), new Blocklist([new ListedApp("olk.exe", "Outlook")], ["youtube.com"]));
Equal("the biggest is first", wrows.FirstOrDefault()?.Name, "Writer");
Equal("the shares add up to the whole", (int)Math.Round(wrows.Sum(r => r.Share) * 100), 100);
Equal("a browser's line names its sites", wrows.FirstOrDefault(r => r.Name == "Microsoft Edge")?.Label, "Microsoft Edge: youtube.com, library.example");
Same("what is on a list is marked", wrows.Where(r => r.Distraction).Select(r => r.Name).Order(), ["Microsoft Edge", "Outlook"]);
Equal("a block with no record has no rows", Went.Rows([]).Count, 0);
var manyApps = new TimingLog();
foreach (var (mins, k) in new[] { 50, 40, 30, 20, 10, 5, 3, 2 }.Select((x, j) => (x, j))) manyApps.Add("m", t, $"a{k}.exe", $"App {k}", mins * MIN);
var folded = Went.Rows(manyApps.For(["m"]), limit: 4);
Equal("more than the limit folds the rest into one line, called Other", (folded.Count, folded[^1].Name), (4, "Other"));
Equal("a ready-made list marks a site too", Went.Rows(wlog.For(["b1"]), new Blocklist { Lists = [new GuardList { Categories = ["social"] }] },
    new FakeMatcher(new() { ["social"] = ["youtube.com"] })).First(r => r.Name == "Microsoft Edge").Distraction, true);
wlog.Prune(t + 1);
Check("old blocks are pruned", wlog.IsEmpty);
var tFolder = Path.Join(Path.GetTempPath(), $"chataltul-timing-{Guid.NewGuid():N}");
Directory.CreateDirectory(tFolder);
var tl = new TimingLog();
tl.Add("z", t, "x.exe", "X", 5000);
tl.Write(tFolder);
Equal("it survives the file", TimingLog.Read(tFolder).Blocks["z"].Apps["x.exe"].Ms, 5000L);
TimingLog.Erase(tFolder);
Check("and can be erased", TimingLog.Read(tFolder).IsEmpty);

Section("streaks with a pause");
Hist DayHist(int y, int mo, int d) => new(HistJson($"s{y}{mo}{d}", new DateTime(y, mo, d, 10, 0, 0, DateTimeKind.Local), 30, null));
// Monday 26 October 2026 is a Monday
var fri = new DateTime(2026, 10, 30, 12, 0, 0, DateTimeKind.Local);
var sun = new DateTime(2026, 11, 1, 12, 0, 0, DateTimeKind.Local);
var sk = Streaks.WithPause([DayHist(2026, 10, 26), DayHist(2026, 10, 27), DayHist(2026, 10, 28), DayHist(2026, 10, 29), DayHist(2026, 10, 30)], fri);
Equal("Monday to Friday is five in a row, with the week's pause still there", (sk.Days, sk.PausesLeft), (5, 1));
sk = Streaks.WithPause([DayHist(2026, 10, 26), DayHist(2026, 10, 27), DayHist(2026, 10, 28), DayHist(2026, 10, 29), DayHist(2026, 10, 30), DayHist(2026, 11, 1)], sun);
Equal("a missed Saturday is carried over by the week's pause", (sk.Days, sk.PausedDays.Count, sk.PausesLeft), (6, 1, 0));
Equal("which was Saturday", sk.PausedDays[0].DayOfWeek, DayOfWeek.Saturday);
Equal("only one pause a week: the second miss ends it", Streaks.WithPause([DayHist(2026, 10, 26), DayHist(2026, 10, 28), DayHist(2026, 10, 30)], fri).Days, 2);
Equal("two missed days in a row end it", Streaks.WithPause([DayHist(2026, 10, 26), DayHist(2026, 10, 27), DayHist(2026, 10, 30)], fri).Days, 1);
sk = Streaks.WithPause([DayHist(2026, 10, 28), DayHist(2026, 10, 29)], new DateTime(2026, 10, 30, 8, 0, 0, DateTimeKind.Local));
Equal("today isn't over: yesterday still counts, no pause used", (sk.Days, sk.PausesLeft), (2, 1));
sk = Streaks.WithPause([DayHist(2026, 10, 22), DayHist(2026, 10, 23), DayHist(2026, 10, 25), DayHist(2026, 10, 26)], new DateTime(2026, 10, 26, 12, 0, 0, DateTimeKind.Local));
Equal("a pause in last week is that week's, and this week's is still there", (sk.Days, sk.PausedDays.Count, sk.PausesLeft), (4, 1, 1));
Equal("no blocks, no streak", Streaks.WithPause([], fri).Days, 0);

Section("the week in words");
var wkPeriod = Insights.PeriodOf(Span.Week, new DateTime(2026, 10, 28, 12, 0, 0, DateTimeKind.Local));
Hist WH(DateTime d, int min, string? tag, int drifts = 0, string[]? whys = null, string? outcome = "done") =>
    new(JsonDocument.Parse(JsonSerializer.Serialize(new { id = $"w{Format.Ms(d)}", start = Format.Ms(d), actual = min, minutes = min, kitten = "home", drifts, whys = whys ?? Array.Empty<string>(), tag, outcome, cat = "miso", intention = "" })).RootElement);
DateTime D(int day, int hour) => new(2026, 10, day, hour, 0, 0, DateTimeKind.Local);
var weekBlocks = new List<Hist>
{
    WH(D(26, 10), 120, "Writing"), WH(D(27, 10), 150, "Writing"), WH(D(27, 16), 40, "Work", 1, ["web"]),
    WH(D(28, 16), 60, "Reading", 2, ["web", "phone"]), WH(D(29, 15), 60, "Writing", 1, ["web"], "partly"), WH(D(30, 11), 90, "Writing"),
};
var wf = Review.Facts(weekBlocks, wkPeriod, 120, new StreakInfo(5, [], 1));
Equal("the week's minutes", wf.Minutes, 520);
Equal("best on Tuesday", wf.BestDay?.Name, "Tuesday");
Equal("Writing took most", wf.TopTag, "Writing");
Equal("the web pulled most, 3 times", (wf.Reasons[0].Id, wf.Reasons[0].Times), ("web", 3));
Equal("the drifts were late in the day", wf.LateDrifts, true);
Equal("days that met the goal", wf.DaysMet, 2);
var said = Review.Summary(wf);
Check("the summary starts with a verdict and the numbers", said.StartsWith("An uneven week: 8 h 40 min over 6 blocks, best on Tuesday."), said);
Check("and names the tag", said.Contains("Writing took most of it."));
Check("and what pulled away, and when", said.Contains("usually the web, mostly in blocks that began after 3 PM"));
Check("and suggests the speed bump", said.Contains("might want the speed bump on"));
Check("a week with no blocks says so", Review.Summary(new WeekFacts()).StartsWith("No blocks yet"));
Check("a phone drift is out of reach", Review.Summary(Review.Facts([WH(D(26, 10), 60, null, 1, ["phone"])], wkPeriod, 0, wf.Streak)).Contains("out of Chataltul's reach"));
Equal("best day question", Review.Answer("which day was my best day?", wf), "Your best day was Tuesday, with 3 h 10 min.");
Equal("total question", Review.Answer("How much did I do?", wf), "8 h 40 min over 6 blocks.");
Equal("hours is the total, not the best hour", Review.Answer("How many hours did I focus?", wf), "8 h 40 min over 6 blocks.");
Check("when did I drift is about drifting", Review.Answer("When did I drift?", wf).StartsWith("What pulled you away"), Review.Answer("When did I drift?", wf));
Check("distraction question", Review.Answer("what distracted me", wf).StartsWith("What pulled you away: the web 3 times"), Review.Answer("what distracted me", wf));
Check("tag question", Review.Answer("what did I work on most", wf).Contains("Writing"));
Check("streak question", Review.Answer("what's my streak", wf).Contains("5 days in a row"));
Check("a question it can't answer says what it can", Review.Answer("why is the sky blue", wf).Contains("I can answer"));
var stacked = Insights.SlotsByTag(weekBlocks, wkPeriod);
Equal("stacked by tag: Tuesday has Writing and Work", (stacked[1].GetValueOrDefault("Writing"), stacked[1].GetValueOrDefault("Work")), (150, 40));
Equal("and each day adds up to the day", stacked[1].Values.Sum(), Insights.Slots(weekBlocks, wkPeriod)[1]);

Section("export");
Equal("a plain field is left alone", Export.Field("hello"), "hello");
Equal("a comma gets quotes", Export.Field("a, b"), "\"a, b\"");
Equal("a quote is doubled", Export.Field("say \"hi\""), "\"say \"\"hi\"\"\"");
var csvText = Export.BlocksCsv(weekBlocks, id => id == "miso" ? "Miso" : id);
Equal("a header and a row for each block", csvText.TrimEnd('\n').Split('\n').Length, 7);
Check("the header names the columns", csvText.StartsWith("start,minutes,title,tag,how it went,drifts,what pulled away,cat,the kitten,note\n"));
Check("a row has the time, the length and the tag", csvText.Contains("2026-10-27T10:00:00") && csvText.Contains(",150,Writing,Writing,done,0,,Miso,came home,"), csvText.Split('\n')[2]);
Check("the reasons are in words", csvText.Contains("The web; Phone"));
Equal("where it went as CSV", Export.WentCsv([new WentRow("Writer", 41 * MIN, 0.82, [], false)]), "app,minutes,share\nWriter,41,82%\n");
var md = Export.ReviewMarkdown("This week", "26 to 1 November", said, wf, [new WentRow("Writer", 41 * MIN, 0.82, [], false)]);
Check("the review as Markdown", md.StartsWith("# This week\n") && md.Contains("## What pulled you away") && md.Contains("Kept only on this PC"));
Check("remembering is on, and nothing runs at a block's start or end", new WinSettings() is { RecordTiming: true, RunAtStart: "", RunAtEnd: "" });

// ---------------------------------------------------------------- lists that do more than the speed bump (the Mac's tests)
Section("what a list asks for, and when");
var dist = new GuardList { Id = "d", Name = "Distractions", Apps = [new ListedApp("discord.exe", "Discord")], Sites = ["youtube.com"] };
var newsList = new GuardList { Id = "n", Name = "News", Sites = ["news.ycombinator.com", "bbc.co.uk"], Mode = GuardMode.WaitItOut, When = new GuardWhen { Kind = GuardWhen.Kinds.Weekdays }, AllowanceMin = 15 };
var chat = new GuardList { Id = "m", Name = "Messages", Apps = [new ListedApp("whatsapp.exe", "WhatsApp")], Mode = GuardMode.Locked, When = new GuardWhen { Kind = GuardWhen.Kinds.Always } };
var multi = new Blocklist { Lists = [dist, newsList, chat] };
Equal("a site is found in the list that has it", multi.Hit("https://www.bbc.co.uk/news", "Edge")?.List.Id, "n");
Equal("an app is found in its list", multi.Hit("whatsapp.exe")?.List.Id, "m");
Check("adding to a chosen list", multi.AddSite("lobste.rs", "n") && multi.Lists[1].Sites[^1] == "lobste.rs");
DateTime At(int y, int mo, int d, int h, int mi = 0) => new(y, mo, d, h, mi, 0, DateTimeKind.Local);
var mon10 = At(2026, 10, 26, 10);
var mon18 = At(2026, 10, 26, 18);
var sat10 = At(2026, 10, 31, 10);
var blockEnd = Format.Ms(At(2026, 10, 26, 10, 25));
Equal("a block: the list's own mode, until the block ends", (GuardPolicy.For(dist, mon10, blockEnd, 0)?.Mode, GuardPolicy.For(dist, mon10, blockEnd, 0)?.Until), ((GuardMode?)GuardMode.SpeedBump, (long?)blockEnd));
Equal("no block, 'during blocks' only: nothing", GuardPolicy.For(dist, mon10, null, 0), null);
Equal("a weekday inside 9 to 5, with an allowance left: free", GuardPolicy.For(newsList, mon10, null, 6 * MIN), null);
var usedUp = GuardPolicy.For(newsList, mon10, null, 15 * MIN);
Equal("allowance used up: it asks for the wait, says why, and until 5 PM", (usedUp?.Mode, usedUp?.Reason, usedUp?.Until),
    ((GuardMode?)GuardMode.WaitItOut, (Enforcement.Reasons?)Enforcement.Reasons.AllowanceUsed, (long?)Format.Ms(At(2026, 10, 26, 17))));
Equal("after 5 PM: nothing", GuardPolicy.For(newsList, mon18, null, 99 * MIN), null);
Equal("on Saturday: nothing", GuardPolicy.For(newsList, sat10, null, 99 * MIN), null);
Equal("always: locked outside a block too", GuardPolicy.For(chat, sat10, null, 0)?.Mode, GuardMode.Locked);
Same("the allowance counts only outside blocks, inside the window",
    new[] { GuardPolicy.CountsUsage(newsList, mon10, null, 0), GuardPolicy.CountsUsage(newsList, mon10, blockEnd, 0), GuardPolicy.CountsUsage(newsList, mon18, null, 0), GuardPolicy.CountsUsage(newsList, mon10, null, 15 * MIN) },
    [true, false, false, false]);
Check("a locked list locks the block; no locked list, no lock", GuardPolicy.LocksTheBlock(multi) && !GuardPolicy.LocksTheBlock(Blocklist.Starter()));

Section("a day's usage");
var usage = new DayUsage { Day = DayUsage.Key(At(2026, 10, 26, 0)) };
usage.Add("n", 6 * MIN, At(2026, 10, 26, 11));
Equal("it adds up", usage.Used("n", At(2026, 10, 26, 12)), 6 * MIN);
Equal("and starts again on a new day", usage.Used("n", At(2026, 10, 27, 9)), 0L);

Section("wait it out");
var mHit = new GuardHit(chat, new AppTarget(new ListedApp("whatsapp.exe", "WhatsApp")));
var waitEnf = new Enforcement(GuardMode.WaitItOut, Enforcement.Reasons.Block, "News", blockEnd);
var other = new OtherGuard();
Equal("nothing listed: nothing", other.Front(null, null, "notepad.exe", "miso", "Miso", t).Count, 0);
var oa = other.Front(mHit, waitEnf, "whatsapp.exe", "miso", "Miso", t);
if (oa.FirstOrDefault() is not OtherAction.Show { Card: var wcard }) { Console.WriteLine("FAIL the wait card didn't come"); return 1; }
Equal("a listed app brings the wait, 30 seconds long", (wcard.Kind, wcard.WaitMs), (OtherKind.WaitItOut, 30_000L));
Equal("it counts down", wcard.Line(t + 7_000), "WhatsApp opens in 23 seconds. Most urges pass by then.");
Equal("not over at 29 seconds", other.Tick(t + 29_000).Count, 0);
Same("over at 30: the card goes", other.Tick(t + 30_000), [new OtherAction.Hide()]);
Equal("and the visit may stay", other.Front(mHit, waitEnf, "whatsapp.exe", "miso", "Miso", t + 31_000).Count, 0);
other.Front(null, null, "notepad.exe", "miso", "Miso", t + 40_000);
Equal("leaving for 2 seconds and coming back is the same visit", other.Front(mHit, waitEnf, "whatsapp.exe", "miso", "Miso", t + 42_000).Count, 0);
other.Front(null, null, "notepad.exe", "miso", "Miso", t + 50_000);
Check("leaving for 10 seconds, it waits again, every time", other.Front(mHit, waitEnf, "whatsapp.exe", "miso", "Miso", t + 60_000) is [OtherAction.Show]);
Same("leaving during the wait takes the card away", other.Front(null, null, "notepad.exe", "miso", "Miso", t + 65_000), [new OtherAction.Hide()]);
Equal("Back to work with no card does nothing", other.BackToWork().Count, 0);
other = new OtherGuard();
other.Front(null, null, "notepad.exe", "miso", "Miso", t);
other.Front(mHit, waitEnf, "whatsapp.exe", "miso", "Miso", t + 1000);
Same("back to work goes to where they were", other.BackToWork(), [new OtherAction.Hide(), new OtherAction.Activate("notepad.exe")]);
Equal("and the same app in front doesn't bring it straight back", other.Front(mHit, waitEnf, "whatsapp.exe", "miso", "Miso", t + 2000).Count, 0);

Section("locked, and used up");
other = new OtherGuard();
oa = other.Front(mHit, new Enforcement(GuardMode.Locked, Enforcement.Reasons.Block, "Messages", blockEnd), "whatsapp.exe", "miso", "Miso", t);
if (oa.FirstOrDefault() is OtherAction.Show { Card: var lcard })
{
    Equal("a locked app brings the lock", lcard.Kind, OtherKind.Locked);
    Equal("it says until when", lcard.Title, $"Locked until {Format.ClockTime(blockEnd)}.");
}
else Check("the lock card", false);
Equal("and it never opens by itself", other.Tick(t + 10 * MIN).Count, 0);
other = new OtherGuard();
oa = other.Front(new GuardHit(newsList, new SiteTarget("bbc.co.uk", "Edge")), new Enforcement(GuardMode.WaitItOut, Enforcement.Reasons.AllowanceUsed, "News", Format.Ms(At(2026, 10, 26, 17))), "msedge.exe", "miso", "Miso", t);
Check("used up says so", oa.FirstOrDefault() is OtherAction.Show { Card.Kind: OtherKind.UsedUp });
Same("a speed bump isn't this guard's", other.Front(new GuardHit(dist, new SiteTarget("youtube.com", "Chrome")), new Enforcement(GuardMode.SpeedBump, Enforcement.Reasons.Block, "Distractions", blockEnd), "chrome.exe", "miso", "Miso", t + 1000), [new OtherAction.Hide()]);

Section("sounds");
var sounds = SoundCatalog.Load(Path.Join(repo, "mac", "Chataltul", "Resources", "sounds.json"));
Check("there are sounds", sounds.Kinds.Count > 30, $"{sounds.Kinds.Count}");
Equal("seven groups", sounds.Groups.Count, 7);
foreach (var g in sounds.Groups) Check($"a free sound in {g.Name}", sounds.In(g.Id).Any(k => k.Free));
host.Load(null);
var prices = host.Run("tick", now: t).State.Prices;
foreach (var k in sounds.Kinds.Where(s => !s.Free)) Check($"{k.Name} has a price in the shop", prices.GetValueOrDefault($"sound-{k.Id}") > 0);
foreach (var k in sounds.Kinds.Where(s => s.Id is not "brown" and not "white")) Check($"{k.Id} has a recording", k.File != null);
Equal("three meows", sounds.Meows.Count, 3);
Same("a third sound takes the oldest one's place", SoundCatalog.Toggle("fire", [new MixEntry("rain"), new MixEntry("purr")]).Select(m => m.Id), ["purr", "fire"]);
Same("playing needs owning", sounds.Playable([new MixEntry("rain"), new MixEntry("fire")], []).Select(m => m.Id), ["rain"]);
Same("a bought one plays", sounds.Playable([new MixEntry("fire")], ["sound-fire"]).Select(m => m.Id), ["fire"]);

Section("breaks");
Equal("Pomodoro is 25 and 5", Cycles.Minutes(CyclePreset.Pomodoro), (25, 5));
Equal("52 / 17", Cycles.Minutes(CyclePreset.FiftyTwo), (52, 17));
Equal("Deep work is 90 and 20", Cycles.Minutes(CyclePreset.Deep), (90, 20));
Check("Flowtime is a stopwatch", Cycles.Stopwatch(CyclePreset.Flow) && Cycles.Minutes(CyclePreset.Flow) == null);
Same("a flow break is a fifth of the stretch, 5 to 25", new[] { Cycles.FlowBreak(20), Cycles.FlowBreak(70), Cycles.FlowBreak(180) }, [5, 14, 25]);
var bs = new BreakSettings();
Same("the ideas are the mockup's", bs.Ideas.Take(3), ["Stand up and stretch", "Get some water", "Look out of a window"]);
Equal("an idea and others to turn to", (bs.IdeaFor(0).Main, bs.IdeaFor(0).Others[0]), ("Stand up and stretch", "Get some water"));
Equal("ideas wrap round", bs.IdeaFor(6).Main, "Stand up and stretch");
Equal("no ideas still says something", new BreakSettings { Ideas = [] }.IdeaFor(0).Main, "Take a breath.");
Check("the break settings survive the settings file", JsonSerializer.Deserialize<WinSettings>(new WinSettings { Breaks = { Strict = true, Start = BreakStart.Auto } }.ToJson(), J.Files)?.Breaks is { Strict: true, Start: BreakStart.Auto });

Section("eye breaks");
var eye = new EyeBreakTimer(20, 20);
Equal("none before a block", eye.Check(t, true, false), false);
eye.BlockStarted(t);
Equal("none in the first 19 minutes", eye.Check(t + 19 * MIN, true, false), false);
Equal("it waits while a call is on", eye.Check(t + 20 * MIN, true, true), false);
Equal("and comes when the call is over", eye.Check(t + 22 * MIN, true, false), true);
Equal("once", eye.Check(t + 22 * MIN + 1000, true, false), false);
eye.Finish(t + 22 * MIN + 20_000);
Equal("the next is a full 20 minutes later", eye.NextAt, t + 22 * MIN + 20_000 + 20 * MIN);
Equal("a pause is no time on the block", eye.Check(t + 50 * MIN, false, false), false);
Equal("away for 30 seconds counts as one", eye.AwayFor(30_000, t + 60 * MIN), true);
Equal("away for 5 seconds doesn't", eye.AwayFor(5_000, t + 61 * MIN), false);
Equal("so the next one moved", eye.NextAt, t + 80 * MIN);
eye.BlockEnded();
Check("no block, no eye breaks", eye.NextAt == null && !eye.Check(t + 200 * MIN, true, false));

// ---------------------------------------------------------------- the ready-made lists (the Mac's tests, check for check)
Section("lists: reading what the web publishes");
var hostsText = string.Join("\n",
    "# Title: a hosts file", "127.0.0.1 localhost", "127.0.0.1 localhost.localdomain", "::1 ip6-localhost", "0.0.0.0 0.0.0.0",
    "0.0.0.0 Tracker.Example.com   # a comment", "0.0.0.0 www.cats.example", "127.0.0.1\tdog.example   alias.example", "0.0.0.0 *.wild.example",
    "255.255.255.255 broadcasthost", "fe80::1%lo0 localhost", "10.0.0.1 10.0.0.2");
Same("a hosts file gives names only, lowercase, without www", ListParser.Domains(hostsText), ["tracker.example.com", "cats.example", "dog.example"]);
var plainText = "cats.example\r\nDogs.Example.org\n\n  spaced.example  \n# no\nlocalhost\nexample\n1.2.3.4\nhttp://x.example/path\nunder_score.example\nlast.example";
Same("a plain list: one name a line, however it ends", ListParser.Domains(plainText), ["cats.example", "dogs.example.org", "spaced.example", "under_score.example", "last.example"]);
var abpText = string.Join("\n",
    "[Adblock Plus 2.0]", "! Title: x", "||ads.example^", "||Social.example^$important", "||all.example^$all", "||third.example^$third-party",
    "||path.example/ads^", "||wild*.example^", "@@||ok.example^", "example.org##.banner", "example.net#@#.banner", "||www.news.example^", "||port.example:8080^");
Same("AdGuard rules: whole domains only", ListParser.Domains(abpText), ["ads.example", "social.example", "all.example", "news.example"]);
var webPage = ListParser.Parse("  <!DOCTYPE html><html><body>Not found</body></html>"u8);
Check("a web page isn't a list", webPage.WebPage && webPage.Hashes.Length == 0);
Equal("a byte-order mark is ignored", ListParser.Parse(new byte[] { 0xEF, 0xBB, 0xBF }.Concat("cats.example\n"u8.ToArray()).ToArray()).Hashes.Length, 1);
Equal("an empty answer has no names", ListParser.Parse([]).Hashes.Length, 0);
Check("the hashes are those of the names", ListParser.Parse(System.Text.Encoding.UTF8.GetBytes(hostsText)).Hashes.ToHashSet()
    .SetEquals(new[] { "tracker.example.com", "cats.example", "dog.example" }.Select(DomainSet.Hash)));

Section("lists: a set of domains");
Equal("a name hashes the same in any case", DomainSet.Hash("TikTok.COM"), DomainSet.Hash("tiktok.com"));
Equal("and the hash never changes (the copies on disk depend on it, and the Mac's are the same)", DomainSet.Hash("example.com"), 10777630962659677593UL);
var ds = DomainSet.Of(["tiktok.com", "bad.example.org", "tiktok.com", "ONE.example"]);
Equal("repeats are dropped", ds.Count, 3);
Equal("a listed domain is found", ds.Contains("tiktok.com"), true);
Equal("a subdomain finds it", ds.FirstSuffix("m.l.tiktok.com"), "tiktok.com");
Equal("the host itself", ds.FirstSuffix("tiktok.com"), "tiktok.com");
Equal("a name that only ends the same isn't it", ds.FirstSuffix("nottiktok.com"), null);
Equal("a name below it isn't it, either way round", ds.FirstSuffix("tiktok.com.evil.net"), null);
Equal("a parent of a listed name isn't listed", ds.FirstSuffix("example.org"), null);
Equal("a listed deeper name", ds.FirstSuffix("x.bad.example.org"), "bad.example.org");
Equal("the broadest listed name is the one given", DomainSet.Of(["a.tiktok.com", "tiktok.com"]).FirstSuffix("b.a.tiktok.com"), "tiktok.com");
Equal("one name alone never matches", DomainSet.Of(["com"]).FirstSuffix("com"), null);
Equal("nor does a host with no dots", ds.FirstSuffix("localhost"), null);
Equal("an empty host doesn't crash", ds.FirstSuffix(""), null);
Equal("it survives the disk", DomainSet.FromSerialized(ds.Serialized()), ds);
Equal("a cut-short copy is refused", DomainSet.FromSerialized(ds.Serialized()[..^3]), null);
Equal("so is something else", DomainSet.FromSerialized("hello world, not a list"u8), null);
var swapped = ds.Serialized();
swapped[16..24].CopyTo(swapped, 8);
Equal("and one that isn't in order", DomainSet.FromSerialized(swapped), null);
Equal("an empty set round-trips", DomainSet.FromSerialized(new DomainSet().Serialized()), new DomainSet());

Section("lists: the catalog");
var catalog = Catalog.All;
Equal("category ids are their own", catalog.Select(c => c.Id).Distinct().Count(), catalog.Count);
var allSources = catalog.SelectMany(c => c.Sources).ToList();
Equal("source ids are their own", allSources.Select(s => s.Id).Distinct().Count(), allSources.Count);
Check("every category has a list behind it", catalog.All(c => c.Sources.Count > 0));
Check("every address is https", allSources.All(s => s.Url.StartsWith("https://")));
Check("every list names its licence and who made it", allSources.All(s => s.License.Length > 0 && s.Credit.Length > 0));
Check("an archive names the file in it", allSources.All(s => s.Member == null || s.Url.EndsWith(".tar.gz")));
Check("the kinds people asked for are there", new[] { "adult", "social", "dating", "gambling" }.All(id => Catalog.Category(id) != null));
var mine = Catalog.Custom(" Work stuff ", " https://lists.example.net/path/hosts.txt ");
Same("a list added by its link", new[] { mine?.Name, mine?.Sources[0].Url, mine?.Custom == true ? "custom" : "no" }, ["Work stuff", "https://lists.example.net/path/hosts.txt", "custom"]);
Equal("with no name it is called by its host", Catalog.Custom("", "https://lists.example.net/a.txt")?.Name, "lists.example.net");
Check("http isn't taken, nor a file, a word or a password", Catalog.Custom("x", "http://lists.example.net/a.txt") == null && Catalog.Custom("x", "file:///etc/hosts") == null
    && Catalog.Custom("x", "lists") == null && Catalog.Custom("x", "https://me:pw@lists.example.net/a.txt") == null);
Equal("a custom list is found among the person's own", Catalog.Category(mine!.Id, [mine])?.Name, "Work stuff");

Section("lists: on a list");
var fakeMatcher = new FakeMatcher(new() { ["social"] = ["tiktok.com", "linkedin.com", "reddit.com"], ["dating"] = ["match.example"] });
var soc = new GuardList { Id = "s", Name = "Social", Categories = ["social"], Allow = ["linkedin.com"] };
Equal("without the lists, a category finds nothing", soc.Site("https://m.tiktok.com/"), null);
Equal("with them, a subdomain is found", soc.Site("https://m.tiktok.com/", fakeMatcher), "tiktok.com");
Equal("and shown by the listed name", new Blocklist { Lists = [soc] }.Hit("https://www.reddit.com/r/cats", "Edge", fakeMatcher)?.Target, new SiteTarget("reddit.com", "Edge"));
Equal("an allowed site isn't matched", soc.Site("https://www.linkedin.com/in/me", fakeMatcher), null);
Equal("nor its subdomains", soc.Site("https://jobs.linkedin.com/", fakeMatcher), null);
Equal("only the categories switched on count", soc.Site("https://match.example/", fakeMatcher), null);
Equal("a typed site still counts", new GuardList { Sites = ["linkedin.com"], Categories = ["social"], Allow = ["linkedin.com"] }.Site("https://linkedin.com/", fakeMatcher), "linkedin.com");
var two = new Blocklist { Lists = [soc, new GuardList { Id = "t", Name = "Typed", Sites = ["tiktok.com"] }] };
Equal("a site typed into one list isn't taken by another list's category", two.Hit("https://tiktok.com/", "Chrome", fakeMatcher)?.List.Id, "t");
Equal("a ready-made list brings the card for a site that is only in it", two.Hit("https://reddit.com/", "Chrome", fakeMatcher)?.List.Id, "s");
Equal("and nothing for one that is in none", two.Hit("https://example.org/", "Chrome", fakeMatcher), null);
Check("a list with only categories isn't empty, and watches sites", !new Blocklist { Lists = [soc] }.IsEmpty && new Blocklist { Lists = [soc] }.WatchesSites);
Check("a list with nothing isn't watching", new Blocklist { Lists = [new GuardList()] }.IsEmpty && !new Blocklist { Lists = [new GuardList()] }.WatchesSites);
Same("the lists switched on are counted once each", new Blocklist { Lists = [soc, new GuardList { Categories = ["social", "dating"] }] }.CategoryIds, ["social", "dating"]);

Section("lists: downloading, with a fake web");
var fakeWeb = new FakeFetcher();
var clockMs = 1_800_000_000_000L;
var listDir = Path.Join(Path.GetTempPath(), $"chataltul-lists-{Guid.NewGuid():N}");
var mineCat = Catalog.Custom("Mine", "https://lists.example.net/mine.txt")!;
const string url1 = "https://lists.example.net/mine.txt";
static string Names(int n) => string.Join("\n", Enumerable.Range(1, n).Select(i => $"site{i}.example")) + "\n";
static byte[] Text(string s) => System.Text.Encoding.UTF8.GetBytes(s);
var cs = new CategoryStore(listDir, fakeWeb, clock: () => clockMs) { Custom = [mineCat] };
var changes = 0;
cs.Changed += () => changes++;
Equal("before any download, it is not here", cs.State(mineCat.Id), new CategoryState.None(0));
Equal("an unknown list is not here either", cs.State("nope"), new CategoryState.None(0));
Equal("and nothing matches", cs.Match("site1.example", [mineCat.Id]), null);
fakeWeb.Answers[url1] = new Fetched.Body(Text(Names(6)), "\"v1\"", "Mon, 01 Jun 2026 00:00:00 GMT");
await cs.Refresh([mineCat.Id]);
Equal("it is here with its six sites", cs.State(mineCat.Id), new CategoryState.Ready(6, clockMs));
Equal("a listed site is found", cs.Match("site3.example", [mineCat.Id]), "site3.example");
Equal("a subdomain too", cs.Match("a.b.site3.example", [mineCat.Id]), "site3.example");
Equal("another isn't", cs.Match("other.example", [mineCat.Id]), null);
Equal("only for the categories asked", cs.Match("site3.example", ["dating"]), null);
Check("the app was told", changes >= 2, $"{changes}");
Equal("one request went out, with no etag to offer", (fakeWeb.Calls.Count, fakeWeb.Calls[0].Etag), (1, (string?)null));
await cs.Refresh([mineCat.Id]);
Equal("asking again right away doesn't go to the web", fakeWeb.Calls.Count, 1);
var cs2 = new CategoryStore(listDir, fakeWeb, clock: () => clockMs) { Custom = [mineCat] };
Equal("it is still here after a restart", cs2.State(mineCat.Id), new CategoryState.Ready(6, clockMs));
Equal("and still matches", cs2.Match("site5.example", [mineCat.Id]), "site5.example");
var onDisk = File.ReadAllBytes(Path.Join(listDir, mineCat.Sources[0].Id + ".chd"));
Check("the copy on disk has no names in it", onDisk.Length > 0 && onDisk.AsSpan().IndexOf("site1"u8) < 0);
clockMs += 8 * 86_400_000L;
fakeWeb.Answers[url1] = new Fetched.NotModified();
await cs.Refresh([mineCat.Id]);
Equal("after a week it asks again, with the etag and the date it was given", (fakeWeb.Calls.Count, fakeWeb.Calls[1].Etag, fakeWeb.Calls[1].Modified), (2, "\"v1\"", "Mon, 01 Jun 2026 00:00:00 GMT"));
Equal("not modified: the copy stays as it was", cs.State(mineCat.Id), new CategoryState.Ready(6, clockMs - 8 * 86_400_000L));
Equal("and is checked today", cs.StatusOf(mineCat.Sources[0].Id)?.Checked, clockMs);
fakeWeb.Answers[url1] = new Fetched.Body(Text(Names(7)), "\"v2\"", null);
await cs.Refresh([mineCat.Id], force: true);
Equal("forced, it takes the new one", cs.State(mineCat.Id), new CategoryState.Ready(7, clockMs));
fakeWeb.Answers[url1] = new Fetched.Failed("Server said 500");
clockMs += 1000;
await cs.Refresh([mineCat.Id], force: true);
Equal("a failure says so and keeps what it had", cs.State(mineCat.Id), new CategoryState.Failed("Server said 500", 7));
Equal("which still matches", cs.Match("site7.example", [mineCat.Id]), "site7.example");
var callsBefore = fakeWeb.Calls.Count;
await cs.Refresh([mineCat.Id]);
Equal("a failed list isn't tried again at once", fakeWeb.Calls.Count, callsBefore);
clockMs += 3_600_000;
fakeWeb.Answers[url1] = new Fetched.Body(Text("<html><body>Sorry</body></html>"), null, null);
await cs.Refresh([mineCat.Id]);
Check("after an hour it tries again", fakeWeb.Calls.Count == callsBefore + 1);
Equal("a web page in place of the list is refused", cs.State(mineCat.Id), new CategoryState.Failed("Not a list of sites", 7));
var ut1 = Catalog.Category("dating")!.Sources[0];
using (var tgz = new MemoryStream())
{
    using (var gz = new System.IO.Compression.GZipStream(tgz, System.IO.Compression.CompressionLevel.Fastest, leaveOpen: true))
    using (var tw = new System.Formats.Tar.TarWriter(gz))
    {
        var e = new System.Formats.Tar.PaxTarEntry(System.Formats.Tar.TarEntryType.RegularFile, "dating/domains") { DataStream = new MemoryStream(Text(Names(1200))) };
        tw.WriteEntry(e);
    }
    var built = CategoryStore.Build(ut1, tgz.ToArray());
    Equal("a UT1 archive gives its list, read in memory", built.Set?.Count, 1200);
}
Equal("fewer sites than the list's floor are refused", CategoryStore.Build(ut1 with { Member = null }, Text(Names(10))).Why, "Too few sites");
try { Directory.Delete(listDir, true); } catch (Exception) { }

// ---------------------------------------------------------------- the engine change we ask for, on a copy
Section("E.takeOff, with mac/docs/engine-takeoff.patch applied to a copy of the engine");
var patched = Path.Join(Path.GetTempPath(), $"chataltul-takeoff-{Guid.NewGuid():N}");
Directory.CreateDirectory(patched);
foreach (var f in Directory.GetFiles(js)) File.Copy(f, Path.Join(patched, Path.GetFileName(f)));
var patch = File.ReadAllLines(Path.Join(repo, "mac", "docs", "engine-takeoff.patch"));
var added = string.Join("\n", patch.Where(l => l.StartsWith('+') && !l.StartsWith("+++")).Select(l => l[1..])) + "\n";
var engine = File.ReadAllText(Path.Join(patched, "engine.js"));
const string Anchor = "E.extend = (doc, id, more, now) => {";
Check("the patch still applies to the synced engine", engine.Contains(Anchor) && !engine.Contains("E.takeOff"));
File.WriteAllText(Path.Join(patched, "engine.js"), engine.Replace(Anchor, added + Anchor));
try
{
    var h2 = new EngineHost(patched);
    h2.Load(null);
    h2.Run("tick", now: t);
    r = h2.Run("start", new Dictionary<string, object?> { ["intention"] = "Take off", ["minutes"] = 30 }, t);
    var endBefore = r.State.View.Active!.End;
    var checkinBefore = r.State.Pings.First(p => p.Kind == "checkin").At;
    Equal("with it, the state says so", r.State.CanTakeOff, true);
    r = h2.Run("takeOff", new Dictionary<string, object?> { ["from"] = t + 5 * MIN, ["to"] = t + 8 * MIN }, t + 9 * MIN);
    Equal("takeOff moves the end later by the time away", r.State.View.Active?.End, endBefore + 3 * MIN);
    Equal("and the op says where it is now", r.Result?.GetProperty("end").GetInt64(), endBefore + 3 * MIN);
    Equal("the check-ins still to come move with it", r.State.Pings.First(p => p.Kind == "checkin").At, checkinBefore + 3 * MIN);
    Equal("the kitten stays", r.State.View.Active?.Kitten, "growing");
    r = h2.Run("takeOff", new Dictionary<string, object?> { ["from"] = t - 5 * MIN, ["to"] = t + 2 * MIN }, t + 10 * MIN);
    Equal("a gap from before the block is refused", r.Error, "takeOff");
    r = h2.Run("takeOff", new Dictionary<string, object?> { ["from"] = t + 10 * MIN, ["to"] = t + 20 * MIN }, t + 12 * MIN);
    Equal("a gap that hasn't ended yet is refused", r.Error, "takeOff");
    h2.Run("pause", now: t + 12 * MIN);
    r = h2.Run("takeOff", new Dictionary<string, object?> { ["from"] = t + 10 * MIN, ["to"] = t + 11 * MIN }, t + 12 * MIN + 1000);
    Equal("a paused block is refused", r.Error, "takeOff");
}
catch (Exception e) { Check("the patched engine runs", false, e.Message); }
finally { try { Directory.Delete(patched, true); } catch (Exception) { } }

// ---------------------------------------------------------------- the blocklist
Section("blocklist");
var list = new Blocklist([new ListedApp(@"C:\Program Files\Discord\Discord.exe", "Discord")], ["youtube.com", "news.ycombinator.com"]);
Equal("a listed domain matches", list.Site("https://youtube.com/watch?v=1"), "youtube.com");
Equal("www is ignored", list.Site("https://www.youtube.com/"), "youtube.com");
Equal("subdomains are covered", list.Site("https://m.youtube.com/feed"), "youtube.com");
Equal("as Chrome shows it, with no scheme", list.Site("youtube.com/watch?v=1"), "youtube.com");
Equal("another site isn't", list.Site("https://notyoutube.com/"), null);
Equal("a look-alike suffix isn't", list.Site("https://evilyoutube.com/"), null);
Equal("a path that mentions it isn't", list.Site("https://example.com/youtube.com"), null);
Equal("a host with a port", list.Site("http://news.ycombinator.com:80/item?id=1"), "news.ycombinator.com");
Equal("typed with no scheme", Blocklist.NormalizeDomain("Reddit.com/r/cats"), "reddit.com");
Equal("typed with www", Blocklist.NormalizeDomain("https://www.x.com/home"), "x.com");
Equal("not a domain", Blocklist.NormalizeDomain("cats"), null);
Equal("an app by its program file, in any case", list.App("DISCORD.EXE")?.Name, "Discord");
Equal("the path is left out", list.Apps.First().Exe, "discord.exe");
Check("an app is hit", list.Hit("discord.exe")?.Target is AppTarget { App.Name: "Discord" });
Check("a site is hit with its browser", list.Hit("https://www.youtube.com/x", "Edge")?.Target == new SiteTarget("youtube.com", "Edge"));
Check("adding a site twice adds it once", list.AddSite("reddit.com") && !list.AddSite("www.reddit.com"));
Check("adding an app twice adds it once", list.AddApp(new ListedApp("slack.exe", "Slack")) && !list.AddApp(new ListedApp("Slack.exe", "Slack")));
Check("a site comes off the list", list.RemoveSite("reddit.com") && list.Site("https://reddit.com/") == null);
Equal("the starter list has sites", Blocklist.Starter().Sites.Contains("youtube.com"), true);
Equal("and no apps", Blocklist.Starter().Apps.Count(), 0);
Equal("Firefox can be read on Windows", Browser.With("firefox.exe")?.Gecko, true);
Equal("Edge is a browser", Browser.With(@"C:\x\MSEDGE.EXE")?.Name, "Edge");
Equal("Word isn't", Browser.With("winword.exe"), null);
Check("an address box with an address", Browser.LooksLikeAddress("youtube.com/watch?v=1") && Browser.LooksLikeAddress("https://localhost:3000/") && Browser.LooksLikeAddress("127.0.0.1:8080/x"));
Check("a search being typed isn't one", !Browser.LooksLikeAddress("cat videos") && !Browser.LooksLikeAddress("youtu") && !Browser.LooksLikeAddress(""));

// ---------------------------------------------------------------- the speed bump
Section("speed bump");
var msg = new BumpBlock("b1", false, "growing", "pepper", "Milo", "Draft the newsletter", t + 25 * MIN);
Target youtube = new SiteTarget("youtube.com", "Chrome");
Target discord = new AppTarget(new ListedApp("discord.exe", "Discord"));
var bump = new SpeedBump();
var acts = bump.Front(null, "writer.exe", msg, t);
Equal("nothing on the list: nothing happens", acts.Count, 0);
Equal("where they were is remembered", bump.Previous, "writer.exe");
Equal("no block running: no card", bump.Front(youtube, null, null, t).Count, 0);
Equal("a paused block: no card", bump.Front(youtube, null, new BumpBlock("b1", Paused: true), t).Count, 0);
Equal("a kitten that already left: no card", bump.Front(youtube, null, new BumpBlock("b1", Kitten: "gone"), t).Count, 0);
acts = bump.Front(youtube, null, msg, t);
if (acts.FirstOrDefault() is not BumpAction.Show { Card: var card }) { Console.WriteLine("FAIL the card didn't come up"); return 1; }
Equal("a listed site brings the card", acts.Count, 1);
Equal("the card uses the cat's own name", card.CatName, "Milo");
Equal("the line", card.Line(t, 15_000), "Come back within 15 seconds, or Milo wanders off.");
Equal("and it counts down", card.Line(t + 4_200, 15_000), "Come back within 11 seconds, or Milo wanders off.");
Equal("the last second is singular", card.Line(t + 14_500, 15_000), "Come back within 1 second, or Milo wanders off.");
Equal("the ring starts full", card.Fraction(t, 15_000), 1.0);
Equal("and ends empty", card.Fraction(t + 15_000, 15_000), 0.0);
Equal("still the same card on the next look", bump.Front(youtube, null, msg, t + 1000).Count, 0);
Equal("nothing at 14 seconds", bump.Tick(msg, t + 14_999).Count, 0);
acts = bump.Front(discord, null, msg, t + 5000);
Check("another listed target keeps the same card and countdown", acts is [BumpAction.Update { Card.ShownAt: var shownAt }] && shownAt == t);
acts = bump.Tick(msg, t + 15_000);
Same("at 15 seconds the kitten wanders off", acts, [new BumpAction.Hide(), new BumpAction.Away(t, 15_000), new BumpAction.Wandered("Milo")]);
Equal("and the card is gone", bump.Card == null, true);
Equal("it doesn't come up again for that block", bump.Front(youtube, null, msg, t + 20_000).Count, 0);
bump.Front(null, "writer.exe", msg, t + 21_000);
Equal("not even after leaving and coming back", bump.Front(youtube, null, msg, t + 22_000).Count, 0);
var next = new BumpBlock("b2", false, "growing", "miso", "Miso", "Next", t + 90 * MIN);
Check("a new block gets its card again", bump.Front(youtube, null, next, t + 30_000).FirstOrDefault() is BumpAction.Show);

Section("speed bump: back to work");
bump = new SpeedBump();
bump.Front(null, "writer.exe", msg, t);
bump.Front(discord, null, msg, t + 1000);
Same("Back to work goes to the app they were in", bump.BackToWork(), [new BumpAction.Hide(), new BumpAction.Activate("writer.exe")]);
Equal("it doesn't bump again while that target stays in front", bump.Front(discord, null, msg, t + 2000).Count, 0);
bump.Front(null, "writer.exe", msg, t + 3000);
Check("it does after they left it and came back", bump.Front(discord, null, msg, t + 4000).FirstOrDefault() is BumpAction.Show);
Same("leaving the listed app takes the card away", bump.Front(null, "writer.exe", msg, t + 5000), [new BumpAction.Hide()]);
Equal("Back to work with no card does nothing", bump.BackToWork().Count, 0);

Section("speed bump: leave anyway");
bump = new SpeedBump();
bump.Front(youtube, null, msg, t);
Same("held Leave anyway: the kitten goes right away", bump.LeaveHeld(t + 7000), [new BumpAction.Hide(), new BumpAction.Away(t + 7000, 0), new BumpAction.Wandered("Milo")]);
Equal("and it stays quiet for the block", bump.Front(youtube, null, msg, t + 9000).Count, 0);
bump = new SpeedBump();
bump.Front(youtube, null, msg, t);
Same("a pause takes the card away without costing the kitten", bump.Tick(new BumpBlock("b1", Paused: true), t + 3000), [new BumpAction.Hide()]);
bump = new SpeedBump();
bump.Front(youtube, null, msg, t);
Same("the block ending takes the card away", bump.Tick(null, t + 3000), [new BumpAction.Hide()]);

Section("the hold");
var hold = new HoldTracker();
hold.Down(t);
Equal("half way", hold.Progress(t + 1000), 0.5);
Equal("not yet at 1.9 seconds", hold.Check(t + 1900), false);
Equal("held for 2 seconds", hold.Check(t + 2000), true);
Equal("it counts once", hold.Check(t + 2100), false);
hold = new HoldTracker();
hold.Down(t); hold.Up(t + 150);
Equal("a plain tap only says Hold to leave", hold.Hint, true);
Equal("and does not leave", hold.Check(t + 5000), false);
hold = new HoldTracker();
hold.Down(t); hold.Up(t + 1500);
Equal("letting go early does not leave", hold.Check(t + 2500), false);
Equal("and shows no hint (it was a real hold)", hold.Hint, false);
hold.Down(t + 3000);
Equal("it can be started again", hold.Check(t + 5000), true);

// ---------------------------------------------------------------- away from the PC
Section("away from the PC");
var away = new AwayTracker();
Equal("busy: nothing", away.Sample(5_000, t, true), null);
Equal("idle 60 s: not away yet", away.Sample(60_000, t + 55_000, true), null);
Equal("idle 130 s: away, not asked yet", away.Sample(130_000, t + 125_000, true), null);
Check("away", away.IsAway);
var back = away.Sample(2_000, t + 12 * MIN, true);
Equal("back after 12 minutes: asked", back?.Minutes, 12);
Equal("it began when the last key was pressed", back?.From, t + 125_000 - 130_000);
Equal("not asked twice", away.Sample(1_000, t + 12 * MIN + 2000, true), null);
away = new AwayTracker();
away.Sample(125_000, t + 125_000, true);
Equal("away with no block running: not asked", away.Sample(1_000, t + 6 * MIN, false), null);
away = new AwayTracker();
away.Sample(100_000, t + 100_000, true);
Equal("under two minutes: never asks", away.Sample(1_000, t + 110_000, true), null);
away = new AwayTracker();
away.OfflineNow(t, 3_000);
Equal("asleep: not asked", away.Sample(4 * MIN, t + 4 * MIN, true), null);
away.OnlineNow();
Equal("woke after half an hour: asked", away.Sample(2_000, t + 30 * MIN, true)?.Minutes, 30);
away = new AwayTracker();
away.OfflineNow(t, 0);
away.OnlineNow();
Equal("a screen lock of 30 seconds: not asked", away.Sample(1_000, t + 30_000, true), null);
away = new AwayTracker(5 * MIN);
away.Sample(4 * MIN, t + 4 * MIN, true);
Equal("a longer limit in Settings is respected: 4 minutes idle is not away", away.Sample(1_000, t + 6 * MIN, true), null);
away.Sample(6 * MIN, t + 12 * MIN, true);
Equal("and 6 minutes is", away.Sample(1_000, t + 20 * MIN, true)?.Minutes, 14);

// ---------------------------------------------------------------- the settings
Section("settings");
var ws = new WinSettings();
Check("the settings start with the starter blocklist", ws.Blocklist.Sites.SequenceEqual(Blocklist.Starter().Sites));
Same("the shortcuts", new[] { ws.StartKey.Label, ws.ParkKey.Label, ws.YesKey.Label, ws.DriftedKey.Label }, ["Ctrl+Alt+Space", "Ctrl+Alt+P", "Ctrl+Alt+Y", "Ctrl+Alt+D"]);
Check("a shortcut needs Ctrl, Alt or Win", !new KeyCombo(0x41, KeyCombo.Shift).IsValid && new KeyCombo(0x41, KeyCombo.Win).IsValid && !new KeyCombo(0x11, KeyCombo.Control).IsValid);
ws.Blocklist.AddSite("example.org");
ws.Blocklist.AddApp(new ListedApp("game.exe", "A Game"));
ws.AwayAction = AwayAction.Count;
var folder = Path.Join(Path.GetTempPath(), $"chataltul-tests-{Guid.NewGuid():N}");
var store = new SettingsStore(folder);
store.Write(ws);
var read = store.Read();
Equal("they survive a round trip", read.ToJson(), ws.ToJson());
Check("the file says it in words", File.ReadAllText(store.File).Contains("\"awayAction\": \"count\"") && File.ReadAllText(store.File).Contains("\"mode\": \"speedBump\""));
File.WriteAllText(store.File, "{}");
Equal("an old file without new keys still loads", store.Read().ToJson(), new WinSettings().ToJson());
File.WriteAllText(store.File, "{ not json");
Equal("a file that doesn't read gives the defaults", store.Read().ToJson(), new WinSettings().ToJson());
Check("and is kept aside", Directory.GetFiles(folder, "windows.json.bad-*").Length == 1);
Check("the cycle: a long break after 4", !new Cycle().IsLong(3) && new Cycle().IsLong(4) && new Cycle().IsLong(8));

Section("the doc file");
var docs = new DocStore(folder);
Equal("no doc yet", docs.Read(), null);
docs.Write("{\"a\":1}");
Equal("written whole", docs.Read(), "{\"a\":1}");
Check("a doc that doesn't load is kept aside", docs.SetAside() is { } aside && File.Exists(aside));
try { Directory.Delete(folder, true); } catch (Exception) { }

// ---------------------------------------------------------------- the tray icon
Section("the tray icon");
host.Load(null);
var n0 = t + 400 * 86_400_000L;
r = host.Run("start", new Dictionary<string, object?> { ["minutes"] = 20 }, n0);
var st = TrayState.Of(r.State.View.Active, null, null, n0 + 5 * MIN);
Check("a running block is focusing, three quarters left", st is TrayState.Focusing { Remaining: 0.75, Text: "15:00", Pending: false }, st.ToString());
Equal("its tip has the time", st.Tip("Write", null), "Chataltul · 15:00 · Write");
Check("a check-in waiting gives the dot", TrayState.Of(host.Run("tick", now: n0 + 15 * MIN + 1).State.View.Active, null, null, n0 + 15 * MIN + 1) is TrayState.Focusing { Pending: true });
Check("nothing running is ready", TrayState.Of(null, null, null, n0) is TrayState.Ready);
Check("the picture is only drawn again when the ring moves enough to show",
    new TrayState.Focusing(0.51, "a", false).Key == new TrayState.Focusing(0.512, "b", false).Key && new TrayState.Focusing(0.51, "a", false).Key != new TrayState.Focusing(0.45, "a", false).Key);
foreach (var size in new[] { 16, 20, 24, 32 })
{
    var px = TrayArt.Draw(new TrayState.Ready(), size, true);
    Equal($"a {size}-pixel picture", px.Length, size * size * 4);
    var lit = Enumerable.Range(0, size * size).Count(i => px[i * 4 + 3] > 0);
    Check($"the {size}-pixel kitten is drawn, and isn't a block of color", lit > size && lit < size * size / 2, $"{lit} pixels");
}
var ring = TrayArt.Draw(new TrayState.Focusing(1, "", false), 32, false);
var half = TrayArt.Draw(new TrayState.Focusing(0.5, "", false), 32, false);
int Alpha(byte[] p, int x, int y) => p[(y * 32 + x) * 4 + 3];
Check("a full ring reaches the left side", Alpha(ring, 2, 16) > 100, $"{Alpha(ring, 2, 16)}");
Check("half a ring is the right half only", Alpha(half, 29, 16) > 100 && Alpha(half, 2, 16) < 120, $"{Alpha(half, 29, 16)} / {Alpha(half, 2, 16)}");
var dot = TrayArt.Draw(new TrayState.Focusing(0.5, "", true), 32, true);
Check("the check-in dot is orange, top right", dot[(5 * 32 + 27) * 4 + 2] > 200 && dot[(5 * 32 + 27) * 4 + 3] > 200);
var pngBytes = Png.Encode(32, 32, Png.FromBgra(ring));
Check("a picture makes a PNG file", pngBytes.Length > 60 && pngBytes[1] == (byte)'P' && pngBytes[2] == (byte)'N' && pngBytes[3] == (byte)'G');

// ---------------------------------------------------------------- the words
Section("the words");
Equal("a clock", Format.FmtClock(4 * MIN + 59_000), "04:59");
Equal("a long clock", Format.FmtClock(62 * MIN + 3_000), "1:02:03");
Equal("minutes", Format.FmtMin(65), "1 h 5 min");
Equal("a block with no title goes by its tag", Format.TitleOf("", "Study"), "Study");
Equal("else Focus", Format.TitleOf("", null), "Focus");
Equal("the reasons' words", Reasons.Label("web"), "The web");
Check("the quick reasons are the engine's", Reasons.Quick.All(q => Reasons.Ids.Contains(q)));
Art.Learn(host.Run("tick", now: t).State.Cats);
Equal("a painting's path, as the app carries it", Art.File(Art.Portrait("miso")), Path.Join("cats", "miso-1.png"));

Console.WriteLine($"{passed} passed, {failed} failed in {(DateTime.Now - started).TotalSeconds:0.0} s");
Console.WriteLine(failed == 0 ? "SELFTEST passed" : "SELFTEST failed");
return failed == 0 ? 0 : 1;

/// <summary>Categories with their domains in plain words, for the rules of a list.</summary>
sealed class FakeMatcher(Dictionary<string, string[]> sets) : IDomainMatcher
{
    public string? Match(string host, IEnumerable<string> categories)
    {
        foreach (var c in categories)
            foreach (var d in sets.GetValueOrDefault(c) ?? [])
                if (host == d || host.EndsWith("." + d)) return d;
        return null;
    }
}

/// <summary>The web, as the tests want it to answer: each address's answer, and every request kept.</summary>
sealed class FakeFetcher : IListFetcher
{
    public readonly Dictionary<string, Fetched> Answers = [];
    public readonly List<(string Url, string? Etag, string? Modified)> Calls = [];

    public Task<Fetched> Fetch(Uri url, string? etag, string? lastModified, long maxBytes)
    {
        Calls.Add((url.ToString(), etag, lastModified));
        return Task.FromResult(Answers.GetValueOrDefault(url.ToString()) ?? new Fetched.Failed("Couldn't reach it"));
    }
}
