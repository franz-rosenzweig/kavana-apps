using Chataltul.Core;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;
using Windows.Foundation;

namespace Chataltul;

/// <summary>
/// A soft full-screen break, on every screen: the night sky, and on the main screen the cat stretching, an idea from
/// the person's own list, the time left, and Postpone and Skip (or neither, with strict breaks). Each window simply
/// covers its whole screen, above everything, with no frame and nothing that changes it: one state, as Moshe
/// learned, so there is no way for it to be half full screen. The Mac's BreakScreen.swift.
/// </summary>
sealed class BreakScreen(Store store, Controller app)
{
    readonly List<Floating> windows = [];
    readonly List<Action<long>> tickers = [];
    DispatcherQueueTimer? timer;
    /// <summary>The buttons on the main screen, by what they say, for the self-test.</summary>
    public readonly Dictionary<string, Action> Buttons = [];
    public bool IsShown => windows.Count > 0;
    /// <summary>The main screen's window (the self-test draws it).</summary>
    public Floating? Main => windows.FirstOrDefault();

    static readonly Windows.UI.Color Ink = Palette.C(0xF2EDE6), Soft = Palette.C(0xCFC6BD), Faint = Palette.C(0xA59C93), Teal = Palette.C(0x5FD0C0);

    /// <summary>After every change: a break on, with full-screen breaks, is on every screen; else none is.</summary>
    public void Update()
    {
        if (!store.Settings.Breaks.FullScreen || store.OnBreak is not { } b) { Hide(); return; }
        if (!IsShown) Show(b);
    }

    void Show(Brk b)
    {
        Hide();
        var bs = store.Settings.Breaks;
        var idea = bs.IdeaFor(store.CatsHomeToday + store.BlocksToday);
        var screens = Native.Screens().OrderByDescending(s => s.Primary).ToList();
        if (screens.Count == 0) screens.Add((Native.MonitorAt(new Native.POINT()), true));
        foreach (var (area, primary) in screens)
        {
            var w = new Floating("Chataltul break", activates: primary, Backdrop.None) { KeepOnClose = true };
            w.Root.RequestedTheme = ElementTheme.Dark;
            w.Root.Background = new LinearGradientBrush
            {
                StartPoint = new Point(0.5, 0), EndPoint = new Point(0.5, 1),
                GradientStops =
                {
                    new GradientStop { Color = Palette.C(0x141A3E), Offset = 0 },
                    new GradientStop { Color = Palette.C(0x2B2E5E), Offset = 0.55 },
                    new GradientStop { Color = Palette.C(0x3A396B), Offset = 1 },
                },
            };
            var all = new Grid();
            all.Children.Add(Stars());
            if (primary) all.Children.Add(Content(b, idea, bs));
            w.Content = all;
            w.Move(area.Left, area.Top, area.Width, area.Height);
            w.Show();
            windows.Add(w);
        }
        timer = Ui.Every(TimeSpan.FromSeconds(1), () => { foreach (var t in tickers.ToList()) t(Clock.Now); });
        foreach (var t in tickers) t(Clock.Now);
    }

    public void Hide()
    {
        timer?.Stop();
        timer = null;
        tickers.Clear();
        Buttons.Clear();
        foreach (var w in windows)
        {
            w.KeepOnClose = false;
            w.Hide();
            w.Window.Close();
        }
        windows.Clear();
    }

    static Canvas Stars()
    {
        var c = new Canvas();
        c.SizeChanged += (_, e) =>
        {
            c.Children.Clear();
            foreach (var (x, y, r, a) in new[] { (0.12, 0.18, 1.2, 0.8), (0.26, 0.09, 1.0, 0.6), (0.41, 0.22, 1.0, 0.7), (0.63, 0.12, 1.2, 0.8), (0.78, 0.26, 1.0, 0.6), (0.90, 0.08, 1.0, 0.7), (0.08, 0.44, 1.0, 0.5), (0.94, 0.40, 1.0, 0.5), (0.55, 0.05, 0.9, 0.5), (0.33, 0.35, 0.9, 0.4), (0.7, 0.5, 0.9, 0.4) })
            {
                var dot = new Ellipse { Width = r * 2, Height = r * 2, Fill = Palette.B(Palette.C(0xFFFFFF, a)) };
                Canvas.SetLeft(dot, x * e.NewSize.Width - r);
                Canvas.SetTop(dot, y * e.NewSize.Height - r);
                c.Children.Add(dot);
            }
        };
        return c;
    }

    Button Btn(string text, Action click, bool outline = false)
    {
        Buttons[text] = click;
        var b = new Button
        {
            Content = text, FontSize = 15, Padding = new Thickness(20, 11, 20, 11), CornerRadius = new CornerRadius(12),
            Foreground = Palette.B(Ink), Background = Palette.B(outline ? Microsoft.UI.Colors.Transparent : Palette.C(0xFFFFFF, 0.12)),
            BorderBrush = Palette.B(Palette.C(0xFFFFFF, outline ? 0.18 : 0)), BorderThickness = new Thickness(1),
        };
        b.Click += (_, _) => click();
        return b;
    }

    static TextBlock Words(string text, double size, Windows.UI.Color color, bool bold = false)
    {
        var t = V.T(text, size, bold ? FontWeights.Bold : FontWeights.Normal, color, wrap: true, align: TextAlignment.Center);
        t.HorizontalAlignment = HorizontalAlignment.Center;
        return t;
    }

    FrameworkElement Content(Brk b, (string Main, List<string> Others) idea, BreakSettings bs)
    {
        var label = Words($"BREAK · {b.Minutes} MIN", 12, Teal, bold: true);
        label.CharacterSpacing = 160;
        var cat = V.Painting(Art.Pose(store.State.Companion, "stretch"), 300);
        cat.HorizontalAlignment = HorizontalAlignment.Center;
        var then = idea.Others.FirstOrDefault();
        const string rest = "The screen stays like this until the break is over, and your next block is ready when you're back.";
        var sentence = Words(then is { Length: > 0 } t ? $"Then {char.ToLowerInvariant(t[0])}{t[1..]}. {rest}" : rest, 19, Soft);
        sentence.MaxWidth = 620;
        var clock = V.ClockText("", 36, Ink);
        clock.VerticalAlignment = VerticalAlignment.Center;
        var ring = V.Arc(130, 5, 1, Teal);
        var dial = new Grid { Width = 130, Height = 130, HorizontalAlignment = HorizontalAlignment.Center };
        dial.Children.Add(V.Ring(130, 5, Palette.C(0xFFFFFF, 0.14)));
        dial.Children.Add(ring);
        dial.Children.Add(clock);
        tickers.Add(now =>
        {
            var brk = store.OnBreak ?? b;
            var left = Math.Max(0, brk.End - now);
            clock.Text = Format.FmtClock(left);
            V.SetArc(ring, 130, 5, (double)left / Math.Max(1, brk.End - brk.Start));
        });
        var middle = V.Col(18, label, cat, Words($"{idea.Main}.", 46, Ink, bold: true), sentence, dial);
        if (!bs.Strict)
        {
            var buttons = V.Row(10, Btn($"Postpone {bs.PostponeMin} min", app.PostponeBreak), Btn("Skip this break", store.StopBreak, outline: true));
            buttons.HorizontalAlignment = HorizontalAlignment.Center;
            middle.Children.Add(buttons);
            middle.Children.Add(Words("Strict breaks are off, so you can skip. Turn them on in Settings and these buttons go away.", 13, Faint));
        }
        middle.HorizontalAlignment = HorizontalAlignment.Center;
        middle.VerticalAlignment = VerticalAlignment.Center;
        middle.Margin = new Thickness(24, 0, 24, 50);

        var page = new Grid { Padding = new Thickness(24) };
        page.Children.Add(middle);
        if (bs.EyeBreaks)
        {
            var eye = new Border
            {
                Width = 300, CornerRadius = new CornerRadius(14), Padding = new Thickness(16, 14, 16, 14),
                Background = Palette.B(Palette.C(0x1F1C23, 0.92)), BorderBrush = Palette.B(Palette.C(0xFFFFFF, 0.09)), BorderThickness = new Thickness(1),
                HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Top,
                Child = V.Col(2, V.T("And during a block: eye breaks", 13, FontWeights.Bold, Ink),
                    V.T($"Every {bs.EyeEveryMin} min a {bs.EyeSeconds}-second card: look at something far away. It waits during calls and full-screen video.", 12, color: Faint, wrap: true)),
            };
            page.Children.Add(eye);
        }
        var others = V.Row(10, V.T("Other ideas:", 13, color: Faint));
        foreach (var o in idea.Others)
            others.Children.Add(new Border { Child = V.T(o, 13, color: Soft), Padding = new Thickness(12, 6, 12, 6), CornerRadius = new CornerRadius(14), Background = Palette.B(Palette.C(0xFFFFFF, 0.08)) });
        Buttons["Edit ideas"] = () => { Hide(); app.OpenSettings("breaks"); };
        var edit = V.Link("Edit ideas", Buttons["Edit ideas"]);
        edit.Foreground = Palette.B(Teal);
        others.Children.Add(edit);
        others.HorizontalAlignment = HorizontalAlignment.Center;
        others.VerticalAlignment = VerticalAlignment.Bottom;
        others.Margin = new Thickness(0, 0, 0, 36);
        page.Children.Add(others);
        return page;
    }
}
