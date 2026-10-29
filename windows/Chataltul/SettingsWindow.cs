using Chataltul.Core;
using Microsoft.UI;
using Microsoft.UI.Text;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Windows.Graphics;
using Windows.System;

namespace Chataltul;

/// <summary>
/// Settings: General, Blocks and check-ins, Blocklist, Widgets, Shortcuts, Account and sync, as Windows 11's own
/// Settings looks (MicaShell). The Mac's SettingsWindow.swift (Breaks come with 0.3 on Windows).
/// </summary>
sealed class SettingsWindow
{
    readonly Store store;
    readonly Controller app;
    readonly MicaShell shell;
    public Window Window => shell.Window;
    public string Pane { get; private set; } = "general";
    public bool IsShown => shell.IsShown;
    /// <summary>Which shortcut is waiting for its new keys, if any.</summary>
    string? recording;
    /// <summary>A change made by a control on the page, which shows it already: the page isn't built again under it.</summary>
    bool quiet;

    void Quietly(Action<WinSettings> edit)
    {
        quiet = true;
        try { store.ChangeSettings(edit); } finally { quiet = false; }
    }

    static readonly (string Id, string Title, string Glyph)[] Panes =
    [
        ("general", "General", ""), ("blocks", "Blocks and check-ins", ""), ("blocklist", "Blocklist", ""), ("breaks", "Breaks", "\uE708"), ("widgets", "Widgets", "\uECA5"), ("shortcuts", "Shortcuts", ""), ("account", "Account and sync", "\uE77B"),
    ];

    // what is being typed on the account page, kept when the page is built again
    string email = "", code = "", accountName = "", password = "", current = "";
    bool usePassword, deleting;
    /// <summary>A key is going into a field on the account page: the page waits to be built again until it's done.</summary>
    bool typing;

    public SettingsWindow(Store store, Controller app)
    {
        this.store = store;
        this.app = app;
        shell = new MicaShell("Chataltul Settings", Panes, 900, 640) { Maximizable = false, StartZoom = store.Settings.TextZoom };
        shell.Picked += id => { if (id != Pane) { Pane = id; Build(); } };
        shell.Zoomed += z => Quietly(s => s.TextZoom = z);
        shell.BeforeKey = OnKey;
        shell.Hidden += () =>
        {
            recording = null;
            app.Recording = false;
            app.RegisterShortcuts();
        };
        store.SettingsChanged += () => { if (IsShown && recording == null && !quiet) Build(); };
        app.Sync.Changed += () => { if (IsShown && Pane == "account" && !typing) Build(); };
        app.WidgetsChanged += () => { if (IsShown && Pane == "widgets") Build(); };
    }

    /// <summary>The text size, as Ctrl+Plus, Ctrl+Minus and Ctrl+0 change it.</summary>
    public void Zoom(float factor) => shell.Zoom(factor);

    public float ZoomFactor => shell.ZoomFactor;

    public void Show(string? pane)
    {
        if (pane != null) Pane = pane;
        Palette.Dark = Palette.SystemDark();
        shell.Theme = Palette.Theme;
        shell.Select(Pane);
        Build();
        shell.Show();
    }

    public void Hide() => shell.Hide();

    public UIElement Build()
    {
        UIElement content = Pane switch
        {
            "blocks" => Blocks(),
            "blocklist" => BlocklistPane(),
            "breaks" => BreaksPane(),
            "widgets" => WidgetsPane(),
            "shortcuts" => Shortcuts(),
            "account" => AccountPane(),
            _ => General(),
        };
        shell.SetContent(content);
        return content;
    }

    // ---------------------------------------------------------------- pieces

    static TextBlock Heading(string text) => new() { Text = text, FontSize = 24, FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 0, 0, 8) };
    static TextBlock Section(string text) => new() { Text = text, FontSize = 15, FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 14, 0, 2) };
    static TextBlock Note(string text) => new() { Text = text, FontSize = 12, Opacity = 0.7, TextWrapping = TextWrapping.WrapWholeWords };

    static ToggleSwitch Switch(string header, bool on, Action<bool> changed)
    {
        var t = new ToggleSwitch { Header = header, IsOn = on };
        t.Toggled += (_, _) => changed(t.IsOn);
        return t;
    }

    static ComboBox Choice<T>(string header, IEnumerable<(T Value, string Label)> items, T now, Action<T> changed)
    {
        var list = items.ToList();
        var c = new ComboBox { Header = header, MinWidth = 220 };
        foreach (var (_, label) in list) c.Items.Add(label);
        c.SelectedIndex = Math.Max(0, list.FindIndex(i => EqualityComparer<T>.Default.Equals(i.Value, now)));
        c.SelectionChanged += (_, _) => { if (c.SelectedIndex >= 0) changed(list[c.SelectedIndex].Value); };
        return c;
    }

    static StackPanel Col(params UIElement?[] children)
    {
        var s = new StackPanel { Spacing = 10, MaxWidth = 620, HorizontalAlignment = HorizontalAlignment.Left };
        foreach (var c in children) if (c != null) s.Children.Add(c);
        return s;
    }

    void Engine(string key, object? value) => store.SetEngine(new() { [key] = value });

    // ---------------------------------------------------------------- general

    UIElement General()
    {
        var commitFile = Path.Join(AppContext.BaseDirectory, "js", "WEB_COMMIT");
        var commit = File.Exists(commitFile) ? File.ReadAllText(commitFile).Trim() : "?";
        var web = commit[..Math.Min(7, commit.Length)];
        var folder = new Button { Content = "Open the folder" };
        folder.Click += (_, _) => Outside.Open(store.Folder);
        var forget = new Button { Content = "Forget where my blocks went", IsEnabled = !store.Timing.IsEmpty };
        forget.Click += (_, _) => { store.ForgetTiming(); Build(); };
        var runStart = TypedBox("runStart", @"A program, a shortcut, a script or a link", 420, () => { });
        runStart.Header = "When a block starts, run";
        runStart.Text = store.Settings.RunAtStart;
        runStart.LostFocus += (_, _) => Quietly(s => s.RunAtStart = runStart.Text.Trim());
        var runEnd = TypedBox("runEnd", @"A program, a shortcut, a script or a link", 420, () => { });
        runEnd.Header = "When it ends, run";
        runEnd.Text = store.Settings.RunAtEnd;
        runEnd.LostFocus += (_, _) => Quietly(s => s.RunAtEnd = runEnd.Text.Trim());
        return Col(
            Heading("General"),
            Switch("Open Chataltul when I sign in to Windows", Startup.IsOn, on => Startup.Set(on)),
            Switch("Cats and treats (the kitten that grows in each block)", store.State.CatsOn, on => Engine("cat", on)),
            Switch("The cat peeks up in the corner of the screen for check-ins", store.Settings.CatInCorner, on => Quietly(s => s.CatInCorner = on)),
            Note("Off, check-ins come as Windows notifications only."),
            Section("Where the time goes"),
            Switch("Note which app each block's time goes to", store.Settings.RecordTiming, on => Quietly(s => s.RecordTiming = on)),
            Note("While a block runs, Chataltul notes which app is in front and, in a browser, the site's name (never the address or the page). It shows when a block comes home and in Today. It stays in a file on this PC and is never sent anywhere, not even with an account."),
            forget,
            Section("At a block's start and end"),
            Note("Windows doesn't let an app turn Do not disturb on itself, but you can: name a shortcut or a script that does it (or anything else) and Chataltul runs it when a block starts, and the other one when it ends."),
            runStart, runEnd,
            Section("Where your things are"),
            Note($"The doc, these settings and the log are in {store.Folder}. " + (app.Sync.SignedIn
                ? "Signed in, your blocks, Later, cats and settings are also kept by the web app's server."
                : "Without an account nothing leaves this PC.")),
            folder,
            Section("About"),
            Note($"Chataltul {App.Version} for Windows, with the web's engine at {web}."));
    }

    // ---------------------------------------------------------------- blocks and check-ins

    UIElement Blocks()
    {
        var s = store.EngineSettings;
        var intervals = new[] { 0, 10, 15, 20, 30, 45, 60 }.Select(i => (i, i == 0 ? "Not during a block" : $"Every {i} minutes"));
        var after = new NumberBox
        {
            Header = "Ask after this many minutes away from the PC", Value = store.Settings.AwayAfterMin, Minimum = 1, Maximum = 60,
            SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Inline, SmallChange = 1, MinWidth = 220, HorizontalAlignment = HorizontalAlignment.Left,
        };
        after.ValueChanged += (_, e) =>
        {
            if (!double.IsNaN(e.NewValue)) Quietly(x => x.AwayAfterMin = Math.Clamp((int)Math.Round(e.NewValue), 1, 60));
        };
        var awayChoices = new List<(AwayAction, string)> { (AwayAction.Ask, "Ask me whether they count"), (AwayAction.Count, "Count them") };
        if (store.State.CanTakeOff) awayChoices.Add((AwayAction.TakeOff, "Take them off"));
        var cycle = store.Settings.Cycle;
        var perDay = new NumberBox { Header = "Blocks in a day (the dashes in the popover)", Value = cycle.PerDay, Minimum = 1, Maximum = 16, SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Inline, MinWidth = 220, HorizontalAlignment = HorizontalAlignment.Left };
        perDay.ValueChanged += (_, e) => { if (!double.IsNaN(e.NewValue)) Quietly(x => x.Cycle.PerDay = Math.Clamp((int)e.NewValue, 1, 16)); };
        var longAfter = new NumberBox { Header = "A long break after this many blocks", Value = cycle.LongAfter, Minimum = 0, Maximum = 12, SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Inline, MinWidth = 220, HorizontalAlignment = HorizontalAlignment.Left };
        longAfter.ValueChanged += (_, e) => { if (!double.IsNaN(e.NewValue)) Quietly(x => x.Cycle.LongAfter = Math.Clamp((int)e.NewValue, 0, 12)); };
        return Col(
            Heading("Blocks and check-ins"),
            Choice("Check in", intervals, s.Interval, i => Engine("interval", i)),
            Choice("How", new[] { ("ask", "Ask me (Yes or Drifted)"), ("nudge", "Nudge me (a line, no answer)") }, s.Style, v => Engine("style", v)),
            Choice("Pausing", new[] { ("five", "Five minutes at most"), ("off", "No pausing") }, s.Pause, v => Engine("pause", v)),
            Switch("Count extra time: the block goes on past its end until I press Stop", s.Overtime, on => Engine("overtime", on)),
            Note("These are the web's own settings, so with an account they would follow you to the web and the phone."),
            Section("Away from the PC"),
            after,
            Choice("When I'm back", awayChoices, store.Settings.AwayAction, a => Quietly(x => x.AwayAction = a)),
            Note(store.State.CanTakeOff ? "Being away isn't drifting: the kitten stays either way."
                : "Being away isn't drifting: the kitten stays either way. \"Take them off\" needs a change in the web's engine (mac/docs/engine-takeoff.md), so for now the minutes count."),
            Section("The day"),
            perDay,
            longAfter);
    }

    // ---------------------------------------------------------------- the blocklist

    /// <summary>The colors a list can have, for its dot (the Mac's listColors).</summary>
    static readonly (uint Rgb, string Name)[] ListColors = [(0xE98A3C, "Orange"), (0x2C8A7E, "Teal"), (0x7C5CC4, "Violet"), (0x2F7FD8, "Blue"), (0xD94F70, "Pink"), (0x5E9E3A, "Green")];

    /// <summary>A list's name, link and allowed site being typed, kept while the page is built again.</summary>
    readonly Dictionary<string, string> typed = [];
    string Typed(string key) => typed.GetValueOrDefault(key, "");

    /// <summary>A list arrived or failed (on the lists' own thread): the page shows it.</summary>
    public void ListsChanged() { if (IsShown && Pane == "blocklist" && !typing) Build(); }

    UIElement BlocklistPane()
    {
        var b = store.Settings.Blocklist;
        var lists = new StackPanel { Spacing = 10 };
        foreach (var l in b.Lists.ToList()) lists.Children.Add(ListCard(l, b.Lists.Count > 1));
        var addList = new Button { Content = "Add a list" };
        addList.Click += (_, _) => store.ChangeSettings(s => s.Blocklist.Lists.Add(new GuardList { Name = $"List {s.Blocklist.Lists.Count + 1}", Color = s.Blocklist.Lists.Count % ListColors.Length }));
        return Col(
            Heading("Blocklist"),
            Switch("Watch my lists: the cat comes up over what's on them", store.Settings.SpeedBump, on => Quietly(s => s.SpeedBump = on)),
            Note("Each list says what happens when something on it opens. The speed bump: the cat walks over it, 15 seconds to come back, or hold Leave anyway and your kitten wanders off. Wait it out: 30 seconds with the cat before it opens, every time. Locked: it doesn't open until the block ends, the block can't be stopped after its first ten seconds, and Chataltul won't quit meanwhile."),
            lists,
            addList,
            Section("Browsers"),
            Switch("Look at the site open in my browser", store.Settings.ReadBrowsers, on => Quietly(s => s.ReadBrowsers = on)),
            Note("In " + string.Join(", ", Browser.All.Where(x => !x.Gecko).Select(x => x.Name)) + ", Chataltul reads the address of the front tab to find the sites on your lists (Firefox and Zen are tried too). It only ever uses the site's name, and nothing leaves this PC."),
            Section("Where the ready-made lists come from"),
            Note("UT1, the Université Toulouse Capitole's blacklists (CC BY-SA 4.0); StevenBlack's hosts files (MIT); Hagezi's DNS blocklists (GPL-3.0). None is bundled: a list is downloaded over https when it's switched on and looked at again about once a week. Only the site that hosts a list sees that it was downloaded. What's kept is a short code for each site, not the names."));
    }

    void EditList(string id, Action<GuardList> edit, bool quietly = false)
    {
        void Change(WinSettings s) { if (s.Blocklist.Lists.FirstOrDefault(x => x.Id == id) is { } l) edit(l); }
        if (quietly) Quietly(Change); else store.ChangeSettings(Change);
    }

    UIElement ListCard(GuardList l, bool removable)
    {
        var dot = new Microsoft.UI.Xaml.Shapes.Ellipse { Width = 12, Height = 12, Fill = new SolidColorBrush(Palette.C(ListColors[Math.Clamp(l.Color, 0, ListColors.Length - 1)].Rgb)), VerticalAlignment = VerticalAlignment.Center };
        var what = new List<string>();
        if (l.Apps.Count > 0) what.Add(Format.Plural(l.Apps.Count, "app"));
        if (l.Sites.Count > 0) what.Add(Format.Plural(l.Sites.Count, "site"));
        if (l.Categories.Count > 0) what.Add(Format.Plural(l.Categories.Count, "ready-made list"));
        var mode = l.Mode switch { GuardMode.WaitItOut => "wait it out", GuardMode.Locked => "locked", _ => "the speed bump" };
        var header = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10 };
        header.Children.Add(dot);
        header.Children.Add(new TextBlock { Text = l.Name, FontSize = 14, FontWeight = FontWeights.SemiBold, VerticalAlignment = VerticalAlignment.Center });
        header.Children.Add(new TextBlock { Text = (what.Count == 0 ? "empty" : string.Join(", ", what)) + $" · {mode}", FontSize = 12, Opacity = 0.7, VerticalAlignment = VerticalAlignment.Center });

        var name = new TextBox { Header = "Name", Text = l.Name, MinWidth = 220 };
        name.LostFocus += (_, _) => { var n = name.Text.Trim(); if (n.Length > 0 && n != l.Name) EditList(l.Id, x => x.Name = n); };
        var color = Choice("Color", ListColors.Select((c, i) => (i, c.Name)), l.Color, i => EditList(l.Id, x => x.Color = i));
        var modeBox = Choice("When something on it opens", new[] { (GuardMode.SpeedBump, "The speed bump"), (GuardMode.WaitItOut, "Wait it out (30 seconds)"), (GuardMode.Locked, "Locked until the block ends") },
            l.Mode, m => EditList(l.Id, x => x.Mode = m));
        var when = Choice("Watched", new[] { (GuardWhen.Kinds.Blocks, "During my blocks"), (GuardWhen.Kinds.Weekdays, "And on weekdays between two times"), (GuardWhen.Kinds.Always, "Always") },
            l.When.Kind, k => EditList(l.Id, x => x.When.Kind = k));
        UIElement? hours = null;
        if (l.When.Kind == GuardWhen.Kinds.Weekdays)
        {
            var from = new TimePicker { Header = "From", Time = TimeSpan.FromMinutes(l.When.FromMin), MinuteIncrement = 15 };
            var to = new TimePicker { Header = "To", Time = TimeSpan.FromMinutes(l.When.ToMin), MinuteIncrement = 15 };
            from.SelectedTimeChanged += (_, e) => { if (e.NewTime is { } t) EditList(l.Id, x => x.When.FromMin = (int)t.TotalMinutes, quietly: true); };
            to.SelectedTimeChanged += (_, e) => { if (e.NewTime is { } t) EditList(l.Id, x => x.When.ToMin = (int)t.TotalMinutes, quietly: true); };
            hours = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12, Children = { from, to } };
        }
        UIElement? allowance = null;
        if (l.When.Kind != GuardWhen.Kinds.Blocks)
        {
            var box = new NumberBox
            {
                Header = "Minutes a day allowed outside blocks (0: none)", Value = l.AllowanceMin, Minimum = 0, Maximum = 600, SmallChange = 5,
                SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Inline, MinWidth = 220, HorizontalAlignment = HorizontalAlignment.Left,
            };
            box.ValueChanged += (_, e) => { if (!double.IsNaN(e.NewValue)) EditList(l.Id, x => x.AllowanceMin = Math.Clamp((int)e.NewValue, 0, 600), quietly: true); };
            var used = l.AllowanceMin > 0 ? Note($"{Format.FmtMin((int)(store.UsedMs(l.Id) / 60_000))} of it used today. When it's used up, the list stops everything on it for the rest of the day.") : null;
            allowance = Col(box, used);
        }

        // apps
        var apps = new StackPanel { Spacing = 6 };
        foreach (var a in l.Apps.ToList()) apps.Children.Add(Item(a.Name, a.Exe, () => EditList(l.Id, x => x.Apps.RemoveAll(y => y.Exe == a.Exe))));
        if (l.Apps.Count == 0) apps.Children.Add(Note("No apps."));
        var add = new DropDownButton { Content = "Add an app" };
        var menu = new MenuFlyout { Placement = Microsoft.UI.Xaml.Controls.Primitives.FlyoutPlacementMode.Bottom };
        menu.Opening += (_, _) =>
        {
            menu.Items.Clear();
            foreach (var (exe, appName) in RunningApps())
            {
                var item = new MenuFlyoutItem { Text = $"{appName}  ({exe})" };
                item.Click += (_, _) => store.ChangeSettings(s => s.Blocklist.AddApp(new ListedApp(exe, appName), l.Id));
                menu.Items.Add(item);
            }
            if (menu.Items.Count == 0) menu.Items.Add(new MenuFlyoutItem { Text = "No other apps are open", IsEnabled = false });
            menu.Items.Add(new MenuFlyoutSeparator());
            var browse = new MenuFlyoutItem { Text = "Find a program…" };
            browse.Click += async (_, _) => await Browse(l.Id);
            menu.Items.Add(browse);
        };
        add.Flyout = menu;

        // sites
        var sites = new StackPanel { Spacing = 6 };
        foreach (var d in l.Sites.ToList()) sites.Children.Add(Item(d, null, () => EditList(l.Id, x => x.Sites.Remove(d))));
        if (l.Sites.Count == 0) sites.Children.Add(Note("No sites typed in."));
        var problem = Note("");
        var siteBox = TypedBox($"site:{l.Id}", "youtube.com", 260, () =>
        {
            var t = Typed($"site:{l.Id}");
            if (Blocklist.NormalizeDomain(t) == null) { problem.Text = "That isn't a site's address."; return; }
            typed.Remove($"site:{l.Id}");
            store.ChangeSettings(s => s.Blocklist.AddSite(t, l.Id));
        });
        var addSite = new Button { Content = "Add" };
        addSite.Click += (_, _) => { var t = Typed($"site:{l.Id}"); if (Blocklist.NormalizeDomain(t) != null) { typed.Remove($"site:{l.Id}"); store.ChangeSettings(s => s.Blocklist.AddSite(t, l.Id)); } else problem.Text = "That isn't a site's address."; };

        var remove = removable ? new HyperlinkButton { Content = $"Take the {l.Name} list away" } : null;
        if (remove != null) remove.Click += (_, _) => store.ChangeSettings(s => s.Blocklist.Lists.RemoveAll(x => x.Id == l.Id));

        var body = Col(
            new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12, Children = { name, color } },
            modeBox, when, hours, allowance,
            Section("Apps"), apps, add,
            Section("Websites"), sites, new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, Children = { siteBox, addSite } }, problem,
            Note("A site covers its subdomains (youtube.com covers m.youtube.com)."),
            Section("Ready-made lists"), ReadyMade(l),
            remove);
        var exp = new Expander
        {
            Header = header, Content = body, IsExpanded = store.Settings.Blocklist.Lists.Count == 1 || expanded.Contains(l.Id),
            HorizontalAlignment = HorizontalAlignment.Stretch, HorizontalContentAlignment = HorizontalAlignment.Left, MinWidth = 560,
        };
        exp.Expanding += (_, _) => expanded.Add(l.Id);
        exp.Collapsed += (_, _) => expanded.Remove(l.Id);
        return exp;
    }

    /// <summary>Which lists are open on the page, kept while it is built again.</summary>
    readonly HashSet<string> expanded = [];

    TextBox TypedBox(string key, string placeholder, double width, Action submit)
    {
        var box = new TextBox { PlaceholderText = placeholder, Text = Typed(key), MinWidth = width };
        box.TextChanged += (_, _) => typed[key] = box.Text;
        box.GotFocus += (_, _) => typing = true;
        box.LostFocus += (_, _) => typing = false;
        box.KeyDown += (_, e) => { if (e.Key == VirtualKey.Enter) { e.Handled = true; typing = false; submit(); } };
        return box;
    }

    /// <summary>The ready-made lists a list can switch on, each with how it stands; the sites always let through; a list from a link.</summary>
    UIElement ReadyMade(GuardList l)
    {
        var rows = new StackPanel { Spacing = 4 };
        foreach (var c in Catalog.All.Concat(store.Settings.CustomLists))
        {
            var on = l.Categories.Contains(c.Id);
            var box = new CheckBox { Content = c.Name, IsChecked = on, MinWidth = 180 };
            Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(box, $"{c.Name} in {l.Name}");
            box.Click += (_, _) =>
            {
                var now = box.IsChecked == true;
                EditList(l.Id, x => { x.Categories.Remove(c.Id); if (now) x.Categories.Add(c.Id); });
                if (now) _ = store.Categories.Refresh([c.Id]);
            };
            var state = store.Categories.State(c.Id) switch
            {
                CategoryState.Working => "Downloading…",
                CategoryState.Ready r => $"{r.Sites:N0} sites · checked {Format.Local(r.Updated):d MMM}",
                CategoryState.Failed f => f.Sites > 0 ? $"Couldn't update ({f.Why}); the copy here still works" : $"Couldn't download it: {f.Why}",
                CategoryState.None n => n.SizeKB > 0 ? $"About {Size(n.SizeKB)} to download" : "Not downloaded yet",
                _ => "",
            };
            var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12 };
            row.Children.Add(box);
            row.Children.Add(new TextBlock { Text = state, FontSize = 12, Opacity = 0.7, VerticalAlignment = VerticalAlignment.Center });
            if (c.Custom)
            {
                var drop = new HyperlinkButton { Content = "Remove", FontSize = 12, Padding = new Thickness(4, 0, 4, 0) };
                drop.Click += (_, _) =>
                {
                    store.ChangeSettings(s => { s.CustomLists.RemoveAll(x => x.Id == c.Id); foreach (var gl in s.Blocklist.Lists) gl.Categories.Remove(c.Id); });
                    store.Categories.Remove(c.Sources.Select(x => x.Id));
                };
                row.Children.Add(drop);
            }
            rows.Children.Add(row);
        }
        var retry = new HyperlinkButton { Content = "Look for new versions now", FontSize = 12 };
        retry.Click += (_, _) => store.RefreshLists(force: true);

        var allow = new StackPanel { Spacing = 6 };
        foreach (var a in l.Allow.ToList()) allow.Children.Add(Item(a, null, () => EditList(l.Id, x => x.Allow.Remove(a))));
        void AddAllow()
        {
            if (Blocklist.NormalizeDomain(Typed($"allow:{l.Id}")) is not { } d) return;
            typed.Remove($"allow:{l.Id}");
            EditList(l.Id, x => { if (!x.Allow.Contains(d)) x.Allow.Add(d); });
        }
        var allowBox = TypedBox($"allow:{l.Id}", "linkedin.com", 220, AddAllow);
        var allowAdd = new Button { Content = "Always allow" };
        allowAdd.Click += (_, _) => AddAllow();

        void AddLink()
        {
            if (Catalog.Custom(Typed("linkname"), Typed("link")) is not { } c)
            {
                linkProblem = "That isn't an https link to a list.";
                Build();
                return;
            }
            typed.Remove("linkname"); typed.Remove("link");
            linkProblem = null;
            store.ChangeSettings(s => { s.CustomLists.Add(c); s.Blocklist.Lists.FirstOrDefault(x => x.Id == l.Id)?.Categories.Add(c.Id); });
            _ = store.Categories.Refresh([c.Id]);
        }
        var linkName = TypedBox("linkname", "A name (optional)", 160, AddLink);
        var link = TypedBox("link", "https://… a hosts file, a list of domains or an AdGuard list", 320, AddLink);
        var linkAdd = new Button { Content = "Add the list" };
        linkAdd.Click += (_, _) => AddLink();
        return Col(
            Note("On top of the sites typed in: whole kinds of sites, from public lists."),
            rows, retry,
            Note("Always allow: a site the ready-made lists never stop (and its subdomains), like linkedin.com out of Social media."),
            allow, new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, Children = { allowBox, allowAdd } },
            Note("Add a list from a link: any https link to a hosts file, a plain list of domains or an AdGuard list."),
            new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, Children = { linkName, link, linkAdd } },
            linkProblem is { } p ? Note(p) : null);
    }

    string? linkProblem;

    static string Size(int kb) => kb >= 1000 ? $"{kb / 1000.0:0.#} MB" : $"{kb} KB";

    async Task Browse(string listId)
    {
        var picker = new Windows.Storage.Pickers.FileOpenPicker { SuggestedStartLocation = Windows.Storage.Pickers.PickerLocationId.ComputerFolder };
        picker.FileTypeFilter.Add(".exe");
        WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(Window));
        var file = await picker.PickSingleFileAsync();
        if (file == null) return;
        store.ChangeSettings(s => s.Blocklist.AddApp(new ListedApp(file.Path, FrontApp.NameOf(file.Path)), listId));
    }

    // ---------------------------------------------------------------- breaks

    UIElement BreaksPane()
    {
        var s = store.Settings;
        var bs = s.Breaks;
        var presets = new Grid { ColumnSpacing = 8, MaxWidth = 620, HorizontalAlignment = HorizontalAlignment.Left };
        var all = Enum.GetValues<CyclePreset>();
        for (var i = 0; i < all.Length; i++)
        {
            var p = all[i];
            presets.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            var card = new Microsoft.UI.Xaml.Controls.Primitives.ToggleButton
            {
                IsChecked = store.Preset == p, HorizontalAlignment = HorizontalAlignment.Stretch, HorizontalContentAlignment = HorizontalAlignment.Left, Padding = new Thickness(12),
                Content = new StackPanel { Spacing = 2, Children = { new TextBlock { Text = Cycles.Title(p), FontWeight = FontWeights.SemiBold }, new TextBlock { Text = Cycles.Summary(p), FontSize = 12, Opacity = 0.8, TextWrapping = TextWrapping.WrapWholeWords } } },
            };
            Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(card, Cycles.Title(p));
            card.Click += (_, _) => store.ApplyPreset(p);
            Grid.SetColumn(card, i);
            presets.Children.Add(card);
        }
        var ideas = new StackPanel { Spacing = 6 };
        foreach (var idea in bs.Ideas.ToList()) ideas.Children.Add(Item(idea, null, () => store.ChangeSettings(x => x.Breaks.Ideas.Remove(idea))));
        void AddIdea()
        {
            var t = Typed("idea").Trim();
            typed.Remove("idea");
            if (t.Length > 0 && !store.Settings.Breaks.Ideas.Contains(t)) store.ChangeSettings(x => x.Breaks.Ideas.Add(t)); else Build();
        }
        var ideaBox = TypedBox("idea", "+ Add your own", 260, AddIdea);
        var ideaAdd = new Button { Content = "Add" };
        ideaAdd.Click += (_, _) => AddIdea();
        var usual = new HyperlinkButton { Content = "Back to the usual ones" };
        usual.Click += (_, _) => store.ChangeSettings(x => x.Breaks.Ideas = [.. BreakSettings.DefaultIdeas]);
        return Col(
            Heading("Breaks"),
            Note("When to stop, and how firmly Chataltul asks."),
            Section("Cycle"),
            presets,
            Choice("A long break after", new[] { 2, 3, 4, 5, 6 }.Select(n => (n, $"{n} blocks")), s.Cycle.LongAfter, n => Quietly(x => x.Cycle.LongAfter = n)),
            Choice("A long break is", new[] { 10, 15, 20, 25, 30 }.Select(n => (n, $"{n} min")), s.Cycle.LongBreak, n => Quietly(x => x.Cycle.LongBreak = n)),
            Choice("When a block is filed", new[] { (BreakStart.Offer, "Offer the break"), (BreakStart.Auto, "Start it as soon as the kitten is home") }, bs.Start, v => Quietly(x => x.Breaks.Start = v)),
            Switch($"Full-screen breaks: the screen goes calm, with {store.Cat(null).Name} stretching", bs.FullScreen, on => Quietly(x => x.Breaks.FullScreen = on)),
            Switch("Strict breaks: no Postpone and no Skip", bs.Strict, on => Quietly(x => x.Breaks.Strict = on)),
            Choice("Postpone a break by", new[] { 3, 5, 10 }.Select(n => (n, $"{n} min")), bs.PostponeMin, n => Quietly(x => x.Breaks.PostponeMin = n)),
            Section("Eye breaks"),
            Switch($"Eye breaks during a block: every {bs.EyeEveryMin} min, {bs.EyeSeconds} seconds looking far away", bs.EyeBreaks, on => Quietly(x => x.Breaks.EyeBreaks = on)),
            Choice("Every", new[] { 15, 20, 30, 45 }.Select(n => (n, $"{n} min")), bs.EyeEveryMin, n => Quietly(x => x.Breaks.EyeEveryMin = n)),
            Choice("For", new[] { 10, 20, 30 }.Select(n => (n, $"{n} seconds")), bs.EyeSeconds, n => Quietly(x => x.Breaks.EyeSeconds = n)),
            Switch("Wait during calls and full-screen video: eye breaks and check-ins hold until you're done", bs.WaitDuringCalls, on => Quietly(x => x.Breaks.WaitDuringCalls = on)),
            Section("Break ideas"),
            ideas,
            new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, Children = { ideaBox, ideaAdd } },
            usual);
    }

    UIElement Item(string title, string? detail, Action remove)
    {
        var g = new Grid { ColumnSpacing = 10, MaxWidth = 520, HorizontalAlignment = HorizontalAlignment.Left };
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var words = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        words.Children.Add(new TextBlock { Text = title, FontSize = 14 });
        if (detail != null) words.Children.Add(Note(detail));
        var x = new Button { Content = new FontIcon { Glyph = V.Glyph.Delete, FontSize = 14, FontFamily = V.Symbols }, Padding = new Thickness(8) };
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(x, $"Take {title} off the list");
        x.Click += (_, _) => remove();
        Grid.SetColumn(x, 1);
        g.Children.Add(words);
        g.Children.Add(x);
        g.MinWidth = 420;
        return g;
    }

    /// <summary>Apps with a window open now, and the ones seen in front since Chataltul started; not those already listed.</summary>
    List<(string Exe, string Name)> RunningApps()
    {
        var found = new Dictionary<string, string>(app.Watcher.Seen);
        Native.EnumWindows((h, _) =>
        {
            if (!Native.IsWindowVisible(h) || Native.GetWindow(h, Native.GW_OWNER) != IntPtr.Zero || Native.GetWindowTextLength(h) == 0) return true;
            if (FrontApp.Of(h) is { } a && a.Pid != Environment.ProcessId && !Skip.Contains(a.Exe)) found.TryAdd(a.Exe, a.Name);
            return true;
        }, IntPtr.Zero);
        var listed = store.Settings.Blocklist.Apps.Select(a => a.Exe).ToHashSet();
        return found.Where(kv => !listed.Contains(kv.Key)).Select(kv => (kv.Key, kv.Value)).OrderBy(x => x.Value, StringComparer.CurrentCultureIgnoreCase).ToList();
    }

    static readonly HashSet<string> Skip = ["explorer.exe", "textinputhost.exe", "shellexperiencehost.exe", "searchhost.exe", "startmenuexperiencehost.exe", "applicationframehost.exe", "lockapp.exe"];

    // ---------------------------------------------------------------- shortcuts

    UIElement Shortcuts()
    {
        var s = store.Settings;
        return Col(
            Heading("Shortcuts"),
            Note("They work in every app. Yes and Drifted are only taken while a check-in is waiting, so those keys are free the rest of the time."),
            Shortcut("start", "Start a block (press it again to start)", s.StartKey, KeyCombo.StartDefault),
            Shortcut("park", "Park a thought", s.ParkKey, KeyCombo.ParkDefault),
            Shortcut("yes", "Answer a check-in: Yes", s.YesKey, KeyCombo.YesDefault),
            Shortcut("drifted", "Answer a check-in: Drifted", s.DriftedKey, KeyCombo.DriftedDefault),
            app.ShortcutProblem is { } p ? Note(p) : null);
    }

    UIElement Shortcut(string id, string label, KeyCombo now, KeyCombo standard)
    {
        var row = new Grid { ColumnSpacing = 10, MaxWidth = 560, HorizontalAlignment = HorizontalAlignment.Left, MinWidth = 520 };
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var words = new TextBlock { Text = label, VerticalAlignment = VerticalAlignment.Center };
        var keys = new Button { Content = recording == id ? "Press the keys…" : now.Label, MinWidth = 160 };
        keys.Click += (_, _) =>
        {
            recording = id;
            app.Recording = true;
            app.Hotkeys.ClearAll();   // so the keys reach this window, not the shortcut
            Build();
        };
        var reset = new HyperlinkButton { Content = "Reset", IsEnabled = now != standard };
        reset.Click += (_, _) => Set(id, standard);
        Grid.SetColumn(keys, 1);
        Grid.SetColumn(reset, 2);
        row.Children.Add(words);
        row.Children.Add(keys);
        row.Children.Add(reset);
        return row;
    }

    /// <summary>While a shortcut waits for its keys, every key goes to it (the zoom keys too); true if it took this one.</summary>
    bool OnKey(Microsoft.UI.Xaml.Input.KeyRoutedEventArgs e)
    {
        if (recording == null) return false;
        var vk = (int)e.Key;
        e.Handled = true;
        if (e.Key == VirtualKey.Escape) { recording = null; app.Recording = false; app.RegisterShortcuts(); Build(); return true; }
        if (KeyCombo.IsModifierKey(vk)) { e.Handled = false; return true; }
        var mods = 0;
        if (Down(VirtualKey.Control)) mods |= KeyCombo.Control;
        if (Down(VirtualKey.Menu)) mods |= KeyCombo.Alt;
        if (Down(VirtualKey.Shift)) mods |= KeyCombo.Shift;
        if (Down(VirtualKey.LeftWindows) || Down(VirtualKey.RightWindows)) mods |= KeyCombo.Win;
        var combo = new KeyCombo(vk, mods);
        if (!combo.IsValid) return true;   // needs Ctrl, Alt or Win with it
        var id = recording;
        recording = null;
        app.Recording = false;
        Set(id, combo);
        return true;
    }

    static bool Down(VirtualKey k) => MicaShell.Down(k);


    // ---------------------------------------------------------------- widgets

    UIElement WidgetsPane()
    {
        var placed = new StackPanel { Spacing = 6 };
        foreach (var w in app.Widgets.Shown.ToList())
        {
            var kind = Widgets.Kind(w.Place.Kind);
            placed.Children.Add(Item(kind?.Title ?? w.Place.Kind, Widgets.SizeName(w.Place.Size), () => app.Widgets.Remove(w.Place.Id)));
        }
        if (app.Widgets.Shown.Count == 0) placed.Children.Add(Note("None yet. Add one below: it goes at the right of your main screen, and you can drag it anywhere."));

        var gallery = new StackPanel { Spacing = 8 };
        foreach (var k in Widgets.All)
        {
            var size = k.Sizes[0];
            var row = new Grid { ColumnSpacing = 12, MaxWidth = 620, HorizontalAlignment = HorizontalAlignment.Left, MinWidth = 520 };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            // the sizes' column is there for every row, so the Add buttons line up
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(140) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var words = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            words.Children.Add(new TextBlock { Text = k.Title, FontSize = 14 });
            words.Children.Add(Note(k.About));
            row.Children.Add(words);
            if (k.Sizes.Length > 1)
            {
                var sizes = new ComboBox { MinWidth = 130, VerticalAlignment = VerticalAlignment.Center };
                foreach (var s in k.Sizes) sizes.Items.Add(Widgets.SizeName(s));
                sizes.SelectedIndex = 0;
                sizes.SelectionChanged += (_, _) => { if (sizes.SelectedIndex >= 0) size = k.Sizes[sizes.SelectedIndex]; };
                Grid.SetColumn(sizes, 1);
                row.Children.Add(sizes);
            }
            var add = new Button { Content = "Add", VerticalAlignment = VerticalAlignment.Center };
            Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(add, $"Add {k.Title}");
            add.Click += (_, _) => app.Widgets.Add(k.Id, size);
            Grid.SetColumn(add, 2);
            row.Children.Add(add);
            gallery.Children.Add(row);
        }
        var removeAll = new Button { Content = "Take them all away", IsEnabled = app.Widgets.Shown.Count > 0 };
        removeAll.Click += (_, _) => app.Widgets.RemoveAll();
        return Col(
            Heading("Widgets"),
            Note("Little cards on your desktop, under your windows, like a phone's widgets. Drag one by its background to move it; right-click it for its size or to take it away. Clicking one opens Chataltul."),
            Section("On your desktop"),
            placed,
            removeAll,
            Switch("Hide block titles: the widgets show the time, never what a block is called", store.Settings.HideTitles, on => store.ChangeSettings(s => s.HideTitles = on)),
            Section("Add a widget"),
            gallery,
            Section("On the taskbar"),
            Note("Right-click Chataltul's taskbar button or its Start menu entry for Start 25, Start 50, Park a thought and Open the café. Pin Chataltul to the taskbar to have them there all the time."));
    }

    // ---------------------------------------------------------------- account and sync

    /// <summary>A field whose text is kept in <c>keep</c>; Enter does <c>submit</c>.</summary>
    TextBox Typed(string header, string placeholder, string text, Action<string> keep, Action? submit = null)
    {
        var box = new TextBox { Header = header, PlaceholderText = placeholder, Text = text, MinWidth = 300, HorizontalAlignment = HorizontalAlignment.Left };
        box.TextChanged += (_, _) => keep(box.Text);
        box.GotFocus += (_, _) => typing = true;
        box.LostFocus += (_, _) => typing = false;
        if (submit != null) box.KeyDown += (_, e) => { if (e.Key == VirtualKey.Enter) { e.Handled = true; typing = false; submit(); } };
        return box;
    }

    PasswordBox Secret(string header, string text, Action<string> keep, Action? submit = null)
    {
        var box = new PasswordBox { Header = header, Password = text, MinWidth = 300, HorizontalAlignment = HorizontalAlignment.Left };
        box.PasswordChanged += (_, _) => keep(box.Password);
        box.GotFocus += (_, _) => typing = true;
        box.LostFocus += (_, _) => typing = false;
        if (submit != null) box.KeyDown += (_, e) => { if (e.Key == VirtualKey.Enter) { e.Handled = true; typing = false; submit(); } };
        return box;
    }

    static Button Accent(string text, Action click) => V.Btn(text, click, accent: true);
    static Button Plain(string text, Action click) => V.Btn(text, click);
    static StackPanel Buttons(params UIElement?[] b)
    {
        var s = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        foreach (var x in b) if (x != null) s.Children.Add(x);
        return s;
    }

    UIElement AccountPane()
    {
        var sync = app.Sync;
        sync.LoadConfig();
        var said = sync.Message is { } m ? new TextBlock { Text = m, TextWrapping = TextWrapping.WrapWholeWords, Foreground = new SolidColorBrush(Palette.Caution) } : null;
        var busy = sync.Busy ? new ProgressRing { IsActive = true, Width = 20, Height = 20, HorizontalAlignment = HorizontalAlignment.Left } : null;
        if (sync.Account is { } a)
        {
            var where = sync.Trouble == "too big" ? "Not syncing: the history is too big for the server."
                : sync.LastSync is { } at ? $"Synced with {sync.Server} at {at:t}." : $"Syncs with {sync.Server}.";
            UIElement security;
            if (deleting)
            {
                // deleting needs the password, or for an account without one, a code to its email
                security = a.HasPassword
                    ? Col(Note("This deletes the account and its history on the server. This PC keeps its own copy."),
                        Secret("Your password", current, v => current = v, () => sync.DeleteAccount("password", current)),
                        Buttons(Accent("Delete the account", () => sync.DeleteAccount("password", current)), Plain("Keep it", () => { deleting = false; Build(); })))
                    : sync.CodeSentTo is { } sent
                        ? Col(Note($"A code went to {sent}. This deletes the account and its history on the server; this PC keeps its own copy."),
                            Typed("Code", "123456", code, v => code = v, () => sync.DeleteAccount("code", code)),
                            Buttons(Accent("Delete the account", () => sync.DeleteAccount("code", code)), Plain("Keep it", () => { deleting = false; sync.CodeSentTo = null; Build(); })))
                        : Col(Note("To be sure it's you, Chataltul emails you a code first."),
                            Buttons(Accent("Send me a code", sync.SendDeleteCode), Plain("Keep it", () => { deleting = false; Build(); })));
            }
            else
            {
                security = Col(
                    Secret(a.HasPassword ? "A new password" : "A password, if you want one", password, v => password = v),
                    a.HasPassword ? Secret("Your password now", current, v => current = v) : null,
                    Buttons(Plain(a.HasPassword ? "Change the password" : "Set the password", () => { sync.SetPassword(password, a.HasPassword ? current : null); password = ""; current = ""; }),
                        V.Link("Delete the account…", () => { deleting = true; sync.Message = null; Build(); })));
            }
            return Col(
                Heading("Account and sync"),
                Section("Signed in"),
                new TextBlock { Text = a.Label, FontSize = 14 },
                Note(where),
                Note("Your blocks, Later, cats and settings are the same here, on the web and on your other devices. A change goes up a moment after you make it. Where each block went never leaves this PC."),
                Buttons(Plain("Sign out", sync.SignOut), Plain("Open the web app", () => Outside.Open(store.Settings.Server)), Plain("Sync now", () => { _ = sync.Push(); })),
                Note("Signing out takes the account's history off this PC; it stays safe on the server."),
                Section("Password and the account"),
                security,
                busy, said);
        }

        UIElement form;
        if (usePassword || sync.EmailOn == false)
        {
            form = Col(
                Typed("Name or email", "", accountName, v => accountName = v),
                Secret("Password", password, v => password = v, () => sync.SignIn("login", accountName, password)),
                Buttons(Accent("Sign in", () => sync.SignIn("login", accountName, password)), Plain("Make an account", () => sync.SignIn("signup", accountName, password))));
        }
        else if (sync.CodeSentTo is { } sent)
        {
            form = Col(
                Note($"A 6-digit code went to {sent}. It can take a minute."),
                Typed("Code", "123456", code, v => code = v, () => sync.SignIn(sent, code)),
                Buttons(Accent("Sign in", () => sync.SignIn(sent, code)), Plain("Another address", () => { sync.CodeSentTo = null; code = ""; Build(); })));
        }
        else
        {
            form = Col(
                Typed("Email", "you@example.com", email, v => email = v, () => sync.SendCode(email)),
                Buttons(Accent("Send me a code", () => sync.SendCode(email))));
        }
        var other = sync.EmailOn != false
            ? V.Link(usePassword ? "Use an email code" : "Use a name and a password", () => { usePassword = !usePassword; sync.Message = null; Build(); })
            : null;
        return Col(
            Heading("Account and sync"),
            Note(sync.EmailOn == false
                ? "This server doesn't send email, so use a name and a password."
                : "No password to remember: Chataltul emails you a 6-digit code. If you're new, the code makes your account. This PC's blocks come along. Without an account nothing leaves this PC."),
            form, other, busy, said,
            Note($"The account is the web app's, at {sync.Server}."));
    }

    void Set(string id, KeyCombo combo)
    {
        store.ChangeSettings(s =>
        {
            switch (id)
            {
                case "start": s.StartKey = combo; break;
                case "park": s.ParkKey = combo; break;
                case "yes": s.YesKey = combo; break;
                case "drifted": s.DriftedKey = combo; break;
            }
        });
        app.RegisterShortcuts();
        Build();
    }
}
