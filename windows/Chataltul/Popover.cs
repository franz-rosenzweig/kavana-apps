using Chataltul.Core;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Shapes;
using Windows.System;
using Windows.UI;

namespace Chataltul;

enum BlockKind { Focus, Short, Long }

/// <summary>
/// The popover, opened from the tray icon: a Windows 11 flyout (acrylic, the system's rounded corners, Windows'
/// own buttons) that is ready, focusing, came home, back from being away, or on a break. It is built again on
/// every change; what is being typed is kept. The Mac's PopoverViews.swift.
/// </summary>
sealed class Popover
{
    const double Width = 360;
    readonly Store store;
    readonly Controller app;
    public Floating Win { get; } = new("Chataltul", activates: true, Backdrop.Acrylic);
    readonly List<Action<long>> tickers = [];
    long hiddenAt;
    Native.POINT? anchor;

    // what is being typed or chosen, kept when the card is built again
    string title = "", note = "";
    BlockKind kind = BlockKind.Focus;
    bool sure;
    TextBox? titleBox;

    /// <summary>Which card is up (ready, focus, home, away, break), for the self-test.</summary>
    public string Pane { get; private set; } = "";
    /// <summary>The buttons on the card, by what they say, for the self-test.</summary>
    public readonly Dictionary<string, Action> Buttons = [];

    static readonly Dictionary<BlockKind, int[]> Presets = new()
    {
        [BlockKind.Focus] = [15, 20, 25, 30, 45, 50, 52, 60, 90],
        [BlockKind.Short] = [5, 10, 15, 17, 20],
        [BlockKind.Long] = [10, 15, 20, 25, 30],
    };

    public Popover(Store store, Controller app)
    {
        this.store = store;
        this.app = app;
        Win.Deactivated += Hide;
        Win.Root.KeyDown += (_, e) => { if (e.Key == VirtualKey.Escape) Hide(); };
    }

    public bool IsShown => Win.IsShown;

    /// <summary>A click on the tray icon. The click that closed it (by taking the front away) doesn't open it again.</summary>
    public void Toggle(Native.POINT at)
    {
        if (IsShown) { Hide(); return; }
        if (Environment.TickCount64 - hiddenAt < 400) return;
        Show(at);
    }

    public void Show(Native.POINT? near = null, bool focusTitle = false)
    {
        if (near != null) anchor = near;
        if (focusTitle) kind = BlockKind.Focus;
        Build();
        Place();
        if (!IsShown) Win.Show();
        // once WinUI has laid the card out on the screen, its size is sure
        Ui.Post(() => { if (IsShown) Place(); });
        if (focusTitle) Ui.Post(() => titleBox?.Focus(FocusState.Programmatic));
    }

    public void Hide()
    {
        if (!IsShown) return;
        Win.Hide();
        hiddenAt = Environment.TickCount64;
    }

    /// <summary>The state changed: the card is built again if it is up.</summary>
    public void Refresh()
    {
        if (!IsShown) return;
        var focused = titleBox?.FocusState is FocusState.Keyboard or FocusState.Programmatic or FocusState.Pointer;
        Build();
        Place();
        if (focused) Ui.Post(() => titleBox?.Focus(FocusState.Programmatic));
    }

    public void Tick(long now)
    {
        if (!IsShown) return;
        foreach (var t in tickers.ToList()) t(now);
    }

    /// <summary>The card, in the theme Windows has now.</summary>
    public FrameworkElement Build()
    {
        Palette.Dark = Palette.SystemDark();
        Win.Root.RequestedTheme = Palette.Theme;
        tickers.Clear();
        Buttons.Clear();
        titleBox = null;
        UIElement content;
        if (store.AwayPrompt is { } away && store.Active != null) { Pane = "away"; content = Away(away); }
        else if (store.OnBreak is { } brk && store.Active == null) { Pane = "break"; content = Break(brk); }
        else if (store.Awaiting is { } w) { Pane = "home"; content = Home(w); }
        else if (store.Active is { } b) { Pane = "focus"; content = Focus(b); }
        else { Pane = "ready"; content = Ready(); }
        var card = new Border
        {
            Child = content, Width = Width, Padding = new Thickness(16, 12, 16, 12),
            HorizontalAlignment = HorizontalAlignment.Left, VerticalAlignment = VerticalAlignment.Top,
        };
        Win.Content = card;
        foreach (var t in tickers) t(Clock.Now);
        return card;
    }

    /// <summary>Just above the tray icon (or below it, or beside it, wherever the taskbar is), inside the screen.</summary>
    void Place()
    {
        var near = anchor ?? app.TrayPoint();
        var work = Native.WorkAreaAt(near);
        var scale = Floating.ScaleAt(near);
        var size = Win.Measure(Width);
        int w = (int)Math.Ceiling(size.Width * scale), h = (int)Math.Ceiling(size.Height * scale), gap = (int)(12 * scale);
        int x, y;
        if (near.X < work.Left || near.X >= work.Right)
        {
            // the taskbar is on the left or the right
            x = near.X < work.Left ? work.Left + gap : work.Right - w - gap;
            y = Math.Clamp(near.Y - h / 2, work.Top + gap, Math.Max(work.Top + gap, work.Bottom - h - gap));
        }
        else
        {
            x = Math.Clamp(near.X - w / 2, work.Left + gap, Math.Max(work.Left + gap, work.Right - w - gap));
            y = near.Y < work.Top ? work.Top + gap : work.Bottom - h - gap;
        }
        Win.Move(x, y, w, h);
    }

    Button Btn(object content, Action click, string name, bool accent = false, bool stretch = false, double? height = null)
    {
        Buttons[name] = click;
        return V.Btn(content, click, accent, stretch, name, height: height);
    }

    Button IconBtn(string glyph, Action click, string name, double size = 32)
    {
        Buttons[name] = click;
        return V.IconBtn(glyph, click, name, size);
    }

    // ---------------------------------------------------------------- shared pieces

    UIElement Header(string left) =>
        V.Spread(V.Caption(left), IconBtn(V.Glyph.Settings, () => app.OpenSettings(null), "Settings"));

    UIElement Footer(string left, bool quit = false)
    {
        Buttons["Open the café"] = app.OpenCafe;
        var links = V.Row(0, V.Link("Open the café", app.OpenCafe), quit ? V.Link("Quit", () => app.Quit()) : null);
        var row = V.Spread(V.Caption(left), links);
        row.Margin = new Thickness(0, 6, 0, 0);
        return V.Col(0, V.Divider(), row);
    }

    string TodayLine => $"{Format.FmtMin(store.State.Stats.Today)} today · {Format.Plural(store.CatsHomeToday, "cat")} home";

    static TextBlock Centered(TextBlock t) { t.HorizontalAlignment = HorizontalAlignment.Center; t.TextAlignment = TextAlignment.Center; return t; }

    static FrameworkElement Spaced(FrameworkElement e, double top) { e.Margin = new Thickness(0, top, 0, 0); return e; }

    // ---------------------------------------------------------------- ready

    UIElement Ready()
    {
        var cycle = store.Settings.Cycle;
        var minutes = kind switch { BlockKind.Short => cycle.ShortBreak, BlockKind.Long => cycle.LongBreak, _ => store.Settings.FocusMinutes };
        var next = store.State.View.Later.FirstOrDefault();
        var today = V.Col(6,
            next != null ? V.Strong($"Up next: {next.Text}") : null,
            Spaced(V.Dashes(cycle.PerDay, store.BlocksToday), 2));
        var round = new RoundWindow(200);
        round.Update(Art.Nap(store.ShownCat.Id), 0.82, null);
        var clock = V.Col(0, V.ClockText(Format.FmtClock(minutes * 60_000L), 44), Centered(V.Caption(kind == BlockKind.Focus ? "Ready" : "Break")));
        TextBox? field = null;
        if (kind == BlockKind.Focus)
        {
            field = V.Field("Add a title (optional)", title);
            field.TextChanged += (_, _) => title = field.Text;
            field.KeyDown += (_, e) => { if (e.Key == VirtualKey.Enter) { e.Handled = true; StartPressed(); } };
            titleBox = field;
        }
        var start = Btn(V.Labelled(V.Glyph.Play, kind == BlockKind.Focus ? "Start block" : "Start break"), StartPressed, "Start", accent: true, stretch: true, height: 36);
        var summary = Centered(V.Caption(Summary()));
        return V.Col(12, Header($"{store.BlocksToday} of {cycle.PerDay} blocks today"), today,
            store.State.View.BreakOffer is { } offer ? Offered(offer.Minutes, () => store.StartBreak(BreakLength(offer.Minutes))) : null,
            round.View, clock, KindPicker(), field, start, summary, Footer(TodayLine, quit: true));
    }

    string Summary()
    {
        var s = store.EngineSettings;
        var check = s.Interval == 0 ? "no check-ins" : s.Style == "ask" ? $"check in every {s.Interval} min" : $"nudges every {s.Interval} min";
        return $"Pomodoro · {check}";
    }

    int BreakLength(int engineOffer) { var b = store.BreakAfter(); return b == 0 ? engineOffer : b; }

    /// <summary>The start shortcut pressed again: a focus block, with the title typed so far.</summary>
    public void StartFromShortcut()
    {
        kind = BlockKind.Focus;
        StartPressed();
    }

    public void StartPressed()
    {
        switch (kind)
        {
            case BlockKind.Focus:
                store.Start(title.Trim());
                title = "";
                break;
            case BlockKind.Short: store.StartBreak(store.Settings.Cycle.ShortBreak); break;
            case BlockKind.Long: store.StartBreak(store.Settings.Cycle.LongBreak); break;
        }
    }

    /// <summary>Focus, Break, Long: the three lengths of the cycle, as a segmented control. The chosen one's length can be changed.</summary>
    UIElement KindPicker()
    {
        var c = store.Settings.Cycle;
        return V.Even(4, Segment(BlockKind.Focus, "Focus", store.Settings.FocusMinutes), Segment(BlockKind.Short, "Break", c.ShortBreak), Segment(BlockKind.Long, "Long", c.LongBreak));
    }

    UIElement Segment(BlockKind k, string name, int minutes)
    {
        var on = kind == k;
        var label = V.Col(0, Centered(V.T(name, 13, FontWeights.SemiBold)), Centered(V.T(on ? $"{minutes} min ▾" : $"{minutes} min", 12)));
        void Choose() { if (kind != k) { kind = k; Refresh(); } }
        var b = V.Segment(label, on, Choose, name);
        Buttons[name] = () => { kind = k; Refresh(); };
        if (on)
        {
            var menu = new MenuFlyout();
            foreach (var m in Presets[k])
            {
                var item = new MenuFlyoutItem { Text = $"{m} minutes" };
                item.Click += (_, _) => SetLength(k, m);
                menu.Items.Add(item);
            }
            // the chosen one stays chosen when pressed again, and shows its lengths
            b.Click += (_, _) => { b.IsChecked = true; menu.ShowAt(b); };
        }
        return b;
    }

    void SetLength(BlockKind k, int m) => store.ChangeSettings(s =>
    {
        switch (k)
        {
            case BlockKind.Focus: s.FocusMinutes = m; break;
            case BlockKind.Short: s.Cycle.ShortBreak = m; break;
            case BlockKind.Long: s.Cycle.LongBreak = m; break;
        }
    });

    /// <summary>"Break · 5 min", one press to start it.</summary>
    UIElement Offered(int minutes, Action start)
    {
        var mins = BreakLength(minutes);
        var isLong = store.Settings.Cycle.IsLong(store.BlocksToday);
        var row = new Grid { ColumnSpacing = 10 };
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var pic = V.Painting(Art.Nap(store.State.Companion), 36);
        var words = V.Col(0, V.Strong(isLong ? $"Long break · {mins} min" : $"Break · {mins} min"), V.Caption("Stand up and stretch."));
        words.VerticalAlignment = VerticalAlignment.Center;
        var go = Btn("Start", start, "Start break", accent: true);
        go.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(words, 1);
        Grid.SetColumn(go, 2);
        row.Children.Add(pic);
        row.Children.Add(words);
        row.Children.Add(go);
        return V.Card(row, new Thickness(12, 8, 12, 8));
    }

    // ---------------------------------------------------------------- focusing

    UIElement Focus(Block b)
    {
        var perDay = store.Settings.Cycle.PerDay;
        var n = Math.Min(store.BlocksToday + 1, Math.Max(perDay, store.BlocksToday + 1));
        var paused = b.Paused != null;
        var dashes = new Border { Margin = new Thickness(0, 2, 0, 0) };
        var top = V.Col(4, V.Label(paused ? "Paused" : "Focusing", paused ? Palette.Caution : null), V.T(b.Title, 16, FontWeights.SemiBold, Palette.Text), dashes);
        var round = new RoundWindow(200);
        var clock = V.ClockText("", 44, paused ? Palette.Caution : null);
        var face = V.Col(0, clock, Centered(V.Caption(paused ? "Paused" : "Focus")));
        UIElement middle;
        TextBlock? nextLine = null;
        if (b.Pending != null) middle = Checkin();
        else
        {
            nextLine = Centered(V.Caption(""));
            middle = nextLine;
        }
        tickers.Add(now =>
        {
            var at = b.Paused ?? now;
            var total = Math.Max(1, b.End - b.Start);
            var p = Math.Clamp((double)(at - b.Start) / total, 0, 1);
            round.Update(b, now);
            clock.Text = Format.FmtClock(b.Stopwatch ? at - b.Start : Math.Max(0, b.End - at));
            dashes.Child = V.Dashes(perDay, store.BlocksToday, p);
            if (nextLine != null) nextLine.Text = NextLine(b, now);
        });
        var guard = Guarding(b);
        return V.Col(12, Header($"Block {n} of {perDay}"), top, round.View, face, middle, FocusButtons(b), Footer(guard));
    }

    UIElement Checkin()
    {
        var dot = new Ellipse { Width = 8, Height = 8, Fill = Palette.B(Palette.Caution), VerticalAlignment = VerticalAlignment.Center };
        var line = V.Row(8, dot, V.Strong("A check-in is waiting"));
        return V.Card(V.Col(10, line, V.Even(8,
            Btn(V.Labelled(V.Glyph.Check, "Yes"), () => app.Answer("yes"), "Yes", accent: true, stretch: true),
            Btn(V.Labelled(V.Glyph.Drift, "Drifted"), () => app.Answer("drifted"), "Drifted", stretch: true))));
    }

    static string NextLine(Block b, long now)
    {
        if (b.Paused != null) return $"Starts again by itself in {Format.MinLeft(Math.Max(0, (b.PauseEnds ?? now) - now))} min";
        if (b.Next is { } n && n > now) return $"Next {(b.Style == "ask" ? "check-in" : "nudge")} in {Format.MinLeft(n - now)} min";
        if (b.Style == "ask" && b.Next == null && !b.Stopwatch) return "Check-in at the end";
        return "";
    }

    UIElement FocusButtons(Block b)
    {
        Button main;
        if (b.Paused != null)
            main = Btn(V.Labelled(V.Glyph.Play, "Resume"), store.Resume, "Resume", accent: true, stretch: true, height: 36);
        else
        {
            main = Btn(V.Labelled(V.Glyph.Pause, "Pause"), store.Pause, "Pause", stretch: true, height: 36);
            main.IsEnabled = store.State.CanPause && !b.Stopwatch;
        }
        var park = IconBtn(V.Glyph.Bulb, app.ShowPark, "Park a thought", 36);
        var cancel = Clock.Now <= b.CancelUntil;
        Button stop;
        if (sure)
        {
            stop = Btn("Sure?", StopPressed, "Stop", height: 36);
            stop.Padding = new Thickness(8, 0, 8, 0);
        }
        else stop = IconBtn(V.Glyph.Stop, StopPressed, cancel ? "Cancel" : "Stop", 36);
        Buttons["Stop"] = StopPressed;
        var g = new Grid { ColumnSpacing = 4 };
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        Grid.SetColumn(park, 1);
        Grid.SetColumn(stop, 2);
        g.Children.Add(main);
        g.Children.Add(park);
        g.Children.Add(stop);
        return g;
    }

    /// <summary>Stop: in the first ten seconds it takes the block back at once; after that it asks "Sure?" first.</summary>
    public void StopPressed()
    {
        if (store.Active is not { } b) return;
        if (Clock.Now <= b.CancelUntil || sure)
        {
            sure = false;
            store.Stop();
            return;
        }
        sure = true;
        Refresh();
        Ui.After(TimeSpan.FromSeconds(3), () => { if (sure) { sure = false; Refresh(); } });
    }

    string Guarding(Block b)
    {
        var n = store.Settings.Blocklist.Count;
        if (!store.Settings.SpeedBump || n == 0 || !store.State.CatsOn) return "";
        return $"Watching {Format.Plural(n, "app or site", "apps and sites")} until {Format.ClockTime(b.End)}";
    }

    // ---------------------------------------------------------------- came home

    UIElement Home(Block b)
    {
        var cat = store.Cat(b.Cat);
        var home = b.Kitten == "home";
        var round = new RoundWindow(88, deepSky: true);
        round.Update(home ? Art.PathFor(b.Cat, Art.Stage.Home, b.Minutes < 25) : Art.Gone, 1, null);
        var parts = new List<string> { b.Title, Format.FmtMin(b.Minutes) };
        if (b.Treats > 0) parts.Add($"+{b.Treats} treats");
        var words = V.Col(2, V.Label(home ? "Came home" : "Wandered off", Palette.Accent),
            V.T(home ? $"{cat.Name} came home to your café" : $"{cat.Name} wandered off", 16, FontWeights.SemiBold, Palette.Text, wrap: true),
            V.Caption(string.Join(" · ", parts), wrap: true));
        words.VerticalAlignment = VerticalAlignment.Center;
        var top = new Grid { ColumnSpacing = 14 };
        top.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        top.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        Grid.SetColumn(words, 1);
        top.Children.Add(round.View);
        top.Children.Add(words);

        var field = V.Field("A note for later (optional)", note);
        field.TextChanged += (_, _) => note = field.Text;
        field.KeyDown += (_, e) => { if (e.Key == VirtualKey.Enter) { e.Handled = true; FileIt(b, "done"); } };
        var outcomes = V.Even(6,
            Btn("Done", () => FileIt(b, "done"), "Done", accent: true, stretch: true),
            Btn("Partly", () => FileIt(b, "partly"), "Partly", stretch: true),
            Btn("Not really", () => FileIt(b, "notreally"), "Not really", stretch: true));
        var how = V.Col(8, V.Strong("How did it go?"), field, outcomes);
        var perDay = store.Settings.Cycle.PerDay;
        return V.Col(14, top, how, Offered(store.BreakAfter(), () => { FileIt(b, null); store.StartBreak(store.BreakAfter()); }),
            Footer($"{store.BlocksToday} of {perDay} blocks · {Format.FmtMin(store.State.Stats.Today)} today"));
    }

    /// <summary>Files the block with how it went (and the note, if one was typed).</summary>
    void FileIt(Block b, string? outcome)
    {
        var n = note.Trim();
        if (n.Length > 0) store.Note(b.Id, n);
        note = "";
        store.Outcome(b.Id, outcome);
    }

    // ---------------------------------------------------------------- away

    UIElement Away(AwayReturn away)
    {
        var b = store.Active;
        var round = new RoundWindow(140);
        round.Update(Art.Nap(b?.Cat ?? store.State.Companion), 0.95, null, 0.7);
        var words = V.Col(4, Centered(V.Label("Welcome back")),
            V.Subtitle($"You were away from the PC for {Format.Plural(away.Minutes, "minute")}.", align: TextAlignment.Center),
            Centered(V.Body($"Being away isn't drifting, so {store.Cat(b?.Cat).Name} is still here. Should those minutes count for {b?.Title ?? "this block"}?",
                wrap: true, color: Palette.Text2, align: TextAlignment.Center)));
        var count = Btn("Count them", store.CountAway, "Count them", accent: true, stretch: true, height: 36);
        UIElement buttons = store.State.CanTakeOff
            ? V.Even(8, count, Btn("Take them off", store.TakeAwayOff, "Take them off", stretch: true, height: 36))
            : count;
        var foot = store.State.CanTakeOff && b != null
            ? $"Taking them off moves the end to {Format.ClockTime(b.End + away.Ms)}. Away less than {store.Settings.AwayAfterMin} min never asks."
            : $"Away less than {store.Settings.AwayAfterMin} min never asks.";
        return V.Col(14, round.View, words, buttons, Centered(V.Caption(foot, wrap: true, align: TextAlignment.Center)));
    }

    // ---------------------------------------------------------------- on a break

    UIElement Break(Brk brk)
    {
        var round = new RoundWindow(190);
        var clock = V.ClockText("", 44);
        tickers.Add(now =>
        {
            var total = Math.Max(1, brk.End - brk.Start);
            var left = Math.Max(0, brk.End - now);
            round.Update(Art.Nap(store.State.Companion), 0.82, (double)left / total, 0.6);
            clock.Text = Format.FmtClock(left);
        });
        var end = Btn("End break", store.StopBreak, "End break", stretch: true, height: 36);
        return V.Col(12, Header("Break"), round.View, V.Col(0, clock, Centered(V.Caption("Break"))),
            Centered(V.Body("Stand up and stretch.", color: Palette.Text2)), end, Footer(TodayLine));
    }
}
