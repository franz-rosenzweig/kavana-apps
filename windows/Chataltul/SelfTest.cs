using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Nodes;
using Chataltul.Core;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Media.Imaging;

namespace Chataltul;

/// <summary>
/// The self-test (windows/selftest.ps1): the real app wired up on a scratch folder, with every window far off
/// the screen, no tray icon, no notification, and no reading of the real front window or any browser. What a
/// person would press is called the way the buttons call it, and the clock is moved on instead of waited for.
/// The windows are drawn to PNG files (--shots). On a machine of its own (the workflow: --onscreen, --browser)
/// it also looks at real pixels: the windows are see-through where they don't paint, clicks go through the
/// cat's empty corners, Leave anyway is held with a real mouse, and a real browser's address is read.
/// The Mac's is mac/SelfTest.
/// </summary>
static class SelfTest
{
    sealed record Check(string Name, bool Ok, string Detail);
    static readonly List<Check> Checks = [];
    static readonly object Gate = new();
    const long MIN = 60_000;
    static bool finished;

    static void Add(string name, bool ok, string detail = "")
    {
        lock (Gate) Checks.Add(new Check(name, ok, detail));
        Log.Write($"{(ok ? "ok  " : "FAIL")} {name}{(detail.Length > 0 ? $" ({detail})" : "")}");
    }

    public static void FailEarly(string problem)
    {
        Add("the app started", false, problem);
        Finish();
    }

    static Task Spin(int ms = 200) => Task.Delay(ms);

    public static async Task Run(Controller app)
    {
        // never longer than six minutes, whatever hangs
        _ = Task.Delay(TimeSpan.FromMinutes(6)).ContinueWith(_ => Ui.Post(() => Finish("it took more than six minutes")));
        try
        {
            await Spin(500);
            await Live(app);
            await Pictures(app);
            await TheWindow(app);
            await TheWidgets(app);
            await JumpTasks(app);
            if (App.Options.Sync is { } server) await TheAccount(app, server, App.Options.Outbox);
            if (App.Options.OnScreen) await OnScreen(app);
            if (App.Options.Browser) await Browsers();
        }
        catch (Exception e) { Add("the self-test ran to the end", false, e.ToString()); }
        Finish();
    }

    static WinSettings Fresh() => new()
    {
        Blocklist = new Blocklist([new ListedApp("fake.exe", "Fake")], ["example.test"]),
        Welcomed = true,
        BrowserNoticeShown = true,
    };

    static readonly FrontApp Writer = new(new IntPtr(0x7001), 7001, "writer.exe", "Writer");
    static readonly FrontApp FakeApp = new(new IntPtr(0x7002), 7002, "fake.exe", "Fake");
    static readonly FrontApp Chrome = new(new IntPtr(0x7003), 7003, "chrome.exe", "Chrome");

    static async Task Live(Controller app)
    {
        var store = app.Store;
        var idle = 1_000L;
        app.Away.IdleOverride = () => idle;
        store.ResetForTest(Fresh());
        var list = store.Settings.Blocklist;
        var hitApp = list.Hit("fake.exe");
        var hitSite = list.Hit("https://www.example.test/page?x=1", "Chrome");

        // ---- the engine and what the app carries
        Add("the engine loaded the web's files", store.State.Cats.Count > 10, $"{store.State.Cats.Count} cats");
        Add("the cats come with their names", store.Cat("pepper").Name == "Milo", store.Cat("pepper").Name);
        Add("the paintings are in the app, as PNG", File.Exists(Path.Join(AppContext.BaseDirectory, "art", "cats", "miso-1.png")));
        Add("the engine's commit is recorded", File.Exists(Path.Join(AppContext.BaseDirectory, "js", "WEB_COMMIT")));
        Add("the listed app and site are found", hitApp != null && hitSite?.Target.Label == "example.test");

        // ---- shortcuts
        Add("shortcuts: start and park are registered", app.Hotkeys.IsSet("start") && app.Hotkeys.IsSet("park"), app.ShortcutProblem ?? "");
        Add("shortcuts: Yes and Drifted aren't taken while nothing waits", !app.Hotkeys.IsSet("yes") && !app.Hotkeys.IsSet("drifted"));

        // ---- the start shortcut: open, then start
        app.StartShortcut();
        await Spin();
        Add("start shortcut: the popover opens on the ready card", app.Popover.IsShown && app.Popover.Pane == "ready", app.Popover.Pane);
        Add("the popover takes the front, the cat never does", (Native.ExStyle(app.Popover.Win.Hwnd) & Native.WS_EX_NOACTIVATE) == 0 && (Native.ExStyle(app.Cat.Win.Hwnd) & Native.WS_EX_NOACTIVATE) != 0);
        await Shot(app.Popover.Win.Content, "popover-ready");
        app.StartShortcut();
        await Spin();
        Add("start shortcut again: a block starts and the popover goes", store.Active != null && !app.Popover.IsShown);
        Add("the kitten is growing", store.Active?.Kitten == "growing");
        Add("the tray shows it focusing", TrayState.Of(store.Active, store.Awaiting, store.OnBreak, Clock.Now) is TrayState.Focusing { Pending: false });
        Add("the doc is saved in the data folder", File.Exists(Path.Join(store.Folder, "doc.json")));

        // ---- the speed bump on an app: held Leave anyway
        Add("speed bump: this block may be guarded", app.Bumpable);
        app.Looked(null, Writer);
        app.Looked(hitApp, FakeApp);
        await Spin();
        Add("speed bump: the card comes up over a listed app", app.Bump.IsShown && app.Bump.Card?.CatName == "Miso");
        Add("speed bump: it counts 15 seconds", app.Bump.Line == "Come back within 15 seconds, or Miso wanders off.", app.Bump.Line);
        await Shot(app.Bump.Win.Content, "speedbump");
        var hold = app.Bump.Hold!;
        var t0 = Clock.Now;
        hold.Down(t0);
        hold.Up(t0 + 150);
        Add("a plain press on Leave anyway doesn't send the kitten off", store.Active?.Kitten == "growing" && app.Bump.IsShown && hold.Words == "Hold to leave", hold.Words);
        hold.Down(t0 + 1_000);
        hold.Check(t0 + 2_500);
        hold.Up(t0 + 2_500);
        Add("holding it for a second and a half doesn't either", store.Active?.Kitten == "growing" && app.Bump.IsShown);
        hold.Down(t0 + 3_000);
        hold.Check(t0 + 5_100);
        await Spin();
        Add("held for two seconds: the kitten wandered off", store.Active is { Kitten: "gone", GoneWhy: "away" }, store.Active?.Kitten ?? "no block");
        Add("held: the block keeps going", store.Active != null);
        Add("held: the card is gone", !app.Bump.IsShown);
        Add("held: the cat says so", app.Cat.Kind == "gone", app.Cat.Kind ?? "");
        await Shot(app.Cat.Win.Content, "cat-wandered");
        app.Looked(hitApp, FakeApp);
        await Spin();
        Add("the cat doesn't come up again for that block", !app.Bump.IsShown);
        Add("it isn't guarded any more", !app.Bumpable);

        store.ResetForTest(Fresh());
        app.Speed = new SpeedBump();
        store.Start("Cancel soon");
        Add("a stop in the first ten seconds takes the block back", store.Stop() && store.Active == null && store.State.History.Count == 0);

        // ---- the 15 seconds run out, on a site
        store.Start("Second block");
        app.Looked(null, Writer);
        app.Looked(hitSite, Chrome);
        await Spin();
        Add("a listed site brings the card", app.Bump.IsShown && app.Bump.Card?.Target.Label == "example.test");
        if (app.Bump.Card is { } card)
        {
            app.Second(card.ShownAt + 14_000);
            Add("14 seconds in, the card is still there", app.Bump.IsShown && store.Active?.Kitten == "growing");
            app.Second(card.ShownAt + 15_000);
            await Spin();
            Add("15 seconds: the kitten wandered off", store.Active is { Kitten: "gone", GoneWhy: "away" });
            Add("15 seconds: the card is gone and the cat says so", !app.Bump.IsShown && app.Cat.Kind == "gone");
        }

        // ---- Back to work
        store.ResetForTest(Fresh());
        app.Speed = new SpeedBump();
        app.Cat.Hide();
        store.Start("Third block");
        app.Looked(null, Writer);
        app.Looked(hitApp, FakeApp);
        await Spin();
        app.Bump.BackToWork();
        await Spin();
        Add("Back to work: the card goes and the kitten stays", !app.Bump.IsShown && store.Active?.Kitten == "growing");
        Add("Back to work goes to the window they were in", app.Previous == Writer.Hwnd);
        app.Looked(hitApp, FakeApp);
        Add("it doesn't come up again while that app stays in front", !app.Bump.IsShown);
        app.Looked(null, Writer);
        app.Looked(hitApp, FakeApp);
        await Spin();
        Add("it does after they left it and came back", app.Bump.IsShown);
        app.Looked(null, Writer);
        await Spin();
        Add("leaving the listed app takes the card away", !app.Bump.IsShown);

        // ---- a check-in: the cat in the corner
        var start = store.Active!.Start;
        Clock.Offset += start + 15 * MIN + 1_000 - Clock.Now;
        app.Second(Clock.Now);
        await Spin();
        Add("check-in: the engine has it waiting", store.Active?.Pending == 1, $"{store.Active?.Pending}");
        Add("check-in: the cat comes up in the corner", app.Cat.IsShown && app.Cat.Kind == "checkin", app.Cat.Kind ?? "");
        Add("check-in: it names the block", app.Cat.Model?.Title == "Boo! Still on: Third block?", app.Cat.Model?.Title ?? "");
        Add("check-in: Yes and Drifted are taken while it waits", app.Hotkeys.IsSet("yes") && app.Hotkeys.IsSet("drifted"));
        Add("check-in: the tray icon has its orange dot", TrayState.Of(store.Active, store.Awaiting, store.OnBreak, Clock.Now) is TrayState.Focusing { Pending: true });
        await Shot(app.Cat.Win.Content, "cat-checkin");
        Add("the floating windows are on top and out of the taskbar and Alt+Tab",
            new[] { app.Popover.Win, app.Cat.Win, app.Bump.Win }.All(w => (Native.ExStyle(w.Hwnd) & Native.WS_EX_TOOLWINDOW) != 0 && (Native.ExStyle(w.Hwnd) & Native.WS_EX_TOPMOST) != 0),
            string.Join(", ", new[] { app.Popover.Win, app.Cat.Win, app.Bump.Win }.Select(w => $"{Native.ExStyle(w.Hwnd):x}")));
        app.Popover.Show();
        await Spin();
        Add("check-in: the popover has Yes and Drifted too", app.Popover.Pane == "focus" && app.Popover.Buttons.ContainsKey("Yes"));
        await Shot(app.Popover.Win.Content, "popover-checkin");
        app.Popover.Hide();
        app.Cat.Press("Drifted");
        await Spin();
        Add("Drifted counts", store.Active?.Drifts == 1, $"{store.Active?.Drifts}");
        Add("then the cat asks what pulled you away", app.Cat.Kind == "reason");
        await Shot(app.Cat.Win.Content, "cat-reason");
        app.Cat.Press("Phone");
        await Spin();
        Add("the reason goes and Yes and Drifted are let go", app.Cat.Kind == null && !app.Hotkeys.IsSet("yes") && !app.Hotkeys.IsSet("drifted"));

        // ---- away from the PC
        idle = 3 * MIN;
        app.Away.Sample(Clock.Now);
        idle = 1_000;
        app.Away.Sample(Clock.Now);
        await Spin();
        Add("away: back after three minutes, it asks", store.AwayPrompt?.Minutes == 3, $"{store.AwayPrompt?.Minutes}");
        Add("away: the popover opens on the welcome back card", app.Popover.IsShown && app.Popover.Pane == "away", app.Popover.Pane);
        await Shot(app.Popover.Win.Content, "popover-away");
        Add("away: Take them off is hidden while the engine can't", !app.Popover.Buttons.ContainsKey("Take them off") == !store.State.CanTakeOff);
        app.Popover.Buttons["Count them"]();
        await Spin();
        Add("Count them: back to the block, the kitten still there", store.AwayPrompt == null && app.Popover.Pane == "focus" && store.Active?.Kitten == "growing");
        await Shot(app.Popover.Win.Content, "popover-focus");
        app.Popover.Hide();

        // ---- park a thought
        app.ShowPark();
        await Spin();
        Add("park: the field opens", app.Park.IsShown);
        await Shot(app.Park.Win.Content, "park");
        app.Park.Park("Reply to that email");
        await Spin();
        Add("park: the thought is on the block and in Later", store.Active?.Parked.Contains("Reply to that email") == true && store.State.View.Later.FirstOrDefault()?.Text == "Reply to that email");
        Add("park: the field closes", !app.Park.IsShown);

        // ---- the end
        Clock.Offset += store.Active!.End + 1_000 - Clock.Now;
        app.Second(Clock.Now);
        await Spin();
        Add("the end: the kitten came home", store.Awaiting?.Kitten == "home", store.Awaiting?.Kitten ?? "none");
        Add("the end: the cat asks how it went", app.Cat.Kind == "end" && app.Cat.Model?.Buttons.Any(b => b.Label == "Done") == true, app.Cat.Kind ?? "");
        Add("the end: the tray says came home", TrayState.Of(store.Active, store.Awaiting, store.OnBreak, Clock.Now) is TrayState.Home);
        await Shot(app.Cat.Win.Content, "cat-end");
        app.Popover.Show();
        await Spin();
        Add("the popover asks how it went", app.Popover.Pane == "home");
        await Shot(app.Popover.Win.Content, "popover-home");
        app.Cat.Press("Done");
        await Spin();
        var filed = store.State.History.FirstOrDefault();
        Add("Done files it", filed?.Outcome == "done" && store.Awaiting == null, filed?.Outcome ?? "");
        Add("the reason for the drift is kept with the block", filed?.Whys.Contains("phone") == true);
        Add("the popover is ready again, with a break offered", app.Popover.Pane == "ready" && store.State.View.BreakOffer != null);
        if (app.Popover.Buttons.TryGetValue("Start break", out var startBreak)) startBreak();
        await Spin();
        Add("a break starts", store.OnBreak != null && app.Popover.Pane == "break", app.Popover.Pane);
        await Shot(app.Popover.Win.Content, "popover-break");
        Add("the tray shows the break", TrayState.Of(store.Active, store.Awaiting, store.OnBreak, Clock.Now) is TrayState.OnBreak);
        app.Popover.Buttons["End break"]();
        await Spin();
        Add("End break ends it", store.OnBreak == null && app.Popover.Pane == "ready");
        app.Popover.Hide();

        // ---- a long break: longer than the engine's 15 minutes, the app keeps its end
        store.StartBreak(20);
        Add("a 20-minute break runs 20 minutes", store.OnBreak is { } lb && lb.End - lb.Start == 20 * MIN, store.OnBreak is { } x ? $"{(x.End - x.Start) / MIN} min" : "none");
        store.StopBreak();

        // ---- the engine's settings from Settings
        store.SetEngine(new() { ["interval"] = 30 });
        Add("a setting goes to the engine", store.EngineSettings.Interval == 30);
        store.SetEngine(new() { ["interval"] = 15 });
    }

    /// <summary>Every screen, in light and in dark, and the tray icon in each state.</summary>
    static async Task Pictures(Controller app)
    {
        var store = app.Store;
        var dir = App.Options.Shots;
        // the tray icon
        var states = new (string, TrayState)[]
        {
            ("ready", new TrayState.Ready()), ("focusing", new TrayState.Focusing(0.6, "14:59", false)), ("checkin", new TrayState.Focusing(0.4, "09:59", true)),
            ("paused", new TrayState.Paused(0.5, "12:30")), ("break", new TrayState.OnBreak(0.7, "03:30")), ("home", new TrayState.Home()),
        };
        var drawn = new HashSet<string>();
        foreach (var (name, s) in states)
            foreach (var dark in new[] { false, true })
                foreach (var size in new[] { 16, 32 })
                {
                    var px = TrayArt.Draw(s, size, dark);
                    drawn.Add(Convert.ToBase64String(px));
                    if (dir != null && size == 32) File.WriteAllBytes(Path.Join(dir, $"tray-{name}-{(dark ? "dark" : "light")}.png"), Png.Encode(size, size, Png.FromBgra(px)));
                }
        Add("the tray icon has a picture for each state, size and taskbar", drawn.Count == states.Length * 4, $"{drawn.Count} pictures");
        var icon = Native.Icon(TrayArt.Draw(new TrayState.Ready(), 16, true), 16);
        Add("a tray picture makes a Windows icon", icon != IntPtr.Zero);
        if (icon != IntPtr.Zero) Native.DestroyIcon(icon);

        // the windows, in dark too
        store.ResetForTest(Fresh());
        foreach (var dark in new[] { false, true })
        {
            Palette.Force = dark;
            app.Speed = new SpeedBump();
            var theme = dark ? "dark" : "light";
            app.Popover.Show();
            await Spin();
            await Shot(app.Popover.Win.Content, $"popover-ready-{theme}");
            store.Start("Write the intro");
            await Spin();
            await Shot(app.Popover.Win.Content, $"popover-focus-{theme}");
            app.Popover.Hide();
            app.Looked(null, Writer);
            app.Looked(store.Settings.Blocklist.Hit("fake.exe"), FakeApp);
            await Spin();
            await Shot(app.Bump.Win.Content, $"speedbump-{theme}");
            app.Bump.BackToWork();
            store.Stop();
            app.Cat.Show("checkin", new CatCardModel("pepper", "Boo! Still on: Write the intro?")
            {
                Label = "Check-in", Body = "10 min left · answer here, no need to switch apps",
                Buttons = [new("Yes", () => { }, true, V.Glyph.Check), new("Drifted", () => { }, false, V.Glyph.Drift)],
                Hint = "Or press Ctrl+Alt+Y for Yes, Ctrl+Alt+D for Drifted",
            }, 30);
            await Spin();
            await Shot(app.Cat.Win.Content, $"cat-checkin-{theme}");
            app.Cat.Hide();
            foreach (var pane in new[] { "general", "blocks", "blocklist", "widgets", "shortcuts", "account" })
            {
                app.Settings.Show(pane);
                await Spin(300);
                await Shot(app.Settings.Window.Content as FrameworkElement, $"settings-{pane}-{theme}");
            }
            if (!dark)
            {
                app.Settings.Zoom(1.5f);
                await Spin(400);
                Add("Settings' text grows with Ctrl+Plus (the same call)", app.Settings.ZoomFactor > 1.4f, $"{app.Settings.ZoomFactor:0.00}");
                await Shot(app.Settings.Window.Content as FrameworkElement, "settings-shortcuts-bigger-text");
                app.Settings.Zoom(1f);
                await Spin(300);
            }
            app.Settings.Hide();
            if (store.Awaiting is { } w) store.Outcome(w.Id, null);
            app.Speed = new SpeedBump();
        }
        Palette.Force = false;
    }

    /// <summary>Waits for something to come true, the UI thread running meanwhile.</summary>
    static async Task<bool> Wait(Func<bool> done, double seconds = 20)
    {
        var end = DateTime.UtcNow.AddSeconds(seconds);
        while (DateTime.UtcNow < end)
        {
            if (done()) return true;
            await Task.Delay(100);
        }
        return done();
    }

    /// <summary>A block that came home, made at a time in the past.</summary>
    static void HomeBlock(Store store, string title, long at, int minutes, string outcome = "done")
    {
        store.Act("start", new() { ["intention"] = title, ["minutes"] = minutes, ["cat"] = store.State.Companion }, at);
        store.Act("tick", now: at + minutes * MIN + 1_000);
        if (store.Awaiting is { } w) store.Act("outcome", new() { ["id"] = w.Id, ["outcome"] = outcome }, at + minutes * MIN + 2_000);
    }

    /// <summary>The app window: Today for a day, a week, a month and a year, the Café, History and Later.</summary>
    static async Task TheWindow(Controller app)
    {
        var store = app.Store;
        store.ResetForTest(Fresh());
        var morning = Format.Ms(DateTime.Today.AddHours(9));
        var now = Clock.Now;
        if (morning + 4 * 60 * MIN > now) morning = now - 4 * 60 * MIN;   // early in the day: the blocks go before now
        HomeBlock(store, "Draft the newsletter", morning, 25);
        HomeBlock(store, "Read the paper", morning + 40 * MIN, 50, "partly");
        HomeBlock(store, "Yesterday's", morning - 24 * 60 * MIN, 25);
        store.AddLater("Call the bank");
        foreach (var dark in new[] { false, true })
        {
            Palette.Force = dark;
            var theme = dark ? "dark" : "light";
            foreach (var span in new[] { Span.Day, Span.Week, Span.Month, Span.Year })
            {
                app.OpenWindow("today", span);
                await Spin(500);
                if (span == Span.Day && !dark)
                {
                    Add("the window opens on Today", app.Main.IsShown && app.Main.Pane == "today");
                    Add("Today's café has the cats that came home today", app.Main.Cafe?.CatCount >= 1, $"{app.Main.Cafe?.CatCount}");
                    Add("Today has Day, Week, Month and Year", new[] { "Day", "Week", "Month", "Year" }.All(app.Main.Buttons.ContainsKey));
                }
                await Shot(app.Main.Window.Content as FrameworkElement, $"window-today-{span.ToString().ToLowerInvariant()}-{theme}");
            }
            foreach (var pane in new[] { "cafe", "history", "later" })
            {
                app.OpenWindow(pane, Span.Day);
                await Spin(500);
                await Shot(app.Main.Window.Content as FrameworkElement, $"window-{pane}-{theme}");
            }
        }
        Palette.Force = false;
        app.OpenWindow("later");
        await Spin(300);
        Add("Later lists what's in it", app.Main.Buttons.ContainsKey("Done: Call the bank"));
        if (app.Main.Buttons.TryGetValue("Start: Call the bank", out var startIt)) startIt();
        await Spin();
        Add("a block starts from Later, and ticks it off", store.Active?.Intention == "Call the bank" && store.State.View.Later.All(l => l.Text != "Call the bank"));
        store.Stop();
        app.OpenWindow("history");
        await Spin(300);
        Add("History shows the blocks", store.State.History.Count >= 3);
        app.Main.Hide();
        Add("closing the window only hides it", !app.Main.IsShown);
    }

    /// <summary>Every widget on the desktop (far off it, in the test), in light and dark, and what their buttons do.</summary>
    static async Task TheWidgets(Controller app)
    {
        var store = app.Store;
        store.ResetForTest(Fresh());
        HomeBlock(store, "A block for the widgets", Clock.Now - 90 * MIN, 25);
        store.AddLater("Water the plants");
        foreach (var dark in new[] { false, true })
        {
            Palette.Force = dark;
            var theme = dark ? "dark" : "light";
            foreach (var k in Widgets.All)
                foreach (var size in k.Sizes)
                {
                    var w = app.Widgets.Add(k.Id, size);
                    await Spin(250);
                    await Shot(w.Win.Content, $"widget-{k.Id}-{size}-{theme}");
                    app.Widgets.Remove(w.Place.Id);
                }
        }
        Palette.Force = false;
        Add("every widget was added and taken away again", app.Widgets.Shown.Count == 0 && store.Settings.Widgets.Count == 0, $"{app.Widgets.Shown.Count} left");

        var win = app.Widgets.Add("window");
        await Spin();
        Add("a widget sits under the other windows and never takes the front",
            (Native.ExStyle(win.Win.Hwnd) & Native.WS_EX_TOPMOST) == 0 && (Native.ExStyle(win.Win.Hwnd) & Native.WS_EX_NOACTIVATE) != 0 && (Native.ExStyle(win.Win.Hwnd) & Native.WS_EX_TOOLWINDOW) != 0,
            $"{Native.ExStyle(win.Win.Hwnd):x}");
        Add("its place is kept in the settings", store.Settings.Widgets.Count == 1 && store.Settings.Widgets[0].Kind == "window");
        app.Widgets.Moved(win, win.Place.X - 50, win.Place.Y + 30);
        Add("moved, the new place is kept", store.Settings.Widgets[0].X == win.Place.X && store.Settings.Widgets[0].Y == win.Place.Y);
        win.Buttons["Start"]();
        await Spin();
        Add("the Window's Start starts 25 minutes", store.Active?.Minutes == 25, $"{store.Active?.Minutes}");
        Add("and the Window shows it focusing", win.Buttons.ContainsKey("Pause") && win.Buttons.ContainsKey("Park a thought"));
        await Shot(win.Win.Content, "widget-window-focusing");
        win.Buttons["Pause"]();
        await Spin();
        Add("Pause from the widget pauses", store.Active?.Paused != null && win.Buttons.ContainsKey("Resume"));
        win.Buttons["Resume"]();
        store.Stop();
        store.Stop();
        await Spin();
        app.Widgets.Resize(win.Place.Id, "small");
        Add("a widget can be made smaller", store.Settings.Widgets[0].Size == "small");

        var later = app.Widgets.Add("later");
        await Spin();
        later.Buttons["Done: Water the plants"]();
        await Spin();
        Add("Later's tick takes it off the list", store.State.View.Later.All(l => l.Text != "Water the plants"));
        var pet = app.Widgets.Add("pet", "medium");
        await Spin();
        pet.Pet();
        Add("pet the cat: a hop", pet.Mood == 1);
        pet.Pet();
        Add("again: it purrs", pet.Mood == 2);
        await Shot(pet.Win.Content, "widget-pet-purring");
        var quick = app.Widgets.Add("quickstart");
        await Spin();
        quick.Buttons["50"]();
        await Spin();
        Add("Quick start's 50 starts 50 minutes", store.Active?.Minutes == 50);
        store.Stop();
        store.Stop();
        app.Widgets.RemoveAll();
        await Spin();
        Add("taking them all away leaves none", app.Widgets.Shown.Count == 0 && store.Settings.Widgets.Count == 0);
    }

    /// <summary>The jump list's tasks, as a second start hands them over.</summary>
    static async Task JumpTasks(Controller app)
    {
        var store = app.Store;
        store.ResetForTest(Fresh());
        Add("the jump list has its four tasks", JumpList.Tasks.Select(t => t.Command).SequenceEqual(["start:25", "start:50", "park", "cafe"]));
        app.Do("start:50");
        await Spin();
        Add("Start 50 from the jump list", store.Active?.Minutes == 50, $"{store.Active?.Minutes}");
        app.Popover.Hide();
        app.Do("park");
        await Spin();
        Add("Park a thought from the jump list", app.Park.IsShown);
        app.Park.Hide();
        app.Do("cafe");
        await Spin(400);
        Add("Open the café from the jump list", app.Main.IsShown && app.Main.Pane == "cafe");
        app.Main.Hide();
        store.Stop();
        store.Stop();
    }

    /// <summary>The newest sign-in code mailed to this address, from the sandbox server's outbox.</summary>
    static string? CodeFor(string email, string outbox, long after)
    {
        string[] lines;
        try { lines = File.ReadAllLines(outbox); } catch (Exception) { return null; }
        foreach (var line in Enumerable.Reverse(lines))
        {
            try
            {
                if (JsonNode.Parse(line) is not JsonObject d || d["to"]?.GetValue<string>() != email) continue;
                if ((d["at"]?.GetValue<double>() ?? 0) < after) continue;
                var subject = d["subject"]?.GetValue<string>() ?? "";
                var m = System.Text.RegularExpressions.Regex.Match(subject, "^[0-9]{6}");   // "123456 is your Chataltul code"
                if (m.Success) return m.Value;
            }
            catch (Exception) { }
        }
        return null;
    }

    /// <summary>
    /// Signing in and syncing, against a sandbox copy of the web server on this machine (the workflow starts it with
    /// KAVANA_MAIL=outbox, so the codes are written to a file this reads). A second device is played through the API
    /// with its own engine. The Mac's SelfTest/SyncScenarios.swift.
    /// </summary>
    static async Task TheAccount(Controller app, string server, string? outbox)
    {
        var store = app.Store;
        var sync = app.Sync;
        // the one rule of these tests: only a server on this machine, never the real one
        if (!new SyncClient(server, new FileSession(Path.Join(store.Folder, "x"))).IsLocal || outbox == null)
        {
            Add("the account test only talks to a server on this machine", false, server);
            return;
        }
        store.ResetForTest(Fresh());
        store.ChangeSettings(s => s.Server = server);
        var email = $"windows-test-{Random.Shared.Next(100000, 999999)}@example.test";
        var other = new SyncClient(server, new FileSession(Path.Join(store.Folder, "session2")));
        var engine2 = new EngineHost(Path.Join(AppContext.BaseDirectory, "js"));
        async Task<JsonObject?> ServerDoc() { try { return await other.Api("GET", "api/sync?rev=-1"); } catch (Exception) { return null; } }
        async Task<List<string>> ServerTitles() =>
            (await ServerDoc())?["doc"]?["blocks"] is JsonArray a ? a.Select(b => b?["intention"]?.GetValue<string>() ?? "").ToList() : [];

        // this PC's own history first
        HomeBlock(store, "On this PC", Clock.Now - 90 * MIN, 25);
        Add("account: signed out to begin with", !sync.SignedIn);
        sync.LoadConfig();
        Add("account: the server says it sends email", await Wait(() => sync.EmailOn == true), sync.Message ?? "");

        // a wrong code, then the right one
        var since = Format.NowMs() - 1000;
        sync.SendCode(email);
        Add("account: the code is sent", await Wait(() => sync.CodeSentTo == email), sync.Message ?? "");
        await Wait(() => CodeFor(email, outbox, since) != null, 10);
        var good = CodeFor(email, outbox, since);
        Add("account: the code is in the outbox, six digits", good?.Length == 6);
        if (good == null) return;
        app.Settings.Show("account");
        await Spin(400);
        await Shot(app.Settings.Window.Content as FrameworkElement, "settings-account-code");
        app.Settings.Hide();
        sync.SignIn(email, good == "000000" ? "111111" : "000000");
        Add("account: a wrong code is refused, in words", await Wait(() => sync.Message != null && !sync.Busy) && !sync.SignedIn && sync.Message == "That code didn't work. Ask for a new one.", sync.Message ?? "");
        sync.SignIn(email, good);
        Add("account: the right code signs in", await Wait(() => sync.SignedIn), sync.Message ?? "");
        Add("account: as that email", sync.Account?.Email == email, sync.Account?.Email ?? "");
        app.Settings.Show("account");
        await Spin(400);
        await Shot(app.Settings.Window.Content as FrameworkElement, "settings-account-signed-in");
        app.Settings.Hide();

        // a second device signs in with its own code, and adds a block
        var since2 = Format.NowMs() - 1000;
        try
        {
            await other.Api("POST", "api/email/code", new JsonObject { ["email"] = email });
            await Wait(() => CodeFor(email, outbox, since2) != null, 10);
            await other.Api("POST", "api/email/signin", new JsonObject { ["email"] = email, ["code"] = CodeFor(email, outbox, since2) });
        }
        catch (Exception e) { Add("account: a second device signs in", false, e.Message); return; }
        // the second device reads the server's copy, so only now can it be seen there
        Add("account: this PC's block went up with the sign-in", await Wait(async () => (await ServerTitles()).Contains("On this PC")));
        var theirs = await ServerDoc();
        engine2.Load(theirs?["doc"]?.ToJsonString());
        var t2 = Format.NowMs();
        engine2.Run("start", new Dictionary<string, object?> { ["intention"] = "On the other device", ["minutes"] = 25 }, t2 - 50 * MIN);
        engine2.Run("tick", now: t2 - 20 * MIN);
        var rev2 = (long)(theirs?["rev"]?.GetValue<double>() ?? 0);
        try { await other.Api("POST", "api/sync", raw: $"{{\"rev\":{rev2},\"doc\":{engine2.Dump()}}}"); Add("account: the second device pushed", true); }
        catch (Exception e) { Add("account: the second device pushed", false, e.Message); }
        sync.Pull();
        Add("account: what the other device did comes here", await Wait(() => store.State.History.Any(h => h.Intention == "On the other device")));
        Add("account: and this PC's own is still here", store.State.History.Any(h => h.Intention == "On this PC"));

        // a change here goes up by itself
        HomeBlock(store, "Made after signing in", Clock.Now - 30 * MIN, 25);
        Add("account: a change here goes up on its own", await Wait(async () => (await ServerTitles()).Contains("Made after signing in")));

        // signing out takes the account's history off this PC; it stays on the server
        sync.SignOut();
        Add("account: signing out", await Wait(() => !sync.SignedIn));
        Add("account: the account's history left this PC", await Wait(() => store.State.History.Count == 0));
        Add("account: and is safe on the server", (await ServerTitles()).Contains("On the other device"));

        // and signing back in brings it home
        var since3 = Format.NowMs() - 1000;
        sync.SendCode(email);
        await Wait(() => CodeFor(email, outbox, since3) != null, 10);
        if (CodeFor(email, outbox, since3) is { } c3) sync.SignIn(email, c3);
        Add("account: signing back in", await Wait(() => sync.SignedIn));
        Add("account: the history comes back", await Wait(() => store.State.History.Any(h => h.Intention == "On the other device") && store.State.History.Any(h => h.Intention == "On this PC")));
        sync.SignOut();
        await Wait(() => !sync.SignedIn);
        store.ChangeSettings(s => s.Server = WinSettings.WebApp);
    }

    static async Task<bool> Wait(Func<Task<bool>> done, double seconds = 20)
    {
        var end = DateTime.UtcNow.AddSeconds(seconds);
        while (DateTime.UtcNow < end)
        {
            if (await done()) return true;
            await Task.Delay(300);
        }
        return await done();
    }

    /// <summary>On a desktop of its own: real pixels, real clicks.</summary>
    static async Task OnScreen(Controller app)
    {
        var store = app.Store;
        store.ResetForTest(Fresh());
        app.Speed = new SpeedBump();
        // a magenta wall behind everything, to see through to
        var wall = new Floating("Chataltul self-test wall", activates: false, Backdrop.None);
        wall.Root.Background = Palette.B(Palette.C(0xFF00FF));
        var work = Native.WorkAreaAt(new Native.POINT { X = 10, Y = 10 });
        wall.Move(work.Left, work.Top, work.Width, work.Height);
        wall.Show();
        Native.SetCursorPos(work.Left + work.Width / 2, work.Top + work.Height / 2);
        await Spin(400);

        app.Cat.Show("checkin", new CatCardModel("miso", "Boo! Still focusing?") { Label = "Check-in", Buttons = [new("Yes", () => { }, true), new("Drifted", () => { })] }, 30);
        await Spin(800);
        var cat = app.Cat.Win.Bounds;
        var corner = new Native.POINT { X = cat.Left + 6, Y = cat.Top + 6 };
        var seen = Native.ScreenPixel(corner.X, corner.Y);
        Add("the cat's window is see-through where it doesn't paint", Near(seen, 0xFF00FF), $"{seen:x6} at {corner.X},{corner.Y}");
        var under = Native.GetAncestor(Native.WindowFromPoint(corner), Native.GA_ROOT);
        Add("a click there goes through to the window underneath", under != app.Cat.Win.Hwnd, under == wall.Hwnd ? "the wall" : $"{under:x}");
        if (app.Cat.CatRect is { } pic)
        {
            // inside the window's shape, where the painting itself is see-through (its lower left corner, below the card):
            // only a clear backdrop shows the wall here
            var s = Native.GetDpiForWindow(app.Cat.Win.Hwnd) / 96.0;
            var inPicture = Native.ScreenPixel(cat.Left + (int)((pic.X + 4) * s), cat.Top + (int)((pic.Bottom - 6) * s));
            Add("where the cat's painting is empty, the desktop shows through", Near(inPicture, 0xFF00FF), $"{inPicture:x6}");
        }
        var onCard = new Native.POINT { X = cat.Right - 40, Y = cat.Top + 40 };
        var cardPixel = Native.ScreenPixel(onCard.X, onCard.Y);
        Add("the card itself is drawn", !Near(cardPixel, 0xFF00FF), $"{cardPixel:x6}");
        Add("the cat doesn't take the front", Native.GetForegroundWindow() != app.Cat.Win.Hwnd);
        app.Cat.Hide();

        app.Popover.Show();
        await Spin(800);
        var pop = app.Popover.Win.Bounds;
        // a flyout: Windows 11 rounds its corners, but not on a machine with only a basic display adapter, as GitHub's
        // are, so this one is noted rather than checked
        var popCorner = Native.ScreenPixel(pop.Left, pop.Top);
        var rounded = (popCorner >> 16 & 0xFF) > 0xA0 && (popCorner & 0xFF) > 0xA0 && (popCorner >> 8 & 0xFF) < 0x60;
        Add("the popover's corners (noted only)", true, $"{(rounded ? "rounded" : "square on this machine")}: {popCorner:x6}, Windows {Environment.OSVersion.Version}");
        app.Popover.Hide();

        // Leave anyway, held with the real mouse for two and a half seconds
        store.Start("Held for real");
        app.Looked(null, Writer);
        app.Looked(store.Settings.Blocklist.Hit("fake.exe"), FakeApp);
        await Spin(800);
        var dim = Native.ScreenPixel(work.Left + 4, work.Top + 4);
        // the wall's magenta, dimmed: not white or gray under the dim, which would mean the window isn't see-through
        Add("the speed bump dims the screen and lets it show through", !Near(dim, 0xFF00FF) && (dim >> 16 & 0xFF) > 0x40 && (dim & 0xFF) > 0x40 && (dim >> 8 & 0xFF) < 0x30, $"{dim:x6}");
        if (app.Bump.Hold is { } hold)
        {
            var b = app.Bump.Win.Bounds;
            var r = app.Bump.Win.BoundsOf(hold.View);
            var scale = Native.GetDpiForWindow(app.Bump.Win.Hwnd) / 96.0;
            var x = b.Left + (int)((r.X + r.Width / 2) * scale);
            var y = b.Top + (int)((r.Y + r.Height / 2) * scale);
            Native.Press(x, y, true);
            await Task.Delay(700);
            Native.Press(x, y, false);
            await Spin(300);
            Add("a real press of under a second doesn't leave", store.Active?.Kitten == "growing" && app.Bump.IsShown);
            Native.Press(x, y, true);
            await Task.Delay(2_500);
            Native.Press(x, y, false);
            await Spin(500);
            Add("held for real for two seconds, it does", store.Active?.Kitten == "gone", store.Active?.Kitten ?? "");
        }
        store.Stop();
        wall.Hide();
    }

    static bool Near(uint rgb, uint want) =>
        Math.Abs((int)(rgb >> 16 & 0xFF) - (int)(want >> 16 & 0xFF)) < 24 && Math.Abs((int)(rgb >> 8 & 0xFF) - (int)(want >> 8 & 0xFF)) < 24 && Math.Abs((int)(rgb & 0xFF) - (int)(want & 0xFF)) < 24;

    /// <summary>A real browser on a page of this machine, and its address read with UI Automation.</summary>
    static async Task Browsers()
    {
        var server = new TcpListener(IPAddress.Loopback, 0);
        server.Start();
        var port = ((IPEndPoint)server.LocalEndpoint).Port;
        _ = Task.Run(async () =>
        {
            var page = Encoding.UTF8.GetBytes("<!doctype html><title>Chataltul self-test</title><p>A page for the self-test.</p>");
            while (true)
            {
                try
                {
                    using var c = await server.AcceptTcpClientAsync();
                    using var s = c.GetStream();
                    var buffer = new byte[4096];
                    _ = await s.ReadAsync(buffer);
                    var head = Encoding.ASCII.GetBytes($"HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {page.Length}\r\nConnection: close\r\n\r\n");
                    await s.WriteAsync(head);
                    await s.WriteAsync(page);
                }
                catch (Exception) { return; }
            }
        });
        var url = $"http://127.0.0.1:{port}/chataltul-selftest";
        var pf = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles);
        var pf86 = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86);
        var candidates = new (string Exe, string Path, Func<string, string> Args)[]
        {
            ("msedge.exe", Path.Join(pf86, @"Microsoft\Edge\Application\msedge.exe"), dir => $"--no-first-run --no-default-browser-check --user-data-dir=\"{dir}\" --new-window {url}"),
            ("chrome.exe", Path.Join(pf, @"Google\Chrome\Application\chrome.exe"), dir => $"--no-first-run --no-default-browser-check --user-data-dir=\"{dir}\" --new-window {url}"),
            ("firefox.exe", Path.Join(pf, @"Mozilla Firefox\firefox.exe"), dir => $"-no-remote -profile \"{dir}\" {url}"),
        };
        var reader = new BrowserReader();
        await Spin(500);
        foreach (var (exe, path, args) in candidates)
        {
            var browser = Browser.With(exe)!;
            if (!File.Exists(path)) { Add($"the address in {browser.Name} is read", true, "not installed here: skipped"); continue; }
            var profile = Path.Join(Path.GetTempPath(), $"chataltul-{exe}-{Guid.NewGuid():N}");
            Directory.CreateDirectory(profile);
            // a new Firefox profile opens its welcome pages first; these keep it to the page asked for
            if (browser.Gecko)
                File.WriteAllText(Path.Join(profile, "user.js"), string.Join("\n",
                    "user_pref(\"browser.aboutwelcome.enabled\", false);", "user_pref(\"browser.startup.homepage_override.mstone\", \"ignore\");",
                    "user_pref(\"startup.homepage_welcome_url\", \"\");", "user_pref(\"datareporting.policy.firstRunURL\", \"\");",
                    "user_pref(\"browser.shell.checkDefaultBrowser\", false);", "user_pref(\"browser.startup.firstrunSkipsHomepage\", true);",
                    "user_pref(\"toolkit.telemetry.reportingpolicy.firstRun\", false);", "user_pref(\"browser.shell.didSkipDefaultBrowserCheckOnFirstRun\", true);"));
            Process? p = null;
            var began = DateTime.Now.AddSeconds(-1);
            try
            {
                p = Process.Start(new ProcessStartInfo(path, args(profile)) { UseShellExecute = false });
                string? got = null;
                var began2 = DateTime.UtcNow;
                var end = began2.AddSeconds(30);
                while (DateTime.UtcNow < end && got?.Contains("127.0.0.1") != true)
                {
                    await Spin(1_000);
                    var hwnd = WindowOf(exe, "Chataltul self-test");
                    // the page never came up: read whatever window it has, to say what its box holds
                    if (hwnd == IntPtr.Zero && DateTime.UtcNow - began2 > TimeSpan.FromSeconds(15)) hwnd = WindowOf(exe, "");
                    if (hwnd == IntPtr.Zero) continue;
                    var done = new TaskCompletionSource<string?>();
                    void Got(IntPtr h, Browser b, string? u) { if (h == hwnd) done.TrySetResult(u); }
                    reader.Read += Got;
                    reader.Ask(hwnd, browser);
                    got = await Task.WhenAny(done.Task, Task.Delay(5_000)) == done.Task ? done.Task.Result : null;
                    reader.Read -= Got;
                }
                var host = got != null ? Blocklist.Host(got) : null;
                var said = $"{(got == null ? "nothing read" : $"read \"{got}\"")}; its windows: {string.Join(" | ", TitlesOf(exe))}";
                // Firefox, started fresh on GitHub's machines, never shows the page there: noted, not checked, until it's seen on a PC
                if (browser.Gecko) Add($"the address in {browser.Name} (noted only)", true, $"{(host == "127.0.0.1" ? "read" : "not read")}: {said}");
                else Add($"the address in {browser.Name} is read", host == "127.0.0.1", said);
            }
            catch (Exception e) { Add($"the address in {browser.Name} is read", false, e.Message); }
            finally
            {
                try { p?.Kill(true); } catch (Exception) { }
                foreach (var proc in Process.GetProcessesByName(Path.GetFileNameWithoutExtension(exe)))
                    try { if (proc.StartTime >= began) proc.Kill(true); } catch (Exception) { }
            }
        }
        reader.Dispose();
        server.Stop();
    }

    /// <summary>The titles of this program's visible windows, for a check that failed.</summary>
    static List<string> TitlesOf(string exe)
    {
        var titles = new List<string>();
        Native.EnumWindows((h, _) =>
        {
            var n = Native.GetWindowTextLength(h);
            if (!Native.IsWindowVisible(h) || n == 0) return true;
            if (FrontApp.Of(h) is not { } a || a.Exe != exe) return true;
            var text = new StringBuilder(n + 1);
            Native.GetWindowText(h, text, text.Capacity);
            titles.Add(text.ToString());
            return true;
        }, IntPtr.Zero);
        return titles;
    }

    /// <summary>A visible top-level window of this program whose title has these words (the page's title), if one is open.</summary>
    static IntPtr WindowOf(string exe, string title)
    {
        var found = IntPtr.Zero;
        Native.EnumWindows((h, _) =>
        {
            var n = Native.GetWindowTextLength(h);
            if (!Native.IsWindowVisible(h) || n == 0) return true;
            var text = new StringBuilder(n + 1);
            Native.GetWindowText(h, text, text.Capacity);
            if (!text.ToString().Contains(title, StringComparison.Ordinal)) return true;
            if (FrontApp.Of(h) is { } a && a.Exe == exe) { found = h; return false; }
            return true;
        }, IntPtr.Zero);
        return found;
    }

    /// <summary>A window's content drawn to a PNG file in --shots.</summary>
    static async Task Shot(FrameworkElement? e, string name)
    {
        if (App.Options.Shots is not { } dir || e == null) return;
        try
        {
            Directory.CreateDirectory(dir);
            await Spin(150);
            var r = new RenderTargetBitmap();
            await r.RenderAsync(e);
            var bgra = (await r.GetPixelsAsync()).ToArray();
            // premultiplied BGRA laid on what the window's backdrop would show (Mica or acrylic, light or dark), as RGBA
            var back = Palette.Dark ? (R: 0x20, G: 0x20, B: 0x20) : (R: 0xF3, G: 0xF3, B: 0xF3);
            var rgba = new byte[bgra.Length];
            for (var i = 0; i < bgra.Length; i += 4)
            {
                var a = bgra[i + 3];
                rgba[i] = (byte)(bgra[i + 2] + back.R * (255 - a) / 255);
                rgba[i + 1] = (byte)(bgra[i + 1] + back.G * (255 - a) / 255);
                rgba[i + 2] = (byte)(bgra[i] + back.B * (255 - a) / 255);
                rgba[i + 3] = 255;
            }
            File.WriteAllBytes(Path.Join(dir, $"{name}.png"), Png.Encode(r.PixelWidth, r.PixelHeight, rgba));
            Add($"drawn: {name}", r.PixelWidth > 0 && r.PixelHeight > 0, $"{r.PixelWidth} x {r.PixelHeight}");
        }
        catch (Exception x) { Add($"drawn: {name}", false, x.Message); }
    }

    static void Finish(string? problem = null)
    {
        if (finished) return;
        finished = true;
        Check[] checks;
        lock (Gate)
        {
            if (problem != null) Checks.Add(new Check("the self-test ran to the end", false, problem));
            checks = [.. Checks];
        }
        var ok = checks.Length > 0 && checks.All(c => c.Ok);
        var report = new JsonObject
        {
            ["ok"] = ok,
            ["version"] = App.Version,
            ["checks"] = new JsonArray(checks.Select(c => (JsonNode?)new JsonObject { ["name"] = c.Name, ["ok"] = c.Ok, ["detail"] = c.Detail }).ToArray()),
        };
        try
        {
            if (App.Options.Out is { } path)
                File.WriteAllText(path, report.ToJsonString(new JsonSerializerOptions { WriteIndented = true, Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping }));
        }
        catch (Exception e) { Log.Write($"self-test results: {e.Message}"); }
        Environment.Exit(ok ? 0 : 1);
    }
}
