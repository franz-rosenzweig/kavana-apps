using Chataltul.Core;
using Microsoft.UI.Dispatching;

namespace Chataltul;

/// <summary>
/// Puts the pieces together: the tray icon and the popover, the cat in the corner, the speed bump, the
/// shortcuts, being away, the notifications. The Mac's AppDelegate.swift.
/// </summary>
sealed class Controller
{
    public Store Store { get; }
    public MessageWindow Messages { get; private set; } = null!;
    public Tray Tray { get; private set; } = null!;
    public Hotkeys Hotkeys { get; private set; } = null!;
    public Watcher Watcher { get; private set; } = null!;
    public AwayMonitor Away { get; private set; } = null!;
    public Notifier Notifier { get; private set; } = null!;
    public Popover Popover { get; private set; } = null!;
    public CatCorner Cat { get; private set; } = null!;
    public BumpWindow Bump { get; private set; } = null!;
    public ParkWindow Park { get; private set; } = null!;
    SettingsWindow? settings;
    public SettingsWindow Settings => settings ??= new SettingsWindow(Store, this);
    MainWindow? main;
    /// <summary>The app window: Today, the Café, History and Later.</summary>
    public MainWindow Main => main ??= new MainWindow(Store, this);
    public SyncController Sync { get; private set; } = null!;
    public WidgetHost Widgets { get; private set; } = null!;
    /// <summary>A widget was added, moved, resized or taken away (Settings shows the list).</summary>
    public event Action? WidgetsChanged;
    public SpeedBump Speed { get; set; } = new();
    /// <summary>The window that was in front before something on the list: where Back to work goes.</summary>
    public IntPtr Previous { get; private set; }
    /// <summary>What went wrong registering a shortcut, for Settings.</summary>
    public string? ShortcutProblem { get; private set; }

    readonly HashSet<string> shownCheckins = [];
    DispatcherQueueTimer? clock;
    int seconds;
    bool taskbarDark = true;

    public Controller(Store store) { Store = store; }

    /// <summary>Everything after the store exists. <c>firstShow</c>: started by a person, so say hello.</summary>
    public void Start(bool firstShow)
    {
        var test = App.Options.SelfTest;
        Ui.Init();
        Messages = new MessageWindow();
        Tray = new Tray(Messages);
        Hotkeys = new Hotkeys(Messages);
        Watcher = new Watcher(Store) { Disabled = test };
        Away = new AwayMonitor(Store, Messages);
        Notifier = new Notifier(enabled: !test);
        Popover = new Popover(Store, this);
        Cat = new CatCorner();
        Bump = new BumpWindow();
        Park = new ParkWindow(Store);
        // the session in Windows' Credential Manager; a test copy keeps it in a file of its own folder
        Sync = new SyncController(Store, test ? new FileSession(Path.Join(Store.Folder, "session")) : new CredentialSession("xyz.tiresia.chataltul.session"));
        Widgets = new WidgetHost(Store, this);
        Widgets.Changed += () => WidgetsChanged?.Invoke();
        taskbarDark = Palette.TaskbarDark();

        Bump.BackToWork = () => Apply(Speed.BackToWork());
        Bump.LeaveHeld = () => Apply(Speed.LeaveHeld(Clock.Now));
        Bump.EditList = () => OpenSettings("blocklist");
        Tray.Click += (x, y) => Popover.Toggle(new Native.POINT { X = x, Y = y });
        Tray.Menu += TrayMenu;
        Messages.Wake += () => Popover.Show();
        Messages.Command += Do;
        Messages.Ending += () => Quit(force: true);
        Messages.ThemeChanged += () => { taskbarDark = Palette.TaskbarDark(); RefreshTray(Clock.Now); Popover.Refresh(); };
        Watcher.Front += Looked;
        Watcher.FirstBrowser += SayBrowser;
        Notifier.Answer += AnswerFromNotification;
        Notifier.Open += () => Popover.Show();

        Store.Due += Deliver;
        Store.Changed += Changed;
        Store.DocChanged += Sync.Queue;
        Store.AwayPromptChanged += () => { if (Store.AwayPrompt != null) Popover.Show(); else Popover.Refresh(); };
        Store.SettingsChanged += () => { RegisterShortcuts(); Popover.Refresh(); Widgets.Refresh(); };

        if (!test && !Tray.Add()) Log.Write("no taskbar to put the icon in yet; it comes when Explorer starts");
        RegisterShortcuts();
        Changed(Store.State);
        clock = Ui.Every(TimeSpan.FromSeconds(1), () => Second(Clock.Now));
        Startup.Refresh();
        Sync.Opened();
        if (!test) Widgets.Load();
        JumpList.Write();
        if (App.Options.Do is { } task) Ui.Post(() => Do(task));
        else if (firstShow)
        {
            if (!Store.Settings.Welcomed) Welcome();
            else Popover.Show();
        }
    }

    /// <summary>A task from the jump list: start 25 or 50, park a thought, open the café.</summary>
    public void Do(string task)
    {
        switch (task)
        {
            case "park": ShowPark(); break;
            case "cafe": OpenWindow("cafe"); break;
            case "window": OpenWindow(null); break;
            case var s when s.StartsWith("start:") && int.TryParse(s[6..], out var m) && m is > 0 and <= 180:
                if (Store.Active == null && Store.Awaiting == null) Store.Start("", m);
                Popover.Show();
                break;
            default: Popover.Show(); break;
        }
    }

    public void Quit(bool force = false)
    {
        clock?.Stop();
        Tray?.Remove();
        Hotkeys?.ClearAll();
        Watcher?.Dispose();
        Microsoft.UI.Xaml.Application.Current.Exit();
    }

    // ---------------------------------------------------------------- the tray

    /// <summary>Where the popover hangs: the icon, else the corner of the screen by the clock.</summary>
    public Native.POINT TrayPoint()
    {
        if (Tray.Rect() is { } r) return new Native.POINT { X = (r.Left + r.Right) / 2, Y = (r.Top + r.Bottom) / 2 };
        var work = Native.WorkAreaAt(new Native.POINT());
        return new Native.POINT { X = work.Right - (int)(180 * Floating.ScaleAt(new Native.POINT())), Y = work.Bottom + 1 };
    }

    void RefreshTray(long now)
    {
        var s = TrayState.Of(Store.Active, Store.Awaiting, Store.OnBreak, now);
        var name = Store.Awaiting is { Kitten: "home" } w ? Store.Cat(w.Cat).Name : null;
        Tray.Show(s, s.Tip(Store.Active?.Title, name), taskbarDark);
    }

    void TrayMenu(int x, int y)
    {
        switch (Tray.PopUp(x, y, (1, "Open Chataltul"), (4, "Today and the café"), (5, "Widgets…"), (2, "Settings…"), (0, null), (3, "Quit Chataltul")))
        {
            case 1: Popover.Show(); break;
            case 4: OpenWindow(null); break;
            case 5: OpenSettings("widgets"); break;
            case 2: OpenSettings(null); break;
            case 3: Quit(); break;
        }
    }

    // ---------------------------------------------------------------- shortcuts

    /// <summary>Settings is waiting for the keys of a new shortcut: none is taken meanwhile, so the keys reach it.</summary>
    public bool Recording { get; set; }

    public void RegisterShortcuts()
    {
        if (Recording) return;
        var s = Store.Settings;
        var taken = new List<string>();
        if (!Hotkeys.Set("start", s.StartKey, StartShortcut)) taken.Add(s.StartKey.Label);
        if (!Hotkeys.Set("park", s.ParkKey, () => { Popover.Hide(); Park.Toggle(); })) taken.Add(s.ParkKey.Label);
        RegisterCheckinKeys();
        ShortcutProblem = taken.Count == 0 ? null : $"{string.Join(" and ", taken)} {(taken.Count == 1 ? "is" : "are")} taken by another app. Pick other keys here.";
    }

    /// <summary>Yes and Drifted are only taken while a check-in is waiting, so those keys are free in other apps the rest of the time.</summary>
    void RegisterCheckinKeys()
    {
        var s = Store.Settings;
        if (Store.Active?.Pending != null)
        {
            Hotkeys.Set("yes", s.YesKey, () => Answer("yes"));
            Hotkeys.Set("drifted", s.DriftedKey, () => Answer("drifted"));
        }
        else
        {
            Hotkeys.Clear("yes");
            Hotkeys.Clear("drifted");
        }
    }

    /// <summary>Ctrl+Alt+Space: open the popover with the cursor in the title; pressed again, start the block.</summary>
    public void StartShortcut()
    {
        if (Popover.IsShown && Popover.Pane == "ready" && !Park.IsShown)
        {
            Popover.StartFromShortcut();
            Popover.Hide();
            return;
        }
        Popover.Show(focusTitle: true);
    }

    // ---------------------------------------------------------------- check-ins and the other pings

    public void Answer(string a)
    {
        if (Store.Active is not { Pending: { } n } b) return;
        var c = Store.Cat(b.Cat);
        Store.Answer(a);
        Notifier.ClearAll();
        Cat.Hide();
        if (a == "drifted") AskReason(b.Id, n, c);
    }

    /// <summary>After Drifted: what pulled you away? A press on the card is kept with the check-in.</summary>
    void AskReason(string id, int n, CatInfo c)
    {
        if (!Store.State.CatsOn || !Store.Settings.CatInCorner) return;
        var buttons = Reasons.Quick.Select(r => new CatButton(Reasons.Label(r), () => NoteReason(id, n, r)))
            .Append(new CatButton("Other", () => NoteReason(id, n, "other"))).ToList();
        Cat.Show("reason", new CatCardModel(c.Id, "No worries. What pulled you away?") { Label = "Drifted", Buttons = buttons, Compact = true }, 20);
    }

    void NoteReason(string id, int n, string why)
    {
        Store.Act("answer", new() { ["id"] = id, ["n"] = n, ["answer"] = "drifted", ["why"] = why });
        Cat.Hide("reason");
    }

    void AnswerFromNotification(string id, int n, string a)
    {
        if (Store.Active?.Id != id) return;
        Store.Act("answer", new() { ["id"] = id, ["n"] = n, ["answer"] = a });
        Cat.Hide();
        if (a == "drifted") AskReason(id, n, Store.Cat(Store.Active?.Cat));
    }

    bool CatShows => Store.State.CatsOn && Store.Settings.CatInCorner;

    void Say(string title, string? body, string cat, double seconds, string kind = "say", string? picture = null)
    {
        if (!CatShows) return;
        Cat.Show(kind, new CatCardModel(cat, title) { Body = body, Picture = picture }, seconds);
    }

    void ShowCheckin(Block b, int n)
    {
        var c = Store.Cat(b.Cat);
        var left = Math.Max(0, b.End - Clock.Now);
        var title = b.Intention.Length == 0 ? "Boo! Still focusing?" : $"Boo! Still on: {b.Intention}?";
        var s = Store.Settings;
        var model = new CatCardModel(c.Id, title)
        {
            Label = "Check-in",
            Body = $"{Format.MinLeft(left)} min left · answer here, no need to switch apps",
            Buttons = [new("Yes", () => Answer("yes"), true, V.Glyph.Check), new("Drifted", () => Answer("drifted"), false, V.Glyph.Drift)],
            Hint = $"Or press {s.YesKey.Label} for Yes, {s.DriftedKey.Label} for Drifted",
        };
        // nobody answered: it waits as a notification, and in the tray icon's orange dot
        Cat.Show("checkin", model, 90, () => Notifier.Post(title, "Answer here, or in Chataltul.", b.Id, n));
    }

    /// <summary>A check-in that is waiting: the cat shows it once (the ping that brought it is already gone).</summary>
    void ShowPendingCheckin(UiState st)
    {
        if (st.View.Active is not { Pending: { } n } b || !CatShows) return;
        if (!shownCheckins.Add($"{b.Id}:{n}")) return;
        ShowCheckin(b, n);
    }

    void Deliver(List<Ping> due)
    {
        var st = Store.State;
        foreach (var p in due)
        {
            if (!CatShows)
            {
                var body = p.Kind == "checkin" ? p.Body.Replace(" Tap to answer.", "") : p.Body;
                Notifier.Post(p.Title, body, p.Kind == "checkin" ? p.Block : null, p.N);
                continue;
            }
            var catId = st.View.Active?.Cat ?? st.View.Awaiting?.Cat ?? st.Companion;
            var title = p.Title.Replace("🐱 ", "");
            switch (p.Kind)
            {
                case "checkin": break;   // ShowPendingCheckin does it
                case "nudge":
                    Cat.Show("nudge", new CatCardModel(catId, p.Body) { Body = st.View.Active?.Intention }, 9);
                    break;
                case "end":
                    if (st.View.Active is { } a)
                        Cat.Show("end", new CatCardModel(a.Cat, title) { Body = p.Body, Buttons = [new("Open Chataltul", () => { Cat.Hide(); Popover.Show(); }, true)] }, 30);
                    else if (st.View.Awaiting is { } w)
                    {
                        var c = st.Cat(w.Cat);
                        var says = w.Kitten == "home" ? $"{c.Name} came home to your café. +{w.Treats} treats" : $"{c.Name} wandered off.";
                        var text = string.Join("\n", new[] { w.Intention.Length == 0 ? null : w.Intention, says }.Where(x => x != null));
                        Action Outcome(string o) => () => { Store.Outcome(w.Id, o); Cat.Hide(); Notifier.ClearAll(); };
                        Cat.Show("end", new CatCardModel(w.Cat, title)
                        {
                            Body = text,
                            Buttons = [new("Done", Outcome("done"), true), new("Partly", Outcome("partly")), new("Not really", Outcome("notreally"))],
                        }, 60, () => Notifier.Post(p.Title, p.Body));
                    }
                    break;
                case "break-over":
                    if (Store.LongBreakEnd != null) continue;   // a longer break is still on: the app says when it is over
                    Cat.Show("break", new CatCardModel(catId, title)
                    {
                        Body = "It's time to focus. Ready for the next block?",
                        Buttons = [new("Open Chataltul", () => { Cat.Hide(); Popover.Show(); }, true)],
                    }, 30, () => Notifier.Post(p.Title, p.Body));
                    break;
            }
        }
    }

    // ---------------------------------------------------------------- after every change, and every second

    void Changed(UiState st)
    {
        RefreshTray(Clock.Now);
        Popover.Refresh();
        RegisterCheckinKeys();
        ShowPendingCheckin(st);
        if (st.View.Active?.Pending == null) Cat.Hide("checkin");
        if (st.View.Active is not { Kitten: "growing", Paused: null }) Apply(Speed.Tick(BumpBlock(), Clock.Now));
        if (st.View.Active != null) Watcher.Look();
        main?.Refresh();
        Widgets.Refresh();
    }

    public void Second(long now)
    {
        seconds++;
        var hadBreak = Store.LongBreakEnd != null;
        Store.Second(now);
        if (hadBreak && Store.LongBreakEnd == null) Say("Break's over.", "It's time to focus. Ready for the next block?", Store.State.Companion, 20);
        RefreshTray(now);
        Popover.Tick(now);
        main?.Tick(now);
        Widgets.Tick(now);
        if (seconds % 2 == 0) Away.Sample(now);
        if (Bumpable) Watcher.Look();
        Apply(Speed.Tick(BumpBlock(), now));
        if (Store.Active?.Pending != null) ShowPendingCheckin(Store.State);
    }

    // ---------------------------------------------------------------- the speed bump

    /// <summary>A block the cat may guard: running, kitten growing, and a list to guard.</summary>
    public bool Bumpable => BumpBlock() is { Open: true };

    public BumpBlock? BumpBlock()
    {
        var s = Store.Settings;
        if (!s.SpeedBump || s.Blocklist.IsEmpty || !Store.State.CatsOn || Store.Active is not { } b) return null;
        return new BumpBlock(b.Id, b.Paused != null, b.Kitten, b.Cat, Store.Cat(b.Cat).Name, b.Title, b.End);
    }

    /// <summary>Something came to the front (or was looked at again): the speed bump may answer.</summary>
    public void Looked(GuardHit? hit, FrontApp app)
    {
        if (hit == null) Previous = app.Hwnd;
        var now = Clock.Now;
        if (!Store.Settings.SpeedBump) { Apply(Speed.Front(null, app.Exe, null, now)); return; }
        // version 0.1 is the speed bump for every list (wait it out and locked come with 0.2)
        Apply(Speed.Front(hit?.Target, hit == null ? app.Exe : null, BumpBlock(), now));
    }

    public void Apply(List<BumpAction> actions)
    {
        foreach (var a in actions)
            switch (a)
            {
                case BumpAction.Show s:
                    Bump.GraceMs = Speed.GraceMs;
                    Bump.Show(s.Card);
                    Cat.Hide();
                    break;
                case BumpAction.Update u: Bump.Update(u.Card); break;
                case BumpAction.Hide: Bump.Hide(); break;
                case BumpAction.Away w: Store.KittenLeaves(w.At, w.Grace); break;
                case BumpAction.Wandered w:
                    Say($"{w.CatName} wandered off.", "Your block keeps going.", Store.Active?.Cat ?? Store.State.Companion, 4, "gone", Art.Gone);
                    break;
                case BumpAction.Activate: Watcher.Activate(Previous); break;
            }
    }

    /// <summary>The first time a browser is read: the cat says plainly what it looks at, once.</summary>
    void SayBrowser(Browser browser)
    {
        if (Store.Settings.BrowserNoticeShown) return;
        Store.ChangeSettings(s => s.BrowserNoticeShown = true);
        if (!Store.Running || !CatShows) return;
        Cat.Show("browser", new CatCardModel(Store.ShownCat.Id, $"I can see which site is open in {browser.Name}.")
        {
            Label = "So you know",
            Body = "Your list has websites on it, so I look at the address of the front tab during a block. I only ever note the site's name, and nothing leaves this PC.",
            Buttons = [new("OK", () => Cat.Hide("browser"), true), new("Settings", () => { Cat.Hide("browser"); OpenSettings("blocklist"); })],
        }, 60);
    }

    // ---------------------------------------------------------------- windows

    void Welcome()
    {
        Store.ChangeSettings(s => s.Welcomed = true);
        Cat.Show("welcome", new CatCardModel(Store.State.Companion, "I live by the clock now.")
        {
            Label = "Hello",
            Body = "Click the kitten in the notification area to start a block. If you can't see it, open the ^ beside the clock and drag me onto the taskbar.",
            Buttons = [new("Open Chataltul", () => { Cat.Hide(); Popover.Show(); }, true), new("Got it", () => Cat.Hide())],
        }, 120);
    }

    public void OpenSettings(string? pane)
    {
        Popover.Hide();
        Settings.Show(pane);
    }

    /// <summary>The app window's café (the shop and the cat collection are the web app's).</summary>
    public void OpenCafe() => OpenWindow("cafe");

    /// <summary>The app window, on a page (today, cafe, history, later) and a span.</summary>
    public void OpenWindow(string? pane, Core.Span? span = null)
    {
        Popover.Hide();
        Main.Show(pane, span);
    }

    public void ShowPark()
    {
        Popover.Hide();
        Park.Show();
    }
}
