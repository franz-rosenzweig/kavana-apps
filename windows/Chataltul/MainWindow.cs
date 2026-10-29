using Chataltul.Core;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Shapes;
using Windows.System;
using Windows.UI;

namespace Chataltul;

/// <summary>
/// The app window: Today (the day, week, month or year: the numbers, the café of the period and where the time
/// went), the Café, History and Later, from the popover, the Start menu or the taskbar's jump list. Settings and the
/// web app's shop are a click away at the bottom. A tray app has no taskbar button until this opens; closing it
/// only hides it. The Mac's AppWindow.swift.
/// </summary>
sealed class MainWindow
{
    readonly Store store;
    readonly Controller app;
    readonly MicaShell shell;
    public string Pane { get; private set; } = "today";
    public Span Span { get; private set; } = Span.Day;
    public bool IsShown => shell.IsShown;
    public Window Window => shell.Window;
    /// <summary>The café on the page now, if there is one (the self-test counts its cats).</summary>
    public CafeView? Cafe { get; private set; }
    /// <summary>The buttons on the page, by what they say, for the self-test.</summary>
    public readonly Dictionary<string, Action> Buttons = [];
    readonly List<Action<long>> tickers = [];
    string laterText = "";
    bool typing;
    /// <summary>"Ask about your week": what's asked, and the answer from the numbers.</summary>
    string question = "", answer = "";

    static readonly (string Id, string Title, string Glyph)[] Panes =
    [
        ("today", "Today", ""), ("cafe", "Café", ""), ("history", "History", ""), ("later", "Later", ""),
    ];

    public MainWindow(Store store, Controller app)
    {
        this.store = store;
        this.app = app;
        shell = new MicaShell("Chataltul", Panes, 1180, 780, 232) { Maximizable = true, StartZoom = store.Settings.TextZoom };
        shell.Picked += id => { if (id != Pane) { Pane = id; Build(); } };
        shell.Zoomed += z => store.ChangeSettings(s => s.TextZoom = z);
        // below the pages: what opens elsewhere (Settings, the web app's shop), so they never stay picked
        foreach (var (id, title, glyph) in new[] { ("blocklist", "Blocklist", ""), ("shop", "Cats and shop", ""), ("settings", "Settings", "") })
            shell.Nav.FooterMenuItems.Add(new NavigationViewItem { Content = title, Tag = id, Icon = new FontIcon { Glyph = glyph }, SelectsOnInvoked = false });
        shell.Nav.ItemInvoked += (_, e) =>
        {
            switch (e.InvokedItemContainer?.Tag as string)
            {
                case "blocklist": app.OpenSettings("blocklist"); break;
                case "shop": Outside.Open(store.Settings.Server); break;
                case "settings": app.OpenSettings(null); break;
            }
        };
        shell.Page.SizeChanged += (_, e) => { if (IsShown && Pane == "cafe" && Math.Abs(e.NewSize.Height - e.PreviousSize.Height) > 40) Build(); };
    }

    public void Show(string? pane = null, Span? span = null)
    {
        if (pane != null) Pane = pane;
        if (span is { } s) Span = s;
        Palette.Dark = Palette.SystemDark();
        shell.Theme = Palette.Theme;
        shell.Select(Pane);
        Build();
        shell.Show();
    }

    public void Hide() => shell.Hide();

    /// <summary>The state changed: the page is built again if it's up (not under someone typing in Later).</summary>
    public void Refresh()
    {
        if (IsShown && !typing) Build();
    }

    public void Tick(long now)
    {
        if (!IsShown) return;
        foreach (var t in tickers.ToList()) t(now);
    }

    public UIElement Build()
    {
        tickers.Clear();
        Buttons.Clear();
        Cafe = null;
        UIElement page = Pane switch
        {
            "cafe" => CafePage(),
            "history" => History(),
            "later" => Later(),
            _ => Today(),
        };
        shell.Nav.PaneFooter = Footer();
        shell.SetContent(page);
        foreach (var t in tickers) t(Clock.Now);
        return page;
    }

    Button Btn(object content, Action click, string name, bool accent = false)
    {
        Buttons[name] = click;
        return V.Btn(content, click, accent, name: name);
    }

    // ---------------------------------------------------------------- pieces

    static TextBlock Title(string text) => V.T(text, 28, FontWeights.SemiBold, Palette.Text);

    static StackPanel Page(params UIElement?[] children)
    {
        var s = V.Col(18, children);
        s.MaxWidth = 1200;
        s.HorizontalAlignment = HorizontalAlignment.Stretch;
        return s;
    }

    static Border Panel(params UIElement?[] children) => V.Card(V.Col(10, children), new Thickness(16, 14, 16, 14));

    static StackPanel Heading(string title, string subtitle) => V.Col(4, Title(title), V.Body(subtitle, wrap: true, color: Palette.Text2));

    /// <summary>The bottom of the pages on the left: the block that runs, or a button to start one.</summary>
    UIElement Footer()
    {
        if (store.Active is { } b)
        {
            var dot = new Ellipse { Width = 8, Height = 8, Fill = Palette.B(b.Paused != null ? Palette.Caution : Palette.Accent), VerticalAlignment = VerticalAlignment.Center };
            var left = V.Caption("");
            tickers.Add(now => left.Text = b.Paused != null ? "paused" : b.Stopwatch ? Format.FmtClock(now - b.Start) : $"{Format.FmtClock(Math.Max(0, b.End - now))} left");
            var words = V.Col(1, V.Strong(b.Title), left);
            var row = V.Row(10, dot, words);
            var card = V.Card(row, new Thickness(12, 10, 12, 10));
            card.Margin = new Thickness(12, 0, 12, 12);
            return card;
        }
        var start = Btn(V.Labelled(V.Glyph.Play, "Start a block"), () => app.Popover.Show(focusTitle: true), "Start a block", accent: true);
        start.HorizontalAlignment = HorizontalAlignment.Stretch;
        return V.Col(6, start, V.Caption($"or {store.Settings.StartKey.Label} from any app", wrap: true)).With(new Thickness(12, 0, 12, 12));
    }

    // ---------------------------------------------------------------- Today

    Period Period => Insights.PeriodOf(Span, DateTime.Now);

    UIElement Today()
    {
        var p = Period;
        var blocks = store.Blocks(p.From, p.To);
        var previous = Insights.Before(p);
        List<Hist> before = Span == Span.Day ? new() : store.Blocks(previous.From, previous.To);
        var header = V.Spread(Heading(TodayTitle(p), TodaySubtitle(p)), V.Row(8, Spans(), ExportMenu(blocks, p)));
        if (Span == Span.Week)
            return Page(header, Reports.Week(store, blocks, p, TagColor, question, answer, q => question = q, () => AskWeek(blocks, p), t => typing = t));
        var went = store.Settings.RecordTiming ? Reports.WentPanel(store.WentRows(blocks.Select(b => b.Id))) : null;
        UIElement main;
        if (Span == Span.Day) main = Columns(CafeCard(blocks, p), V.Col(14, Timeline(blocks), Hours(blocks), went));
        else main = Columns(CafeCard(blocks, p), V.Col(14, Chart(blocks, p), Tags(blocks), went));
        return Page(header, Stats(blocks, before, p), main);
    }

    /// <summary>"Ask about your week": answered from the week's numbers (there is no model on the PC to ask).</summary>
    void AskWeek(IReadOnlyList<Hist> blocks, Period p)
    {
        var q = question.Trim();
        if (q.Length == 0) return;
        answer = Review.Answer(q, Review.Facts(blocks, p, store.EngineSettings.Goal, store.StreakWithPause));
        typing = false;
        Build();
    }

    /// <summary>Export: the blocks as a spreadsheet (CSV), where the time went as a spreadsheet, the review in words (Markdown).</summary>
    UIElement ExportMenu(List<Hist> blocks, Period p)
    {
        var stem = $"chataltul-{Span.ToString().ToLowerInvariant()}-{p.Start:yyyy-MM-dd}";
        var went = store.Settings.RecordTiming ? store.WentRows(blocks.Select(b => b.Id)) : new List<WentRow>();
        var hwnd = shell.Hwnd;
        void Blocks() => _ = Reports.Save(hwnd, $"{stem}-blocks.csv", Export.BlocksCsv(blocks, id => store.Cat(id).Name), "A spreadsheet", ".csv");
        void WentFile() => _ = Reports.Save(hwnd, $"{stem}-where-it-went.csv", Export.WentCsv(went), "A spreadsheet", ".csv");
        void Words()
        {
            var f = Review.Facts(blocks, p, store.EngineSettings.Goal, store.StreakWithPause);
            var range = $"{p.Start:d MMM yyyy} to {p.Last:d MMM yyyy}";
            _ = Reports.Save(hwnd, $"{stem}-review.md", Export.ReviewMarkdown(TodayTitle(p), range, Review.Summary(f), f, went), "Markdown", ".md");
        }
        Buttons["Export blocks"] = Blocks;
        Buttons["Export where it went"] = WentFile;
        Buttons["Export the review"] = Words;
        var menu = new MenuFlyout();
        foreach (var (text, act) in new (string, Action)[] { ("The blocks, as a spreadsheet (CSV)", Blocks), ("Where the time went, as a spreadsheet (CSV)", WentFile), ("The review in words (Markdown)", Words) })
        {
            var item = new MenuFlyoutItem { Text = text };
            item.Click += (_, _) => act();
            menu.Items.Add(item);
        }
        return new DropDownButton { Content = "Export", Flyout = menu };
    }

    /// <summary>Day, Week, Month, Year: a selector bar, as Windows' own apps switch views.</summary>
    UIElement Spans()
    {
        var bar = new SelectorBar();
        foreach (var s in new[] { Span.Day, Span.Week, Span.Month, Span.Year })
        {
            var item = new SelectorBarItem { Text = s.ToString(), Tag = s };
            bar.Items.Add(item);
            if (s == Span) bar.SelectedItem = item;
            Buttons[s.ToString()] = () => { Span = s; Build(); };
        }
        bar.SelectionChanged += (_, _) =>
        {
            if (bar.SelectedItem is { Tag: Span s } && s != Span) { Span = s; Build(); }
        };
        return bar;
    }

    static string TodayTitle(Period p) => p.Span switch
    {
        Span.Day => "Today",
        Span.Week => "This week",
        Span.Month => p.Start.ToString("MMMM"),
        _ => p.Start.ToString("yyyy"),
    };

    static string TodaySubtitle(Period p) => p.Span switch
    {
        Span.Day => DateTime.Now.ToString("dddd, d MMMM"),
        Span.Week => $"{p.Start:d MMM} to {p.Last:d MMM}",
        Span.Month => "Your café this month, and where the time went",
        _ => "Your café this year, month by month",
    };

    static string PreviousName(Period p) => p.Span switch
    {
        Span.Week => "last week",
        Span.Month => Insights.Before(p).Start.ToString("MMMM"),
        Span.Year => Insights.Before(p).Start.ToString("yyyy"),
        _ => "yesterday",
    };

    UIElement Stats(List<Hist> blocks, List<Hist> before, Period p)
    {
        var m = Insights.Minutes(blocks);
        var home = Insights.Home(blocks);
        var goal = store.EngineSettings.Goal;
        if (Span == Span.Day)
        {
            var drifts = Insights.Drifts(blocks);
            var streak = store.StreakWithPause;
            return V.Even(14,
                Tile(Format.FmtMin(m), goal > 0 ? $"focused, goal {Format.FmtMin(goal)}" : "focused"),
                Tile(home.ToString(), home == 1 ? "cat came home" : "cats came home"),
                Tile(drifts.ToString(), "drifted"),
                Tile(Format.Plural(streak.Days, "day"), "in a row" + (streak.PausesLeft > 0 ? " · 1 pause left" : "")));
        }
        var o = Insights.Outcomes(blocks);
        var prev = Insights.Compare(m, Insights.Minutes(before));
        return V.Even(14,
            Tile(Format.FmtMin(m), prev != null ? $"focused · {prev} on {PreviousName(p)}" : "focused"),
            Tile(home.ToString(), home == 1 ? "cat came home" : "cats came home"),
            Tile(Insights.Wandered(blocks).ToString(), "wandered off"),
            Tile($"{o.Done} · {o.Partly} · {o.NotReally}", "done · partly · not really"));
    }

    static Border Tile(string big, string small) =>
        V.Card(V.Col(4, V.ClockText(big, 26).Left(), V.Caption(small, wrap: true)), new Thickness(16, 14, 16, 14));

    /// <summary>The café beside the panels, a little more than half the width.</summary>
    static Grid Columns(UIElement left, UIElement right)
    {
        var g = new Grid { ColumnSpacing = 16 };
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(0.535, GridUnitType.Star) });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(0.465, GridUnitType.Star) });
        Grid.SetColumn((FrameworkElement)right, 1);
        ((FrameworkElement)right).VerticalAlignment = VerticalAlignment.Top;
        g.Children.Add(left);
        g.Children.Add(right);
        return g;
    }

    UIElement CafeCard(List<Hist> blocks, Period p)
    {
        var home = Insights.Home(blocks);
        var cafe = new CafeView();
        Cafe = cafe;
        cafe.Show(CafeData.Make(store, blocks, Span, Palette.Dark, unchecked((int)(Hash(Span.ToString()) + p.From / 1000))));
        cafe.View.Height = 420;
        UIElement top = Span == Span.Day
            ? V.Spread(V.Strong("Your café today"), V.Link("Open the café", () => { Pane = "cafe"; shell.Select(Pane); Build(); }))
            : V.Spread(V.Strong($"{Format.Plural(home, "cat")} this {Span.ToString().ToLowerInvariant()}"),
                V.Row(4, V.IconBtn("", () => cafe.Spin(-1), "Turn the café left"), V.IconBtn("", () => cafe.Spin(1), "Turn the café right")));
        var empty = home == 0
            ? V.Caption(blocks.Any(b => b.Kitten == "gone") ? "No cats stayed. Next time, stay to the end." : "No cats came home yet. Finish a block and one will.", wrap: true, align: TextAlignment.Center)
            : null;
        var room = new Grid();
        room.Children.Add(cafe.View);
        if (empty != null)
        {
            empty.HorizontalAlignment = HorizontalAlignment.Center;
            empty.VerticalAlignment = VerticalAlignment.Center;
            empty.Margin = new Thickness(30, 0, 30, 0);
            room.Children.Add(empty);
        }
        return Panel(top, room);
    }

    static uint Hash(string s)
    {
        uint h = 7;
        foreach (var c in s) h = unchecked(h * 31 + c);
        return h;
    }

    /// <summary>A tag's color: its place among the tags, or one picked by its name.</summary>
    Color TagColor(string? tag)
    {
        if (string.IsNullOrEmpty(tag)) return Palette.Text3;
        var i = store.EngineSettings.Tags.IndexOf(tag);
        if (i < 0) i = (int)(Hash(tag) % 6);
        return TagColors[i % TagColors.Length];
    }

    static readonly Color[] TagColors =
        [Palette.C(0x0F7B6C), Palette.C(0xC2410C), Palette.C(0x7C3AED), Palette.C(0x2563EB), Palette.C(0xBE185D), Palette.C(0x4D7C0F)];

    string Detail(Hist b)
    {
        var parts = new List<string>();
        if (!string.IsNullOrEmpty(b.Tag)) parts.Add(b.Tag);
        parts.Add(b.Stopped ? "Stopped early" : b.Outcome switch { "done" => "Done", "partly" => "Partly", "notreally" => "Not really", _ => $"{b.Actual} min" });
        var name = store.Cat(b.Cat).Name;
        if (b.Kitten == "home") parts.Add($"{name} came home");
        else if (b.Kitten == "gone") parts.Add($"{name} wandered off");
        return string.Join(" · ", parts);
    }

    UIElement Timeline(List<Hist> blocks)
    {
        var list = V.Col(10);
        foreach (var b in blocks.OrderBy(b => b.Start))
        {
            var bar = new Rectangle { Width = 4, RadiusX = 2, RadiusY = 2, Fill = Palette.B(TagColor(b.Tag)) };
            var top = V.Spread(V.Strong(b.Title), V.Caption($"{Format.ClockTime(b.Start)} – {Format.ClockTime(b.Start + b.Actual * 60_000L)}"));
            var row = new Grid { ColumnSpacing = 12 };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            var words = V.Col(3, top, V.Caption(Detail(b)));
            Grid.SetColumn(words, 1);
            row.Children.Add(bar);
            row.Children.Add(words);
            list.Children.Add(row);
        }
        if (store.Active is { } a)
        {
            var now = V.Caption("");
            tickers.Add(t => now.Text = a.Stopwatch ? $"now, {Format.FmtClock(t - a.Start)}" : $"now, {Format.MinLeft(Math.Max(0, a.End - t))} min left");
            var row = V.Row(12, V.Painting(Art.Nap(a.Cat), 30), V.Col(1, V.Strong(a.Title), now), V.Label("FOCUS", Palette.Accent));
            list.Children.Add(V.Card(row, new Thickness(10, 8, 10, 8)));
        }
        if (blocks.Count == 0 && store.Active == null) list.Children.Add(V.Caption("Nothing yet today."));
        return Panel(V.Strong("Timeline"), list);
    }

    UIElement Hours(List<Hist> blocks)
    {
        var h = Insights.ByHour(blocks);
        var slice = Enumerable.Range(8, 11).ToArray();
        var best = slice.OrderByDescending(i => h[i]).First();
        var most = Math.Max(1, slice.Max(i => h[i]));
        return Panel(
            V.Spread(V.Strong("When you focus"), h[best] > 0 ? V.Caption($"most around {Insights.HourName(best)}") : null),
            Bars(slice.Select(i => h[i]).ToArray(), most, 72, null),
            V.Spread(V.Caption("8 AM"), V.Caption("6 PM"), V.Caption("12 PM")));
    }

    /// <summary>A bar for each slot, the empty ones a short grey stub; labels under them if given.</summary>
    static UIElement Bars(int[] values, int most, double height, string[]? labels, double spacing = 6)
    {
        var g = new Grid { ColumnSpacing = spacing, Height = height + (labels != null ? 18 : 0) };
        for (var i = 0; i < values.Length; i++)
        {
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            var bar = new Border
            {
                Height = Math.Max(6, (double)values[i] / most * height), CornerRadius = new CornerRadius(3),
                Background = Palette.B(values[i] > 0 ? Palette.Accent : Palette.RingTrack),
                VerticalAlignment = VerticalAlignment.Bottom, Margin = new Thickness(0, 0, 0, labels != null ? 18 : 0),
            };
            Grid.SetColumn(bar, i);
            g.Children.Add(bar);
            if (labels != null)
            {
                var l = V.T(labels[i], 10, color: Palette.Text3, align: TextAlignment.Center);
                l.HorizontalAlignment = HorizontalAlignment.Center;
                l.VerticalAlignment = VerticalAlignment.Bottom;
                Grid.SetColumn(l, i);
                g.Children.Add(l);
            }
        }
        return g;
    }

    UIElement Chart(List<Hist> blocks, Period p)
    {
        var slots = Insights.Slots(blocks, p);
        var most = Math.Max(1, slots.Max());
        var bestIndex = Array.IndexOf(slots, slots.Max());
        string BestName(int i) => p.Span switch
        {
            Span.Week => p.Start.AddDays(i).ToString("dddd"),
            Span.Month => $"the {Insights.Ordinal(i + 1)}",
            _ => new DateTime(p.Start.Year, i + 1, 1).ToString("MMMM"),
        };
        string[]? labels = p.Span switch
        {
            Span.Week => Enumerable.Range(0, 7).Select(i => p.Start.AddDays(i).ToString("ddd")).ToArray(),
            Span.Year => Enumerable.Range(1, 12).Select(m => new DateTime(p.Start.Year, m, 1).ToString("MMM")[..1]).ToArray(),
            _ => null,
        };
        return Panel(
            V.Spread(V.Strong(p.Span == Span.Year ? "Each month" : "Each day"), slots[bestIndex] > 0 ? V.Caption($"best: {BestName(bestIndex)}, {Format.FmtMin(slots[bestIndex])}") : null),
            Bars(slots, most, 84, labels, p.Span == Span.Month ? 3 : 8));
    }

    UIElement Tags(List<Hist> blocks)
    {
        var list = Insights.ByTag(blocks);
        var total = Math.Max(1, list.Sum(x => x.Minutes));
        var rows = V.Col(8);
        if (list.Count == 0) rows.Children.Add(V.Caption("No blocks yet."));
        foreach (var (tag, minutes) in list.Take(5))
        {
            var g = new Grid { ColumnSpacing = 10 };
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(90) });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(70) });
            var name = V.Body(tag);
            var track = new Grid { Height = 8, VerticalAlignment = VerticalAlignment.Center };
            track.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(minutes, GridUnitType.Star) });
            track.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(Math.Max(0.0001, total - minutes), GridUnitType.Star) });
            var back = new Border { CornerRadius = new CornerRadius(4), Background = Palette.B(Palette.RingTrack) };
            Grid.SetColumnSpan(back, 2);
            track.Children.Add(back);
            track.Children.Add(new Border { CornerRadius = new CornerRadius(4), Background = Palette.B(TagColor(tag == "Untagged" ? null : tag)) });
            var amount = V.Caption(Format.FmtMin(minutes), align: TextAlignment.Right);
            amount.HorizontalAlignment = HorizontalAlignment.Right;
            Grid.SetColumn(track, 1);
            Grid.SetColumn(amount, 2);
            g.Children.Add(name);
            g.Children.Add(track);
            g.Children.Add(amount);
            rows.Children.Add(g);
        }
        return Panel(V.Strong("By tag"), rows);
    }

    // ---------------------------------------------------------------- the café

    UIElement CafePage()
    {
        var p = Period;
        var blocks = store.Blocks(p.From, p.To);
        var cafe = new CafeView();
        Cafe = cafe;
        cafe.Show(CafeData.Make(store, blocks, Span, Palette.Dark, unchecked((int)(p.From / 1000))));
        cafe.View.Height = Math.Max(360, shell.PageHeight - 150);
        var when = Span == Span.Day ? "today" : $"this {Span.ToString().ToLowerInvariant()}";
        var header = V.Spread(Heading("Café", $"{Format.Plural(Insights.Home(blocks), "cat")} came home {when}. Drag to turn the room, click a cat."),
            V.Row(8, Spans(), V.IconBtn("", () => cafe.Spin(-1), "Turn left"), V.IconBtn("", () => cafe.Spin(1), "Turn right")));
        return Page(header, V.Card(cafe.View, new Thickness(8)));
    }

    // ---------------------------------------------------------------- History and Later

    UIElement History()
    {
        var all = store.State.History;
        var page = Page(Heading("History", "Every block, newest first."));
        if (all.Count == 0) page.Children.Add(V.Body("No blocks yet. Your first one shows up here.", color: Palette.Text2));
        foreach (var day in all.GroupBy(b => Format.Local(b.Start).Date).OrderByDescending(g => g.Key))
        {
            var rows = V.Col(0);
            var list = day.OrderByDescending(b => b.Start).ToList();
            for (var i = 0; i < list.Count; i++)
            {
                var b = list[i];
                var detail = string.Join(" · ", new[] { b.Tag ?? "", b.Stopped ? "Stopped early" : b.Outcome switch { "done" => "Done", "partly" => "Partly", "notreally" => "Not really", _ => "" }, b.Note }.Where(x => x.Length > 0));
                var row = new Grid { ColumnSpacing = 12, Padding = new Thickness(14, 10, 14, 10) };
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                var pic = V.Painting(b.Kitten == "gone" ? Art.Gone : Art.Kitten(b.Cat), 30);
                var words = V.Col(2, V.Strong(b.Title), detail.Length > 0 ? V.Caption(detail) : null);
                var right = V.Col(2, V.Body(Format.FmtMin(b.Actual), align: TextAlignment.Right), V.Caption(Format.ClockTime(b.Start), align: TextAlignment.Right));
                right.HorizontalAlignment = HorizontalAlignment.Right;
                Grid.SetColumn(words, 1);
                Grid.SetColumn(right, 2);
                row.Children.Add(pic);
                row.Children.Add(words);
                row.Children.Add(right);
                rows.Children.Add(row);
                if (i < list.Count - 1) rows.Children.Add(V.Divider());
            }
            page.Children.Add(V.Col(8, V.Spread(V.Strong(DayName(day.Key)), V.Caption(Format.FmtMin(list.Sum(b => b.Actual)))), V.Card(rows, new Thickness(0))));
        }
        return page;
    }

    static string DayName(DateTime d) =>
        d == DateTime.Today ? "Today" : d == DateTime.Today.AddDays(-1) ? "Yesterday" : d.ToString("dddd, d MMMM");

    UIElement Later()
    {
        var field = V.Field("Add something for later", laterText);
        field.MaxWidth = 440;
        field.TextChanged += (_, _) => laterText = field.Text;
        field.GotFocus += (_, _) => typing = true;
        field.LostFocus += (_, _) => typing = false;
        void Add()
        {
            var t = laterText.Trim();
            if (t.Length == 0) return;
            laterText = "";
            typing = false;
            store.AddLater(t);
        }
        field.KeyDown += (_, e) => { if (e.Key == VirtualKey.Enter) { e.Handled = true; Add(); } };
        Buttons["Add"] = Add;
        var add = V.Btn("Add", Add, accent: true);
        var entry = V.Row(8, field, add);

        var items = store.State.View.Later;
        var rows = V.Col(0);
        for (var i = 0; i < items.Count; i++)
        {
            var l = items[i];
            var row = new Grid { ColumnSpacing = 12, Padding = new Thickness(10, 6, 14, 6) };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var done = V.IconBtn("", () => store.DoneLater(l.Id), $"Done: {l.Text}");
            Buttons[$"Done: {l.Text}"] = () => store.DoneLater(l.Id);
            var text = V.Body(l.Text, wrap: true);
            text.VerticalAlignment = VerticalAlignment.Center;
            Grid.SetColumn(text, 1);
            row.Children.Add(done);
            row.Children.Add(text);
            if (store.Active == null)
            {
                var go = Btn("Start a block", () => store.Start(l.Text, fromLater: l.Id), $"Start: {l.Text}");
                Grid.SetColumn(go, 2);
                row.Children.Add(go);
            }
            rows.Children.Add(row);
            if (i < items.Count - 1) rows.Children.Add(V.Divider());
        }
        var list = items.Count == 0 ? (UIElement)V.Body("Nothing here yet.", color: Palette.Text2) : V.Card(rows, new Thickness(0));
        if (list is FrameworkElement fe) fe.MaxWidth = 640;
        return Page(Heading("Later", $"Thoughts parked from anywhere with {store.Settings.ParkKey.Label}, and things for later."), entry, list);
    }
}

static class Placing
{
    public static T With<T>(this T e, Thickness margin) where T : FrameworkElement { e.Margin = margin; return e; }

    public static TextBlock Left(this TextBlock t) { t.HorizontalAlignment = HorizontalAlignment.Left; t.TextAlignment = TextAlignment.Left; return t; }
}
