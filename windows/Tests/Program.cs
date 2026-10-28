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
}

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
    new TrayState.Focusing(0.5, "a", false).Key == new TrayState.Focusing(0.501, "b", false).Key && new TrayState.Focusing(0.5, "a", false).Key != new TrayState.Focusing(0.45, "a", false).Key);
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
