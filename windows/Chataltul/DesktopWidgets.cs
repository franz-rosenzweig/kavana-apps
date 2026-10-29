using Chataltul.Core;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Animation;
using Microsoft.UI.Xaml.Shapes;
using Windows.Foundation;
using Windows.UI;

namespace Chataltul;

/// <summary>
/// The widgets on the desktop: small cards that sit under every window, as a home screen's widgets do on a phone.
/// Each is a window of its own (Floating, kept at the bottom), dragged anywhere by its background, with a menu on a
/// right-click for its size, taking it away, or adding more. Where each one is lives in windows.json. They draw
/// straight from the store, so a change shows on them at once. The Android app's widget/*.kt, as Windows cards.
/// </summary>
sealed class WidgetHost
{
    readonly Store store;
    readonly Controller app;
    readonly Dictionary<string, DesktopWidget> shown = [];
    /// <summary>One was added, moved, resized or taken away.</summary>
    public event Action? Changed;

    public WidgetHost(Store store, Controller app)
    {
        this.store = store;
        this.app = app;
    }

    public IReadOnlyCollection<DesktopWidget> Shown => shown.Values;

    /// <summary>The widgets the settings have, each put back where it was (or onto a screen, if that one is gone).</summary>
    public void Load()
    {
        foreach (var p in store.Settings.Widgets.ToList())
        {
            if (Widgets.Kind(p.Kind) == null) continue;
            var w = new DesktopWidget(store, app, this, p);
            shown[p.Id] = w;
            w.Build();
            w.MoveTo(KeepOnScreen(p));
        }
    }

    public DesktopWidget Add(string kind, string? size = null)
    {
        var k = Widgets.Kind(kind) ?? Widgets.All[0];
        var place = new WidgetPlace { Kind = k.Id, Size = size != null && k.Sizes.Contains(size) ? size : k.Sizes[0] };
        var (x, y) = FreeSpot(place);
        place.X = x;
        place.Y = y;
        store.ChangeSettings(s => s.Widgets.Add(place));
        var w = new DesktopWidget(store, app, this, place);
        shown[place.Id] = w;
        w.Build();
        w.MoveTo((x, y));
        Changed?.Invoke();
        return w;
    }

    public void Remove(string id)
    {
        if (shown.Remove(id, out var w)) w.Close();
        store.ChangeSettings(s => s.Widgets.RemoveAll(p => p.Id == id));
        Changed?.Invoke();
    }

    public void RemoveAll()
    {
        foreach (var id in shown.Keys.ToList()) Remove(id);
    }

    public void Resize(string id, string size)
    {
        if (!shown.TryGetValue(id, out var w)) return;
        store.ChangeSettings(s => { if (s.Widgets.FirstOrDefault(p => p.Id == id) is { } p) p.Size = size; });
        w.Place.Size = size;
        w.Build();
        w.MoveTo((w.Place.X, w.Place.Y));
        Changed?.Invoke();
    }

    /// <summary>A widget was dragged somewhere: it is kept there.</summary>
    public void Moved(DesktopWidget w, int x, int y)
    {
        store.ChangeSettings(s => { if (s.Widgets.FirstOrDefault(p => p.Id == w.Place.Id) is { } p) { p.X = x; p.Y = y; } });
        w.Place.X = x;
        w.Place.Y = y;
        Changed?.Invoke();
    }

    /// <summary>The state changed: every widget draws again.</summary>
    public void Refresh()
    {
        foreach (var w in shown.Values) w.Build();
    }

    public void Tick(long now)
    {
        foreach (var w in shown.Values) w.Tick(now);
    }

    /// <summary>A new widget's place: down the right side of the main screen, then the next column in, where nothing is yet.</summary>
    (int X, int Y) FreeSpot(WidgetPlace place)
    {
        var work = Native.WorkAreaAt(new Native.POINT());
        var scale = Floating.ScaleAt(new Native.POINT());
        var (w, h) = Widgets.SizeOf(place.Size);
        int pw = (int)(w * scale), ph = (int)(h * scale), gap = (int)(16 * scale);
        var taken = shown.Values.Select(d => d.Rect).ToList();
        for (var col = 0; col < 6; col++)
            for (var y = work.Top + gap; y + ph <= work.Bottom; y += (int)(12 * scale))
            {
                var x = work.Right - gap - pw - col * ((int)(360 * scale) + gap);
                var r = new Rect(x, y, pw, ph);
                if (taken.All(t => !Overlaps(t, r, gap))) return (x, y);
            }
        return (work.Right - gap - pw, work.Top + gap);
    }

    static bool Overlaps(Rect a, Rect b, int gap) =>
        a.X < b.X + b.Width + gap && b.X < a.X + a.Width + gap && a.Y < b.Y + b.Height + gap && b.Y < a.Y + a.Height + gap;

    /// <summary>A place on a screen that's still there: else the top right of the main one.</summary>
    static (int X, int Y) KeepOnScreen(WidgetPlace p)
    {
        var at = new Native.POINT { X = p.X + 40, Y = p.Y + 40 };
        var work = Native.WorkAreaAt(at);
        if (at.X >= work.Left && at.X < work.Right && at.Y >= work.Top && at.Y < work.Bottom) return (p.X, p.Y);
        var main = Native.WorkAreaAt(new Native.POINT());
        return (main.Right - 400, main.Top + 16);
    }
}

/// <summary>One widget on the desktop.</summary>
sealed class DesktopWidget
{
    readonly Store store;
    readonly Controller app;
    readonly WidgetHost host;
    public WidgetPlace Place { get; }
    public Floating Win { get; } = new("Chataltul widget", activates: false, Backdrop.Clear, onTop: false);
    readonly List<Action<long>> tickers = [];
    /// <summary>The buttons on it, by what they say, for the self-test.</summary>
    public readonly Dictionary<string, Action> Buttons = [];
    Border? card;
    /// <summary>Pet the cat: 0 sitting, 1 a hop, 2 purring.</summary>
    int mood;
    Microsoft.UI.Dispatching.DispatcherQueueTimer? settle;
    CafeView? cafe;

    public DesktopWidget(Store store, Controller app, WidgetHost host, WidgetPlace place)
    {
        this.store = store;
        this.app = app;
        this.host = host;
        Place = place;
        Win.Root.PointerPressed += Pressed;
        Win.Root.RightTapped += (_, e) => { e.Handled = true; Menu(); };
        Win.Window.Title = $"Chataltul: {Widgets.Kind(place.Kind)?.Title}";
    }

    (double W, double H) Size => Widgets.SizeOf(Place.Size);

    /// <summary>Where it is on the screen, in pixels.</summary>
    public Rect Rect
    {
        get
        {
            var scale = Floating.ScaleAt(new Native.POINT { X = Place.X, Y = Place.Y });
            return new Rect(Place.X, Place.Y, Size.W * scale, Size.H * scale);
        }
    }

    public void MoveTo((int X, int Y) at)
    {
        Place.X = at.X;
        Place.Y = at.Y;
        var scale = Floating.ScaleAt(new Native.POINT { X = at.X, Y = at.Y });
        Win.Move(at.X, at.Y, (int)Math.Ceiling(Size.W * scale), (int)Math.Ceiling(Size.H * scale));
        if (!Win.IsShown) Win.Show();
        Win.Refit(Shape);
    }

    public void Close()
    {
        settle?.Stop();
        Win.KeepOnClose = false;
        Win.Hide();
        Win.Window.Close();
    }

    public void Tick(long now)
    {
        foreach (var t in tickers.ToList()) t(now);
    }

    /// <summary>The card again, from the state as it is now.</summary>
    public void Build()
    {
        Palette.Dark = Palette.SystemDark();
        Win.Root.RequestedTheme = Palette.Theme;
        tickers.Clear();
        Buttons.Clear();
        var (w, h) = Size;
        var v = new WidgetViews(store, app, this, w, h);
        FrameworkElement inside = Place.Kind switch
        {
            "quickstart" => v.QuickStart(),
            "today" => v.Today(),
            "later" => v.Later(),
            "sound" => v.Sound(),
            "pet" => v.Pet(mood),
            "clock" => v.ClockFace(),
            "streak" => v.Streak(),
            "goal" => v.Goal(),
            "missions" => v.Missions(),
            "constellation" => v.Constellation(),
            "weather" => v.Weather(),
            "cafe" => v.Cafe(ref cafe),
            _ => v.Window(),
        };
        card = new Border
        {
            Child = inside, Width = w, Height = h, CornerRadius = new CornerRadius(8),
            Background = v.Background, BorderBrush = Palette.B(Palette.Stroke), BorderThickness = new Thickness(1),
            Padding = Place.Kind is "constellation" or "cafe" ? new Thickness(0) : new Thickness(14, 12, 14, 12),
            HorizontalAlignment = HorizontalAlignment.Left, VerticalAlignment = VerticalAlignment.Top,
        };
        AutomationName(card, Widgets.Kind(Place.Kind)?.Title ?? "Chataltul");
        Win.Content = card;
        card.SizeChanged += (_, _) => Shape();
        foreach (var t in tickers) t(Clock.Now);
    }

    static void AutomationName(DependencyObject e, string name) => Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(e, name);

    /// <summary>Clicks outside the card's rounded corners go through to the desktop.</summary>
    void Shape()
    {
        if (card == null || card.ActualWidth <= 0) return;
        Win.SetShapes([(new Rect(0, 0, card.ActualWidth, card.ActualHeight), 8)], Native.GetDpiForWindow(Win.Hwnd) / 96.0);
    }

    public void AddTicker(Action<long> t) => tickers.Add(t);

    public Button Btn(object content, Action click, string name, bool accent = false, bool stretch = false, double? height = null)
    {
        Buttons[name] = click;
        var b = V.Btn(content, click, accent, stretch, name, height: height);
        // a widget never takes the keyboard from the app being worked in
        b.AllowFocusOnInteraction = false;
        b.IsTabStop = false;
        return b;
    }

    public Button IconBtn(string glyph, Action click, string name, double size = 32)
    {
        Buttons[name] = click;
        var b = V.IconBtn(glyph, click, name, size);
        b.AllowFocusOnInteraction = false;
        b.IsTabStop = false;
        return b;
    }

    /// <summary>Pet the cat: a click hops, a second purrs, and after a few seconds it sits again.</summary>
    public void Pet()
    {
        mood = mood == 1 ? 2 : 1;
        Build();
        settle?.Stop();
        settle = Ui.After(TimeSpan.FromSeconds(mood == 2 ? 5 : 3), () => { mood = 0; Build(); });
    }

    public int Mood => mood;

    /// <summary>A press on the card (not on a button) moves it; let go where it was, and it's a click.</summary>
    void Pressed(object sender, PointerRoutedEventArgs e)
    {
        if (!e.GetCurrentPoint(Win.Root).Properties.IsLeftButtonPressed) return;
        e.Handled = true;
        var before = Win.Bounds;
        Native.ReleaseCapture();
        Native.SendMessage(Win.Hwnd, Native.WM_NCLBUTTONDOWN, new IntPtr(Native.HTCAPTION), IntPtr.Zero);
        var after = Win.Bounds;
        if (Math.Abs(after.Left - before.Left) + Math.Abs(after.Top - before.Top) > 2) host.Moved(this, after.Left, after.Top);
        else Clicked();
    }

    /// <summary>A click on the card: the popover for what a block is doing, the app window for the numbers.</summary>
    void Clicked()
    {
        switch (Place.Kind)
        {
            case "pet": Pet(); break;
            case "today" or "streak" or "goal" or "missions" or "constellation" or "weather": app.OpenWindow("today"); break;
            case "cafe": app.OpenWindow("cafe"); break;
            case "later": app.OpenWindow("later"); break;
            default: app.Popover.Show(); break;
        }
    }

    void Menu()
    {
        var kind = Widgets.Kind(Place.Kind);
        var items = new List<(int, string?)>();
        if (kind != null && kind.Sizes.Length > 1)
        {
            for (var i = 0; i < kind.Sizes.Length; i++)
                items.Add((10 + i, (kind.Sizes[i] == Place.Size ? "✓ " : "    ") + Widgets.SizeName(kind.Sizes[i])));
            items.Add((0, null));
        }
        items.Add((1, "Remove this widget"));
        items.Add((2, "Add widgets…"));
        items.Add((3, "Open Chataltul"));
        var at = Native.Cursor();
        var chosen = app.Tray.PopUp(at.X, at.Y, [.. items]);
        if (chosen >= 10 && kind != null && chosen - 10 < kind.Sizes.Length) host.Resize(Place.Id, kind.Sizes[chosen - 10]);
        else if (chosen == 1) host.Remove(Place.Id);
        else if (chosen == 2) app.OpenSettings("widgets");
        else if (chosen == 3) app.OpenWindow("today");
    }
}

/// <summary>What each widget shows, as a Windows card: Windows' type and buttons, the café's paintings.</summary>
sealed class WidgetViews(Store store, Controller app, DesktopWidget w, double width, double height)
{
    public Brush Background { get; private set; } = Palette.B(Palette.Surface);
    UiState St => store.State;
    WMode Mode => Widgets.ModeOf(store.Active, store.Awaiting, store.OnBreak, St.CatsOn);
    CatInfo Cat => store.ShownCat;
    string Title(string t) => store.Settings.HideTitles ? "" : t;

    static TextBlock Eyebrow(string text, Color? color = null) => V.T(text, 11, FontWeights.SemiBold, color ?? Palette.Accent);
    static TextBlock Heading(string text, double size = 18, int lines = 2)
    {
        var t = V.T(text, size, FontWeights.SemiBold, Palette.Text, wrap: lines > 1);
        t.MaxLines = lines;
        t.TextTrimming = TextTrimming.CharacterEllipsis;
        return t;
    }
    static TextBlock Sub(string text) => V.Caption(text);

    static Grid Stack(params UIElement?[] parts)
    {
        // words at the top, buttons at the bottom
        var g = new Grid();
        g.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        g.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        var top = V.Col(2);
        for (var i = 0; i < parts.Length - 1; i++) if (parts[i] != null) top.Children.Add(parts[i]);
        g.Children.Add(top);
        if (parts[^1] is FrameworkElement last)
        {
            Grid.SetRow(last, 1);
            g.Children.Add(last);
        }
        return g;
    }

    static Grid Beside(UIElement left, UIElement right, double spacing = 12)
    {
        var g = new Grid { ColumnSpacing = spacing };
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        ((FrameworkElement)left).VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn((FrameworkElement)right, 1);
        g.Children.Add(left);
        g.Children.Add(right);
        return g;
    }

    // ---------------------------------------------------------------- the Window

    public FrameworkElement Window()
    {
        var mode = Mode;
        var small = width < 200;
        var strip = height < 100;
        var round = new RoundWindow(strip ? 60 : small ? 104 : 140);
        var b = store.Active;
        var clock = V.ClockText("", strip ? 20 : small ? 22 : 32);
        clock.HorizontalAlignment = HorizontalAlignment.Left;
        w.AddTicker(now =>
        {
            if (b != null)
            {
                round.Update(b, now);
                var at = b.Paused ?? now;
                clock.Text = b.Paused is { } p && mode == WMode.Paused ? Format.FmtClock(Math.Max(0, (b.PauseEnds ?? p + 5 * 60_000L) - now))
                    : Format.FmtClock(b.Stopwatch || b.Over != null ? at - b.Start : Math.Max(0, b.End - at));
            }
            else if (store.OnBreak is { } brk)
            {
                round.Update(Art.Nap(St.Companion), 0.82, (double)Math.Max(0, brk.End - now) / Math.Max(1, brk.End - brk.Start), 0.6);
                clock.Text = Format.FmtClock(Math.Max(0, brk.End - now));
            }
        });
        if (b == null && store.OnBreak == null)
        {
            if (store.Awaiting is { } aw) round.Update(aw.Kitten == "home" ? Art.PathFor(aw.Cat, Art.Stage.Home, aw.Minutes < 25) : Art.Gone, 1, null);
            else round.Update(Art.Nap(Cat.Id), 0.82, null);
        }
        var title = Title(b?.Title ?? "");
        var name = Cat.Name;
        UIElement words;
        UIElement buttons;
        switch (mode)
        {
            case WMode.Ready:
                words = V.Col(2, Eyebrow(Widgets.Greeting(DateTime.Now.Hour)), Heading(St.CatsOn ? $"{name} is waiting" : "Ready when you are", small ? 15 : 18, 1),
                    strip ? null : Sub("Start 25 minutes"));
                buttons = V.Row(6, w.Btn(V.Labelled(V.Glyph.Play, "Start"), () => store.Start("", 25), "Start", accent: true),
                    small ? null : w.Btn("15", () => store.Start("", 15), "15"), small ? null : w.Btn("50", () => store.Start("", 50), "50"));
                break;
            case WMode.Checkin:
                words = V.Col(2, Eyebrow("BOO! CHECK-IN", Palette.Caution), Heading(title.Length > 0 ? $"Still on: {title}?" : "Still focusing?", small ? 14 : 17, strip ? 1 : 3));
                buttons = V.Row(6, w.Btn(V.Labelled(V.Glyph.Check, "Yes"), () => app.Answer("yes"), "Yes", accent: true), w.Btn("Drifted", () => app.Answer("drifted"), "Drifted"));
                break;
            case WMode.Paused:
                words = V.Col(2, Eyebrow("PAUSED", Palette.Caution), clock, strip || small ? null : Sub("Starts again by itself"));
                buttons = w.Btn(V.Labelled(V.Glyph.Play, "Resume"), store.Resume, "Resume", accent: true);
                break;
            case WMode.Home or WMode.Stopped:
                {
                    var e = store.Awaiting!;
                    var mins = Math.Max(1, (int)(((e.Ended ?? e.End) - e.Start) / 60_000));
                    words = mode == WMode.Home
                        ? V.Col(2, Eyebrow($"{name.ToUpperInvariant()} CAME HOME"), Heading($"Nice, {mins} minutes", small ? 15 : 18, 1), V.T($"+{e.Treats + e.Bonus} treats", 13, FontWeights.SemiBold, Palette.Caution))
                        : V.Col(2, Eyebrow("STOPPED EARLY", Palette.Caution), Heading($"{name} wandered off", small ? 15 : 18, 1), strip ? null : Sub($"{mins} min · how did it go?"));
                    void Out(string o) { store.Outcome(e.Id, o); }
                    buttons = small
                        ? (UIElement)w.Btn("Done", () => Out("done"), "Done", accent: true)
                        : V.Row(6, w.Btn("Done", () => Out("done"), "Done", accent: true), w.Btn("Partly", () => Out("partly"), "Partly"), w.Btn("Not really", () => Out("notreally"), "Not really"));
                    break;
                }
            case WMode.Break:
                words = V.Col(2, Eyebrow("BREAK"), clock, strip || small ? null : Sub("Stand up, get some water."));
                buttons = w.Btn("End break", store.StopBreak, "End break");
                break;
            default:
                {
                    // focusing, extra time, or the kitten wandered off and the block goes on
                    var next = mode == WMode.Extra ? "Chataltul keeps counting"
                        : b?.Next is { } n ? $"Check-in in {Format.MinLeft(n - Clock.Now)} min" : "Check-in at the end";
                    var line = title.Length > 0 ? title : St.CatsOn ? $"{name} is growing" : "";
                    words = mode == WMode.Gone
                        ? V.Col(2, Eyebrow($"{name.ToUpperInvariant()} WANDERED OFF", Palette.Caution), clock, strip || small ? null : Sub("Your block keeps going."))
                        : V.Col(2, Eyebrow(mode == WMode.Extra ? "EXTRA TIME" : "FOCUSING"), clock, strip ? null : V.T(line, 13, FontWeights.SemiBold, Palette.Text), strip || small ? null : Sub(next));
                    buttons = mode == WMode.Extra
                        ? (UIElement)w.Btn(V.Labelled(V.Glyph.Check, "Done"), () => store.Stop(), "Done", accent: true)
                        : V.Row(4,
                            b?.Stopwatch == true ? null : w.Btn(V.Labelled(V.Glyph.Pause, small ? "" : "Pause"), store.Pause, "Pause"),
                            w.IconBtn(V.Glyph.Bulb, app.ShowPark, "Park a thought"),
                            small ? null : w.IconBtn(V.Glyph.Stop, () => app.Popover.Show(), "Stop"));
                    break;
                }
        }
        if (strip) return Beside(round.View, Beside(words, buttons, 8), 10);
        if (small)
        {
            round.View.HorizontalAlignment = HorizontalAlignment.Center;
            return Stack(round.View, buttons is FrameworkElement fb ? Center(fb) : buttons);
        }
        return Beside(round.View, Stack(words, buttons), 14);
    }

    static FrameworkElement Center(FrameworkElement e) { e.HorizontalAlignment = HorizontalAlignment.Center; return e; }

    // ---------------------------------------------------------------- quick start

    public FrameworkElement QuickStart()
    {
        if (store.Active != null)
            return Beside(V.Painting(Art.Nap(Cat.Id), 48), V.Col(2, Eyebrow("FOCUSING"), Heading(Title(store.Active.Title), 15, 1)));
        return V.Col(8, Eyebrow("QUICK START"), V.Even(6,
            w.Btn("15", () => store.Start("", 15), "15", stretch: true), w.Btn("25", () => store.Start("", 25), "25", accent: true, stretch: true),
            w.Btn("50", () => store.Start("", 50), "50", stretch: true), w.Btn("∞", () => store.Start("", 180, stopwatch: true), "Stopwatch", stretch: true)));
    }

    // ---------------------------------------------------------------- Today, the goal, the streak, the missions

    UIElement GoalRing(double size)
    {
        var goal = store.EngineSettings.Goal;
        var today = St.Stats.Today;
        var g = new Grid { Width = size, Height = size };
        g.Children.Add(V.Ring(size, 8, Palette.RingTrack));
        if (goal > 0 && today > 0) g.Children.Add(V.Arc(size, 8, Math.Min(1, (double)today / goal), Palette.Accent));
        var mid = V.Col(0, V.ClockText(Widgets.Hours(today), size * 0.2), goal > 0 ? CenterText(V.Caption($"of {Format.FmtMin(goal)}")) : null);
        mid.VerticalAlignment = VerticalAlignment.Center;
        g.Children.Add(mid);
        return g;
    }

    static TextBlock CenterText(TextBlock t) { t.HorizontalAlignment = HorizontalAlignment.Center; t.TextAlignment = TextAlignment.Center; return t; }

    UIElement Paws(bool[] days, double size)
    {
        var row = V.Row(4);
        var names = new[] { "M", "T", "W", "T", "F", "S", "S" };
        for (var i = 0; i < 7; i++)
        {
            var on = days[i];
            var dot = new Grid { Width = size, Height = size };
            dot.Children.Add(new Ellipse { Fill = Palette.B(on ? Palette.Accent : Palette.RingTrack) });
            var l = V.T(on ? "\U0001F43E" : names[i], size * 0.45, color: on ? Palette.C(0xFFFFFF) : Palette.Text3, align: TextAlignment.Center);
            l.HorizontalAlignment = HorizontalAlignment.Center;
            l.VerticalAlignment = VerticalAlignment.Center;
            dot.Children.Add(l);
            row.Children.Add(dot);
        }
        return row;
    }

    Button? Collect()
    {
        var waiting = St.Collectable;
        if (waiting.Count == 0) return null;
        var total = waiting.Sum(r => r.Pay);
        return w.Btn($"Collect +{total}", () => { foreach (var r in waiting) store.Claim(r.Id); }, "Collect", accent: true);
    }

    public FrameworkElement Today()
    {
        var day = St.Missions.Day;
        var done = day.Count(m => m.Done);
        var streak = St.Stats.Streak;
        var right = V.Col(6,
            Eyebrow("TODAY"),
            V.T(streak == 1 ? "1 day in a row" : $"{streak} days in a row", 15, FontWeights.SemiBold, Palette.Text),
            Paws(Widgets.WeekPaws(St.History, DateTime.Now), 18),
            V.Caption(day.Count == 0 ? "No missions yet" : $"{done} of {day.Count} missions"),
            (UIElement?)Collect() ?? (day.FirstOrDefault(m => !m.Done) is { } next ? V.Caption(next.Progress, wrap: true) : null));
        return Beside(GoalRing(120), right, 16);
    }

    public FrameworkElement Goal()
    {
        var goal = store.EngineSettings.Goal;
        var today = St.Stats.Today;
        var ring = GoalRing(112);
        ((FrameworkElement)ring).HorizontalAlignment = HorizontalAlignment.Center;
        return V.Col(8, Eyebrow(goal == 0 ? "NO GOAL SET" : today >= goal ? "GOAL REACHED" : "DAILY GOAL"), ring);
    }

    public FrameworkElement Streak()
    {
        var streak = St.Stats.Streak;
        var big = V.ClockText(streak.ToString(), 48);
        return V.Col(4, Eyebrow("STREAK"), big, CenterText(V.Caption(streak == 1 ? "day in a row" : "days in a row")), Center(Paws(Widgets.WeekPaws(St.History, DateTime.Now), 16) as FrameworkElement ?? new Grid()));
    }

    public FrameworkElement Missions()
    {
        var day = St.Missions.Day;
        var list = V.Col(6);
        if (day.Count == 0) list.Children.Add(V.Caption("No missions yet"));
        foreach (var m in day)
        {
            var mark = V.Icon(m.Done ? V.Glyph.Check : "", 14, m.Done ? Palette.Accent : Palette.Text3);
            var text = V.T(m.Progress, 13, color: m.Done ? Palette.Text2 : Palette.Text);
            list.Children.Add(V.Row(8, mark, text, V.T($"+{m.Pay}", 12, FontWeights.SemiBold, Palette.Caution)));
        }
        var foot = (UIElement?)Collect() ?? (day.Count > 0 && day.All(m => m.Done) ? V.Caption("All done for today") : null);
        return Stack(Eyebrow("MISSIONS"), list, foot);
    }

    // ---------------------------------------------------------------- Later

    public FrameworkElement Later()
    {
        var items = St.View.Later.Take(4).ToList();
        var list = V.Col(2);
        if (items.Count == 0) list.Children.Add(V.Caption("Nothing for later. Park a thought here, or with " + store.Settings.ParkKey.Label + "."));
        foreach (var l in items)
        {
            var row = new Grid { ColumnSpacing = 6 };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var tick = w.IconBtn("", () => store.DoneLater(l.Id), $"Done: {l.Text}", 28);
            var text = V.T(l.Text, 13, color: Palette.Text);
            text.VerticalAlignment = VerticalAlignment.Center;
            Grid.SetColumn(text, 1);
            row.Children.Add(tick);
            row.Children.Add(text);
            if (store.Active == null)
            {
                var go = w.IconBtn(V.Glyph.Play, () => store.Start(l.Text, fromLater: l.Id), $"Start: {l.Text}", 28);
                Grid.SetColumn(go, 2);
                row.Children.Add(go);
            }
            list.Children.Add(row);
        }
        return Stack(V.Spread(Eyebrow("LATER"), V.Caption(St.View.Later.Count > 4 ? $"{St.View.Later.Count} in all" : "")), list,
            w.Btn(V.Labelled(V.Glyph.Bulb, "Park a thought"), app.ShowPark, "Park a thought"));
    }

    // ---------------------------------------------------------------- the sounds

    public FrameworkElement Sound()
    {
        var mix = store.PlayableMix;
        var playing = app.Sound.Playing.Count > 0;
        var line = mix.Count == 0 ? "No sound picked" : store.MixLabel;
        var sub = mix.Count == 0 ? "Rain, a café, purring: the web's sounds, two at once." : playing ? "Playing now" : "Plays while a block runs";
        var buttons = V.Row(6, w.Btn(V.Labelled("", mix.Count == 0 ? "Pick sounds" : "Change"), app.Popover.ShowSounds, "Pick sounds"),
            mix.Count > 0 ? w.Btn("Off", () => store.ChangeSettings(s => s.Mix = []), "Sounds off") : null);
        if (height < 100) return Beside(V.Icon("", 28, playing ? Palette.Accent : Palette.Text2), Beside(V.Col(1, Heading(line, 15, 1), Sub(sub)), buttons, 8), 12);
        return Stack(Eyebrow(playing ? "SOUND · PLAYING" : "SOUND"), Heading(line, 18, 2), Sub(sub), buttons);
    }

    // ---------------------------------------------------------------- the playful ones

    public FrameworkElement Pet(int mood)
    {
        if (!St.CatsOn) return CenterText(V.Caption("Cats are off"));
        var hour = DateTime.Now.Hour;
        var night = Widgets.IsNight(hour);
        var boo = night && St.Cats.Any(c => c.Id == "boo" && c.Met);
        var catId = boo ? "boo" : Cat.Id;
        var name = boo ? "Boo" : Cat.Name;
        if (night) Background = V.Vertical(Palette.SkyDeep);
        var picture = night ? Art.Nap(catId) : mood == 2 && Art.HasPose(catId, "loaf") ? Art.Pose(catId, "loaf") : Art.Portrait(catId);
        var catSize = Math.Min(height - 24, 140);
        var cat = V.Painting(picture, catSize);
        cat.VerticalAlignment = VerticalAlignment.Bottom;
        var lift = new TranslateTransform();
        cat.RenderTransform = lift;
        if (mood == 1 && !CatCorner.Still) Hop(lift);
        // the cat takes the click itself, so a press on it pets rather than moves the card
        cat.PointerPressed += (_, e) => { e.Handled = true; };
        cat.Tapped += (_, e) => { e.Handled = true; w.Pet(); };
        w.Buttons["Pet"] = w.Pet;
        var says = night ? (mood == 2 ? "Prrr…" : $"Shh. {name} is asleep.") : mood == 1 ? "Mrrp!" : mood == 2 ? "Prrrr…" : Mode switch
        {
            WMode.Ready => "Boo! Ready when you are.",
            WMode.Home => $"{Cat.Name} came home!",
            WMode.Paused => "Taking a breather.",
            _ => $"Shh. {Cat.Name} is growing.",
        };
        var bubble = new Border
        {
            Child = V.T(says, 12, FontWeights.SemiBold, Palette.C(0x2B211A), wrap: true), Background = Palette.B(Palette.C(0xFFFFFF, 0.95)),
            CornerRadius = new CornerRadius(12), Padding = new Thickness(10, 5, 10, 5), MaxWidth = width < 200 ? 140 : 180,
            HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Top,
        };
        var heart = mood == 1 ? V.T("♥", 18, color: Palette.C(0xE5484D)) : null;
        var g = new Grid();
        if (width >= 250)
        {
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var pane = new Grid();
            cat.HorizontalAlignment = HorizontalAlignment.Left;
            pane.Children.Add(cat);
            pane.Children.Add(bubble);
            if (heart != null) { heart.Margin = new Thickness(catSize * 0.6, 4, 0, 0); pane.Children.Add(heart); }
            var live = store.Active;
            // on the night sky, light words
            var side = V.Col(4,
                Eyebrow(live != null ? (live.Paused != null ? "PAUSED" : "FOCUSING") : "TODAY", night ? Palette.C(0x8FE3D6) : (Color?)null),
                V.ClockText(Widgets.Hours(St.Stats.Today), 24, night ? Palette.C(0xFFFFFF) : null).Left(),
                V.T(store.CatsHomeToday switch { 0 => "No cats home yet", 1 => "1 cat came home", var n => $"{n} cats came home" }, 12,
                    color: night ? Palette.C(0xCFC6BD) : Palette.Text2),
                Mode == WMode.Ready ? w.Btn("Start 25", () => store.Start("", 25), "Start 25", accent: true) : null);
            side.Width = 120;
            Grid.SetColumn(side, 1);
            g.Children.Add(pane);
            g.Children.Add(side);
            return g;
        }
        cat.HorizontalAlignment = HorizontalAlignment.Center;
        g.Children.Add(cat);
        g.Children.Add(bubble);
        if (heart != null) { heart.HorizontalAlignment = HorizontalAlignment.Left; heart.Margin = new Thickness(18, 8, 0, 0); g.Children.Add(heart); }
        return g;
    }

    static void Hop(TranslateTransform t)
    {
        var sb = new Storyboard();
        var a = new DoubleAnimation { From = 0, To = -18, Duration = new Duration(TimeSpan.FromMilliseconds(180)), AutoReverse = true, EasingFunction = new QuadraticEase { EasingMode = EasingMode.EaseOut } };
        Storyboard.SetTarget(a, t);
        Storyboard.SetTargetProperty(a, "Y");
        sb.Children.Add(a);
        sb.Begin();
    }

    /// <summary>A cat with a day: the time, the date and what the cat is up to.</summary>
    public FrameworkElement ClockFace()
    {
        var now = DateTime.Now;
        var night = Widgets.IsNight(now.Hour);
        var boo = night && St.Cats.Any(c => c.Id == "boo" && c.Met);
        if (night) Background = V.Vertical(Palette.SkyDeep);
        var ink = night ? Palette.C(0xFFFFFF) : Palette.Text;
        var time = V.T(now.ToString("t"), 40, FontWeights.SemiBold, ink);
        time.FontFamily = new FontFamily("Segoe UI Variable Display, Segoe UI");
        var date = V.T(now.ToString("dddd, d MMMM"), 13, color: night ? Palette.C(0xCFC6BD) : Palette.Text2);
        w.AddTicker(_ => { var n = DateTime.Now; time.Text = n.ToString("t"); date.Text = n.ToString("dddd, d MMMM"); });
        // Boo is the one out late; the line is about your own cat, asleep
        var line = V.T(St.CatsOn ? Widgets.CatLine(Mode, Cat.Name, now.Hour) : "", 12, color: night ? Palette.C(0x9D94AB) : Palette.Text2, wrap: true);
        line.MaxLines = 2;
        var words = Stack(Eyebrow(boo ? "BOO IS OUT" : Widgets.Greeting(now.Hour).Replace("NIGHT OWL", "GOOD NIGHT"), night ? Palette.C(0xA99BE8) : (Color?)null), time, date, line);
        if (!St.CatsOn) return words;
        var cat = V.Painting(boo ? Art.Portrait("boo") : night ? Art.Nap(Cat.Id) : Art.Portrait(Cat.Id), Math.Min(118, height - 24));
        cat.VerticalAlignment = VerticalAlignment.Bottom;
        var g = new Grid { ColumnSpacing = 8 };
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        Grid.SetColumn(cat, 1);
        g.Children.Add(words);
        g.Children.Add(cat);
        return g;
    }

    public FrameworkElement Constellation()
    {
        Background = V.Vertical(Palette.SkyDeep);
        var now = DateTime.Now;
        var days = DateTime.DaysInMonth(now.Year, now.Month);
        var stars = Widgets.Stars(St.History, now);
        var sky = new Canvas { Width = width, Height = height };
        foreach (var (x, y, r, a) in new[] { (0.1, 0.2, 0.8, 0.5), (0.3, 0.7, 0.7, 0.4), (0.62, 0.15, 0.8, 0.5), (0.85, 0.6, 0.7, 0.4), (0.45, 0.45, 0.6, 0.35), (0.72, 0.85, 0.7, 0.4) })
            Dot(sky, x * width, y * height, r, Palette.C(0xFFFFFF, a));
        foreach (var (d, m, h) in stars)
        {
            var (x, y) = Widgets.StarAt(d, m, h, days);
            var r = Math.Clamp(1.2 + m / 30.0, 1.2, 3.6);
            Dot(sky, x * width, y * height, r + 2.5, Palette.C(0xFFE7A8, 0.18));
            Dot(sky, x * width, y * height, r, Palette.C(0xFFF4D6));
        }
        var streak = St.Stats.Streak;
        var words = V.Col(2,
            V.T(now.ToString("MMMM").ToUpperInvariant(), 11, FontWeights.SemiBold, Palette.C(0xA99BE8)),
            V.T(stars.Count == 0 ? "No stars yet" : Format.Plural(stars.Count, "star"), 18, FontWeights.SemiBold, Palette.C(0xFFFFFF)),
            streak > 0 ? V.T(streak == 1 ? "1 day in a row" : $"{streak} days in a row", 12, color: Palette.C(0xFFCF8A)) : null);
        words.Margin = new Thickness(14, 12, 14, 12);
        var g = new Grid();
        g.Children.Add(sky);
        g.Children.Add(words);
        return g;
    }

    static void Dot(Canvas c, double x, double y, double r, Color color)
    {
        var e = new Ellipse { Width = r * 2, Height = r * 2, Fill = Palette.B(color) };
        Canvas.SetLeft(e, x - r);
        Canvas.SetTop(e, y - r);
        c.Children.Add(e);
    }

    public FrameworkElement Weather()
    {
        var days = Widgets.Days(St.History, DateTime.Now, 7);
        var best = days.OrderByDescending(d => d.Minutes).First();
        var row = V.Even(4, days.Select(d =>
        {
            var size = 34.0;
            var sky = new Grid { Width = size, Height = size, HorizontalAlignment = HorizontalAlignment.Center };
            sky.Children.Add(new Ellipse { Fill = d.Minutes > 0 ? (Brush)V.Vertical(d.Hour >= 18 || (d.Hour >= 0 && d.Hour < 6) ? Palette.SkyNight : Palette.SkyDay) : Palette.B(Palette.RingTrack) });
            if (d.Cat != null) sky.Children.Add(V.Painting(Art.Portrait(d.Cat), size * 0.9));
            var day = CenterText(V.T(d.Date == DateTime.Today ? "Today" : d.Date.ToString("ddd"), 11, FontWeights.SemiBold, Palette.Text2));
            var min = CenterText(V.T(d.Minutes > 0 ? Widgets.Hours(d.Minutes) : "–", 11, color: Palette.Text3));
            return (UIElement)V.Col(4, day, sky, min);
        }).ToArray());
        return Stack(V.Spread(Eyebrow("THIS WEEK, AS WEATHER"), V.Caption(best.Minutes > 0 ? $"best: {best.Date:ddd}" : "no blocks yet")), row, null);
    }

    public FrameworkElement Cafe(ref CafeView? cafe)
    {
        var p = Insights.PeriodOf(Span.Day, DateTime.Now);
        var blocks = store.Blocks(p.From, p.To);
        cafe ??= new CafeView();
        cafe.Show(CafeData.Make(store, blocks, Span.Day, Palette.Dark, unchecked((int)(p.From / 1000))));
        var view = cafe.View;
        if (view.Parent is Panel old) old.Children.Remove(view);
        view.Width = width;
        view.Height = height - 30;
        var home = Insights.Home(blocks);
        var words = V.Spread(V.T("Your café today", 12, FontWeights.SemiBold, Palette.Text), V.Caption(home == 0 ? "No cats home yet" : Format.Plural(home, "cat")));
        words.Margin = new Thickness(12, 8, 12, 0);
        var g = new Grid();
        g.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        g.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        Grid.SetRow(view, 1);
        g.Children.Add(words);
        g.Children.Add(view);
        return g;
    }
}
